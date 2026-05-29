package blue.bex.api;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Evaluated request passed to a host intrinsic processor.
 */
public final class BexIntrinsicInvocation {
    private final String blueId;
    private final BexValue type;
    private final Map<String, BexValue> fields;
    private final LongConsumer gasCharger;
    private final LongSupplier gasUsed;

    public BexIntrinsicInvocation(String blueId,
                                  BexValue type,
                                  Map<String, BexValue> fields,
                                  LongConsumer gasCharger,
                                  LongSupplier gasUsed) {
        this.blueId = Objects.requireNonNull(blueId, "blueId");
        this.type = type != null ? type : BexValues.undefined();
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields != null
                ? fields
                : Collections.<String, BexValue>emptyMap()));
        this.gasCharger = gasCharger != null ? gasCharger : amount -> { };
        this.gasUsed = gasUsed != null ? gasUsed : () -> 0L;
    }

    public String blueId() {
        return blueId;
    }

    /**
     * Static Blue type value from {@code $intrinsic.type}.
     */
    public BexValue type() {
        return type;
    }

    /**
     * Evaluated payload fields, excluding {@code type}.
     */
    public Map<String, BexValue> fields() {
        return fields;
    }

    public BexValue field(String name) {
        BexValue value = fields.get(name);
        return value != null ? value : BexValues.undefined();
    }

    /**
     * Charge deterministic gas from the intrinsic implementation.
     */
    public void chargeGas(long amount) {
        gasCharger.accept(amount);
    }

    public long gasUsed() {
        return gasUsed.getAsLong();
    }
}
