package blue.bex.test;

import blue.bex.gas.BexGasChargeContext;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.gas.BexHostGasExhaustion;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;

import java.util.Map;
import java.util.Objects;

/** Test-only adapter for detached Contracts gas ledgers. */
public final class TestGasLedgerCapability
        implements BexGasLedgerCapability {
    private final GasMeter.ChildGasLedger delegate;

    private TestGasLedgerCapability(GasMeter.ChildGasLedger delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static TestGasLedgerCapability wrap(
            GasMeter.ChildGasLedger delegate) {
        return new TestGasLedgerCapability(delegate);
    }

    public GasMeter.ChildGasLedger delegate() {
        return delegate;
    }

    @Override public String namespace() { return delegate.namespace(); }
    @Override public long totalGas() { return delegate.totalGas(); }
    @Override public long remainingGas() { return delegate.remainingGas(); }
    @Override public long effectiveBudget() { return delegate.effectiveBudget(); }
    @Override public Map<String, Long> counterWeights() {
        return delegate.counterWeights();
    }

    @Override
    public void charge(
            String counter,
            long quantity,
            BexGasChargeContext context) {
        BexGasChargeContext exact = context != null
                ? context : BexGasChargeContext.empty();
        try {
            delegate.charge(
                    counter,
                    quantity,
                    GasChargeContext.of(
                            exact.scopePath(),
                            exact.contractKey(),
                            exact.logicalPath(),
                            exact.reason()));
        } catch (GasLimitExceededException exhausted) {
            throw new BexHostGasExhaustion(
                    exhausted.namespace(),
                    exhausted.counter(),
                    exhausted.quantity(),
                    exhausted.weight(),
                    exhausted.admittedGas(),
                    exhausted.effectiveBudget(),
                    exhausted);
        }
    }
}
