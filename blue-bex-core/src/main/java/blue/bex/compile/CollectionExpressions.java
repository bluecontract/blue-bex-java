package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValues;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class KindExpr extends Expr {
    private final CompiledExpression value;

    KindExpr(CompiledExpression value) {
        this.value = value;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return BexValues.scalar(BexValues.kind(value.eval(frame)));
    }
}

final class IsKindExpr extends Expr {
    private final CompiledExpression value;
    private final Set<String> kinds;

    IsKindExpr(CompiledExpression value, Set<String> kinds) {
        this.value = value;
        this.kinds = Collections.unmodifiableSet(new LinkedHashSet<>(kinds));
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return BexValues.scalar(kinds.contains(BexValues.kind(value.eval(frame))));
    }
}

enum CollectionOp { MAP, FILTER, FLAT_MAP, SOME, FIND, FIND_ENTRY }

final class CollectionQueryExpr extends Expr {
    private final CompiledExpression input;
    private final int itemSlot;
    private final int keySlot;
    private final int indexSlot;
    private final CompiledExpression body;
    private final CollectionOp op;

    CollectionQueryExpr(CompiledExpression input,
                        int itemSlot,
                        int keySlot,
                        int indexSlot,
                        CompiledExpression body,
                        CollectionOp op) {
        this.input = input;
        this.itemSlot = itemSlot;
        this.keySlot = keySlot;
        this.indexSlot = indexSlot;
        this.body = body;
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        SlotSnapshot snapshot = SlotSnapshot.capture(frame, itemSlot, keySlot, indexSlot);
        try {
            BexValue value = input.eval(frame);
            if (value.isList()) {
                return evalList(frame, value);
            }
            if (value.isObject()) {
                return evalObject(frame, value);
            }
            throw new BexException(collectionName() + " input must be list or object");
        } finally {
            snapshot.restore(frame);
        }
    }

    private BexValue evalList(CompiledFrame frame, BexValue list) {
        List<BexValue> out = needsListOutput() ? new ArrayList<BexValue>() : null;
        for (int i = 0; i < list.size(); i++) {
            BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
            BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
            frame.machine().metrics().incrementLoopIterations();
            BexValue item = list.get(String.valueOf(i));
            bind(frame, item, BexValues.undefined(), BexValues.scalar(BigInteger.valueOf(i)));
            BexValue result = body.eval(frame);
            switch (op) {
                case MAP:
                    producedListItem(frame);
                    out.add(result);
                    break;
                case FILTER:
                    if (BexValues.truthy(result)) {
                        producedListItem(frame);
                        out.add(item);
                    }
                    break;
                case FLAT_MAP:
                    appendList(frame, out, result);
                    break;
                case SOME:
                    if (BexValues.truthy(result)) {
                        return BexValues.scalar(true);
                    }
                    break;
                case FIND:
                    if (BexValues.truthy(result)) {
                        return item;
                    }
                    break;
                case FIND_ENTRY:
                    if (BexValues.truthy(result)) {
                        Map<String, BexValue> entry = new LinkedHashMap<>();
                        BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
                        BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED, 2L);
                        entry.put("val", item);
                        entry.put("index", BexValues.scalar(BigInteger.valueOf(i)));
                        return BexValues.map(entry);
                    }
                    break;
                default:
                    throw new BexException("Unknown collection operator");
            }
        }
        return finishNoMatch(out);
    }

    private BexValue evalObject(CompiledFrame frame, BexValue object) {
        List<BexValue> listOut = needsListOutput() ? new ArrayList<BexValue>() : null;
        Map<String, BexValue> objectOut = op == CollectionOp.FILTER ? new LinkedHashMap<String, BexValue>() : null;
        List<String> keys = object.keys();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
            BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
            frame.machine().metrics().incrementLoopIterations();
            BexValue item = object.get(key);
            bind(frame, item, BexValues.scalar(key), BexValues.scalar(BigInteger.valueOf(i)));
            BexValue result = body.eval(frame);
            switch (op) {
                case MAP:
                    producedListItem(frame);
                    listOut.add(result);
                    break;
                case FILTER:
                    if (BexValues.truthy(result)) {
                        BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
                        BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
                        objectOut.put(key, item);
                    }
                    break;
                case FLAT_MAP:
                    appendList(frame, listOut, result);
                    break;
                case SOME:
                    if (BexValues.truthy(result)) {
                        return BexValues.scalar(true);
                    }
                    break;
                case FIND:
                    if (BexValues.truthy(result)) {
                        return item;
                    }
                    break;
                case FIND_ENTRY:
                    if (BexValues.truthy(result)) {
                        Map<String, BexValue> entry = new LinkedHashMap<>();
                        BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
                        BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED, 3L);
                        entry.put("key", BexValues.scalar(key));
                        entry.put("val", item);
                        entry.put("index", BexValues.scalar(BigInteger.valueOf(i)));
                        return BexValues.map(entry);
                    }
                    break;
                default:
                    throw new BexException("Unknown collection operator");
            }
        }
        if (op == CollectionOp.FILTER) {
            return BexValues.map(objectOut);
        }
        return finishNoMatch(listOut);
    }

    private void bind(CompiledFrame frame, BexValue item, BexValue key, BexValue index) {
        frame.set(itemSlot, item);
        if (keySlot >= 0) {
            frame.set(keySlot, key);
        }
        if (indexSlot >= 0) {
            frame.set(indexSlot, index);
        }
    }

    private boolean needsListOutput() {
        return op == CollectionOp.MAP || op == CollectionOp.FLAT_MAP || op == CollectionOp.FILTER;
    }

    private BexValue finishNoMatch(List<BexValue> out) {
        switch (op) {
            case MAP:
            case FLAT_MAP:
            case FILTER:
                return BexValues.list(out);
            case SOME:
                return BexValues.scalar(false);
            case FIND:
            case FIND_ENTRY:
                return BexValues.undefined();
            default:
                throw new BexException("Unknown collection operator");
        }
    }

    private void appendList(CompiledFrame frame, List<BexValue> out, BexValue value) {
        if (!value.isList()) {
            throw new BexException("$flatMap expr must return a list");
        }
        for (int i = 0; i < value.size(); i++) {
            BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
            producedListItem(frame);
            out.add(value.get(String.valueOf(i)));
        }
    }

    private void producedListItem(CompiledFrame frame) {
        BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
        BexGasWork.charge(frame, BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
    }

    private String collectionName() {
        switch (op) {
            case MAP:
                return "$map";
            case FILTER:
                return "$filter";
            case FLAT_MAP:
                return "$flatMap";
            case SOME:
                return "$some";
            case FIND:
                return "$find";
            case FIND_ENTRY:
                return "$findEntry";
            default:
                return "collection operator";
        }
    }
}

