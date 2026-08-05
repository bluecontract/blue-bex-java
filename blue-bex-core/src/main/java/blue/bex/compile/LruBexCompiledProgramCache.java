package blue.bex.compile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU compiled program cache.
 */
public final class LruBexCompiledProgramCache implements BexCompiledProgramCache {
    private final Map<BexCompiledProgramKey, BexCompiledProgram> programs;

    public LruBexCompiledProgramCache() {
        this(4096);
    }

    public LruBexCompiledProgramCache(final int capacity) {
        this.programs = new LinkedHashMap<BexCompiledProgramKey, BexCompiledProgram>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BexCompiledProgramKey, BexCompiledProgram> eldest) {
                return size() > capacity;
            }
        };
    }

    @Override
    public synchronized BexCompiledProgram get(BexCompiledProgramKey key) {
        return programs.get(key);
    }

    @Override
    public synchronized void put(BexCompiledProgramKey key, BexCompiledProgram program) {
        programs.put(key, program);
    }
}
