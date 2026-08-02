package blue.bex.gas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of an ordered BEX child gas ledger.
 */
public final class BexGasLedger {
    private static final BexGasLedger EMPTY =
            new BexGasLedger(Collections.<BexGasCharge>emptyList());

    private final List<BexGasCharge> trace;
    private final Map<BexGasCounter, Long> quantities;
    private final Map<String, Long> namedQuantities;
    private final long totalGas;
    private final String scheduleId;
    private final String manifestIdentity;

    public BexGasLedger(List<BexGasCharge> trace) {
        this(requireDefaultManifestTrace(trace),
                BexGasCounter.SCHEDULE_ID,
                BexGasCounter.MANIFEST_IDENTITY);
    }

    /**
     * Reconstructs a trace with an explicit schedule and manifest identity.
     * Callers using non-default weights must use this constructor so evidence
     * never falsely claims the default BEX manifest.
     */
    public BexGasLedger(List<BexGasCharge> trace,
                        String scheduleId,
                        String manifestIdentity) {
        Objects.requireNonNull(trace, "trace");
        ArrayList<BexGasCharge> copy = new ArrayList<>(trace.size());
        EnumMap<BexGasCounter, Long> quantityTotals =
                new EnumMap<>(BexGasCounter.class);
        LinkedHashMap<String, Long> namedQuantityTotals =
                new LinkedHashMap<>();
        long gasTotal = 0L;
        for (int index = 0; index < trace.size(); index++) {
            BexGasCharge charge =
                    Objects.requireNonNull(trace.get(index), "trace entry");
            if (charge.sequence() != index) {
                throw new IllegalArgumentException(
                        "Gas trace sequence must be contiguous from zero: "
                                + charge.sequence() + " at index " + index);
            }
            gasTotal = addExact(gasTotal, charge.gas());
            if (charge.portableCounter() != null) {
                Long prior = quantityTotals.get(charge.portableCounter());
                quantityTotals.put(charge.portableCounter(),
                        addExact(prior != null ? prior : 0L, charge.quantity()));
            }
            String qualifiedName = charge.qualifiedCounterName();
            Long namedPrior = namedQuantityTotals.get(qualifiedName);
            namedQuantityTotals.put(qualifiedName,
                    addExact(namedPrior != null ? namedPrior : 0L,
                            charge.quantity()));
            copy.add(charge);
        }
        this.trace = Collections.unmodifiableList(copy);
        this.quantities = Collections.unmodifiableMap(quantityTotals);
        this.namedQuantities =
                Collections.unmodifiableMap(namedQuantityTotals);
        this.totalGas = gasTotal;
        this.scheduleId = Objects.requireNonNull(
                scheduleId, "scheduleId");
        this.manifestIdentity = Objects.requireNonNull(
                manifestIdentity, "manifestIdentity");
    }

    public static BexGasLedger empty() {
        return EMPTY;
    }

    /**
     * Returns the exact immutable trace. The entries themselves are immutable.
     */
    public List<BexGasCharge> trace() {
        return trace;
    }

    public long totalGas() {
        return totalGas;
    }

    /** Compatibility alias for {@link #totalGas()}. */
    public long gasUsed() {
        return totalGas;
    }

    public long quantity(BexGasCounter counter) {
        Long quantity = quantities.get(Objects.requireNonNull(counter, "counter"));
        return quantity != null ? quantity : 0L;
    }

    public Map<BexGasCounter, Long> quantities() {
        return quantities;
    }

    public long quantity(String namespace, String counterName) {
        Long quantity = namedQuantities.get(
                BexGasMeter.qualifiedCounterName(namespace, counterName));
        return quantity != null ? quantity : 0L;
    }

    /**
     * Returns immutable totals keyed by their enclosing host-ledger counter
     * names. Portable BEX keys are unqualified; registered child keys are
     * namespace-qualified.
     */
    public Map<String, Long> namedQuantities() {
        return namedQuantities;
    }

    public String scheduleId() {
        return scheduleId;
    }

    public String manifestIdentity() {
        return manifestIdentity;
    }

    private static List<BexGasCharge> requireDefaultManifestTrace(
            List<BexGasCharge> trace) {
        Objects.requireNonNull(trace, "trace");
        for (BexGasCharge charge : trace) {
            Objects.requireNonNull(charge, "trace entry");
            BexGasCounter counter = charge.portableCounter();
            if (counter == null
                    || !BexGasCounter.NAMESPACE.equals(charge.namespace())
                    || charge.weight() != counter.defaultWeight()) {
                throw new IllegalArgumentException(
                        "Caller-supplied gas trace does not match the default "
                                + "BEX manifest; provide explicit schedule and "
                                + "manifest identities");
            }
        }
        return trace;
    }

    private static long addExact(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            throw new IllegalArgumentException("Gas total exceeds long range");
        }
        return left + right;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof BexGasLedger
                && trace.equals(((BexGasLedger) other).trace)
                && scheduleId.equals(
                        ((BexGasLedger) other).scheduleId)
                && manifestIdentity.equals(
                        ((BexGasLedger) other).manifestIdentity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trace, scheduleId, manifestIdentity);
    }

    @Override
    public String toString() {
        return "BexGasLedger{scheduleId=" + scheduleId
                + ", manifestIdentity=" + manifestIdentity
                + ", totalGas=" + totalGas
                + ", trace=" + trace + '}';
    }
}
