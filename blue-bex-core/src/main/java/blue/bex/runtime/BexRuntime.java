package blue.bex.runtime;

import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.BexCompiledProgramRuntimeAccess;
import blue.bex.compile.BexExecutionMachine;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexAdmittedValue;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexOutputKind;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexChangeset;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetricsRecorder;
import blue.bex.result.BexPatchEntry;
import blue.bex.result.BexResultOverlay;
import blue.bex.type.BexBlueTypeMatcher;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.runtime.BlueLanguage;
import blue.language.model.wire.JsonPointer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime for one compiled BEX execution.
 */
public final class BexRuntime implements BexExecutionMachine {
    private final BexCompiledProgram program;
    private final BexRuntimeContext context;
    private final BexGasMeter gas;
    private final BexMetricsRecorder metrics;
    private final BexPointerCache pointerCache;
    private final BexExecutionAccumulator accumulator;
    private final BexBlueTypeMatcher typeMatcher;
    private final BlueLanguage blue;
    private final BexRuntimeIntrinsics intrinsics;
    private final BexOutputAdmission outputAdmission;
    private final BexRuntimeGasSession gasSession;
    private final BexResultOverlay rollbackOverlay;

    public BexRuntime(BexCompiledProgram program,
                      BexRuntimeContext context,
                      BlueLanguage blue,
                      BexGasSchedule gasSchedule,
                      BexMetricsRecorder metrics,
                      BexPointerCache pointerCache) {
        this(program, context, blue, gasSchedule, metrics, pointerCache,
                BexRuntimeIntrinsics.EMPTY);
    }

    public BexRuntime(BexCompiledProgram program,
                      BexRuntimeContext context,
                      BlueLanguage blue,
                      BexGasSchedule gasSchedule,
                      BexMetricsRecorder metrics,
                      BexPointerCache pointerCache,
                      BexRuntimeIntrinsics intrinsics) {
        this.program = program;
        this.context = context;
        this.blue = blue;
        this.intrinsics = intrinsics != null
                ? intrinsics
                : BexRuntimeIntrinsics.EMPTY;
        this.gasSession = BexRuntimeGasSession.open(
                context,
                gasSchedule,
                this.intrinsics,
                program.requiredIntrinsicBlueIds());
        this.gas = gasSession.meter();
        this.metrics = metrics;
        this.pointerCache = pointerCache;
        this.outputAdmission = new BexOutputAdmission(
                gas,
                context.semanticIdentityBoundary(),
                context.failureBoundary());
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
            BexValue value = BexCompiledProgramRuntimeAccess.execute(
                    program, this);
            BexAdmittedValue output =
                    outputAdmission.admit(value, BexOutputKind.ROOT_RESULT);
            BexExecutionResult result = new BexExecutionResult(
                    value,
                    accumulator.changeset(),
                    accumulator.events(),
                    gas.ledger(),
                    metrics.snapshot(),
                    output);
            gasSession.completeSuccessfully();
            return result;
        } catch (RuntimeException | Error ex) {
            accumulator.discard(rollbackOverlay);
            gasSession.completeAfterFailure(ex);
            if (ex instanceof RuntimeException) {
                throw context.failureBoundary().translate(
                        (RuntimeException) ex);
            }
            throw ex;
        }
    }

    public BexCompiledProgram program() { return program; }
    public BexRuntimeContext context() { return context; }
    public BexGasMeter gas() { return gas; }
    public BexMetricsRecorder metrics() { return metrics; }
    public BexPointerCache pointerCache() { return pointerCache; }
    public BexExecutionAccumulator accumulator() { return accumulator; }
    public BexBlueTypeMatcher typeMatcher() { return typeMatcher; }
    public BexRuntimeIntrinsics intrinsics() { return intrinsics; }
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

    @Override
    public boolean matchesType(
            BexValue value,
            blue.language.snapshot.FrozenNode pattern,
            blue.bex.BexSourcePath sourcePath) {
        return typeMatcher.matches(value, pattern, gas, sourcePath);
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

    @Override
    public void appendChange(BexPatchEntry entry) {
        accumulator.appendChange(entry);
    }

    @Override
    public void appendEvent(BexValue event) {
        accumulator.appendEvent(event);
    }

    @Override
    public BexValue changesetValue() {
        return accumulator.changeset().asValue();
    }

    @Override
    public BexValue eventsValue() {
        return accumulator.events().asValue();
    }

}
