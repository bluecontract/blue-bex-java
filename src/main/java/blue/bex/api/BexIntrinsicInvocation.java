package blue.bex.api;

import blue.bex.output.BexAdmittedValue;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Function;

/**
 * Evaluated request passed to one exact registry-bound intrinsic processor.
 */
public final class BexIntrinsicInvocation {
    @FunctionalInterface
    interface NamedGasCharger {
        void charge(String counterName, long quantity, String reason);
    }

    private final String blueId;
    private final BexValue type;
    private final Map<String, BexValue> fields;
    private final String gasNamespace;
    private final Map<String, Long> namedCounterWeights;
    private final NamedGasCharger gasCharger;
    private final LongSupplier gasUsed;
    private final Function<BexValue, BexAdmittedValue> exactAdmission;

    BexIntrinsicInvocation(String blueId,
                           BexValue type,
                           Map<String, BexValue> fields,
                           String gasNamespace,
                           Map<String, Long> namedCounterWeights,
                           NamedGasCharger gasCharger,
                           LongSupplier gasUsed,
                           Function<BexValue, BexAdmittedValue> exactAdmission) {
        this.blueId = Objects.requireNonNull(blueId, "blueId");
        this.type = type != null ? type : BexValues.undefined();
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                fields != null
                        ? fields
                        : Collections.<String, BexValue>emptyMap()));
        this.gasNamespace = Objects.requireNonNull(gasNamespace, "gasNamespace");
        this.namedCounterWeights = Collections.unmodifiableMap(
                new LinkedHashMap<>(namedCounterWeights));
        this.gasCharger = Objects.requireNonNull(gasCharger, "gasCharger");
        this.gasUsed = gasUsed != null ? gasUsed : () -> 0L;
        this.exactAdmission =
                Objects.requireNonNull(exactAdmission, "exactAdmission");
    }

    public String blueId() {
        return blueId;
    }

    public BexValue type() {
        return type;
    }

    public Map<String, BexValue> fields() {
        return fields;
    }

    public BexValue field(String name) {
        BexValue value = fields.get(name);
        return value != null ? value : BexValues.undefined();
    }

    public String gasNamespace() {
        return gasNamespace;
    }

    /**
     * Exact registry-declared counter vocabulary and weights.
     */
    public Map<String, Long> namedCounterWeights() {
        return namedCounterWeights;
    }

    public void charge(String counterName, long quantity) {
        charge(counterName, quantity, "intrinsic-work");
    }

    public void charge(String counterName, long quantity, String reason) {
        if (!namedCounterWeights.containsKey(counterName)) {
            throw new IllegalArgumentException(
                    "Unknown intrinsic gas counter " + gasNamespace + "." + counterName);
        }
        gasCharger.charge(counterName, quantity, reason);
    }

    /**
     * Explicitly admits a payload field when this intrinsic's declared
     * semantics require an exact Blue node.
     */
    public BexAdmittedValue exactField(String name) {
        BexValue value = field(name);
        if (value.isUndefined()) {
            throw new IllegalArgumentException(
                    "Required exact intrinsic field is missing: " + name);
        }
        return exactAdmission.apply(value);
    }

    /**
     * Diagnostic total of the shared BEX ledger at this instant.
     */
    public long gasUsed() {
        return gasUsed.getAsLong();
    }
}
