package blue.bex.runtime;

import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexAdmittedValue;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexOutputKind;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexChangeset;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.result.BexResultOverlay;
import blue.bex.type.BexBlueTypeMatcher;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.runtime.BlueLanguage;
import blue.language.processor.GasMeter;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.RuntimeWorkBudget;
import blue.language.model.wire.JsonPointer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime for one compiled BEX execution.
 */
public final class BexRuntime {
    private final BexCompiledProgram program;
    private final BexExecutionContext context;
    private final BexGasMeter gas;
    private final BexMetrics metrics;
    private final BexPointerCache pointerCache;
    private final BexExecutionAccumulator accumulator;
    private final BexBlueTypeMatcher typeMatcher;
    private final BlueLanguage blue;
    private final BexIntrinsicRegistry intrinsics;
    private final BexOutputAdmission outputAdmission;
    private final BexGasLedgerHost gasLedgerHost;
    private final BexResultOverlay rollbackOverlay;

    public BexRuntime(BexCompiledProgram program,
                      BexExecutionContext context,
                      BlueLanguage blue,
                      BexGasSchedule gasSchedule,
                      BexMetrics metrics,
                      BexPointerCache pointerCache) {
        this(program, context, blue, gasSchedule, metrics, pointerCache, BexIntrinsicRegistry.empty());
    }

    public BexRuntime(BexCompiledProgram program,
                      BexExecutionContext context,
                      BlueLanguage blue,
                      BexGasSchedule gasSchedule,
                      BexMetrics metrics,
                      BexPointerCache pointerCache,
                      BexIntrinsicRegistry intrinsics) {
        this.program = program;
        this.context = context;
        this.blue = blue;
        this.intrinsics = intrinsics != null
                ? intrinsics
                : BexIntrinsicRegistry.empty();
        this.gasLedgerHost = context.gasLedgerHost();
        this.gas = newGasMeter(
                context,
                gasSchedule,
                gasLedgerHost,
                this.intrinsics,
                program.requiredIntrinsicBlueIds());
        this.metrics = metrics;
        this.pointerCache = pointerCache;
        this.outputAdmission = new BexOutputAdmission(
                gas, context.semanticIdentityBoundary());
        BexResultOverlay activeOverlay =
                new BexResultOverlay(
                        context.document(), metrics, blue);
        this.accumulator = new BexExecutionAccumulator(
                activeOverlay,
                outputAdmission);
        this.rollbackOverlay = new BexResultOverlay(
                context.document(), metrics, blue);
        this.typeMatcher = new BexBlueTypeMatcher(blue);
    }

    public BexExecutionResult execute() {
        try {
            BexValue value = program.execute(this);
            BexAdmittedValue output =
                    outputAdmission.admit(value, BexOutputKind.ROOT_RESULT);
            BexExecutionResult result = new BexExecutionResult(
                    value,
                    accumulator.changeset(),
                    accumulator.events(),
                    gas.ledger(),
                    metrics,
                    output);
            submitHostLedger();
            return result;
        } catch (RuntimeException | Error ex) {
            accumulator.discard(rollbackOverlay);
            finishHostLedgerAfterFailure(ex);
            throw ex;
        }
    }

    public BexCompiledProgram program() { return program; }
    public BexExecutionContext context() { return context; }
    public BexGasMeter gas() { return gas; }
    public BexMetrics metrics() { return metrics; }
    public BexPointerCache pointerCache() { return pointerCache; }
    public BexExecutionAccumulator accumulator() { return accumulator; }
    public BexBlueTypeMatcher typeMatcher() { return typeMatcher; }
    public BexIntrinsicRegistry intrinsics() { return intrinsics; }
    public BexOutputAdmission outputAdmission() { return outputAdmission; }

