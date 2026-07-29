package blue.bex.gas;

import blue.bex.BexSourcePath;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Deterministic, live-bounded BEX 2.0 named gas meter.
 *
 * <p>Every charge is checked and, when host-backed, admitted to the shared
 * child ledger before the corresponding local trace entry is appended and
 * before the caller performs its work. A charge which cannot fit is absent
 * from both traces.</p>
 */
public final class BexGasMeter {
    /** Sentinel accepted by local-limit constructors for no local sub-limit. */
    public static final long NO_LOCAL_LIMIT = -1L;

    private final BexGasSchedule schedule;
    private final long parentRemainingGas;
    private final long localLimit;
    private final long effectiveBudget;
    private final Map<String, GasMeter.ChildGasLedger> hostLedgers;
    private final boolean qualifiedHostCounters;
    private final boolean hostEnforcesLocalLimit;
    private final Map<String, Long> registeredNamedWeights;
    private final List<BexGasCharge> trace = new ArrayList<>();
    private long totalGas;
    private HostLedgerState hostLedgerState = HostLedgerState.OPEN;

    private enum HostLedgerState {
        OPEN,
        SUBMITTED,
        FAILED,
        UNAVAILABLE,
        EXHAUSTED
    }

    /**
     * Creates a standalone meter bounded by the supplied parent budget.
     */
    public BexGasMeter(BexGasSchedule schedule, long parentRemainingGas) {
        this(schedule,
                requireBudget(parentRemainingGas, "parentRemainingGas"),
                NO_LOCAL_LIMIT,
                Collections.<String, GasMeter.ChildGasLedger>emptyMap(),
                false,
                false,
                Collections.<String, Long>emptyMap());
    }

    /**
     * Creates a standalone meter whose local BEX limit can only reduce the
     * supplied parent budget.
     */
    public BexGasMeter(BexGasSchedule schedule,
                       long parentRemainingGas,
                       long localLimit) {
        this(schedule,
                requireBudget(parentRemainingGas, "parentRemainingGas"),
                requireLocalLimit(localLimit),
                Collections.<String, GasMeter.ChildGasLedger>emptyMap(),
                false,
                false,
                Collections.<String, Long>emptyMap());
    }

    /**
     * Creates a standalone meter with registry-bound named child counters.
     */
    public BexGasMeter(BexGasSchedule schedule,
                       long parentRemainingGas,
                       long localLimit,
                       Map<String, Long> registeredNamedWeights) {
        this(schedule,
                requireBudget(parentRemainingGas, "parentRemainingGas"),
                requireLocalLimit(localLimit),
                Collections.<String, GasMeter.ChildGasLedger>emptyMap(),
                false,
                false,
                registeredNamedWeights);
    }

    /**
     * Creates a meter over a live parent-bounded host child ledger.
     */
    public BexGasMeter(BexGasSchedule schedule,
                       GasMeter.ChildGasLedger hostLedger) {
        this(schedule, hostLedger, NO_LOCAL_LIMIT);
    }

    /**
     * Creates a meter over a live parent-bounded host child ledger. The local
     * limit may only reduce the child ledger's initial remaining budget.
     */
    public BexGasMeter(BexGasSchedule schedule,
                       GasMeter.ChildGasLedger hostLedger,
                       long localLimit) {
        this(schedule,
                Objects.requireNonNull(hostLedger, "hostLedger").remainingGas(),
                requireLocalLimit(localLimit),
                singletonHostLedger(hostLedger),
                true,
                false,
                Collections.<String, Long>emptyMap());
    }

    /**
     * Creates a hosted meter with one physical child ledger per logical
     * runtime namespace.  The map must contain {@code bex}; registered
     * intrinsic namespaces use their own unqualified counter catalogs.
     */
    public BexGasMeter(
            BexGasSchedule schedule,
            Map<String, GasMeter.ChildGasLedger> hostLedgers,
            long localLimit,
            Map<String, Long> registeredNamedWeights) {
        this(schedule,
                parentBudget(hostLedgers),
                requireLocalLimit(localLimit),
                hostLedgers,
                false,
                false,
                registeredNamedWeights);
    }

