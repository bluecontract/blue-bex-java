package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MapBexValue extends AbstractBexValue {
    private final Map<String, BexValue> values;
    private final List<String> canonicalKeys;

    MapBexValue(Map<String, BexValue> values) {
        LinkedHashMap<String, BexValue> copy = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, BexValue> entry : values.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isUndefined()) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        List<String> ordered =
                BexUnicodeOrder.sortedCopy(copy.keySet());
        LinkedHashMap<String, BexValue> canonical =
                new LinkedHashMap<>();
        for (String key : ordered) {
            canonical.put(key, copy.get(key));
        }
        this.values = Collections.unmodifiableMap(canonical);
        this.canonicalKeys =
                Collections.unmodifiableList(ordered);
    }

    @Override
    public boolean isObject() { return true; }

    @Override
    public BexValue get(String key) {
        BexValue value = values.get(key);
        return value != null ? value : BexValues.UNDEFINED;
    }

    @Override
    public List<String> keys() {
        return canonicalKeys;
    }

    @Override
    public int size() { return values.size(); }

    @Override
    public Node toNode() {
        return BexNodeWriter.toNode(this);
    }

    @Override
    public Object toSimple() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (String key : keys()) {
            out.put(key, values.get(key).toSimple());
        }
        return out;
    }
}