    public BexValue readDocument(String absolutePointer, List<String> precompiledSegments, boolean resolved) {
        gas.charge(BexGasCounter.DOCUMENT_READ);
        if (resolved) {
            metrics.incrementResolvedDocumentReads();
        } else {
            metrics.incrementFrozenDocumentReads();
        }

        /*
         * Traverse from the host's exact root so every intermediate reference
         * can be materialized lazily through Blue's verified provider
         * boundary. A final pure or cyclic-set reference stays opaque when the
         * program only carries it or asks for its established identity.
         */
        return readValuePointer(documentAt("/", resolved),
                precompiledSegments);
    }

    private BexValue documentAt(String absolutePointer, boolean resolved) {
        return resolved
                ? context.document().resolvedAt(absolutePointer)
                : context.document().canonicalAt(absolutePointer);
    }

    public BexValue readEvent(List<String> precompiledSegments) {
        gas.charge(BexGasCounter.EVENT_READ);
        metrics.incrementEventReads();
        return readValuePointer(context.event(), precompiledSegments);
    }

    public BexValue readProcessingEvent(List<String> precompiledSegments) {
        gas.charge(BexGasCounter.PROCESSING_EVENT_READ);
        return readValuePointer(context.processingEvent(), precompiledSegments);
    }

    public BexValue readCurrentContract(List<String> precompiledSegments) {
        gas.charge(BexGasCounter.CURRENT_CONTRACT_READ);
        metrics.incrementCurrentContractReads();
        return readValuePointer(context.currentContract(), precompiledSegments);
    }

    public BexValue readBinding(String name, List<String> pathSegments) {
        gas.charge(BexGasCounter.BINDING_READ);
        if (name == null || name.isEmpty()) {
            return BexValues.undefined();
        }
        return readValuePointer(context.binding(name), pathSegments);
    }

    public BexValue readSteps(String step, List<String> pathSegments) {
        gas.charge(BexGasCounter.STEPS_READ);
        metrics.incrementStepsReads();
        return readValuePointer(context.steps().step(step), pathSegments);
    }

    public BexValue readResultValue(String absolutePointer, List<String> segments) {
        gas.charge(BexGasCounter.RESULT_VALUE_READ);
        metrics.incrementResultValueReads();
        return readValuePointer(accumulator.overlay().rootValue(), segments);
    }

    public BexValue defaultResultValue() {
        Map<String, BexValue> result = new LinkedHashMap<>();
        BexChangeset changeset = accumulator.changeset();
        result.put("changeset", changeset.asValue());
        result.put("events", accumulator.events().asValue());
        return BexValues.map(result);
    }

    public BexValue invokeIntrinsic(String blueId, BexValue type, Map<String, BexValue> fields) {
        return intrinsics.invoke(
                blueId, type, fields, gas, outputAdmission);
    }

    public BexValue nodeBlueId(BexValue value) {
        gas.charge(BexGasCounter.NODE_IDENTITY_REQUESTED);
        if (value == null || value.isUndefined()) {
            throw new blue.bex.BexException(
                    "$nodeBlueId operand must not be undefined");
        }
        if (value.isExact()) {
            return BexValues.scalar(value.exactBlueId());
        }
        return BexValues.scalar(outputAdmission
                .admit(value, BexOutputKind.NODE_IDENTITY)
                .nodeBlueId());
    }

    public String resolvePointer(String authoredPointer) {
        return context.document().resolvePointer(authoredPointer);
    }

    public List<String> parseDynamicPointer(String pointer) {
        return pointerCache.get(pointer, metrics).segments();
    }

    /**
     * Traverses one semantic value pointer with canonical per-segment read
     * ownership. The charge is admitted before examining each next member.
     */
    public BexValue readValuePointer(BexValue root, List<String> segments) {
        BexValue current = BexValues.referenceBacked(
                root != null ? root : BexValues.undefined(),
                blue);
        if (segments == null) {
            return current;
        }
        for (String segment : segments) {
            if (current.isUndefined()) {
                return current;
            }
            gas.charge(BexGasCounter.POINTER_SEGMENT_READ);
            if (current.isList()) {
                gas.charge(BexGasCounter.LIST_ITEM_READ);
            } else if (current.isObject()) {
                gas.charge(BexGasCounter.OBJECT_MEMBER_READ);
            }
            current = current.get(segment);
        }
        return current;
    }

