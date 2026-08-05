package blue.bex.gas;

import java.util.Map;
import java.util.Objects;

/** Host-neutral lifecycle for live BEX and intrinsic gas ledgers. */
public interface BexGasLedgerLifecycle {
    BexGasLedgerCapability open(
            String namespace,
            Map<String, Long> counterWeights);

    default BexSharedGasBudget openSharedBudget(long maximumGas) {
        if (maximumGas < 0L) {
            throw new IllegalArgumentException(
                    "Shared BEX gas budget must be non-negative");
        }
        return null;
    }

    default BexGasLedgerCapability open(
            String namespace,
            Map<String, Long> counterWeights,
            BexSharedGasBudget sharedBudget) {
        if (sharedBudget != null) {
            throw new UnsupportedOperationException(
                    "This gas host cannot attach a shared runtime work budget");
        }
        return open(namespace, counterWeights);
    }

    void submit(BexGasLedgerCapability ledger);

    default boolean separatesRuntimeNamespaces() {
        return true;
    }

    void failedDeterministically(BexGasLedgerCapability ledger);

    void evidenceUnavailable(BexGasLedgerCapability ledger);

    default RuntimeException localGasLimitExceeded(
            BexGasLimitExceededException exhaustion,
            RuntimeException originalFailure) {
        Objects.requireNonNull(exhaustion, "exhaustion");
        return Objects.requireNonNull(originalFailure, "originalFailure");
    }

    default void propagateGasExhaustion(
            BexGasLedgerCapability ledger,
            BexHostGasExhaustion exhaustion) {
        Objects.requireNonNull(ledger, "ledger");
        throw Objects.requireNonNull(
                exhaustion, "exhaustion").hostFailure();
    }
}
