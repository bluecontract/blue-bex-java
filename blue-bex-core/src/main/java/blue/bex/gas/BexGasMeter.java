package blue.bex.gas;

import blue.bex.BexSourcePath;

import java.util.Collections;
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

    private final BexGasCounterCatalog catalog;
    private final BexGasBudget budget;
    private final BexGasHostSession hostSession;
    private final BexGasTraceRecorder traceRecorder;
    private final BexGasAdmission admission;

    /**
     * Creates a standalone meter bounded by the supplied parent budget.
     */
    public BexGasMeter(BexGasSchedule schedule, long parentRemainingGas) {
        this(schedule,
                requireBudget(parentRemainingGas, "parentRemainingGas"),
                NO_LOCAL_LIMIT,
                Collections.<String, BexGasLedgerCapability>emptyMap(),
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
                Collections.<String, BexGasLedgerCapability>emptyMap(),
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
                Collections.<String, BexGasLedgerCapability>emptyMap(),
                false,
                false,
                registeredNamedWeights);
    }

    /**
     * Creates a meter over a live parent-bounded host child ledger.
     */
    public BexGasMeter(BexGasSchedule schedule,
                       BexGasLedgerCapability hostLedger) {
        this(schedule, hostLedger, NO_LOCAL_LIMIT);
    }

    /**
     * Creates a meter over a live parent-bounded host child ledger. The local
     * limit may only reduce the child ledger's initial remaining budget.
     */
    public BexGasMeter(BexGasSchedule schedule,
                       BexGasLedgerCapability hostLedger,
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
            Map<String, BexGasLedgerCapability> hostLedgers,
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
            Map<String, BexGasLedgerCapability> hostLedgers,
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
                        Map<String, BexGasLedgerCapability> hostLedgers,
                        boolean qualifiedHostCounters,
                        boolean hostEnforcesLocalLimit,
                        Map<String, Long> registeredNamedWeights) {
        BexGasSchedule exactSchedule =
                Objects.requireNonNull(schedule, "schedule");
        this.catalog = new BexGasCounterCatalog(
                exactSchedule, registeredNamedWeights);
        this.budget = new BexGasBudget(parentRemainingGas, localLimit);
        this.hostSession = new BexGasHostSession(
                hostLedgers,
                qualifiedHostCounters,
                hostEnforcesLocalLimit,
                localLimit);
        this.traceRecorder = new BexGasTraceRecorder(exactSchedule);
        this.admission = new BexGasAdmission(
                budget, hostSession, traceRecorder);
    }

    public BexGasSchedule schedule() {
        return catalog.schedule();
    }

    /**
     * Produces the deterministic host-ledger key for a counter. Portable BEX
     * counters remain unqualified; registry child counters are qualified by
     * their exact namespace.
     */
    public static String qualifiedCounterName(String namespace,
                                              String counterName) {
        return BexGasCounterCatalog.qualifiedName(
                namespace, counterName);
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
        return BexGasCounterCatalog.combinedWeights(
                schedule, registeredNamedWeights);
    }

    public Map<String, Long> registeredNamedWeights() {
        return catalog.registeredWeights();
    }

    public Map<String, Long> childLedgerWeights() {
        return catalog.combinedWeights();
    }

    /**
     * Returns the exact parent budget observed when this child meter began.
     */
    public long parentRemainingGas() {
        return budget.parentRemainingGas();
    }

    /**
     * Returns the configured local sub-limit, or {@link #NO_LOCAL_LIMIT}.
     */
    public long localLimit() {
        return budget.localLimit();
    }

    public long effectiveBudget() {
        return budget.effectiveBudget();
    }

    public long totalGas() {
        return traceRecorder.totalGas();
    }

    /** Compatibility alias for {@link #totalGas()}. */
    public long used() {
        return totalGas();
    }

    public long remainingGas() {
        return budget.remaining(totalGas());
    }

    /** Alias for {@link #remainingGas()}. */
    public long remaining() {
        return remainingGas();
    }

    /**
     * Returns an immutable snapshot of all successfully admitted charges.
     */
    public List<BexGasCharge> trace() {
        return traceRecorder.snapshot();
    }

    /**
     * Returns an immutable snapshot of the current admitted ledger.
     */
    public BexGasLedger ledger() {
        return traceRecorder.ledger();
    }

    public boolean hasHostLedger() {
        return hostSession.isHosted();
    }

    public boolean hostLedgerSubmitted() {
        return hostSession.submitted();
    }

    public boolean hostLedgerFinalized() {
        return hostSession.finalized();
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
        long weight = catalog.schedule().weight(exactCounter);
        admission.admit(
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
        BexGasCounterCatalog.Weight named =
                catalog.weight(namespace, counterName);
        admission.admit(
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
        BexGasCounterCatalog.Weight named =
                catalog.weight(namespace, counterName);
        if (declaredWeight != named.weight) {
            throw new IllegalArgumentException(
                    "Registered gas weight mismatch for "
                            + qualifiedCounterName(namespace, counterName)
                            + ": expected " + named.weight
                            + " but was " + declaredWeight);
        }
        admission.admit(
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

    /**
     * Submits a successfully completed wrapped host child ledger exactly once.
     * The final state is set before invoking the callback, so a
     * throwing callback cannot cause an accidental second merge attempt.
     */
    public void submitHostLedger(
            Consumer<BexGasLedgerCapability> submitter) {
        hostSession.submit(submitter);
    }

    /**
     * Finalizes the BEX side of a deterministic-failure callback.  The host
     * decides whether its contract merges immediately or leaves the ledger
     * staged for enclosing-session finalization.
     */
    public void failHostLedger(
            Consumer<BexGasLedgerCapability> failureHandler) {
        hostSession.fail(failureHandler);
    }

    /**
     * Finalizes the BEX side of a transient-unavailability callback.
     */
    public void unavailableHostLedger(
            Consumer<BexGasLedgerCapability> unavailableHandler) {
        hostSession.unavailable(unavailableHandler);
    }

    /**
     * Hands the exact recorded host rejection back to its owner.
     */
    public void propagateHostGasExhaustion(
            BexHostGasExhaustion exhaustion,
            Consumer<BexGasLedgerCapability> prefixHandler,
            BiConsumer<BexGasLedgerCapability,
                    BexHostGasExhaustion> exhaustionHandler) {
        hostSession.propagateExhaustion(
                exhaustion, prefixHandler, exhaustionHandler);
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

    private static Map<String, BexGasLedgerCapability>
    singletonHostLedger(BexGasLedgerCapability hostLedger) {
        LinkedHashMap<String, BexGasLedgerCapability> singleton =
                new LinkedHashMap<>();
        singleton.put(
                BexGasCounter.NAMESPACE,
                Objects.requireNonNull(hostLedger, "hostLedger"));
        return singleton;
    }

    private static long parentBudget(
            Map<String, BexGasLedgerCapability> hostLedgers) {
        Objects.requireNonNull(hostLedgers, "hostLedgers");
        BexGasLedgerCapability bexLedger =
                hostLedgers.get(BexGasCounter.NAMESPACE);
        if (bexLedger == null) {
            throw new IllegalArgumentException(
                    "Hosted BEX ledger map must contain logical namespace "
                            + BexGasCounter.NAMESPACE);
        }
        return bexLedger.remainingGas();
    }
}
