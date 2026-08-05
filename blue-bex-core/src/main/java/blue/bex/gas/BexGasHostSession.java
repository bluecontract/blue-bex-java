package blue.bex.gas;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Validated hosted-ledger set with exactly-once lifecycle finalization. */
final class BexGasHostSession {
    private enum State {
        OPEN,
        SUBMITTED,
        FAILED,
        UNAVAILABLE,
        EXHAUSTED
    }

    private final Map<String, BexGasLedgerCapability> ledgers;
    private final boolean qualifiedCounters;
    private final boolean enforcesLocalLimit;
    private State state = State.OPEN;

    BexGasHostSession(
            Map<String, BexGasLedgerCapability> ledgers,
            boolean qualifiedCounters,
            boolean enforcesLocalLimit,
            long localLimit) {
        this.ledgers = immutableLedgers(ledgers);
        this.qualifiedCounters = qualifiedCounters;
        if (enforcesLocalLimit
                && (this.ledgers.isEmpty()
                || localLimit == BexGasMeter.NO_LOCAL_LIMIT)) {
            throw new IllegalArgumentException(
                    "Host-enforced local limits require hosted ledgers "
                            + "and a non-negative local limit");
        }
        this.enforcesLocalLimit = enforcesLocalLimit;
    }

    boolean isHosted() {
        return !ledgers.isEmpty();
    }

    boolean enforcesLocalLimit() {
        return enforcesLocalLimit;
    }

    BexGasLedgerCapability ledgerFor(String logicalNamespace) {
        if (ledgers.isEmpty()) {
            return null;
        }
        String selected = qualifiedCounters
                ? BexGasCounter.NAMESPACE
                : logicalNamespace;
        BexGasLedgerCapability ledger = ledgers.get(selected);
        if (ledger == null) {
            throw new IllegalStateException(
                    "No live host child ledger for logical namespace "
                            + logicalNamespace);
        }
        return ledger;
    }

    String physicalCounterName(
            String namespace,
            String counterName) {
        return qualifiedCounters
                ? BexGasMeter.qualifiedCounterName(namespace, counterName)
                : counterName;
    }

    boolean submitted() {
        return state == State.SUBMITTED;
    }

    boolean finalized() {
        return state != State.OPEN;
    }

    void ensureOpenForCharge() {
        if (state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot charge a finalized BEX host child ledger");
        }
    }

    void submit(Consumer<BexGasLedgerCapability> callback) {
        finalizeAll(
                State.SUBMITTED,
                Objects.requireNonNull(callback, "submitter"));
    }

    void fail(Consumer<BexGasLedgerCapability> callback) {
        finalizeAll(
                State.FAILED,
                Objects.requireNonNull(callback, "failureHandler"));
    }

    void unavailable(Consumer<BexGasLedgerCapability> callback) {
        finalizeAll(
                State.UNAVAILABLE,
                Objects.requireNonNull(callback, "unavailableHandler"));
    }

    void propagateExhaustion(
            BexHostGasExhaustion exhaustion,
            Consumer<BexGasLedgerCapability> prefixHandler,
            BiConsumer<BexGasLedgerCapability,
                    BexHostGasExhaustion> exhaustionHandler) {
        requireHostedOpen();
        state = State.EXHAUSTED;
        BexHostGasExhaustion exactExhaustion =
                Objects.requireNonNull(exhaustion, "exhaustion");
        Consumer<BexGasLedgerCapability> exactPrefixHandler =
                Objects.requireNonNull(prefixHandler, "prefixHandler");
        Throwable prefixFailure = null;
        for (BexGasLedgerCapability ledger : ledgers.values()) {
            try {
                exactPrefixHandler.accept(ledger);
            } catch (RuntimeException | Error failure) {
                prefixFailure = retainFailure(prefixFailure, failure);
            }
        }
        try {
            Objects.requireNonNull(
                    exhaustionHandler, "exhaustionHandler").accept(
                    rejectionLedger(exactExhaustion), exactExhaustion);
        } catch (RuntimeException | Error propagated) {
            if (prefixFailure != null && prefixFailure != propagated) {
                propagated.addSuppressed(prefixFailure);
            }
            throw propagated;
        }
        rethrowFailure(prefixFailure);
    }

    private void finalizeAll(
            State finalState,
            Consumer<BexGasLedgerCapability> callback) {
        requireHostedOpen();
        state = finalState;
        Throwable callbackFailure = null;
        for (BexGasLedgerCapability ledger : ledgers.values()) {
            try {
                callback.accept(ledger);
            } catch (RuntimeException | Error failure) {
                callbackFailure = retainFailure(callbackFailure, failure);
            }
        }
        rethrowFailure(callbackFailure);
    }

    private void requireHostedOpen() {
        if (ledgers.isEmpty()) {
            throw new IllegalStateException(
                    "This BEX gas meter has no host child ledgers");
        }
        if (state != State.OPEN) {
            throw new IllegalStateException(
                    "BEX host child ledger was already finalized as "
                            + state);
        }
    }

    private BexGasLedgerCapability rejectionLedger(
            BexHostGasExhaustion exhaustion) {
        for (BexGasLedgerCapability ledger : ledgers.values()) {
            if (ledger.namespace().equals(exhaustion.namespace())) {
                return ledger;
            }
        }
        return ledgers.get(BexGasCounter.NAMESPACE);
    }

    private static Throwable retainFailure(
            Throwable retained,
            Throwable next) {
        if (retained == null) {
            return next;
        }
        if (retained != next) {
            retained.addSuppressed(next);
        }
        return retained;
    }

    private static void rethrowFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    private static Map<String, BexGasLedgerCapability> immutableLedgers(
            Map<String, BexGasLedgerCapability> ledgers) {
        if (ledgers == null || ledgers.isEmpty()) {
            return Collections.emptyMap();
        }
        if (!ledgers.containsKey(BexGasCounter.NAMESPACE)) {
            throw new IllegalArgumentException(
                    "Hosted BEX ledger map must contain logical namespace "
                            + BexGasCounter.NAMESPACE);
        }
        LinkedHashMap<String, BexGasLedgerCapability> copy =
                new LinkedHashMap<>();
        IdentityHashMap<BexGasLedgerCapability, Boolean> identities =
                new IdentityHashMap<>();
        for (Map.Entry<String, BexGasLedgerCapability> entry
                : ledgers.entrySet()) {
            String namespace = requireName(
                    entry.getKey(), "Logical gas namespace");
            BexGasLedgerCapability ledger = Objects.requireNonNull(
                    entry.getValue(), "host child ledger");
            if (identities.put(ledger, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "Each logical runtime namespace requires a distinct "
                                + "host child ledger");
            }
            copy.put(namespace, ledger);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requireName(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
