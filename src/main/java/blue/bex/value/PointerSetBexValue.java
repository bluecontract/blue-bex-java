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

final class PointerSetBexValue extends AbstractBexValue {
    private final BexValue base;
    private final List<String> segments;
    private final BexValue value;
    private final String op;

    PointerSetBexValue(BexValue base, List<String> segments, BexValue value, String op) {
        if (base == null || base.isUndefined() || base.isNull()) {
            base = BexValues.map(Collections.<String, BexValue>emptyMap());
        }
        if (!base.isObject() && !base.isList()) {
            throw new BexException("$pointerSet base must be an object or list");
        }
        this.base = base;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        this.value = value;
        this.op = op == null ? "set" : op;
    }

    @Override
    public boolean isObject() {
        return segments.isEmpty() && !"remove".equals(op) ? value.isObject() : base.isObject();
    }

    @Override
    public boolean isList() {
        return segments.isEmpty() && !"remove".equals(op) ? value.isList() : base.isList();
    }

    @Override
    public BexValue get(String key) {
        if (segments.isEmpty()) {
            return "remove".equals(op) ? BexValues.UNDEFINED : value.get(key);
        }
        String head = segments.get(0);
        if (!head.equals(key)) {
            return base.get(key);
        }
        if (segments.size() == 1) {
            return "remove".equals(op) ? BexValues.UNDEFINED : value;
        }
        BexValue child = base.get(key);
        if (child.isUndefined() || child.isNull()) {
            child = BexValues.map(Collections.<String, BexValue>emptyMap());
        }
        if (!child.isObject() && !child.isList()) {
            throw new BexException("$pointerSet encountered incompatible intermediate scalar");
        }
        return new PointerSetBexValue(child, segments.subList(1, segments.size()), value, op);
    }

    @Override
    public List<String> keys() {
        if (segments.isEmpty()) {
            return "remove".equals(op) ? Collections.<String>emptyList() : value.keys();
        }
        if (base.isList()) {
            return base.keys();
        }
        TreeSet<String> keys = new TreeSet<>(base.keys());
        if (!segments.isEmpty()) {
            if ("remove".equals(op) && segments.size() == 1) {
                keys.remove(segments.get(0));
            } else {
                keys.add(segments.get(0));
            }
        }
        return new ArrayList<>(keys);
    }

    @Override
    public int size() {
        if (segments.isEmpty()) {
            return "remove".equals(op) ? 0 : value.size();
        }
        return base.isList() ? base.size() : keys().size();
    }

    @Override
    public Node toNode() { return BexNodeWriter.toNode(this); }

    @Override
    public Object toSimple() { return BexSimpleWriter.toSimple(this); }
}
