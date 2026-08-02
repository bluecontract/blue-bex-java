package blue.bex.gas;

/** Immutable parent/local budget configuration for one meter. */
final class BexGasBudget {
    private final long parentRemainingGas;
    private final long localLimit;
    private final long effectiveBudget;

    BexGasBudget(long parentRemainingGas, long localLimit) {
        if (parentRemainingGas < 0L) {
            throw new IllegalArgumentException(
                    "parentRemainingGas must be non-negative");
        }
        if (localLimit < BexGasMeter.NO_LOCAL_LIMIT) {
            throw new IllegalArgumentException(
                    "localLimit must be non-negative or NO_LOCAL_LIMIT");
        }
        this.parentRemainingGas = parentRemainingGas;
        this.localLimit = localLimit;
        this.effectiveBudget = localLimit == BexGasMeter.NO_LOCAL_LIMIT
                ? parentRemainingGas
                : Math.min(parentRemainingGas, localLimit);
    }

    long parentRemainingGas() { return parentRemainingGas; }
    long localLimit() { return localLimit; }
    long effectiveBudget() { return effectiveBudget; }

    long admissionLimit(
            boolean hosted,
            boolean hostEnforcesLocalLimit) {
        return !hosted
                ? effectiveBudget
                : hostEnforcesLocalLimit
                ? BexGasMeter.NO_LOCAL_LIMIT
                : localLimit;
    }

    long remaining(long admittedGas) {
        return effectiveBudget - admittedGas;
    }
}
