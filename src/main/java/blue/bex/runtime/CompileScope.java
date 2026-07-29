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
    private int nextSlot;

    public CompileScope() {
        this(null);
    }

    public CompileScope(CompileScope parent) {
        this.parent = parent;
        this.nextSlot = parent != null ? parent.frameSize() : 0;
    }

    public int declareOrGetSlot(String name) {
        Integer existing = slots.get(name);
        if (existing != null) {
            return existing;
        }
        if (parent != null && parent.hasSlot(name)) {
            return parent.resolveSlot(name);
        }
        int slot = nextSlot++;
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
        return Math.max(nextSlot, parent != null ? parent.frameSize() : 0);
    }

    /**
     * Captures which names are visible without rewinding allocated frame slots.
     *
     * <p>Collection-query bindings are lexical only for the query expression.
     * Restoring visibility removes names introduced by the query while keeping
     * the allocated slots in the function frame so the compiled expression can
     * still use them at runtime.</p>
     */
    public Visibility captureVisibility() {
        return new Visibility(new LinkedHashMap<>(slots));
    }

    public void restoreVisibility(Visibility visibility) {
        if (visibility == null) {
            throw new IllegalArgumentException("visibility is required");
        }
        slots.clear();
        slots.putAll(visibility.slots);
    }

    public static final class Visibility {
        private final Map<String, Integer> slots;

        private Visibility(Map<String, Integer> slots) {
            this.slots = slots;
        }
    }
}
