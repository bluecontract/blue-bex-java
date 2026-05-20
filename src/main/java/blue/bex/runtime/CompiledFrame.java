package blue.bex.runtime;

import blue.bex.BexSourcePath;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.List;

/**
 * Slot-based variable frame.
 */
public final class CompiledFrame {
    private final BexRuntime runtime;
    private final BexValue[] slots;
    private final CompiledFrame parent;
    private BexValue returnValue;
    private BexSourcePath sourcePath;

    public CompiledFrame(BexRuntime runtime, int frameSize, CompiledFrame parent) {
        this.runtime = runtime;
        this.slots = new BexValue[frameSize];
        this.parent = parent;
    }

    public BexRuntime runtime() {
        return runtime;
    }

    public BexValue get(int slot) {
        BexValue value = slots[slot];
        return value != null ? value : BexValues.undefined();
    }

    public void set(int slot, BexValue value) {
        slots[slot] = value != null ? value : BexValues.undefined();
    }

    public CompiledFrame parent() {
        return parent;
    }

    public BexValue readDocument(String pointer, List<String> precompiledSegments, boolean resolved) {
        return runtime.readDocument(pointer, precompiledSegments, resolved);
    }

    public BexValue readEvent(List<String> precompiledSegments) {
        return runtime.readEvent(precompiledSegments);
    }

    public BexValue readCurrentContract(List<String> precompiledSegments) {
        return runtime.readCurrentContract(precompiledSegments);
    }

    public BexValue readBinding(String name, List<String> precompiledSegments) {
        return runtime.readBinding(name, precompiledSegments);
    }

    public BexExecutionAccumulator accumulator() {
        return runtime.accumulator();
    }

    public BexValue returnValue() {
        return returnValue;
    }

    public void returnValue(BexValue returnValue) {
        this.returnValue = returnValue;
    }

    public BexSourcePath sourcePath() {
        return sourcePath != null ? sourcePath : parent != null ? parent.sourcePath() : null;
    }

    public BexSourcePath enter(BexSourcePath next) {
        BexSourcePath previous = sourcePath;
        sourcePath = next;
        return previous;
    }

    public void restore(BexSourcePath previous) {
        sourcePath = previous;
    }
}
