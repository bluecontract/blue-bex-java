package blue.bex.gas;

import blue.bex.result.BexMetrics;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic, cached size estimator used for gas accounting.
 */
public final class BexSizeEstimator {
    private final IdentityHashMap<BexValue, Long> identityCache = new IdentityHashMap<>();
    private final Map<String, Long> frozenBlueIdCache;
    private final BexMetrics metrics;

    public BexSizeEstimator(BexMetrics metrics) {
        this(metrics, 8192);
    }

    BexSizeEstimator(BexMetrics metrics, final int frozenCacheCapacity) {
        this.metrics = metrics;
        this.frozenBlueIdCache = new LinkedHashMap<String, Long>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > frozenCacheCapacity;
            }
        };
    }

    public long estimate(BexValue value) {
        metrics.incrementSizeEstimateCalls();
        return estimateInternal(value);
    }

    private long estimateInternal(BexValue value) {
        if (value == null || value.isUndefined() || value.isNull()) {
            return 0;
        }
        String blueId = BexValues.frozenBlueId(value);
        if (blueId != null) {
            Long cached = frozenBlueIdCache.get(blueId);
            if (cached != null) {
                metrics.incrementSizeEstimateCacheHits();
                return cached;
            }
            metrics.incrementSizeEstimateCacheMisses();
            long size = compute(value);
            frozenBlueIdCache.put(blueId, size);
            return size;
        }
        Long cached = identityCache.get(value);
        if (cached != null) {
            metrics.incrementSizeEstimateCacheHits();
            return cached;
        }
        metrics.incrementSizeEstimateCacheMisses();
        long size = compute(value);
        identityCache.put(value, size);
        return size;
    }

    private long compute(BexValue value) {
        if (value.isScalar()) {
            return Math.max(1, value.asText().length());
        }
        long size = value.isList() ? value.size() : value.keys().size();
        if (value.isList()) {
            for (int i = 0; i < value.size(); i++) {
                size += estimateInternal(value.get(String.valueOf(i)));
            }
            return size;
        }
        for (String key : value.keys()) {
            size += key.length();
            size += estimateInternal(value.get(key));
        }
        return size;
    }
}
