package blue.bex.result;

import blue.bex.value.BexValue;

/**
 * Standalone BEX result.
 *
 * <p>The value is the step result. Changeset and events are data outputs for the
 * host to consume; the BEX engine itself does not mutate documents or emit
 * workflow events. Metrics are returned as defensive copies.</p>
 */
public final class BexExecutionResult {
    private final BexValue value;
    private final BexChangeset changeset;
    private final BexEvents events;
    private final long gasUsed;
    private final BexMetrics metrics;

    public BexExecutionResult(BexValue value,
                              BexChangeset changeset,
                              BexEvents events,
                              long gasUsed,
                              BexMetrics metrics) {
        this.value = value;
        this.changeset = changeset;
        this.events = events;
        this.gasUsed = gasUsed;
        this.metrics = metrics != null ? metrics.copy() : new BexMetrics();
    }

    public BexValue value() {
        return value;
    }

    public BexChangeset changeset() {
        return changeset;
    }

    public BexEvents events() {
        return events;
    }

    public long gasUsed() {
        return gasUsed;
    }

    public BexMetrics metrics() {
        return metrics.copy();
    }
}
