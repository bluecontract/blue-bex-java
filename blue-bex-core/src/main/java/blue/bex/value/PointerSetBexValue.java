package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

final class PointerSetBexValue extends AbstractBexValue {
    private final BexValue base;
    private final List<String> segments;
    private final BexValue value;
    private final String op;
    private final boolean sparseListRemoval;
    private final int targetListIndex;
    private volatile List<String> canonicalKeys;

    PointerSetBexValue(BexValue base, List<String> segments, BexValue value, String op) {
        this(base, segments, value, op, false);
    }

    PointerSetBexValue(BexValue base,
                       List<String> segments,
                       BexValue value,
                       String op,
                       boolean sparseListRemoval) {
        if (base == null || base.isUndefined()) {
            base = BexValues.map(Collections.<String, BexValue>emptyMap());
        }
        if (!base.isObject() && !base.isList()) {
            throw new BexException("$pointerSet base must be an object or list");
        }
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "PointerSetBexValue requires a non-root path");
        }
        String operation = op == null ? "set" : op;
        if (!"set".equals(operation) && !"remove".equals(operation)) {
            throw new BexException("Unsupported $pointerSet op: " + operation);
        }
        this.base = base;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        this.value = value != null ? value : BexValues.UNDEFINED;
        this.op = operation;
        this.sparseListRemoval = sparseListRemoval;
        validatePath(base, this.segments, this.value, operation);
        this.targetListIndex = base.isList()
                ? requireExistingListIndex(this.segments.get(0), base.size())
                : -1;
        if (base.isList()) {
            this.canonicalKeys = Collections.emptyList();
        }
    }

    @Override
    public boolean isObject() {
        return base.isObject();
    }

    @Override
    public boolean isList() {
        return base.isList();
    }

    @Override
    public BexValue get(String key) {
        if (base.isList()) {
            return getListItem(key);
        }
        String head = segments.get(0);
        if (!head.equals(key)) {
            return base.get(key);
        }
        if (segments.size() == 1) {
            return isOmittedLeaf() ? BexValues.UNDEFINED : value;
        }
        BexValue child = base.get(key);
        if (child.isUndefined()) {
            child = BexValues.map(Collections.<String, BexValue>emptyMap());
        }
        return new PointerSetBexValue(
                child,
                segments.subList(1, segments.size()),
                value,
                op,
                sparseListRemoval);
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
                if (segments.size() == 1
                        && isOmittedLeaf()) {
                    retained.remove(segments.get(0));
                } else {
                    retained.add(segments.get(0));
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
    public int size() {
        if (!base.isList()) {
            return keys().size();
        }
        if (segments.size() == 1
                && "remove".equals(op)
                && !sparseListRemoval) {
            return base.size() - 1;
        }
        return base.size();
    }

    @Override
    public Node toNode() { return BexNodeWriter.toNode(this); }

    @Override
    public Object toSimple() { return BexSimpleWriter.toSimple(this); }

    private BexValue getListItem(String key) {
        int requested = parseReadableListIndex(key);
        if (requested < 0 || requested >= size()) {
            return BexValues.UNDEFINED;
        }
        if (segments.size() == 1 && "remove".equals(op)) {
            if (sparseListRemoval) {
                return requested == targetListIndex
                        ? BexValues.UNDEFINED
                        : base.get(String.valueOf(requested));
            }
            int source = requested < targetListIndex
                    ? requested
                    : requested + 1;
            return base.get(String.valueOf(source));
        }
        if (requested != targetListIndex) {
            return base.get(String.valueOf(requested));
        }
        if (segments.size() == 1) {
            // Undefined cannot become a dense BEX list item; validatePath
            // rejects this case before the overlay can be observed.
            return value;
        }
        BexValue child = base.get(String.valueOf(targetListIndex));
        if (child.isUndefined()) {
            child = BexValues.map(Collections.<String, BexValue>emptyMap());
        }
        return new PointerSetBexValue(
                child,
                segments.subList(1, segments.size()),
                value,
                op,
                sparseListRemoval);
    }

    private boolean isOmittedLeaf() {
        return "remove".equals(op) || value.isUndefined();
    }

    private static void validatePath(BexValue base,
                                     List<String> segments,
                                     BexValue value,
                                     String op) {
        BexValue current = base;
        for (int index = 0; index < segments.size(); index++) {
            if (current.isUndefined()) {
                current = BexValues.map(
                        Collections.<String, BexValue>emptyMap());
            }
            if (!current.isObject() && !current.isList()) {
                throw new BexException(
                        "$pointerSet encountered incompatible intermediate scalar");
            }

            String segment = segments.get(index);
            boolean terminal = index == segments.size() - 1;
            if (current.isList()) {
                int listIndex =
                        requireExistingListIndex(segment, current.size());
                if (terminal && "set".equals(op) && value.isUndefined()) {
                    throw new BexException(
                            "$pointerSet cannot set a list item to undefined");
                }
                if (!terminal) {
                    current = current.get(String.valueOf(listIndex));
                }
            } else if (!terminal) {
                current = current.get(segment);
            }
        }
    }

    private static int requireExistingListIndex(String segment, int size) {
        int index = parseReadableListIndex(segment);
        if (index < 0) {
            throw new BexException(
                    "$pointerSet list segment must be a non-negative integer: "
                            + segment);
        }
        if (index >= size) {
            throw new BexException(
                    "$pointerSet list index is out of range: " + segment);
        }
        return index;
    }

    private static int parseReadableListIndex(String segment) {
        if (segment == null || segment.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < segment.length(); index++) {
            char ch = segment.charAt(index);
            if (ch < '0' || ch > '9') {
                return -1;
            }
        }
        try {
            BigInteger parsed = new BigInteger(segment);
            if (parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                return -1;
            }
            return parsed.intValue();
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
