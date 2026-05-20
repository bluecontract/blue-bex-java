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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class MapBexValue extends AbstractBexValue {
    private final Map<String, BexValue> values;

    MapBexValue(Map<String, BexValue> values) {
        LinkedHashMap<String, BexValue> copy = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, BexValue> entry : values.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isUndefined()) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.values = Collections.unmodifiableMap(copy);
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
        ArrayList<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        return keys;
    }

    @Override
    public int size() { return values.size(); }

    @Override
    public Node toNode() {
        Node node = new Node();
        LinkedHashMap<String, Node> properties = new LinkedHashMap<>();
        for (String key : keys()) {
            BexValue value = values.get(key);
            if (value != null && !value.isUndefined()) {
                properties.put(key, value.toNode());
            }
        }
        node.properties(properties);
        return node;
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
