package blue.bex.runtime;

import blue.bex.output.BexAdmittedValue;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexOutputKind;
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
    private final List<BexAdmittedValue> admittedEvents = new ArrayList<>();
    private BexResultOverlay overlay;
    private final BexOutputAdmission outputAdmission;

    public BexExecutionAccumulator(BexResultOverlay overlay) {
        this(overlay, null);
    }

    public BexExecutionAccumulator(BexResultOverlay overlay,
                                   BexOutputAdmission outputAdmission) {
        this.overlay = overlay;
        this.outputAdmission = outputAdmission;
    }

    public void appendChange(BexPatchEntry entry) {
        BexPatchEntry admittedEntry = entry;
        if (outputAdmission != null && !"remove".equals(entry.op())) {
            BexAdmittedValue admitted =
                    outputAdmission.admit(entry.val(), BexOutputKind.PATCH_VALUE);
            admittedEntry = new BexPatchEntry(
                    entry.op(),
                    entry.authoredPath(),
                    entry.absolutePath(),
                    admitted.value(),
                    admitted);
        }
        changes.add(admittedEntry);
        overlay.append(admittedEntry);
    }

    public void appendEvent(BexValue event) {
        if (outputAdmission != null) {
            BexAdmittedValue admitted =
                    outputAdmission.admit(event, BexOutputKind.EVENT);
            admittedEvents.add(admitted);
            events.add(admitted.value());
            return;
        }
        events.add(event);
    }

    public BexChangeset changeset() {
        return new BexChangeset(changes);
    }

    public BexEvents events() {
        return new BexEvents(events, admittedEvents);
    }

    public BexResultOverlay overlay() {
        return overlay;
    }

    void discard(BexResultOverlay resetOverlay) {
        changes.clear();
        events.clear();
        admittedEvents.clear();
        overlay = resetOverlay;
    }
}
