package blue.bex.gas;

import blue.bex.BexException;
import blue.bex.result.BexMetrics;
import blue.bex.value.BexValue;

/**
 * Deterministic gas meter.
 */
public final class BexGasMeter {
    private final BexGasSchedule schedule;
    private final long limit;
    private final BexSizeEstimator sizeEstimator;
    private long used;

    public BexGasMeter(BexGasSchedule schedule, long limit, BexMetrics metrics) {
        this.schedule = schedule;
        this.limit = limit;
        this.sizeEstimator = new BexSizeEstimator(metrics);
    }

    public BexGasSchedule schedule() {
        return schedule;
    }

    public long used() {
        return used;
    }

    public void charge(long amount) {
        if (amount <= 0) {
            return;
        }
        used += amount;
        if (limit >= 0 && used > limit) {
            throw new BexException("BEX gas exhausted at " + used + " gas units");
        }
    }

    public long estimatedSize(BexValue value) {
        return sizeEstimator.estimate(value);
    }
}
