package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.math.BigInteger;
import java.util.List;

enum CompareOp { EQ, NE, GT, GTE, LT, LTE }

final class CompareExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final CompareOp op;

    CompareExpr(List<CompiledExpression> expressions, CompareOp op) {
        this.expressions = expressions;
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (expressions.size() != 2) throw new BexException("Comparison expects two operands");
        BexValue a = expressions.get(0).eval(frame);
        BexValue b = expressions.get(1).eval(frame);
        boolean result;
        if (op == CompareOp.EQ || op == CompareOp.NE) {
            result = BexValues.equal(a, b);
            if (op == CompareOp.NE) result = !result;
        } else {
            int compare = a.asNumber().compareTo(b.asNumber());
            result = op == CompareOp.GT ? compare > 0 : op == CompareOp.GTE ? compare >= 0 : op == CompareOp.LT ? compare < 0 : compare <= 0;
        }
        return BexValues.scalar(result);
    }
}

final class LogicalExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final boolean and;

    LogicalExpr(List<CompiledExpression> expressions, boolean and) {
        this.expressions = expressions;
        this.and = and;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        for (CompiledExpression expression : expressions) {
            boolean value = BexValues.truthy(expression.eval(frame));
            if (and && !value) return BexValues.scalar(false);
            if (!and && value) return BexValues.scalar(true);
        }
        return BexValues.scalar(and);
    }
}

final class NotExpr extends Expr {
    private final CompiledExpression expression;

    NotExpr(CompiledExpression expression) {
        this.expression = expression;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return BexValues.scalar(!BexValues.truthy(expression.eval(frame)));
    }
}

final class CoalesceExpr extends Expr {
    private final List<CompiledExpression> expressions;

    CoalesceExpr(List<CompiledExpression> expressions) {
        this.expressions = expressions;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        for (CompiledExpression expression : expressions) {
            BexValue value = expression.eval(frame);
            if (!BexValues.empty(value)) return value;
        }
        return BexValues.undefined();
    }
}

enum NumericOp { ADD, SUBTRACT, MULTIPLY, DIVIDE }

final class NumericExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final NumericOp op;

    NumericExpr(List<CompiledExpression> expressions, NumericOp op) {
        this.expressions = expressions;
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (expressions.isEmpty()) throw new BexException("Numeric operator needs operands");
        BigInteger result = expressions.get(0).eval(frame).asInteger();
        if (op == NumericOp.ADD && expressions.size() == 1) return BexValues.scalar(result);
        for (int i = 1; i < expressions.size(); i++) {
            BigInteger next = expressions.get(i).eval(frame).asInteger();
            switch (op) {
                case ADD:
                    result = result.add(next);
                    break;
                case SUBTRACT:
                    result = result.subtract(next);
                    break;
                case MULTIPLY:
                    result = result.multiply(next);
                    break;
                case DIVIDE:
                    if (BigInteger.ZERO.equals(next)) throw new BexException("Division by zero");
                    BigInteger[] div = result.divideAndRemainder(next);
                    if (!BigInteger.ZERO.equals(div[1])) throw new BexException("Non-exact integer division");
                    result = div[0];
                    break;
                default:
                    throw new BexException("Unknown numeric op");
            }
        }
        return BexValues.scalar(result);
    }
}
