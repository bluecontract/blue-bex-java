package blue.bex.runtime;

import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.gas.BexGasLedgerLifecycle;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.gas.BexHostGasExhaustion;
import blue.bex.gas.BexSharedGasBudget;
import blue.bex.output.BexFailurePolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns construction and exactly-once finalization of one runtime gas set. */
final class BexRuntimeGasSession {
    private final BexRuntimeContext context;
    private final BexGasLedgerLifecycle host;
    private final BexGasMeter meter;

    private BexRuntimeGasSession(
            BexRuntimeContext context,
            BexGasLedgerLifecycle host,
            BexGasMeter meter) {
        this.context = context;
        this.host = host;
        this.meter = meter;
    }

    static BexRuntimeGasSession open(
            BexRuntimeContext context,
            BexGasSchedule schedule,
            BexRuntimeIntrinsics intrinsics,
            Set<String> requiredIntrinsicBlueIds) {
        BexGasLedgerLifecycle host = context.gasLedgerHost();
        return new BexRuntimeGasSession(
                context,
                host,
                openMeter(
                        context,
                        schedule,
                        host,
                        intrinsics,
                        requiredIntrinsicBlueIds));
    }

    BexGasMeter meter() {
        return meter;
    }

    void completeSuccessfully() {
        if (host != null && !meter.hostLedgerFinalized()) {
            meter.submitHostLedger(host::submit);
        }
    }

    void completeAfterFailure(Throwable primaryFailure) {
        if (host == null || meter.hostLedgerFinalized()) {
            return;
        }
        if (evidenceUnavailableWins(
                primaryFailure, context.failureBoundary())) {
            notifyFailureLifecycle(
                    primaryFailure,
                    () -> meter.unavailableHostLedger(
                            host::evidenceUnavailable));
            return;
        }

        BexHostGasExhaustion hostExhaustion = findCause(
                primaryFailure, BexHostGasExhaustion.class);
        if (hostExhaustion != null) {
            try {
                meter.propagateHostGasExhaustion(
                        hostExhaustion,
                        host::failedDeterministically,
                        host::propagateGasExhaustion);
            } catch (RuntimeException canonical) {
                if (canonical == hostExhaustion.hostFailure()) {
                    throw canonical;
                }
                addSuppressed(primaryFailure, canonical);
            } catch (Error lifecycleFailure) {
                addSuppressed(primaryFailure, lifecycleFailure);
            }
            return;
        }

        BexGasLimitExceededException localExhaustion = findCause(
                primaryFailure, BexGasLimitExceededException.class);
        if (localExhaustion != null) {
            notifyFailureLifecycle(
                    primaryFailure,
                    () -> meter.failHostLedger(
                            host::failedDeterministically));
            if (primaryFailure instanceof RuntimeException) {
                throw Objects.requireNonNull(
                        host.localGasLimitExceeded(
                                localExhaustion,
                                (RuntimeException) primaryFailure),
                        "local gas-limit mapping");
            }
            return;
        }
        notifyFailureLifecycle(
                primaryFailure,
                () -> meter.failHostLedger(
                        host::failedDeterministically));
    }

    private static BexGasMeter openMeter(
            BexRuntimeContext context,
            BexGasSchedule schedule,
            BexGasLedgerLifecycle host,
            BexRuntimeIntrinsics intrinsics,
            Set<String> requiredIntrinsicBlueIds) {
        Map<String, Long> registered =
                intrinsics.registeredNamedWeights(
                        requiredIntrinsicBlueIds);
        Map<String, Map<String, Long>> namespaceWeights =
                intrinsics.registeredNamespaceWeights(
                        requiredIntrinsicBlueIds);
        if (host == null) {
            return new BexGasMeter(
                    schedule,
                    context.parentRemainingGas(),
                    context.gasLimit(),
                    registered);
        }
        if (!host.separatesRuntimeNamespaces()
                && !namespaceWeights.isEmpty()) {
            throw new IllegalArgumentException(
                    "A hosted intrinsic registry requires separate runtime namespaces");
        }
        LinkedHashMap<String, BexGasLedgerCapability> children =
                new LinkedHashMap<>();
        BexSharedGasBudget sharedBudget = null;
        try {
            if (context.gasLimit() != BexGasMeter.NO_LOCAL_LIMIT) {
                sharedBudget = host.openSharedBudget(context.gasLimit());
                if (sharedBudget != null
                        && sharedBudget.maximumGas() != context.gasLimit()) {
                    throw new IllegalStateException(
                            "Gas host returned a shared budget with maximum "
                                    + sharedBudget.maximumGas()
                                    + " instead of " + context.gasLimit());
                }
            }
            children.put(
                    BexGasCounter.NAMESPACE,
                    requireOpenedLedger(
                            openHostLedger(
                                    host,
                                    BexGasCounter.NAMESPACE,
                                    schedule.counterWeights(),
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
                    schedule,
                    children,
                    context.gasLimit(),
                    registered)
                    : BexGasMeter.hostedWithSharedLocalLimit(
                    schedule,
                    children,
                    context.gasLimit(),
                    registered);
        } catch (RuntimeException | Error openingFailure) {
            finishOpenedAfterConstructionFailure(
                    host,
                    children,
                    openingFailure,
                    context.failureBoundary());
            throw openingFailure;
        }
    }

    private static BexGasLedgerCapability openHostLedger(
            BexGasLedgerLifecycle host,
            String namespace,
            Map<String, Long> counterWeights,
            BexSharedGasBudget sharedBudget) {
        return sharedBudget == null
                ? host.open(namespace, counterWeights)
                : host.open(namespace, counterWeights, sharedBudget);
    }

    private static BexGasLedgerCapability requireOpenedLedger(
            BexGasLedgerCapability ledger,
            String namespace) {
        if (ledger == null) {
            throw new IllegalStateException(
                    "Gas host returned no child ledger for " + namespace);
        }
        return ledger;
    }

    private static void finishOpenedAfterConstructionFailure(
            BexGasLedgerLifecycle host,
            Map<String, BexGasLedgerCapability> opened,
            Throwable openingFailure,
            BexFailurePolicy failureBoundary) {
        boolean unavailable = evidenceUnavailableWins(
                openingFailure, failureBoundary);
        for (BexGasLedgerCapability ledger : opened.values()) {
            try {
                if (unavailable) {
                    host.evidenceUnavailable(ledger);
                } else {
                    host.failedDeterministically(ledger);
                }
            } catch (RuntimeException | Error lifecycleFailure) {
                addSuppressed(openingFailure, lifecycleFailure);
            }
        }
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

    private static boolean evidenceUnavailableWins(
            Throwable failure,
            BexFailurePolicy failureBoundary) {
        return Objects.requireNonNull(
                failureBoundary, "failureBoundary")
                .evidenceUnavailable(failure);
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
