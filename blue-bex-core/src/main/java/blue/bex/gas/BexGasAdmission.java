package blue.bex.gas;

/**
 * Performs one fail-before-work gas admission against local and hosted
 * budgets, then records the admitted charge.
 */
final class BexGasAdmission {
    private final BexGasBudget budget;
    private final BexGasHostSession hostSession;
    private final BexGasTraceRecorder traceRecorder;

    BexGasAdmission(
            BexGasBudget budget,
            BexGasHostSession hostSession,
            BexGasTraceRecorder traceRecorder) {
        this.budget = budget;
        this.hostSession = hostSession;
        this.traceRecorder = traceRecorder;
    }

    void admit(
            String namespace,
            String counterName,
            BexGasCounter portableCounter,
            long quantity,
            long weight,
            String sourcePath,
            String operator,
            String reason) {
        hostSession.ensureOpenForCharge();
        if (quantity < 0L) {
            throw new IllegalArgumentException(
                    "Gas quantity must be non-negative");
        }
        if (quantity == 0L || weight == 0L) {
            return;
        }
        String exactReason = requireReason(reason);
        long gas = multiplyExact(quantity, weight);

        /*
         * A hosted meter prechecks only the optional BEX-local sub-limit. The
         * processor-owned child ledger remains the sole authority for the live
         * parent budget, including reservations consumed after this meter was
         * opened. A standalone meter has no such owner and therefore checks
         * the complete effective budget itself.
         */
        long localAdmissionBudget = budget.admissionLimit(
                hostSession.isHosted(),
                hostSession.enforcesLocalLimit());
        if (localAdmissionBudget != BexGasMeter.NO_LOCAL_LIMIT
                && gas > localAdmissionBudget - traceRecorder.totalGas()) {
            throw exhausted(
                    namespace,
                    counterName,
                    portableCounter,
                    quantity,
                    weight,
                    null);
        }

        BexGasLedgerCapability hostLedger = hostSession.ledgerFor(namespace);
        if (hostLedger != null) {
            try {
                hostLedger.charge(
                        hostSession.physicalCounterName(
                                namespace, counterName),
                        quantity,
                        BexGasChargeContext.of(
                                emptyToNull(sourcePath),
                                null,
                                emptyToNull(operator),
                                exactReason));
            } catch (BexHostGasExhaustion exhaustion) {
                /*
                 * Retain the exact host rejection so its owning work session
                 * can validate and propagate that same object. The rejected
                 * entry remains absent from the local trace.
                 */
                throw exhausted(
                        namespace,
                        counterName,
                        portableCounter,
                        quantity,
                        weight,
                        exhaustion);
            }
        }

        traceRecorder.append(
                namespace,
                counterName,
                quantity,
                weight,
                gas,
                sourcePath,
                operator,
                exactReason);
    }

    private BexGasLimitExceededException exhausted(
            String namespace,
            String counterName,
            BexGasCounter portableCounter,
            long quantity,
            long weight,
            BexHostGasExhaustion hostGasExhaustion) {
        if (portableCounter != null) {
            return new BexGasLimitExceededException(
                    portableCounter,
                    quantity,
                    weight,
                    traceRecorder.totalGas(),
                    budget.effectiveBudget(),
                    hostGasExhaustion);
        }
        return new BexGasLimitExceededException(
                namespace,
                counterName,
                quantity,
                weight,
                traceRecorder.totalGas(),
                budget.effectiveBudget(),
                hostGasExhaustion);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String requireReason(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Gas charge reason is required");
        }
        return value;
    }

    private static long multiplyExact(long left, long right) {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw new IllegalArgumentException(
                    "Gas subtotal exceeds long range");
        }
        return left * right;
    }
}
