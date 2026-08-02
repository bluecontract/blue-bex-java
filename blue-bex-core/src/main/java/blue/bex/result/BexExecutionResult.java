package blue.bex.result;

import blue.bex.gas.BexGasCharge;
import blue.bex.gas.BexGasLedger;
import blue.bex.output.BexAdmittedValue;
import blue.bex.value.BexValue;

import java.util.List;
import java.util.Objects;

/**
 * Standalone BEX result.
 *
 * <p>The value is the step result. Changeset and events are data outputs for the
 * host to consume; the BEX engine itself does not mutate documents or emit
 * workflow events. Metrics are returned as defensive copies. Gas is retained
 * as its canonical named trace and {@link #gasUsed()} is always derived from
 * that trace.</p>
 */
public final class BexExecutionResult {
    private final BexValue value;
    private final BexChangeset changeset;
    private final BexEvents events;
    private final BexGasLedger gasLedger;
    private final BexMetricsSnapshot metrics;
    private final BexAdmittedValue output;

    public BexExecutionResult(BexValue value,
                              BexChangeset changeset,
                              BexEvents events,
                              List<BexGasCharge> gasTrace,
                              BexMetricsSnapshot metrics) {
        this(value,
                changeset,
                events,
                new BexGasLedger(Objects.requireNonNull(gasTrace, "gasTrace")),
                metrics,
                null);
    }

    public BexExecutionResult(BexValue value,
                              BexChangeset changeset,
                              BexEvents events,
                              BexGasLedger gasLedger,
                              BexMetricsSnapshot metrics) {
        this(value, changeset, events, gasLedger, metrics, null);
    }

    public BexExecutionResult(BexValue value,
                              BexChangeset changeset,
                              BexEvents events,
                              BexGasLedger gasLedger,
                              BexMetricsSnapshot metrics,
                              BexAdmittedValue output) {
        this.value = value;
        this.changeset = changeset;
        this.events = events;
        this.gasLedger = Objects.requireNonNull(gasLedger, "gasLedger");
        this.metrics = metrics != null
                ? metrics
                : new BexMetricsRecorder().snapshot();
        this.output = output;
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

    /**
     * Returns the sum of the immutable canonical gas trace.
     */
    public long gasUsed() {
        return gasLedger.totalGas();
    }

    public BexGasLedger gasLedger() {
        return gasLedger;
    }

    /** Alias for {@link #gasLedger()}. */
    public BexGasLedger ledger() {
        return gasLedger;
    }

    public List<BexGasCharge> gasTrace() {
        return gasLedger.trace();
    }

    /** Alias for {@link #gasTrace()}. */
    public List<BexGasCharge> trace() {
        return gasTrace();
    }

    /**
     * Returns the already-admitted root output metadata, or {@code null} when
     * the execution did not cross a root Blue output boundary.
     */
    public BexAdmittedValue output() {
        return output;
    }

    public BexMetrics metrics() {
        return BexMetrics.fromSnapshot(metrics);
    }

    /** Returns the immutable metrics retained by this result. */
    public BexMetricsSnapshot metricsSnapshot() {
        return metrics;
    }

}
