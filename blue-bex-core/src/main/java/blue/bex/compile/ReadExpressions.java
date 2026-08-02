package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import java.util.List;

final class DocumentExpr extends Expr {
    private final PointerOperand pointer;
    private final boolean resolved;

    DocumentExpr(PointerOperand pointer, boolean resolved) {
        this.pointer = pointer;
        this.resolved = resolved;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        ResolvedPointer resolvedPointer = pointer.resolve(frame);
        return frame.readDocument(resolvedPointer.absolute(), resolvedPointer.segments(), resolved);
    }
}

enum ContextKind { EVENT, PROCESSING_EVENT, CURRENT_CONTRACT }

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
        if (kind == ContextKind.EVENT) {
            return frame.readEvent(segments);
        }
        if (kind == ContextKind.PROCESSING_EVENT) {
            return frame.readProcessingEvent(segments);
        }
        return frame.readCurrentContract(segments);
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
        String stepName = step.get(frame);
        if (stepName.isEmpty()) {
            throw new BexException("$steps.step cannot be empty");
        }
        return frame.machine().readSteps(stepName, pointer.segments(frame));
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
        String bindingName = name.get(frame);
        if (bindingName.isEmpty()) {
            throw new BexException("$binding.name cannot be empty");
        }
        return frame.readBinding(bindingName, pointer.segments(frame));
    }
}

final class VarExpr extends Expr {
    private final int slot;
    private final PointerOperand pointer;

    VarExpr(int slot) {
        this(slot, null);
    }

    VarExpr(int slot, PointerOperand pointer) {
        this.slot = slot;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexGasWork.charge(frame, BexGasCounter.VARIABLE_READ);
        BexValue value = frame.getRequired(slot);
        return pointer != null
                ? frame.machine().readValuePointer(value, pointer.segments(frame))
                : value;
    }
}

final class ConstExpr extends Expr {
    private final String name;
    private final PointerOperand pointer;

    ConstExpr(String name) {
        this(name, null);
    }

    ConstExpr(String name, PointerOperand pointer) {
        this.name = name;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexGasWork.charge(frame, BexGasCounter.CONSTANT_READ);
        BexValue value = frame.machine().program().constant(name);
        return pointer != null
                ? frame.machine().readValuePointer(value, pointer.segments(frame))
                : value;
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
        BexValue value = object.eval(frame);
        String evaluatedKey = key.get(frame);
        if (value.isObject()) {
            BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
        }
        return value.get(evaluatedKey);
    }
}