    /**
     * Creates a hosted meter whose configured local limit is enforced by one
     * invocation-owned budget shared by every supplied host ledger.
     *
     * <p>The meter retains the configured limit for portable diagnostics but
     * does not race the canonical host admission path with a duplicate local
     * precheck. The host therefore records the exact rejected charge before
     * any corresponding BEX or intrinsic work occurs.</p>
     *
     * @param schedule exact BEX gas schedule
     * @param hostLedgers one live physical ledger per logical namespace
     * @param localLimit non-negative maximum enforced by the shared host
     *        budget
     * @param registeredNamedWeights exact intrinsic counter registry
     * @return live BEX meter using canonical host-side local admission
     */
    public static BexGasMeter hostedWithSharedLocalLimit(
            BexGasSchedule schedule,
            Map<String, GasMeter.ChildGasLedger> hostLedgers,
            long localLimit,
            Map<String, Long> registeredNamedWeights) {
        return new BexGasMeter(
                schedule,
                parentBudget(hostLedgers),
                requireLocalLimit(localLimit),
                hostLedgers,
                false,
                true,
                registeredNamedWeights);
    }

    private BexGasMeter(BexGasSchedule schedule,
                        long parentRemainingGas,
                        long localLimit,
                        Map<String, GasMeter.ChildGasLedger> hostLedgers,
                        boolean qualifiedHostCounters,
                        boolean hostEnforcesLocalLimit,
                        Map<String, Long> registeredNamedWeights) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.parentRemainingGas = parentRemainingGas;
        this.localLimit = localLimit;
        this.effectiveBudget = localLimit == NO_LOCAL_LIMIT
                ? parentRemainingGas
                : Math.min(parentRemainingGas, localLimit);
        this.hostLedgers = immutableHostLedgers(hostLedgers);
        this.qualifiedHostCounters = qualifiedHostCounters;
        if (hostEnforcesLocalLimit
                && (this.hostLedgers.isEmpty()
                || localLimit == NO_LOCAL_LIMIT)) {
            throw new IllegalArgumentException(
                    "Host-enforced local limits require hosted ledgers "
                            + "and a non-negative local limit");
        }
        this.hostEnforcesLocalLimit = hostEnforcesLocalLimit;
        this.registeredNamedWeights =
                immutableRegisteredWeights(registeredNamedWeights);
    }

    public BexGasSchedule schedule() {
        return schedule;
    }

    /**
     * Produces the deterministic host-ledger key for a counter. Portable BEX
     * counters remain unqualified; registry child counters are qualified by
     * their exact namespace.
     */
    public static String qualifiedCounterName(String namespace,
                                              String counterName) {
        String exactNamespace = requireName(namespace, "Gas namespace");
        String exactCounter = requireName(counterName, "Gas counter");
        return BexGasCounter.NAMESPACE.equals(exactNamespace)
                ? exactCounter
                : exactNamespace + "." + exactCounter;
    }

    /**
     * Builds a deterministic combined catalog for standalone inspection.
     * Hosted intrinsic execution uses one physical ledger per namespace and
     * never passes this combined map to a host. Registered map keys must
     * already be produced by
     * {@link #qualifiedCounterName(String, String)}.
     */
    public static Map<String, Long> childLedgerWeights(
            BexGasSchedule schedule,
            Map<String, Long> registeredNamedWeights) {
        LinkedHashMap<String, Long> combined = new LinkedHashMap<>(
                Objects.requireNonNull(schedule, "schedule").counterWeights());
        Map<String, Long> registered =
                immutableRegisteredWeights(registeredNamedWeights);
        for (Map.Entry<String, Long> entry : registered.entrySet()) {
            if (combined.containsKey(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Registered gas counter collides with BEX manifest counter: "
                                + entry.getKey());
            }
            combined.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(combined);
    }

    public Map<String, Long> registeredNamedWeights() {
        return registeredNamedWeights;
    }

    public Map<String, Long> childLedgerWeights() {
        return childLedgerWeights(schedule, registeredNamedWeights);
    }

    /**
     * Returns the exact parent budget observed when this child meter began.
     */
    public long parentRemainingGas() {
        return parentRemainingGas;
    }

    /**
     * Returns the configured local sub-limit, or {@link #NO_LOCAL_LIMIT}.
     */
    public long localLimit() {
        return localLimit;
    }

    public long effectiveBudget() {
        return effectiveBudget;
    }

    public long totalGas() {
        return totalGas;
    }

    /** Compatibility alias for {@link #totalGas()}. */
    public long used() {
        return totalGas;
    }

    public long remainingGas() {
        return effectiveBudget - totalGas;
    }

    /** Alias for {@link #remainingGas()}. */
    public long remaining() {
        return remainingGas();
    }

    /**
     * Returns an immutable snapshot of all successfully admitted charges.
     */
    public List<BexGasCharge> trace() {
        return Collections.unmodifiableList(new ArrayList<>(trace));
    }

    /**
     * Returns an immutable snapshot of the current admitted ledger.
     */
    public BexGasLedger ledger() {
        return new BexGasLedger(
                trace,
                schedule.scheduleId(),
                schedule.manifestIdentity());
    }

    public boolean hasHostLedger() {
        return !hostLedgers.isEmpty();
    }

    public boolean hostLedgerSubmitted() {
        return hostLedgerState == HostLedgerState.SUBMITTED;
    }

    public boolean hostLedgerFinalized() {
        return hostLedgerState != HostLedgerState.OPEN;
    }

    public void charge(BexGasCounter counter) {
        charge(counter, 1L);
    }

    public void charge(BexGasCounter counter, long quantity) {
        BexGasCounter exactCounter =
                Objects.requireNonNull(counter, "counter");
        charge(exactCounter,
                quantity,
                (String) null,
                null,
                exactCounter.canonicalName());
    }

    public void charge(BexGasCounter counter,
                       long quantity,
                       String reason) {
        charge(counter, quantity, (String) null, null, reason);
    }

    public void charge(BexGasCounter counter,
                       String sourcePath,
                       String operator,
                       String reason) {
        charge(counter, 1L, sourcePath, operator, reason);
    }

    public void charge(BexGasCounter counter,
                       BexSourcePath sourcePath,
                       String operator,
                       String reason) {
        charge(counter,
                1L,
                sourcePath != null ? sourcePath.toString() : null,
                operator,
                reason);
    }

    public void charge(BexGasCounter counter,
                       long quantity,
                       BexSourcePath sourcePath,
                       String operator,
                       String reason) {
        charge(counter,
                quantity,
                sourcePath != null ? sourcePath.toString() : null,
                operator,
                reason);
    }

    public void charge(BexGasCounter counter,
                       long quantity,
                       String sourcePath,
                       String operator,
                       String reason) {
        BexGasCounter exactCounter =
                Objects.requireNonNull(counter, "counter");
        long weight = schedule.weight(exactCounter);
        chargeAdmitted(
                BexGasCounter.NAMESPACE,
                exactCounter.canonicalName(),
                exactCounter,
                quantity,
                weight,
                sourcePath,
                operator,
                reason);
    }

    public void chargeNamed(String namespace,
                            String counterName,
                            long quantity) {
        chargeNamed(namespace,
                counterName,
                quantity,
                (String) null,
                null,
                qualifiedCounterName(namespace, counterName));
    }

    public void chargeNamed(String namespace,
                            String counterName,
                            long quantity,
                            String reason) {
        chargeNamed(namespace,
                counterName,
                quantity,
                (String) null,
                null,
                reason);
    }

    public void chargeNamed(String namespace,
                            String counterName,
                            long quantity,
                            String sourcePath,
                            String operator,
                            String reason) {
        NamedWeight named = registeredWeight(namespace, counterName);
        chargeAdmitted(
                named.namespace,
                named.counterName,
                named.portableCounter,
                quantity,
                named.weight,
                sourcePath,
                operator,
                reason);
    }

    public void chargeNamed(String namespace,
                            String counterName,
                            long quantity,
                            long declaredWeight,
                            String sourcePath,
                            String operator,
                            String reason) {
        NamedWeight named = registeredWeight(namespace, counterName);
        if (declaredWeight != named.weight) {
            throw new IllegalArgumentException(
                    "Registered gas weight mismatch for "
                            + qualifiedCounterName(namespace, counterName)
                            + ": expected " + named.weight
                            + " but was " + declaredWeight);
        }
        chargeAdmitted(
                named.namespace,
                named.counterName,
                named.portableCounter,
                quantity,
                named.weight,
                sourcePath,
                operator,
                reason);
    }

    public void chargeNamed(String namespace,
                            String counterName,
                            long quantity,
                            BexSourcePath sourcePath,
                            String operator,
                            String reason) {
        chargeNamed(namespace,
                counterName,
                quantity,
                sourcePath != null ? sourcePath.toString() : null,
                operator,
                reason);
    }

    private void chargeAdmitted(String namespace,
                                String counterName,
                                BexGasCounter portableCounter,
                                long quantity,
                                long weight,
                                String sourcePath,
                                String operator,
                                String reason) {
        ensureOpen();
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
        long localAdmissionBudget = hostLedgers.isEmpty()
                ? effectiveBudget
                : hostEnforcesLocalLimit
                ? NO_LOCAL_LIMIT
                : localLimit;
        if (localAdmissionBudget != NO_LOCAL_LIMIT
                && gas > localAdmissionBudget - totalGas) {
            throw exhausted(
                    namespace,
                    counterName,
                    portableCounter,
                    quantity,
                    weight);
        }

        GasMeter.ChildGasLedger hostLedger =
                qualifiedHostCounters
                        ? hostLedgers.get(BexGasCounter.NAMESPACE)
                        : hostLedgers.get(namespace);
        if (!hostLedgers.isEmpty() && hostLedger == null) {
            throw new IllegalStateException(
                    "No live host child ledger for logical namespace "
                            + namespace);
        }
        if (hostLedger != null) {
            try {
                hostLedger.charge(
                        qualifiedHostCounters
                                ? qualifiedCounterName(
                                namespace, counterName)
                                : counterName,
                        quantity,
                        GasChargeContext.of(
                                emptyToNull(sourcePath),
                                null,
                                emptyToNull(operator),
                                exactReason));
            } catch (GasLimitExceededException exhausted) {
                /*
                 * Retain the exact host rejection so the owning runtime work
                 * session can validate and propagate that same object.  The
                 * BEX wrapper still exposes the portable logical namespace and
                 * preserves the invariant that the rejected entry is absent
                 * locally.
                 */
                throw exhausted(
                        namespace,
                        counterName,
                        portableCounter,
                        quantity,
                        weight,
                        exhausted);
            }
        }

        trace.add(new BexGasCharge(
                trace.size(),
                namespace,
                counterName,
                quantity,
                weight,
                gas,
                sourcePath,
                operator,
                exactReason));
        totalGas += gas;
    }

    /**
     * Submits a successfully completed wrapped host child ledger exactly once.
     * The final state is set before invoking the callback, so a
     * throwing callback cannot cause an accidental second merge attempt.
     */
    public void submitHostLedger(
            Consumer<GasMeter.ChildGasLedger> submitter) {
        finalizeHostLedger(
                HostLedgerState.SUBMITTED,
                Objects.requireNonNull(submitter, "submitter"));
    }

    /**
     * Finalizes the BEX side of a deterministic-failure callback.  The host
     * decides whether its contract merges immediately or leaves the ledger
     * staged for enclosing-session finalization.
     */
    public void failHostLedger(
            Consumer<GasMeter.ChildGasLedger> failureHandler) {
        finalizeHostLedger(
                HostLedgerState.FAILED,
                Objects.requireNonNull(failureHandler, "failureHandler"));
    }

    /**
     * Finalizes the BEX side of a transient-unavailability callback.
     */
    public void unavailableHostLedger(
            Consumer<GasMeter.ChildGasLedger> unavailableHandler) {
        finalizeHostLedger(
                HostLedgerState.UNAVAILABLE,
                Objects.requireNonNull(
                        unavailableHandler, "unavailableHandler"));
    }

    /**
     * Hands the exact recorded host rejection back to its owner.
     */
    public void propagateHostGasExhaustion(
            GasLimitExceededException exhaustion,
            Consumer<GasMeter.ChildGasLedger> prefixHandler,
            BiConsumer<GasMeter.ChildGasLedger,
                    GasLimitExceededException> exhaustionHandler) {
        requireOpenHostLedger();
        hostLedgerState = HostLedgerState.EXHAUSTED;
        GasLimitExceededException exactExhaustion =
                Objects.requireNonNull(exhaustion, "exhaustion");
        Consumer<GasMeter.ChildGasLedger> exactPrefixHandler =
                Objects.requireNonNull(
                        prefixHandler, "prefixHandler");
        Throwable prefixFailure = null;
        for (GasMeter.ChildGasLedger hostLedger
                : hostLedgers.values()) {
            try {
                exactPrefixHandler.accept(hostLedger);
            } catch (RuntimeException | Error failure) {
                prefixFailure = retainFailure(
                        prefixFailure, failure);
            }
        }
        try {
            Objects.requireNonNull(
                    exhaustionHandler, "exhaustionHandler").accept(
                    rejectionLedger(exactExhaustion),
                    exactExhaustion);
        } catch (RuntimeException | Error propagated) {
            if (prefixFailure != null
                    && prefixFailure != propagated) {
                propagated.addSuppressed(prefixFailure);
            }
            throw propagated;
        }
        rethrowFailure(prefixFailure);
    }

    private void finalizeHostLedger(
            HostLedgerState finalState,
            Consumer<GasMeter.ChildGasLedger> callback) {
        requireOpenHostLedger();
        hostLedgerState = finalState;
        Throwable callbackFailure = null;
        for (GasMeter.ChildGasLedger hostLedger
                : hostLedgers.values()) {
            try {
                callback.accept(hostLedger);
            } catch (RuntimeException | Error failure) {
                callbackFailure = retainFailure(
                        callbackFailure, failure);
            }
        }
        rethrowFailure(callbackFailure);
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

    private void requireOpenHostLedger() {
        if (hostLedgers.isEmpty()) {
            throw new IllegalStateException(
                    "This BEX gas meter has no host child ledgers");
        }
        if (hostLedgerState != HostLedgerState.OPEN) {
            throw new IllegalStateException(
                    "BEX host child ledger was already finalized as "
                            + hostLedgerState);
        }
    }

    private GasMeter.ChildGasLedger rejectionLedger(
            GasLimitExceededException exhaustion) {
        for (GasMeter.ChildGasLedger ledger : hostLedgers.values()) {
            if (ledger.namespace().equals(exhaustion.namespace())) {
                return ledger;
            }
        }
        /*
         * Semantic admission shares the same work session but is not a BEX
         * child ledger.  The focused host callback receives the primary BEX
         * ledger as an ownership token; the processor adapter validates the
         * exact exception against the session itself.
         */
        return hostLedgers.get(BexGasCounter.NAMESPACE);
    }

    private void ensureOpen() {
        if (hostLedgerState != HostLedgerState.OPEN) {
            throw new IllegalStateException(
                    "Cannot charge a finalized BEX host child ledger");
        }
    }

    private NamedWeight registeredWeight(String namespace,
                                         String counterName) {
        String exactNamespace = requireName(namespace, "Gas namespace");
        String exactCounterName = requireName(counterName, "Gas counter");
        if (BexGasCounter.NAMESPACE.equals(exactNamespace)) {
            BexGasCounter portable =
                    BexGasCounter.fromCanonicalName(exactCounterName);
            return new NamedWeight(
                    exactNamespace,
                    exactCounterName,
                    portable,
                    schedule.weight(portable));
        }
        String qualified =
                qualifiedCounterName(exactNamespace, exactCounterName);
        Long weight = registeredNamedWeights.get(qualified);
        if (weight == null) {
            throw new IllegalArgumentException(
                    "Unregistered named gas counter: " + qualified);
        }
        return new NamedWeight(
                exactNamespace,
                exactCounterName,
                null,
                weight);
    }

    private BexGasLimitExceededException exhausted(
            String namespace,
            String counterName,
            BexGasCounter portableCounter,
            long quantity,
            long weight) {
        return exhausted(
                namespace,
                counterName,
                portableCounter,
                quantity,
                weight,
                null);
    }

    private BexGasLimitExceededException exhausted(
            String namespace,
            String counterName,
            BexGasCounter portableCounter,
            long quantity,
            long weight,
            GasLimitExceededException hostGasLimitExceeded) {
        if (portableCounter != null) {
            return new BexGasLimitExceededException(
                    portableCounter,
                    quantity,
                    weight,
                    totalGas,
                    effectiveBudget,
                    hostGasLimitExceeded);
        }
        return new BexGasLimitExceededException(
                namespace,
                counterName,
                quantity,
                weight,
                totalGas,
                effectiveBudget,
                hostGasLimitExceeded);
    }

    private static long requireBudget(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static long requireLocalLimit(long value) {
        if (value < NO_LOCAL_LIMIT) {
            throw new IllegalArgumentException(
                    "localLimit must be non-negative or NO_LOCAL_LIMIT");
        }
        return value;
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

    private static String requireName(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static Map<String, GasMeter.ChildGasLedger>
    singletonHostLedger(GasMeter.ChildGasLedger hostLedger) {
        LinkedHashMap<String, GasMeter.ChildGasLedger> singleton =
                new LinkedHashMap<>();
        singleton.put(
                BexGasCounter.NAMESPACE,
                Objects.requireNonNull(hostLedger, "hostLedger"));
        return singleton;
    }

    private static long parentBudget(
            Map<String, GasMeter.ChildGasLedger> hostLedgers) {
        Objects.requireNonNull(hostLedgers, "hostLedgers");
        GasMeter.ChildGasLedger bexLedger =
                hostLedgers.get(BexGasCounter.NAMESPACE);
        if (bexLedger == null) {
            throw new IllegalArgumentException(
                    "Hosted BEX ledger map must contain logical namespace "
                            + BexGasCounter.NAMESPACE);
        }
        return bexLedger.remainingGas();
    }

    private static Map<String, GasMeter.ChildGasLedger>
    immutableHostLedgers(
            Map<String, GasMeter.ChildGasLedger> hostLedgers) {
        if (hostLedgers == null || hostLedgers.isEmpty()) {
            return Collections.emptyMap();
        }
        if (!hostLedgers.containsKey(BexGasCounter.NAMESPACE)) {
            throw new IllegalArgumentException(
                    "Hosted BEX ledger map must contain logical namespace "
                            + BexGasCounter.NAMESPACE);
        }
        LinkedHashMap<String, GasMeter.ChildGasLedger> copy =
                new LinkedHashMap<>();
        IdentityHashMap<GasMeter.ChildGasLedger, Boolean> identities =
                new IdentityHashMap<>();
        for (Map.Entry<String, GasMeter.ChildGasLedger> entry
                : hostLedgers.entrySet()) {
            String namespace =
                    requireName(entry.getKey(), "Logical gas namespace");
            GasMeter.ChildGasLedger ledger =
                    Objects.requireNonNull(
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

    private static Map<String, Long> immutableRegisteredWeights(
            Map<String, Long> registeredNamedWeights) {
        if (registeredNamedWeights == null || registeredNamedWeights.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry :
                registeredNamedWeights.entrySet()) {
            String counterName =
                    requireName(entry.getKey(), "Registered gas counter");
            Long weight = Objects.requireNonNull(
                    entry.getValue(), "Registered gas weight");
            if (weight <= 0L) {
                throw new IllegalArgumentException(
                        "Registered gas weight must be positive");
            }
            copy.put(counterName, weight);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static long multiplyExact(long left, long right) {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw new IllegalArgumentException("Gas subtotal exceeds long range");
        }
        return left * right;
    }

    private static final class NamedWeight {
        private final String namespace;
        private final String counterName;
        private final BexGasCounter portableCounter;
        private final long weight;

        private NamedWeight(String namespace,
                            String counterName,
                            BexGasCounter portableCounter,
                            long weight) {
            this.namespace = namespace;
            this.counterName = counterName;
            this.portableCounter = portableCounter;
            this.weight = weight;
        }
    }
}
