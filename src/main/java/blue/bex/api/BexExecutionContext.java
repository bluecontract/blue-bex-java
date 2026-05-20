package blue.bex.api;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable execution context for one BEX run.
 *
 * <p>The context supplies the document view, host-provided bindings, standard
 * convenience bindings, and gas limit. The current scope path is derived from
 * the configured {@link BexDocumentView}; callers should not maintain a
 * separate scope value.</p>
 */
public final class BexExecutionContext {
    private final BexDocumentView document;
    private final Map<String, BexValue> bindings;
    private final BexValue event;
    private final BexValue currentContract;
    private final BexStepResults steps;
    private final long gasLimit;

    private BexExecutionContext(Builder builder) {
        this.document = builder.document;
        this.steps = builder.steps != null ? builder.steps : BexStepResults.empty();
        this.gasLimit = builder.gasLimit;
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        LinkedHashMap<String, BexValue> copy = new LinkedHashMap<>(builder.bindings);
        if (builder.steps != null && !copy.containsKey("steps")) {
            copy.put("steps", builder.steps.asValue());
        }
        this.bindings = Collections.unmodifiableMap(copy);
        this.event = bindingFrom(copy, "event");
        this.currentContract = bindingFrom(copy, "currentContract");
    }

    public static Builder builder() {
        return new Builder();
    }

    public BexDocumentView document() {
        return document;
    }

    public BexValue event() {
        return event;
    }

    public BexValue currentContract() {
        return currentContract;
    }

    public BexStepResults steps() {
        return steps;
    }

    public BexValue binding(String name) {
        BexValue value = bindings.get(name);
        return value != null ? value : BexValues.undefined();
    }

    public Map<String, BexValue> bindings() {
        return bindings;
    }

    public String currentScopePath() {
        return document.currentScopePath();
    }

    public long gasLimit() {
        return gasLimit;
    }

    public static final class Builder {
        private BexDocumentView document;
        private BexStepResults steps;
        private long gasLimit = 100_000L;
        private final LinkedHashMap<String, BexValue> bindings = new LinkedHashMap<>();

        public Builder document(BexDocumentView document) {
            this.document = document;
            return this;
        }

        public Builder event(BexValue event) {
            return binding("event", event);
        }

        public Builder currentContract(BexValue currentContract) {
            return binding("currentContract", currentContract);
        }

        public Builder steps(BexStepResults steps) {
            this.steps = steps;
            if (steps != null) {
                binding("steps", steps.asValue());
            }
            return this;
        }

        public Builder binding(String name, BexValue value) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("binding name is required");
            }
            bindings.put(name, value != null ? value : BexValues.undefined());
            return this;
        }

        public Builder bindings(Map<String, BexValue> values) {
            if (values == null) {
                return this;
            }
            for (Map.Entry<String, BexValue> entry : values.entrySet()) {
                binding(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /**
         * @deprecated current scope belongs to the configured
         * {@link BexDocumentView}. This method is retained as a no-op for
         * source compatibility.
         */
        @Deprecated
        public Builder currentScopePath(String currentScopePath) {
            return this;
        }

        public Builder gasLimit(long gasLimit) {
            this.gasLimit = gasLimit;
            return this;
        }

        public BexExecutionContext build() {
            return new BexExecutionContext(this);
        }
    }

    private static BexValue bindingFrom(Map<String, BexValue> bindings, String name) {
        BexValue value = bindings.get(name);
        return value != null ? value : BexValues.undefined();
    }
}