    public String canonicalPointer(String pointer) {
        return JsonPointer.canonicalize(pointer);
    }

    private static BexGasMeter newGasMeter(BexExecutionContext context,
                                           BexGasSchedule gasSchedule,
                                           BexGasLedgerHost host,
                                           BexIntrinsicRegistry intrinsics,
                                           Set<String>
                                                   requiredIntrinsicBlueIds) {
        Map<String, Long> registered =
                intrinsics.registeredNamedWeights(
                        requiredIntrinsicBlueIds);
        Map<String, Map<String, Long>> namespaceWeights =
                intrinsics.registeredNamespaceWeights(
                        requiredIntrinsicBlueIds);
        if (host == null) {
            return new BexGasMeter(
                    gasSchedule,
                    context.parentRemainingGas(),
                    context.gasLimit(),
                    registered);
        }
        if (!host.separatesRuntimeNamespaces()) {
            if (!namespaceWeights.isEmpty()) {
                throw new IllegalArgumentException(
                        "A hosted intrinsic registry requires separate runtime namespaces");
            }
        }
        LinkedHashMap<String, GasMeter.ChildGasLedger> children =
                new LinkedHashMap<>();
        RuntimeWorkBudget sharedBudget = null;
        try {
            if (context.gasLimit() != BexGasMeter.NO_LOCAL_LIMIT) {
                sharedBudget =
                        host.openSharedBudget(context.gasLimit());
                if (sharedBudget != null
                        && sharedBudget.maximumGas()
                        != context.gasLimit()) {
                    throw new IllegalStateException(
                            "Gas host returned a shared budget with maximum "
                                    + sharedBudget.maximumGas()
                                    + " instead of "
                                    + context.gasLimit());
                }
            }
            children.put(
                    BexGasCounter.NAMESPACE,
                    requireOpenedLedger(
                            openHostLedger(
                                    host,
                                    BexGasCounter.NAMESPACE,
                                    gasSchedule.counterWeights(),
                                    sharedBudget),
                            BexGasCounter.NAMESPACE));
            for (Map.Entry<String, Map<String, Long>> intrinsic
                    : namespaceWeights.entrySet()) {
                children.put(
                        intrinsic.getKey(),
                        requireOpenedLedger(
                                openHostLedger(
                                        host,
                                        intrinsic.getKey(),
                                        intrinsic.getValue(),
                                        sharedBudget),
                                intrinsic.getKey()));
            }
            return sharedBudget == null
                    ? new BexGasMeter(
                            gasSchedule,
                            children,
                            context.gasLimit(),
                            registered)
                    : BexGasMeter.hostedWithSharedLocalLimit(
                            gasSchedule,
                            children,
                            context.gasLimit(),
                            registered);
        } catch (RuntimeException | Error openingFailure) {
            finishOpenedLedgersAfterConstructionFailure(
                    host, children, openingFailure);
            throw openingFailure;
        }
    }

    private static GasMeter.ChildGasLedger openHostLedger(
            BexGasLedgerHost host,
            String namespace,
            Map<String, Long> counterWeights,
            RuntimeWorkBudget sharedBudget) {
        return sharedBudget == null
                ? host.open(namespace, counterWeights)
                : host.open(
                        namespace,
                        counterWeights,
                        sharedBudget);
    }

    private static GasMeter.ChildGasLedger requireOpenedLedger(
            GasMeter.ChildGasLedger ledger,
            String namespace) {
        if (ledger == null) {
            throw new IllegalStateException(
                    "Gas host returned no child ledger for "
                            + namespace);
        }
        return ledger;
    }

