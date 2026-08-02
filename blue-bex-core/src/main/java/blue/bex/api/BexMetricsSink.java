package blue.bex.api;

import blue.bex.result.BexMetricsSnapshot;

/**
 * Optional sink invoked after compilation/execution.
 */
public interface BexMetricsSink {
    void accept(BexMetricsSnapshot metrics);

    BexMetricsSink NOOP = new BexMetricsSink() {
        @Override
        public void accept(BexMetricsSnapshot metrics) {
        }
    };
}