final class ReduceExpr extends Expr {
    private final CompiledExpression input;
    private final int accSlot;
    private final CompiledExpression init;
    private final int itemSlot;
    private final int keySlot;
    private final int indexSlot;
    private final CompiledExpression expr;

    ReduceExpr(CompiledExpression input,
               int accSlot,
               CompiledExpression init,
               int itemSlot,
               int keySlot,
               int indexSlot,
               CompiledExpression expr) {
        this.input = input;
        this.accSlot = accSlot;
        this.init = init;
        this.itemSlot = itemSlot;
        this.keySlot = keySlot;
        this.indexSlot = indexSlot;
        this.expr = expr;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        SlotSnapshot snapshot = SlotSnapshot.capture(frame, accSlot, itemSlot, keySlot, indexSlot);
        try {
            BexValue collection = input.eval(frame);
            BexValue acc = init.eval(frame);
            frame.set(accSlot, acc);
            if (collection.isList()) {
                for (int i = 0; i < collection.size(); i++) {
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
                    BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
                    frame.machine().metrics().incrementLoopIterations();
                    bind(frame, collection.get(String.valueOf(i)), BexValues.undefined(),
                            BexValues.scalar(BigInteger.valueOf(i)));
                    acc = expr.eval(frame);
                    frame.set(accSlot, acc);
                }
                return acc;
            }
            if (collection.isObject()) {
                List<String> keys = collection.keys();
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
                    BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
                    frame.machine().metrics().incrementLoopIterations();
                    bind(frame, collection.get(key), BexValues.scalar(key), BexValues.scalar(BigInteger.valueOf(i)));
                    acc = expr.eval(frame);
                    frame.set(accSlot, acc);
                }
                return acc;
            }
            throw new BexException("$reduce input must be list or object");
        } finally {
            snapshot.restore(frame);
        }
    }

    private void bind(CompiledFrame frame, BexValue item, BexValue key, BexValue index) {
        frame.set(itemSlot, item);
        if (keySlot >= 0) {
            frame.set(keySlot, key);
        }
        if (indexSlot >= 0) {
            frame.set(indexSlot, index);
        }
    }
}

