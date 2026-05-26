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
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getBlueId() == null
                && node.getBlue() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getContracts() == null
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
        return node.getProperties() != null || hasObjectCompatibleLanguageFields();
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
        if ("itemType".equals(key)) {
            return node.getItemType() != null ? BexValues.nodeCursor(node.getItemType()) : BexValues.UNDEFINED;
        }
        if ("keyType".equals(key)) {
            return node.getKeyType() != null ? BexValues.nodeCursor(node.getKeyType()) : BexValues.UNDEFINED;
        }
        if ("valueType".equals(key)) {
            return node.getValueType() != null ? BexValues.nodeCursor(node.getValueType()) : BexValues.UNDEFINED;
        }
        if ("blue".equals(key)) {
            return node.getBlue() != null ? BexValues.nodeCursor(node.getBlue()) : BexValues.UNDEFINED;
        }
        if ("contracts".equals(key)) {
            return node.getContracts() != null ? BexValues.nodeCursor(node.getContracts()) : BexValues.UNDEFINED;
        }
        if ("schema".equals(key)) {
            return node.getSchema() != null ? BexValues.nodeSnapshot(new Node().schema(node.getSchema())) : BexValues.UNDEFINED;
        }
        if ("mergePolicy".equals(key)) {
            return node.getMergePolicy() != null ? BexValues.scalar(node.getMergePolicy()) : BexValues.UNDEFINED;
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
        if (node.getProperties() == null && !hasObjectCompatibleLanguageFields()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (hasObjectCompatibleLanguageFields()) {
            addLanguageKeys(fields);
        }
        if (node.getProperties() != null) {
            fields.addAll(node.getProperties().keySet());
        }
        ArrayList<String> keys = new ArrayList<>(fields);
        Collections.sort(keys);
        return keys;
    }

    @Override
    public int size() {
        if (node.getItems() != null) {
            return node.getItems().size();
        }
        if (node.getProperties() != null) {
            return keys().size();
        }
        return hasObjectCompatibleLanguageFields() ? keys().size() : (isScalar() ? 1 : 0);
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
        if (node.getProperties() != null || hasObjectCompatibleLanguageFields()) {
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

    private boolean hasObjectCompatibleLanguageFields() {
        return node.getValue() == null
                && node.getItems() == null
                && (node.getName() != null
                || node.getDescription() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getBlueId() != null
                || node.getBlue() != null
                || node.getContracts() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null);
    }

    private void addLanguageKeys(LinkedHashSet<String> keys) {
        if (node.getName() != null) keys.add("name");
        if (node.getDescription() != null) keys.add("description");
        if (node.getType() != null) keys.add("type");
        if (node.getItemType() != null) keys.add("itemType");
        if (node.getKeyType() != null) keys.add("keyType");
        if (node.getValueType() != null) keys.add("valueType");
        if (node.getBlueId() != null) keys.add("blueId");
        if (node.getBlue() != null) keys.add("blue");
        if (node.getContracts() != null) keys.add("contracts");
        if (node.getSchema() != null) keys.add("schema");
        if (node.getMergePolicy() != null) keys.add("mergePolicy");
    }
}
