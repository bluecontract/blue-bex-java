package blue.bex.api;

import blue.bex.gas.BexGasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasLimitExceededException;

import java.util.Map;
import java.util.Objects;

/**
 * Parent-runtime bridge for one live BEX named child ledger.
 *
 * <p>The ordinary {@link #submit(GasMeter.ChildGasLedger)} callback is the
 * success path only.  Failure callbacks are separate because a generic
 * processor work session owns final retention or discard, while older
 * detached hosts merged deterministic prefixes directly.</p>
 */
public interface BexGasLedgerHost {
    GasMeter.ChildGasLedger open(String namespace, Map<String, Long> counterWeights);

    void submit(GasMeter.ChildGasLedger ledger);

    /**
     * Whether this host can own separate live child ledgers for BEX and each
     * registered intrinsic namespace.
     *
     * <p>The default requires physical separation. A compatibility host may
     * return {@code false} only when no intrinsic namespace is registered;
     * hosted execution never flattens intrinsic counters into {@code bex}.</p>
     */
    default boolean separatesRuntimeNamespaces() {
        return true;
    }

    /**
     * Reports deterministic failure after the ledger admitted a prefix.
     *
     * <p>Every host must classify this path explicitly. Session-backed
     * adapters leave the ledger staged because the enclosing processor
     * failure path retains every admitted prefix exactly once.</p>
     */
    void failedDeterministically(GasMeter.ChildGasLedger ledger);

    /**
     * Reports transient evidence unavailability.
     *
     * <p>Every host must classify this path explicitly. A session-backed host
     * leaves the reservation staged so its enclosing suspension can discard
     * it.</p>
     */
    void evidenceUnavailable(GasMeter.ChildGasLedger ledger);

    /**
     * Maps a BEX-local sub-limit rejection after every admitted ledger prefix
     * has been reported through {@link #failedDeterministically}.
     *
     * <p>A detached host retains the exact BEX exception.  A processor adapter
     * can expose its stable gas-limit category without inventing a structured
     * host rejection that its live session did not produce.</p>
     */
    default RuntimeException localGasLimitExceeded(
            BexGasLimitExceededException exhaustion,
            RuntimeException originalFailure) {
        Objects.requireNonNull(exhaustion, "exhaustion");
        return Objects.requireNonNull(
                originalFailure, "originalFailure");
    }

    /**
     * Returns a host-recorded rejected charge through the canonical host
     * exhaustion path.
     *
     * <p>BEX reports every opened ledger through
     * {@link #failedDeterministically(GasMeter.ChildGasLedger)} first, so the
     * detached-host default only needs to rethrow the exact exception.
     * Session-backed hosts delegate to their owning runtime work session,
     * which validates that the same exception was recorded live.</p>
     */
    default void propagateGasExhaustion(
            GasMeter.ChildGasLedger ledger,
            GasLimitExceededException exhaustion) {
        Objects.requireNonNull(ledger, "ledger");
        throw Objects.requireNonNull(exhaustion, "exhaustion");
    }
}
