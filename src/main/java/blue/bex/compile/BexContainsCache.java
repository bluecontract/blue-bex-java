package blue.bex.compile;

import blue.bex.result.BexMetrics;
import blue.language.snapshot.FrozenNode;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared cache for detecting whether a frozen subtree contains any BEX operator.
 */
public final class BexContainsCache {
    private final Map<String, Boolean> blueIdCache;
    private final IdentityHashMap<FrozenNode, Boolean> identityCache = new IdentityHashMap<>();

    public BexContainsCache() {
        this(8192);
    }

    public BexContainsCache(final int capacity) {
        this.blueIdCache = new LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
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
        String blueId = node.blueId();
        Boolean cached = blueId != null ? blueIdCache.get(blueId) : identityCache.get(node);
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
        if (blueId != null) {
            blueIdCache.put(blueId, result);
        } else {
            identityCache.put(node, result);
        }
        return result;
    }

    private boolean scan(FrozenNode node) {
        if (node == null) {
            return false;
        }
        if (scan(node.getType())
                || scan(node.getItemType())
                || scan(node.getKeyType())
                || scan(node.getValueType())
                || scan(node.getBlue())) {
            return true;
        }
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
