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

/**
 * Value factories and shared value helpers.
 */
public final class BexValues {
    private BexValues() {
    }

    public static final BexValue UNDEFINED = new UndefinedBexValue();
    public static final BexValue NULL = new NullBexValue();

    public static BexValue undefined() {
        return UNDEFINED;
    }

    public static BexValue nullValue() {
        return NULL;
    }

    public static BexValue scalar(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof BexValue) {
            return (BexValue) value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return new ScalarBexValue(BigInteger.valueOf(((Number) value).longValue()));
        }
        if (value instanceof BigInteger || value instanceof BigDecimal || value instanceof String || value instanceof Boolean) {
            return new ScalarBexValue(value);
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new BexException("Non-finite numeric value is not supported");
            }
            return new ScalarBexValue(BigDecimal.valueOf(d));
        }
        throw new BexException("Unsupported scalar value: " + value.getClass().getName());
    }

    /**
     * Snapshot-safe Node value. Prefer {@link #nodeCursorTrustedImmutable(Node)}
     * only when the caller can enforce immutability during execution.
     *
     * @deprecated use {@link #nodeSnapshot(Node)} or
     * {@link #nodeCursorTrustedImmutable(Node)} to make the boundary contract
     * explicit.
     */
    @Deprecated
    public static BexValue node(Node node) {
        return nodeSnapshot(node);
    }

    /**
     * Direct cursor over a Node with an explicit immutability contract.
     */
    public static BexValue nodeCursorTrustedImmutable(Node node) {
        return node != null ? new NodeBexValue(node) : UNDEFINED;
    }

    /**
     * Backward-compatible direct cursor factory. Prefer
     * {@link #nodeCursorTrustedImmutable(Node)} at host boundaries where the
     * immutability contract matters.
     */
    public static BexValue nodeCursor(Node node) {
        return nodeCursorTrustedImmutable(node);
    }

    /**
     * Snapshot-safe Node value. This clones/freezes the Node at the boundary and
     * is therefore more expensive than a trusted immutable cursor.
     */
    public static BexValue nodeSnapshot(Node node) {
        return node != null ? frozen(FrozenNode.fromResolvedNode(node.clone())) : UNDEFINED;
    }

    public static BexValue frozen(FrozenNode node) {
        return node != null ? new FrozenNodeBexValue(node) : UNDEFINED;
    }

    public static String frozenBlueId(BexValue value) {
        if (!(value instanceof FrozenNodeBexValue)) {
            return null;
        }
        try {
            return ((FrozenNodeBexValue) value).node().blueId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static BexValue map(Map<String, BexValue> values) {
        return new MapBexValue(values);
    }

    public static BexValue list(List<BexValue> values) {
        return new ListBexValue(values);
    }

    public static BexValue overlay(BexValue base, String key, BexValue value) {
        return new OverlayMapBexValue(base, key, value);
    }

    public static BexValue pointerSet(BexValue base, List<String> segments, BexValue value, String op) {
        return new PointerSetBexValue(base, segments, value, op);
    }

    public static BexValue fromSimple(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof Map) {
            Map<String, BexValue> out = new LinkedHashMap<>();
            for (Object entryObject : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
                out.put(String.valueOf(entry.getKey()), fromSimple(entry.getValue()));
            }
            return map(out);
        }
        if (value instanceof List) {
            List<BexValue> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(fromSimple(item));
            }
            return list(out);
        }
        return scalar(value);
    }

    public static boolean truthy(BexValue value) {
        return BexTruthiness.truthy(value);
    }

    public static boolean empty(BexValue value) {
        return !truthy(value);
    }

    public static boolean equal(BexValue left, BexValue right) {
        return BexEquality.equal(left, right);
    }

    static Object scalarSimple(Object value) {
        return value;
    }

    static Object rawScalar(BexValue value) {
        if (value instanceof ScalarBexValue) {
            return ((ScalarBexValue) value).raw();
        }
        if (value instanceof FrozenNodeBexValue) {
            return ((FrozenNodeBexValue) value).rawScalar();
        }
        if (value instanceof NodeBexValue) {
            return ((NodeBexValue) value).rawScalar();
        }
        return value.asText();
    }

    static BexValue atSegments(BexValue value, List<String> segments) {
        BexValue current = value;
        for (String segment : segments) {
            current = current.get(segment);
            if (current.isUndefined()) {
                return current;
            }
        }
        return current;
    }
}
