package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class Expr implements CompiledExpression {
    @Override
    public final BexValue eval(CompiledFrame frame) {
        frame.runtime().metrics().incrementExpressionEvaluations();
        frame.runtime().gas().charge(frame.runtime().gas().schedule().expressionBase);
        try {
            return doEval(frame);
        } catch (BexException ex) {
            BexSourcePath sourcePath = frame.sourcePath();
            if (sourcePath != null && !ex.sourcePath().isPresent()) {
                throw ex.withSourcePath(sourcePath);
            }
            throw ex;
        }
    }

    protected abstract BexValue doEval(CompiledFrame frame);
}

final class SourceExpr implements CompiledExpression {
    private final BexSourcePath sourcePath;
    private final CompiledExpression delegate;

    SourceExpr(BexSourcePath sourcePath, CompiledExpression delegate) {
        this.sourcePath = sourcePath;
        this.delegate = delegate;
    }

    @Override
    public BexValue eval(CompiledFrame frame) {
        BexSourcePath previous = frame.enter(sourcePath);
        try {
            return delegate.eval(frame);
        } catch (BexException ex) {
            if (!ex.sourcePath().isPresent()) {
                throw ex.withSourcePath(sourcePath);
            }
            throw ex;
        } finally {
            frame.restore(previous);
        }
    }
}

final class LiteralExpr extends Expr {
    private final BexValue value;

    LiteralExpr(BexValue value) {
        this.value = value;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return value;
    }
}

final class DocumentExpr extends Expr {
    private final PointerOperand pointer;
    private final boolean resolved;

    DocumentExpr(PointerOperand pointer, boolean resolved) {
        this.pointer = pointer;
        this.resolved = resolved;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        String absolute = pointer.absolute(frame);
        return frame.readDocument(absolute, pointer.segments(frame), resolved);
    }
}

enum ContextKind { EVENT, CURRENT_CONTRACT }

final class ContextPointerExpr extends Expr {
    private final PointerOperand pointer;
    private final ContextKind kind;

    ContextPointerExpr(PointerOperand pointer, ContextKind kind) {
        this.pointer = pointer;
        this.kind = kind;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        List<String> segments = pointer.segments(frame);
        return kind == ContextKind.EVENT ? frame.readEvent(segments) : frame.readCurrentContract(segments);
    }
}

final class StepsExpr extends Expr {
    private final TextOperand step;
    private final PointerOperand pointer;

    StepsExpr(TextOperand step, PointerOperand pointer) {
        this.step = step;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.runtime().readSteps(step.get(frame), pointer.segments(frame));
    }
}

final class BindingExpr extends Expr {
    private final TextOperand name;
    private final PointerOperand pointer;

    BindingExpr(TextOperand name, PointerOperand pointer) {
        this.name = name;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.readBinding(name.get(frame), pointer.segments(frame));
    }
}

final class VarExpr extends Expr {
    private final int slot;

    VarExpr(int slot) {
        this.slot = slot;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        frame.runtime().gas().charge(frame.runtime().gas().schedule().varRead);
        return frame.get(slot);
    }
}

final class ConstExpr extends Expr {
    private final String name;

    ConstExpr(String name) {
        this.name = name;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.runtime().program().constant(name);
    }
}

final class GetExpr extends Expr {
    private final CompiledExpression object;
    private final TextOperand key;

    GetExpr(CompiledExpression object, TextOperand key) {
        this.object = object;
        this.key = key;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return object.eval(frame).get(key.get(frame));
    }
}

final class ObjectExpr extends Expr {
    private final Map<String, CompiledExpression> fields;

    ObjectExpr(Map<String, CompiledExpression> fields) {
        this.fields = fields;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        Map<String, BexValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledExpression> entry : fields.entrySet()) {
            BexValue value = entry.getValue().eval(frame);
            if (!value.isUndefined()) {
                out.put(entry.getKey(), value);
            }
        }
        return BexValues.map(out);
    }
}

final class ListExpr extends Expr {
    private final List<CompiledExpression> items;

    ListExpr(List<CompiledExpression> items) {
        this.items = items;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        List<BexValue> out = new ArrayList<>();
        for (CompiledExpression item : items) {
            out.add(item.eval(frame));
        }
        return BexValues.list(out);
    }
}
