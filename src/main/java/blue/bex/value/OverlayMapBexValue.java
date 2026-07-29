package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class OverlayMapBexValue extends AbstractBexValue {
    private final BexValue base;
    private final Map<String, BexValue> overrides;
    private volatile List<String> canonicalKeys;

    OverlayMapBexValue(BexValue base, String key, BexValue value) {
        this(base, Collections.singletonMap(key, value));
    }

    OverlayMapBexValue(BexValue base, Map<String, BexValue> overrides) {
        if (base == null || base.isUndefined() || base.isNull()) {
            base = BexValues.map(Collections.<String, BexValue>emptyMap());
        }
        if (!base.isObject()) {
            throw new BexException("$objectSet base must be an object");
        }
        this.base = base;
        this.overrides = new LinkedHashMap<>(overrides);
    }

    @Override
    public boolean isObject() { return true; }

    @Override
    public BexValue get(String key) {
        if (overrides.containsKey(key)) {
            BexValue value = overrides.get(key);
            return value != null ? value : BexValues.UNDEFINED;
        }
        return base.get(key);
    }

    @Override
    public List<String> keys() {
        List<String> established = canonicalKeys;
        if (established != null) {
            return established;
        }
        synchronized (this) {
            established = canonicalKeys;
            if (established == null) {
                LinkedHashSet<String> retained =
                        new LinkedHashSet<>(base.keys());
                for (Map.Entry<String, BexValue> entry
                        : overrides.entrySet()) {
                    if (entry.getValue() == null
                            || entry.getValue()
                            .isUndefined()) {
                        retained.remove(entry.getKey());
                    } else {
                        retained.add(entry.getKey());
                    }
                }
                established =
                        Collections.unmodifiableList(
                                BexUnicodeOrder.sortedCopy(
                                        retained));
                canonicalKeys = established;
            }
        }
        return established;
    }

    @Override
    public int size() { return keys().size(); }

    @Override
    public Node toNode() { return BexNodeWriter.toNode(this); }

    @Override
    public Object toSimple() { return BexSimpleWriter.toSimple(this); }
}