final class SlotSnapshot {
    private final int[] slots;
    private final BexValue[] values;
    private final boolean[] initialized;

    private SlotSnapshot(int[] slots, BexValue[] values, boolean[] initialized) {
        this.slots = slots.clone();
        this.values = values.clone();
        this.initialized = initialized.clone();
    }

    static SlotSnapshot capture(CompiledFrame frame, int... candidates) {
        List<Integer> slotList = new ArrayList<>();
        List<BexValue> valueList = new ArrayList<>();
        List<Boolean> initializedList = new ArrayList<>();
        for (int slot : candidates) {
            if (slot >= 0 && !slotList.contains(slot)) {
                slotList.add(slot);
                initializedList.add(frame.isInitialized(slot));
                valueList.add(frame.get(slot));
            }
        }
        int[] slots = new int[slotList.size()];
        BexValue[] values = new BexValue[valueList.size()];
        boolean[] initialized = new boolean[initializedList.size()];
        for (int i = 0; i < slotList.size(); i++) {
            slots[i] = slotList.get(i);
            values[i] = valueList.get(i);
            initialized[i] = initializedList.get(i);
        }
        return new SlotSnapshot(slots, values, initialized);
    }

    void restore(CompiledFrame frame) {
        for (int i = 0; i < slots.length; i++) {
            if (initialized[i]) {
                frame.set(slots[i], values[i]);
            } else {
                frame.clear(slots[i]);
            }
        }
    }
}

final class IncludesExpr extends Expr {
    private final CompiledExpression listExpr;
    private final CompiledExpression valExpr;

    IncludesExpr(CompiledExpression listExpr, CompiledExpression valExpr) {
        this.listExpr = listExpr;
        this.valExpr = valExpr;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue list = listExpr.eval(frame);
        if (!list.isList()) {
            throw new BexException("$includes.list must be a list");
        }
        BexValue val = valExpr.eval(frame);
        for (int i = 0; i < list.size(); i++) {
            BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
            BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
            frame.machine().metrics().incrementLoopIterations();
            if (MeteredEquality.equal(
                    frame, list.get(String.valueOf(i)), val)) {
                return BexValues.scalar(true);
            }
        }
        return BexValues.scalar(false);
    }
}

final class HasKeyExpr extends Expr {
    private final CompiledExpression objectExpr;
    private final TextOperand key;

    HasKeyExpr(CompiledExpression objectExpr, TextOperand key) {
        this.objectExpr = objectExpr;
        this.key = key;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue object = objectExpr.eval(frame);
        if (!object.isObject()) {
            return BexValues.scalar(false);
        }
        String evaluatedKey = key.get(frame);
        BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
        return BexValues.scalar(!object.get(evaluatedKey).isUndefined());
    }
}

final class ObjectFromEntriesExpr extends Expr {
    private final CompiledExpression entriesExpr;

    ObjectFromEntriesExpr(CompiledExpression entriesExpr) {
        this.entriesExpr = entriesExpr;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue entries = entriesExpr.eval(frame);
        if (!entries.isList()) {
            throw new BexException("$objectFromEntries input must be a list");
        }
        Map<String, BexValue> retained = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
            BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
            frame.machine().metrics().incrementLoopIterations();
            BexValue entry = entries.get(String.valueOf(i));
            if (!entry.isObject()) {
                throw new BexException("$objectFromEntries entries must be objects");
            }
            BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
            BexValue key = entry.get("key");
            if (key.isUndefined() || key.isNull()) {
                throw new BexException("$objectFromEntries key cannot be null or undefined");
            }
            BexGasWork.MeteredText convertedKey =
                    BexGasWork.constructedText(frame, key);
            String textKey = convertedKey.text();
            BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
            BexValue val = entry.get("val");
            if (val.isUndefined()) {
                retained.remove(textKey);
            } else {
                retained.put(textKey, val);
            }
        }
        Map<String, BexValue> out = new LinkedHashMap<>();
        for (String key : BexUnicodeOrder.sortedCopy(
                retained.keySet(),
                (left, right) -> {
                    BexGasWork.charge(
                            frame,
                            BexGasCounter.SORT_COMPARISON);
                    BexGasWork.charge(
                            frame,
                            BexGasCounter.COMPARISON_NODE_VISITED);
                    return BexGasWork.compareText(
                            frame, left, right);
                })) {
            BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
            BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
            out.put(key, retained.get(key));
        }
        return BexValues.map(out);
    }
}
