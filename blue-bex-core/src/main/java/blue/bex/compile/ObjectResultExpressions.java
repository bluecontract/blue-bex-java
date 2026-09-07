package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

final class ListGetExpr extends Expr {
    private final CompiledExpression list;
    private final CompiledExpression index;
    private final CompiledExpression defaultValue;

    ListGetExpr(CompiledExpression list, CompiledExpression index, CompiledExpression defaultValue) {
        this.list = list;
        this.index = index;
        this.defaultValue = defaultValue;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue l = list.eval(frame);
        if (!l.isList()) throw new BexException("$listGet list must be list");
        int i = index.eval(frame).asInteger().intValueExact();
        if (i < 0) throw new BexException("$listGet index must be non-negative");
        BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
        BexValue value = l.get(String.valueOf(i));
        return value.isUndefined() && defaultValue != null ? defaultValue.eval(frame) : value;
    }
}

final class ObjectSetExpr extends Expr {
    private final CompiledExpression object;
    private final TextOperand key;
    private final CompiledExpression value;

    ObjectSetExpr(CompiledExpression object, TextOperand key, CompiledExpression value) {
        this.object = object;
        this.key = key;
        this.value = value;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue base = object.eval(frame);
        String evaluatedKey = key.get(frame);
        BexValue val = value.eval(frame);
        if (!base.isUndefined() && !base.isNull() && !base.isObject()) {
            throw new BexException("$objectSet.object must be an object, null, or undefined");
        }
        if (!val.isUndefined()) {
            BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
        }
        return BexValues.overlay(base, evaluatedKey, val);
    }
}

final class PointerGetExpr extends Expr {
    private final CompiledExpression object;
    private final PointerOperand pointer;
    private final CompiledExpression defaultValue;

    PointerGetExpr(CompiledExpression object, PointerOperand pointer, CompiledExpression defaultValue) {
        this.object = object;
        this.pointer = pointer;
        this.defaultValue = defaultValue;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue base = object.eval(frame);
        List<String> segments = pointer.segments(frame);
        BexValue value = readAt(frame, base, segments);
        return value.isUndefined() && defaultValue != null ? defaultValue.eval(frame) : value;
    }

    private BexValue readAt(CompiledFrame frame, BexValue base, List<String> segments) {
        BexValue current = base;
        for (String segment : segments) {
            BexGasWork.charge(frame, BexGasCounter.POINTER_SEGMENT_READ);
            if (current.isObject()) {
                BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
            } else if (current.isList()) {
                BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
            }
            current = current.get(segment);
            if (current.isUndefined()) {
                return current;
            }
        }
        return current;
    }
}

final class PointerSetExpr extends Expr {
    private final CompiledExpression object;
    private final TextOperand op;
    private final PointerOperand pointer;
    private final CompiledExpression value;

    PointerSetExpr(CompiledExpression object, TextOperand op, PointerOperand pointer, CompiledExpression value) {
        this.object = object;
        this.op = op;
        this.pointer = pointer;
        this.value = value;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue base = object.eval(frame);
        List<String> segments = pointer.segments(frame);
        String operation = op.get(frame);
        if (!"set".equals(operation) && !"remove".equals(operation)) throw new BexException("Unsupported $pointerSet op: " + operation);
        if ("set".equals(operation) && value == null) {
            throw new BexException("$pointerSet val is required for set");
        }
        BexValue val = "remove".equals(operation) ? BexValues.undefined() : value.eval(frame);
        chargeAndValidatePointerSet(frame, base, segments, val, operation);
        return BexValues.pointerSet(base, segments, val, operation);
    }

    private void chargeAndValidatePointerSet(CompiledFrame frame,
                                             BexValue base,
                                             List<String> segments,
                                             BexValue val,
                                             String operation) {
        BexValue current = base;
        for (int index = 0; index < segments.size(); index++) {
            BexGasWork.charge(frame, BexGasCounter.POINTER_SEGMENT_WRITTEN);
            if (current.isUndefined()) {
                current = BexValues.map(
                        Collections.<String, BexValue>emptyMap());
            }
            if (!current.isObject() && !current.isList()) {
                throw new BexException("$pointerSet encountered incompatible intermediate scalar");
            }

            String segment = segments.get(index);
            boolean terminal = index == segments.size() - 1;
            boolean omittedLeaf = terminal
                    && ("remove".equals(operation) || val.isUndefined());
            if (current.isList()) {
                int listIndex =
                        requireExistingListIndex(segment, current.size());
                if (terminal
                        && "set".equals(operation)
                        && val.isUndefined()) {
                    throw new BexException(
                            "$pointerSet cannot set a list item to undefined");
                }
                if (!omittedLeaf) {
                    BexGasWork.charge(
                            frame,
                            BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
                }
                if (!terminal) {
                    current = current.get(String.valueOf(listIndex));
                }
            } else {
                if (!omittedLeaf) {
                    BexGasWork.charge(
                            frame,
                            BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
                }
                if (!terminal) {
                    current = current.get(segment);
                }
            }
        }
    }

    private int requireExistingListIndex(String segment, int size) {
        if (segment == null || segment.isEmpty()) {
            throw new BexException(
                    "$pointerSet list segment must be a non-negative integer: "
                            + segment);
        }
        for (int index = 0; index < segment.length(); index++) {
            char ch = segment.charAt(index);
            if (ch < '0' || ch > '9') {
                throw new BexException(
                        "$pointerSet list segment must be a non-negative integer: "
                                + segment);
            }
        }
        BigInteger parsed = new BigInteger(segment);
        if (parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                || parsed.intValue() >= size) {
            throw new BexException(
                    "$pointerSet list index is out of range: " + segment);
        }
        return parsed.intValue();
    }
}

final class ChooseExpr extends Expr {
    private final CompiledExpression cond;
    private final CompiledExpression thenExpr;
    private final CompiledExpression elseExpr;

    ChooseExpr(CompiledExpression cond, CompiledExpression thenExpr, CompiledExpression elseExpr) {
        this.cond = cond;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return BexValues.truthy(cond.eval(frame)) ? thenExpr.eval(frame) : elseExpr.eval(frame);
    }
}

final class ChangesetExpr extends Expr {
    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.changesetValue();
    }
}

final class EventsExpr extends Expr {
    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.eventsValue();
    }
}

final class ResultValueExpr extends Expr {
    private final PointerOperand pointer;

    ResultValueExpr(PointerOperand pointer) {
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        ResolvedPointer resolved = pointer.resolve(frame);
        return frame.machine().readResultValue(
                resolved.absolute(), resolved.segments());
    }
}
