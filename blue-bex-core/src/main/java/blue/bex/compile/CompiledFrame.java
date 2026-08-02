package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.result.BexPatchEntry;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.List;

/** Slot-based invocation frame for compiled BEX IR. */
public final class CompiledFrame {
    private final BexExecutionMachine machine;
    private final BexValue[] slots;
    private final CompiledFrame parent;
    private BexValue returnValue;
    private BexSourcePath sourcePath;

    public CompiledFrame(
            BexExecutionMachine machine,
            int frameSize,
            CompiledFrame parent) {
        this.machine = machine;
        this.slots = new BexValue[frameSize];
        this.parent = parent;
    }

    public BexExecutionMachine machine() {
        return machine;
    }

    public BexValue get(int slot) {
        BexValue value = slots[slot];
        return value != null ? value : BexValues.undefined();
    }

    /** Reads a declared slot and fails while its initializer is incomplete. */
    public BexValue getRequired(int slot) {
        BexValue value = slots[slot];
        if (value != null) {
            return value;
        }
        BexSourcePath path = sourcePath();
        String message = "Binding is uninitialized";
        throw path != null
                ? BexException.at(path, message)
                : new BexException(message);
    }

    public boolean isInitialized(int slot) {
        return slots[slot] != null;
    }

    public void clear(int slot) {
        slots[slot] = null;
    }

    public void set(int slot, BexValue value) {
        slots[slot] = value != null ? value : BexValues.undefined();
    }

    public CompiledFrame parent() {
        return parent;
    }

    public BexValue readDocument(
            String pointer,
            List<String> precompiledSegments,
            boolean resolved) {
        return machine.readDocument(pointer, precompiledSegments, resolved);
    }

    public BexValue readEvent(List<String> precompiledSegments) {
        return machine.readEvent(precompiledSegments);
    }

    public BexValue readProcessingEvent(List<String> precompiledSegments) {
        return machine.readProcessingEvent(precompiledSegments);
    }

    public BexValue readCurrentContract(List<String> precompiledSegments) {
        return machine.readCurrentContract(precompiledSegments);
    }

    public BexValue readBinding(
            String name,
            List<String> precompiledSegments) {
        return machine.readBinding(name, precompiledSegments);
    }

    public void appendChange(BexPatchEntry entry) {
        machine.appendChange(entry);
    }

    public void appendEvent(BexValue event) {
        machine.appendEvent(event);
    }

    public BexValue changesetValue() {
        return machine.changesetValue();
    }

    public BexValue eventsValue() {
        return machine.eventsValue();
    }

    public BexValue returnValue() {
        return returnValue;
    }

    public void returnValue(BexValue returnValue) {
        this.returnValue = returnValue;
    }

    public BexSourcePath sourcePath() {
        return sourcePath != null
                ? sourcePath
                : parent != null ? parent.sourcePath() : null;
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
