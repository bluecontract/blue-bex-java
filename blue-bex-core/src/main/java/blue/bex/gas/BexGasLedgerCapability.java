package blue.bex.gas;

import java.util.Map;

/**
 * Narrow live-ledger capability consumed by pure BEX core.
 */
public interface BexGasLedgerCapability {
    String namespace();
    long totalGas();
    long remainingGas();
    long effectiveBudget();
    Map<String, Long> counterWeights();

    default void charge(String counter, long quantity) {
        charge(counter, quantity, BexGasChargeContext.empty());
    }

    void charge(String counter, long quantity, BexGasChargeContext context);
}
