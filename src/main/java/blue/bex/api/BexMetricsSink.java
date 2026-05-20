package blue.bex.api;

import blue.bex.result.BexMetrics;

/**
 * Optional sink invoked after compilation/execution.
 */
public interface BexMetricsSink {
    void accept(BexMetrics metrics);

    BexMetricsSink NOOP = new BexMetricsSink() {
        @Override
        public void accept(BexMetrics metrics) {
        }
    };
}
