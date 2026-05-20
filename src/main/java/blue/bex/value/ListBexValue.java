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

final class ListBexValue extends AbstractBexValue {
    private final List<BexValue> values;

    ListBexValue(List<BexValue> values) {
        ArrayList<BexValue> copy = new ArrayList<>();
        if (values != null) {
            for (BexValue value : values) {
                if (value == null || value.isUndefined()) {
                    throw new BexException("Undefined cannot be stored in a BEX list");
                }
                copy.add(value);
            }
        }
        this.values = Collections.unmodifiableList(copy);
    }

    @Override
    public boolean isList() { return true; }

    @Override
    public BexValue get(String key) {
        try {
            int index = Integer.parseInt(key);
            return index >= 0 && index < values.size() ? values.get(index) : BexValues.UNDEFINED;
        } catch (NumberFormatException ex) {
            return BexValues.UNDEFINED;
        }
    }

    @Override
    public int size() { return values.size(); }

    @Override
    public Node toNode() {
        ArrayList<Node> items = new ArrayList<>();
        for (BexValue value : values) {
            items.add(value.toNode());
        }
        return new Node().items(items);
    }

    @Override
    public Object toSimple() {
        ArrayList<Object> out = new ArrayList<>();
        for (BexValue value : values) {
            out.add(value.toSimple());
        }
        return out;
    }
}
