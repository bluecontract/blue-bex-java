package blue.bex.gas;

/** Invocation-owned local gas budget shared by hosted BEX ledgers. */
public interface BexSharedGasBudget {
    long maximumGas();
    long admittedGas();
    long remainingGas();
}
