package blue.bex.api;

import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.RuntimeWorkSession;

import java.util.Map;
import java.util.Objects;

/**
 * Contracts 1.0 host adapter for BEX's parent-bounded runtime ledger.
 *
 * <p>The adapter opens the logical BEX ledger under one deterministic
 * physical runtime namespace.  Callers executing more than one BEX program in
 * a host invocation must provide distinct physical namespaces; all such
 * ledgers remain owned by the same {@link RuntimeWorkSession} and therefore
 * share its live parent budget.</p>
 */
public final class ProcessorExecutionContextBexGasLedgerHost implements BexGasLedgerHost {
    private final RuntimeWorkSession session;
    private final String runtimeNamespace;

    public ProcessorExecutionContextBexGasLedgerHost(ProcessorExecutionContext context) {
        this(context, BexGasCounter.NAMESPACE);
    }

    public ProcessorExecutionContextBexGasLedgerHost(
            ProcessorExecutionContext context,
            String runtimeNamespace) {
        this(Objects.requireNonNull(context, "context").runtimeWorkSession(),
                runtimeNamespace);
    }

    public ProcessorExecutionContextBexGasLedgerHost(
            RuntimeWorkSession session,
            String runtimeNamespace) {
        this.session = Objects.requireNonNull(session, "session");
        this.runtimeNamespace =
                requireRuntimeNamespace(runtimeNamespace);
    }

    @Override
    public GasMeter.ChildGasLedger open(String namespace,
                                        Map<String, Long> counterWeights) {
        String logicalNamespace =
                requireRuntimeNamespace(namespace);
        return session.openLedger(
                physicalNamespace(logicalNamespace),
                counterWeights);
    }

    @Override
    public void submit(GasMeter.ChildGasLedger ledger) {
        session.submit(ledger);
    }

    @Override
    public boolean separatesRuntimeNamespaces() {
        return true;
    }

    /**
     * The enclosing processor failure owns prefix retention.  BEX must leave
     * this ledger staged and unsubmitted.
     */
    @Override
    public void failedDeterministically(GasMeter.ChildGasLedger ledger) {
        Objects.requireNonNull(ledger, "ledger");
    }

    /**
     * The enclosing processor suspension owns reservation discard.  BEX must
     * leave this ledger staged and unsubmitted.
     */
    @Override
    public void evidenceUnavailable(GasMeter.ChildGasLedger ledger) {
        Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public RuntimeException localGasLimitExceeded(
            BexGasLimitExceededException exhaustion,
            RuntimeException originalFailure) {
        BexGasLimitExceededException exact =
                Objects.requireNonNull(exhaustion, "exhaustion");
        Objects.requireNonNull(
                originalFailure, "originalFailure");
        return new ProcessorFailureException(
                ProcessorErrorCategory.GasLimitExceeded,
                exact.getMessage(),
                exact);
    }

    @Override
    public void propagateGasExhaustion(
            GasMeter.ChildGasLedger ledger,
            GasLimitExceededException exhaustion) {
        Objects.requireNonNull(ledger, "ledger");
        session.propagateGasExhaustion(
                Objects.requireNonNull(exhaustion, "exhaustion"));
    }

    public String runtimeNamespace() {
        return runtimeNamespace;
    }

    /**
     * Returns the deterministic physical session namespace for one logical
     * portable ledger.  The primary BEX ledger uses the configured namespace
     * verbatim; intrinsic ledgers are separate children below it.
     */
    public String physicalNamespace(String logicalNamespace) {
        String exactLogical =
                requireRuntimeNamespace(logicalNamespace);
        return BexGasCounter.NAMESPACE.equals(exactLogical)
                ? runtimeNamespace
                : runtimeNamespace + "/" + exactLogical;
    }

    private static String requireRuntimeNamespace(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "runtimeNamespace is required");
        }
        if (value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "runtimeNamespace must not contain the reserved '/' separator");
        }
        return value;
    }
}
