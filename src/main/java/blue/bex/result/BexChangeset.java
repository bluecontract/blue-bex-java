package blue.bex.result;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered BEX changeset. Entries are never coalesced or reordered.
 */
public final class BexChangeset {
    private final List<BexPatchEntry> entries;

    public BexChangeset(List<BexPatchEntry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public List<BexPatchEntry> entries() {
        return entries;
    }

    public BexValue asValue() {
        List<BexValue> values = new ArrayList<>();
        for (BexPatchEntry entry : entries) {
            values.add(patchEntryValue(entry));
        }
        return BexValues.list(values);
    }

    public static BexValue patchEntryValue(BexPatchEntry entry) {
        Map<String, BexValue> object = new LinkedHashMap<>();
        object.put("op", BexValues.scalar(entry.op()));
        object.put("path", BexValues.scalar(entry.absolutePath()));
        if (!"remove".equals(entry.op()) && entry.val() != null) {
            object.put("val", entry.val());
        }
        return BexValues.map(object);
    }
}