    private static void finishOpenedLedgersAfterConstructionFailure(
            BexGasLedgerHost host,
            Map<String, GasMeter.ChildGasLedger> opened,
            Throwable openingFailure) {
        boolean unavailable =
                evidenceUnavailableWins(openingFailure);
        for (GasMeter.ChildGasLedger ledger : opened.values()) {
            try {
                if (unavailable) {
                    host.evidenceUnavailable(ledger);
                } else {
                    host.failedDeterministically(ledger);
                }
            } catch (RuntimeException | Error lifecycleFailure) {
                addSuppressed(
                        openingFailure, lifecycleFailure);
            }
        }
    }

    private void submitHostLedger() {
        if (gasLedgerHost == null
                || gas.hostLedgerFinalized()) {
            return;
        }
        gas.submitHostLedger(gasLedgerHost::submit);
    }

    private void finishHostLedgerAfterFailure(Throwable primaryFailure) {
        if (gasLedgerHost == null || gas.hostLedgerFinalized()) {
            return;
        }
        if (evidenceUnavailableWins(primaryFailure)) {
            notifyFailureLifecycle(
                    primaryFailure,
                    () -> gas.unavailableHostLedger(
                            gasLedgerHost::evidenceUnavailable));
            return;
        }

        GasLimitExceededException hostExhaustion =
                findCause(
                        primaryFailure,
                        GasLimitExceededException.class);
        if (hostExhaustion != null) {
            try {
                gas.propagateHostGasExhaustion(
                        hostExhaustion,
                        gasLedgerHost::failedDeterministically,
                        gasLedgerHost::propagateGasExhaustion);
            } catch (GasLimitExceededException canonical) {
                if (canonical == hostExhaustion) {
                    throw canonical;
                }
                addSuppressed(primaryFailure, canonical);
            } catch (RuntimeException | Error lifecycleFailure) {
                addSuppressed(primaryFailure, lifecycleFailure);
            }
            return;
        }

        BexGasLimitExceededException localExhaustion =
                findCause(
                        primaryFailure,
                        BexGasLimitExceededException.class);
        if (localExhaustion != null) {
            notifyFailureLifecycle(
                    primaryFailure,
                    () -> gas.failHostLedger(
                            gasLedgerHost::failedDeterministically));
            if (!(primaryFailure instanceof RuntimeException)) {
                return;
            }
            throw Objects.requireNonNull(
                    gasLedgerHost.localGasLimitExceeded(
                            localExhaustion,
                            (RuntimeException) primaryFailure),
                    "local gas-limit mapping");
        }

        notifyFailureLifecycle(
                primaryFailure,
                () -> gas.failHostLedger(
                        gasLedgerHost::failedDeterministically));
    }

    private static void notifyFailureLifecycle(
            Throwable primaryFailure,
            Runnable lifecycle) {
        try {
            lifecycle.run();
        } catch (RuntimeException | Error lifecycleFailure) {
            addSuppressed(primaryFailure, lifecycleFailure);
        }
    }

    private static void addSuppressed(
            Throwable primaryFailure,
            Throwable lifecycleFailure) {
        if (primaryFailure != lifecycleFailure) {
            primaryFailure.addSuppressed(lifecycleFailure);
        }
    }

    /**
     * Resolves lifecycle classification in causal order. A directly reported
     * deterministic category is authoritative and cannot be reclassified by
     * an unavailable exception nested below it.
     */
    private static boolean evidenceUnavailableWins(
            Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ProcessorFailureException
                    || current
                    instanceof InvalidExecutionEvidenceException
                    || current
                    instanceof PortableLimitExceededException
                    || current
                    instanceof GasLimitExceededException
                    || current
                    instanceof BexGasLimitExceededException) {
                return false;
            }
            if (current
                    instanceof ExecutionEvidenceUnavailableException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private static <T extends Throwable> T findCause(
            Throwable failure,
            Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

}
