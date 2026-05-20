package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

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
        frame.runtime().gas().charge(frame.runtime().gas().schedule().objectSetBase);
        return BexValues.overlay(object.eval(frame), key.get(frame), value.eval(frame));
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
        List<String> segments = pointer.segments(frame);
        frame.runtime().gas().charge(frame.runtime().gas().schedule().pointerGetBase + segments.size());
        BexValue value = object.eval(frame).at(segments);
        return value.isUndefined() && defaultValue != null ? defaultValue.eval(frame) : value;
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
        List<String> segments = pointer.segments(frame);
        String operation = op.get(frame);
        if (!"set".equals(operation) && !"remove".equals(operation)) throw new BexException("Unsupported $pointerSet op: " + operation);
        BexValue val = "remove".equals(operation) ? BexValues.undefined() : value.eval(frame);
        BexValue base = object.eval(frame);
        validatePointerSetBase(base, segments);
        frame.runtime().gas().charge(frame.runtime().gas().schedule().pointerSetBase + segments.size() + frame.runtime().gas().estimatedSize(val) / 64);
        return BexValues.pointerSet(base, segments, val, operation);
    }

    private void validatePointerSetBase(BexValue base, List<String> segments) {
        BexValue current = base;
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (current.isUndefined() || current.isNull()) {
                return;
            }
            if (!current.isObject() && !current.isList()) {
                throw new BexException("$pointerSet encountered incompatible intermediate scalar");
            }
            current = current.get(segments.get(i));
        }
        if (!current.isUndefined() && !current.isNull() && !current.isObject() && !current.isList() && segments.size() > 1) {
            throw new BexException("$pointerSet encountered incompatible intermediate scalar");
        }
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
        return frame.accumulator().changeset().asValue();
    }
}

final class EventsExpr extends Expr {
    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.accumulator().events().asValue();
    }
}

final class ResultValueExpr extends Expr {
    private final PointerOperand pointer;

    ResultValueExpr(PointerOperand pointer) {
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.runtime().readResultValue(pointer.absolute(frame), pointer.segments(frame));
    }
}
