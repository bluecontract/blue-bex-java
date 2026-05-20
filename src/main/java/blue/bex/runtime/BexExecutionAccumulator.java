package blue.bex.runtime;

import blue.bex.result.BexChangeset;
import blue.bex.result.BexEvents;
import blue.bex.result.BexPatchEntry;
import blue.bex.result.BexResultOverlay;
import blue.bex.value.BexValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-execution result accumulator.
 */
public final class BexExecutionAccumulator {
    private final List<BexPatchEntry> changes = new ArrayList<>();
    private final List<BexValue> events = new ArrayList<>();
    private final BexResultOverlay overlay;

    public BexExecutionAccumulator(BexResultOverlay overlay) {
        this.overlay = overlay;
    }

    public void appendChange(BexPatchEntry entry) {
        changes.add(entry);
        overlay.append(entry);
    }

    public void appendEvent(BexValue event) {
        events.add(event);
    }

    public BexChangeset changeset() {
        return new BexChangeset(changes);
    }

    public BexEvents events() {
        return new BexEvents(events);
    }

    public BexResultOverlay overlay() {
        return overlay;
    }
}
