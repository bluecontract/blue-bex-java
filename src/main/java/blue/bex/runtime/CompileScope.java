package blue.bex.runtime;

import blue.bex.BexException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compile-time slot allocator.
 */
public final class CompileScope {
    private final CompileScope parent;
    private final Map<String, Integer> slots = new LinkedHashMap<>();

    public CompileScope() {
        this(null);
    }

    public CompileScope(CompileScope parent) {
        this.parent = parent;
    }

    public int declareOrGetSlot(String name) {
        Integer existing = slots.get(name);
        if (existing != null) {
            return existing;
        }
        int slot = slots.size();
        slots.put(name, slot);
        return slot;
    }

    public int resolveSlot(String name) {
        Integer slot = slots.get(name);
        if (slot != null) {
            return slot;
        }
        if (parent != null) {
            return parent.resolveSlot(name);
        }
        throw new BexException("Unknown variable: " + name);
    }

    public boolean hasSlot(String name) {
        return slots.containsKey(name) || (parent != null && parent.hasSlot(name));
    }

    public int frameSize() {
        return slots.size();
    }
}
