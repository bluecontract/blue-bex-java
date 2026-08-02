package blue.bex.value;

import java.util.List;

/** Read-only event projection consumed by the value layer. */
public interface BexEventsValueView {
    List<BexValue> events();
}
