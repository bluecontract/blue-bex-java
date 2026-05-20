package blue.bex.pointer;

import blue.bex.result.BexMetrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small LRU cache for dynamic pointer parsing.
 */
public final class BexPointerCache {
    private final int capacity;
    private final Map<String, BexPointer> pointers;

    public BexPointerCache() {
        this(4096);
    }

    public BexPointerCache(final int capacity) {
        this.capacity = capacity;
        this.pointers = new LinkedHashMap<String, BexPointer>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BexPointer> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized BexPointer get(String pointer, BexMetrics metrics) {
        BexPointer cached = pointers.get(pointer);
        if (cached != null) {
            if (metrics != null) {
                metrics.incrementPointerCacheHits();
            }
            return cached;
        }
        BexPointer parsed = BexPointer.parse(pointer);
        pointers.put(parsed.text(), parsed);
        if (metrics != null) {
            metrics.incrementPointerCacheMisses();
            metrics.incrementPointerParses();
        }
        return parsed;
    }

    public int capacity() {
        return capacity;
    }
}
