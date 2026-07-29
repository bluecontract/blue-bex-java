package blue.bex.result;

import blue.bex.output.BexAdmittedValue;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered events computed by BEX. Emission is host-runtime behavior.
 */
public final class BexEvents {
    private final List<BexValue> events;
    private final List<BexAdmittedValue> admittedEvents;

    public BexEvents(List<BexValue> events) {
        this(events, Collections.<BexAdmittedValue>emptyList());
    }

    public BexEvents(List<BexValue> events,
                     List<BexAdmittedValue> admittedEvents) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.admittedEvents = Collections.unmodifiableList(
                new ArrayList<>(admittedEvents));
    }

    public List<BexValue> events() {
        return events;
    }

    /**
     * Strict Blue outputs corresponding one-for-one with {@link #events()} for
     * engine-produced events. Engine-produced {@link #events()} are the exact
     * admitted values, so later BEX lanes never reconstruct their transient
     * precursors.
     */
    public List<BexAdmittedValue> admittedEvents() {
        return admittedEvents;
    }

    public BexValue asValue() {
        return BexValues.list(events);
    }
}
