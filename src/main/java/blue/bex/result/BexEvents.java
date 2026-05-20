package blue.bex.result;

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

    public BexEvents(List<BexValue> events) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public List<BexValue> events() {
        return events;
    }

    public BexValue asValue() {
        return BexValues.list(events);
    }
}
