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

final class NodeBexValue extends AbstractBexValue {
    private final Node node;

    NodeBexValue(Node node) {
        this.node = node;
    }

    Object rawScalar() {
        return node.getValue();
    }

    @Override
    public boolean isNull() {
        return node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getBlueId() == null
                && node.getValue() == null
                && node.getItems() == null
                && node.getProperties() == null;
    }

    @Override
    public boolean isScalar() {
        return node.getValue() != null && node.getItems() == null && node.getProperties() == null;
    }

    @Override
    public boolean isObject() {
        return node.getProperties() != null;
    }

    @Override
    public boolean isList() {
        return node.getItems() != null;
    }

    @Override
    public BexValue get(String key) {
        if (node.getProperties() != null && node.getProperties().containsKey(key)) {
            return BexValues.nodeCursor(node.getProperties().get(key));
        }
        if (node.getItems() != null) {
            try {
                int index = Integer.parseInt(key);
                return index >= 0 && index < node.getItems().size()
                        ? BexValues.nodeCursor(node.getItems().get(index))
                        : BexValues.UNDEFINED;
            } catch (NumberFormatException ignored) {
                return BexValues.UNDEFINED;
            }
        }
        if ("name".equals(key)) {
            return node.getName() != null ? BexValues.scalar(node.getName()) : BexValues.UNDEFINED;
        }
        if ("description".equals(key)) {
            return node.getDescription() != null ? BexValues.scalar(node.getDescription()) : BexValues.UNDEFINED;
        }
        if ("blueId".equals(key)) {
            return node.getBlueId() != null ? BexValues.scalar(node.getBlueId()) : BexValues.UNDEFINED;
        }
        if ("value".equals(key)) {
            return node.getValue() != null ? BexValues.scalar(node.getValue()) : BexValues.UNDEFINED;
        }
        if ("type".equals(key)) {
            return node.getType() != null ? BexValues.nodeCursor(node.getType()) : BexValues.UNDEFINED;
        }
        return BexValues.UNDEFINED;
    }

    @Override
    public BexValue at(List<String> pointerSegments) {
        BexValue current = this;
        for (String segment : pointerSegments) {
            current = current.get(segment);
            if (current.isUndefined()) {
                return current;
            }
        }
        return current;
    }

    @Override
    public String asText() {
        if (node.getValue() == null) {
            return super.asText();
        }
        return String.valueOf(node.getValue());
    }

    @Override
    public BigInteger asInteger() {
        return node.getValue() != null ? BexValues.scalar(node.getValue()).asInteger() : super.asInteger();
    }

    @Override
    public BigDecimal asNumber() {
        return node.getValue() != null ? BexValues.scalar(node.getValue()).asNumber() : super.asNumber();
    }

    @Override
    public boolean asBoolean() {
        return node.getValue() != null ? BexValues.scalar(node.getValue()).asBoolean() : super.asBoolean();
    }

    @Override
    public List<String> keys() {
        if (node.getProperties() == null) {
            return Collections.emptyList();
        }
        ArrayList<String> keys = new ArrayList<>(node.getProperties().keySet());
        Collections.sort(keys);
        return keys;
    }

    @Override
    public int size() {
        if (node.getItems() != null) {
            return node.getItems().size();
        }
        if (node.getProperties() != null) {
            return node.getProperties().size();
        }
        return isScalar() ? 1 : 0;
    }

    @Override
    public Node toNode() { return node.clone(); }

    @Override
    public Object toSimple() {
        if (node.getValue() != null) {
            return node.getValue();
        }
        if (node.getItems() != null) {
            ArrayList<Object> out = new ArrayList<>();
            for (Node item : node.getItems()) {
                out.add(BexValues.nodeCursor(item).toSimple());
            }
            return out;
        }
        if (node.getProperties() != null) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (String key : keys()) {
                BexValue value = get(key);
                if (!value.isUndefined()) {
                    out.put(key, value.toSimple());
                }
            }
            return out;
        }
        return null;
    }
}
