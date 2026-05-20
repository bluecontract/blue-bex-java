package blue.bex.value;

import blue.bex.result.BexPatchEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * BEX object view over one patch entry.
 */
public final class PatchEntryBexValue extends AbstractBexValue {
    private final BexPatchEntry entry;

    public PatchEntryBexValue(BexPatchEntry entry) {
        this.entry = entry;
    }

    @Override
    public boolean isObject() {
        return true;
    }

    @Override
    public BexValue get(String key) {
        if ("op".equals(key)) {
            return BexValues.scalar(entry.op());
        }
        if ("path".equals(key)) {
            return BexValues.scalar(entry.absolutePath());
        }
        if ("val".equals(key)) {
            return entry.val() != null ? entry.val() : BexValues.undefined();
        }
        return BexValues.undefined();
    }

    @Override
    public List<String> keys() {
        ArrayList<String> keys = new ArrayList<>(Arrays.asList("op", "path"));
        if (entry.val() != null && !entry.val().isUndefined()) {
            keys.add("val");
        }
        return keys;
    }

    @Override
    public int size() {
        return keys().size();
    }

    @Override
    public blue.language.model.Node toNode() {
        return BexNodeWriter.toNode(this);
    }

    @Override
    public Object toSimple() {
        return BexSimpleWriter.toSimple(this);
    }
}
