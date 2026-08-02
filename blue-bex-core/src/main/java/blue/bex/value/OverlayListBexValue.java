package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copy-on-write list overlay reserved for future list modifications.
 */
public final class OverlayListBexValue extends AbstractBexValue {
    private final BexValue base;
    private final Map<Integer, BexValue> overrides;

    public OverlayListBexValue(BexValue base, Map<Integer, BexValue> overrides) {
        if (base == null || base.isUndefined() || base.isNull()) {
            base = BexValues.list(java.util.Collections.<BexValue>emptyList());
        }
        if (!base.isList()) {
            throw new BexException("OverlayListBexValue base must be a list");
        }
        this.base = base;
        this.overrides = new LinkedHashMap<>(overrides);
    }

    @Override
    public boolean isList() {
        return true;
    }

    @Override
    public BexValue get(String key) {
        try {
            int index = Integer.parseInt(key);
            if (overrides.containsKey(index)) {
                BexValue value = overrides.get(index);
                return value != null ? value : BexValues.undefined();
            }
            return base.get(key);
        } catch (NumberFormatException ex) {
            return BexValues.undefined();
        }
    }

    @Override
    public int size() {
        int size = base.size();
        for (Integer index : overrides.keySet()) {
            if (index >= size && overrides.get(index) != null && !overrides.get(index).isUndefined()) {
                size = index + 1;
            }
        }
        return size;
    }

    @Override
    public Node toNode() {
        return BexNodeWriter.toNode(this);
    }

    @Override
    public Object toSimple() {
        return BexSimpleWriter.toSimple(this);
    }
}
