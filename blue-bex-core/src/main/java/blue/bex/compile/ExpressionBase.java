package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

abstract class Expr implements CompiledExpression {
    @Override
    public final BexValue eval(CompiledFrame frame) {
        BexGasWork.charge(frame, BexGasCounter.EXPRESSION_EVALUATED);
        frame.machine().metrics().incrementExpressionEvaluations();
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

final class TransientLiteralExpr extends Expr {
    private final Object scalar;

    TransientLiteralExpr(Object scalar) {
        this.scalar = scalar;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (scalar instanceof String) {
            BexGasWork.chargeTextConstruction(
                    frame, (String) scalar);
        }
        return BexValues.scalar(scalar);
    }
}
