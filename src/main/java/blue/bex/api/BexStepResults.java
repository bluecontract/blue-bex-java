package blue.bex.api;

import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prior workflow step results keyed by step name.
 */
public final class BexStepResults {
    private final Map<String, BexValue> steps;

    private BexStepResults(Map<String, BexValue> steps) {
        this.steps = Collections.unmodifiableMap(new LinkedHashMap<>(steps));
    }

    public static BexStepResults empty() {
        return new BexStepResults(Collections.<String, BexValue>emptyMap());
    }

    public static Builder builder() {
        return new Builder();
    }

    public BexValue step(String name) {
        BexValue value = steps.get(name);
        return value != null ? value : BexValues.undefined();
    }

    public BexValue asValue() {
        return BexValues.map(steps);
    }

    public static final class Builder {
        private final Map<String, BexValue> steps = new LinkedHashMap<>();

        public Builder put(String name, BexValue value) {
            steps.put(name, value);
            return this;
        }

        public Builder put(String name, BexExecutionResult result) {
            steps.put(name, result.value());
            return this;
        }

        public BexStepResults build() {
            return new BexStepResults(steps);
        }
    }
}
