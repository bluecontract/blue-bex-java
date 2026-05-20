package blue.bex.compile;

import blue.bex.result.BexMetrics;
import blue.language.snapshot.FrozenNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared cache for detecting whether a frozen subtree contains any BEX operator.
 */
public final class BexContainsCache {
    private final Map<String, Boolean> cache;

    public BexContainsCache() {
        this(8192);
    }

    public BexContainsCache(final int capacity) {
        this.cache = new LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized boolean containsBex(FrozenNode node, BexMetrics metrics) {
        if (node == null) {
            return false;
        }
        String key = node.blueId() != null ? "blueId:" + node.blueId() : "identity:" + System.identityHashCode(node);
        Boolean cached = cache.get(key);
        if (cached != null) {
            if (metrics != null) {
                metrics.incrementContainsBexCacheHits();
            }
            return cached;
        }
        if (metrics != null) {
            metrics.incrementContainsBexCacheMisses();
            metrics.incrementContainsBexScans();
        }
        boolean result = scan(node);
        cache.put(key, result);
        return result;
    }

    private boolean scan(FrozenNode node) {
        if (node.getProperties() != null) {
            if (node.getProperties().size() == 1) {
                String key = node.getProperties().keySet().iterator().next();
                if (key.startsWith("$")) {
                    return true;
                }
            }
            for (FrozenNode child : node.getProperties().values()) {
                if (scan(child)) {
                    return true;
                }
            }
        }
        if (node.getItems() != null) {
            for (FrozenNode child : node.getItems()) {
                if (scan(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
