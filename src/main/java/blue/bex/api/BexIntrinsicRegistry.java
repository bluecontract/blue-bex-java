package blue.bex.api;

import blue.bex.BexException;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.utils.BlueIdResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Registry of host intrinsic processors keyed by BlueId.
 */
public final class BexIntrinsicRegistry {
    private static final BexIntrinsicRegistry EMPTY = new BexIntrinsicRegistry(Collections.<String, BexIntrinsicProcessor>emptyMap());

    private final Map<String, BexIntrinsicProcessor> processors;

    private BexIntrinsicRegistry(Map<String, BexIntrinsicProcessor> processors) {
        this.processors = Collections.unmodifiableMap(new LinkedHashMap<>(processors));
    }

    public static BexIntrinsicRegistry empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BexIntrinsicRegistry with(String blueId, BexIntrinsicProcessor processor) {
        Builder builder = builder();
        for (Map.Entry<String, BexIntrinsicProcessor> entry : processors.entrySet()) {
            builder.register(entry.getKey(), entry.getValue());
        }
        builder.register(blueId, processor);
        return builder.build();
    }

    public BexIntrinsicRegistry with(Class<?> typeClass, BexIntrinsicProcessor processor) {
        Builder builder = builder();
        for (Map.Entry<String, BexIntrinsicProcessor> entry : processors.entrySet()) {
            builder.register(entry.getKey(), entry.getValue());
        }
        builder.register(typeClass, processor);
        return builder.build();
    }

    public boolean supports(String blueId) {
        return processors.containsKey(blueId);
    }

    public Set<String> supportedBlueIds() {
        return processors.keySet();
    }

    public BexValue invoke(String blueId,
                           BexValue type,
                           Map<String, BexValue> fields,
                           LongConsumer gasCharger,
                           LongSupplier gasUsed) {
        BexIntrinsicProcessor processor = processors.get(blueId);
        if (processor == null) {
            throw new BexException("Unsupported intrinsic BlueId: " + blueId);
        }
        BexValue value = processor.execute(new BexIntrinsicInvocation(blueId, type, fields, gasCharger, gasUsed));
        return value != null ? value : BexValues.undefined();
    }

    public static final class Builder {
        private final LinkedHashMap<String, BexIntrinsicProcessor> processors = new LinkedHashMap<>();

        public Builder register(String blueId, BexIntrinsicProcessor processor) {
            if (blueId == null || blueId.trim().isEmpty()) {
                throw new IllegalArgumentException("intrinsic blueId is required");
            }
            processors.put(blueId, Objects.requireNonNull(processor, "processor"));
            return this;
        }

        public Builder register(Class<?> typeClass, BexIntrinsicProcessor processor) {
            if (typeClass == null) {
                throw new IllegalArgumentException("intrinsic type class is required");
            }
            String blueId = BlueIdResolver.resolveBlueId(typeClass);
            if (blueId == null || blueId.trim().isEmpty()) {
                throw new IllegalArgumentException("intrinsic type class must have a resolvable @TypeBlueId: "
                        + typeClass.getName());
            }
            return register(blueId, processor);
        }

        public BexIntrinsicRegistry build() {
            if (processors.isEmpty()) {
                return EMPTY;
            }
            return new BexIntrinsicRegistry(processors);
        }
    }
}
