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

final class FrozenNodeBexValue extends AbstractBexValue {
    private final FrozenNode node;

    FrozenNodeBexValue(FrozenNode node) {
        this.node = node;
    }

    FrozenNode node() {
        return node;
    }

    Object rawScalar() {
        return node.getValue();
    }

    @Override
    public boolean isNull() {
        return node.isEmptyNode();
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
            return BexValues.frozen(node.getProperties().get(key));
        }
        if (node.getItems() != null) {
            try {
                return BexValues.frozen(node.item(Integer.parseInt(key)));
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
            String blueId = node.getReferenceBlueId() != null ? node.getReferenceBlueId() : node.blueId();
            return blueId != null ? BexValues.scalar(blueId) : BexValues.UNDEFINED;
        }
        if ("value".equals(key)) {
            return node.getValue() != null ? BexValues.scalar(node.getValue()) : BexValues.UNDEFINED;
        }
        if ("type".equals(key)) {
            return node.getType() != null ? BexValues.frozen(node.getType()) : BexValues.UNDEFINED;
        }
        return BexValues.UNDEFINED;
    }

    @Override
    public BexValue at(List<String> pointerSegments) {
        FrozenNode selected = node.at(pointerSegments);
        if (selected != null) {
            return BexValues.frozen(selected);
        }
        return BexValues.atSegments(this, pointerSegments);
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
    public Node toNode() {
        return node.toNode();
    }

    @Override
    public Object toSimple() {
        if (node.getValue() != null) {
            return node.getValue();
        }
        if (node.getItems() != null) {
            ArrayList<Object> out = new ArrayList<>();
            for (FrozenNode item : node.getItems()) {
                out.add(BexValues.frozen(item).toSimple());
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
