package blue.bex.contracts;

import blue.bex.api.BexGasLedgerHost;
import blue.bex.gas.BexGasChargeContext;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexHostGasExhaustion;
import blue.bex.gas.BexSharedGasBudget;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.RuntimeWorkBudget;
import blue.language.processor.RuntimeWorkSession;

import java.util.Map;
import java.util.Objects;

/** Contracts 1.0 adapter for BEX's host-neutral gas capabilities. */
public final class ProcessorExecutionContextBexGasLedgerHost
        implements BexGasLedgerHost {
    private final ProcessorExecutionContext context;
    private final RuntimeWorkSession session;
    private final String runtimeNamespace;

    public ProcessorExecutionContextBexGasLedgerHost(
            ProcessorExecutionContext context) {
        this(context, BexGasCounter.NAMESPACE);
    }

    public ProcessorExecutionContextBexGasLedgerHost(
            ProcessorExecutionContext context,
            String runtimeNamespace) {
        this.context = Objects.requireNonNull(context, "context");
        this.session = null;
        this.runtimeNamespace = requireRuntimeNamespace(runtimeNamespace);
    }

    public ProcessorExecutionContextBexGasLedgerHost(
            RuntimeWorkSession session,
            String runtimeNamespace) {
        this.context = null;
        this.session = Objects.requireNonNull(session, "session");
        this.runtimeNamespace = requireRuntimeNamespace(runtimeNamespace);
    }

    @Override
    public BexGasLedgerCapability open(
            String namespace,
            Map<String, Long> counterWeights) {
        return open(namespace, counterWeights, null);
    }

    @Override
    public BexSharedGasBudget openSharedBudget(long maximumGas) {
        return session != null
                ? new ContractsSharedGasBudget(
                        this, session.openSharedBudget(maximumGas))
                : BexGasLedgerHost.super.openSharedBudget(maximumGas);
    }

    @Override
    public BexGasLedgerCapability open(
            String namespace,
            Map<String, Long> counterWeights,
            BexSharedGasBudget sharedBudget) {
        String logicalNamespace = requireRuntimeNamespace(namespace);
        String physicalNamespace = physicalNamespace(logicalNamespace);
        GasMeter.ChildGasLedger ledger;
        if (session != null) {
            RuntimeWorkBudget budget = sharedBudget == null
                    ? null
                    : requireSharedBudget(sharedBudget).delegate;
            ledger = session.openLedger(
                    physicalNamespace, counterWeights, budget);
        } else {
            if (sharedBudget != null) {
                throw new IllegalArgumentException(
                        "ProcessorExecutionContext does not expose shared runtime budgets");
            }
            ledger = context.newRuntimeGasLedger(
                    physicalNamespace, counterWeights);
        }
        return new ContractsGasLedger(this, ledger);
    }

    @Override
    public void submit(BexGasLedgerCapability ledger) {
        GasMeter.ChildGasLedger exact = requireLedger(ledger).delegate;
        if (session != null) {
            session.submit(exact);
        } else {
            context.submitRuntimeGasLedger(exact);
        }
    }

    @Override
    public boolean separatesRuntimeNamespaces() {
        return true;
    }

    @Override
    public void failedDeterministically(BexGasLedgerCapability ledger) {
        requireLedger(ledger);
    }

    @Override
    public void evidenceUnavailable(BexGasLedgerCapability ledger) {
        requireLedger(ledger);
    }

    @Override
    public RuntimeException localGasLimitExceeded(
            BexGasLimitExceededException exhaustion,
            RuntimeException originalFailure) {
        BexGasLimitExceededException exact =
                Objects.requireNonNull(exhaustion, "exhaustion");
        Objects.requireNonNull(originalFailure, "originalFailure");
        return new ProcessorFailureException(
                ProcessorErrorCategory.GasLimitExceeded,
                exact.getMessage(),
                exact);
    }

    @Override
    public void propagateGasExhaustion(
            BexGasLedgerCapability ledger,
            BexHostGasExhaustion exhaustion) {
        requireLedger(ledger);
        RuntimeException nativeFailure = Objects.requireNonNull(
                exhaustion, "exhaustion").hostFailure();
        if (!(nativeFailure instanceof GasLimitExceededException)) {
            throw new IllegalArgumentException(
                    "Contracts gas exhaustion must retain its exact host rejection",
                    nativeFailure);
        }
        GasLimitExceededException exact =
                (GasLimitExceededException) nativeFailure;
        if (session != null) {
            session.propagateGasExhaustion(exact);
        }
        throw exact;
    }

    public String runtimeNamespace() {
        return runtimeNamespace;
    }

    public String physicalNamespace(String logicalNamespace) {
        String exactLogical = requireRuntimeNamespace(logicalNamespace);
        return BexGasCounter.NAMESPACE.equals(exactLogical)
                ? runtimeNamespace
                : runtimeNamespace + "/" + exactLogical;
    }

    private ContractsGasLedger requireLedger(
            BexGasLedgerCapability ledger) {
        if (!(ledger instanceof ContractsGasLedger)
                || ((ContractsGasLedger) ledger).owner != this) {
            throw new IllegalArgumentException(
                    "Gas ledger was not opened by this Contracts adapter");
        }
        return (ContractsGasLedger) ledger;
    }

    private ContractsSharedGasBudget requireSharedBudget(
            BexSharedGasBudget budget) {
        if (!(budget instanceof ContractsSharedGasBudget)
                || ((ContractsSharedGasBudget) budget).owner != this) {
            throw new IllegalArgumentException(
                    "Shared gas budget was not opened by this Contracts adapter");
        }
        return (ContractsSharedGasBudget) budget;
    }

    private static String requireRuntimeNamespace(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("runtimeNamespace is required");
        }
        if (value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "runtimeNamespace must not contain the reserved '/' separator");
        }
        return value;
    }

    private static final class ContractsSharedGasBudget
            implements BexSharedGasBudget {
        private final ProcessorExecutionContextBexGasLedgerHost owner;
        private final RuntimeWorkBudget delegate;

        private ContractsSharedGasBudget(
                ProcessorExecutionContextBexGasLedgerHost owner,
                RuntimeWorkBudget delegate) {
            this.owner = owner;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public long maximumGas() { return delegate.maximumGas(); }
        @Override public long admittedGas() { return delegate.admittedGas(); }
        @Override public long remainingGas() { return delegate.remainingGas(); }
    }

    private static final class ContractsGasLedger
            implements BexGasLedgerCapability {
        private final ProcessorExecutionContextBexGasLedgerHost owner;
        private final GasMeter.ChildGasLedger delegate;

        private ContractsGasLedger(
                ProcessorExecutionContextBexGasLedgerHost owner,
                GasMeter.ChildGasLedger delegate) {
            this.owner = owner;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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
}
