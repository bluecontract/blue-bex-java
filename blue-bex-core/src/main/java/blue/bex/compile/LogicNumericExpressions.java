package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

enum CompareOp { EQ, NE, GT, GTE, LT, LTE }

final class MeteredEquality {
    private MeteredEquality() {
    }

    static boolean equal(
            CompiledFrame frame, BexValue left, BexValue right) {
        left = left != null ? left : BexValues.undefined();
        right = right != null ? right : BexValues.undefined();
        BexGasWork.charge(
                frame, BexGasCounter.COMPARISON_NODE_VISITED);

        if (left.isUndefined() || right.isUndefined()) {
            return left.isUndefined() && right.isUndefined();
        }
        if (left.isNull() || right.isNull()) {
            return left.isNull() && right.isNull();
        }
        if (left.isExact() && right.isExact()
                && left.exactBlueId().equals(right.exactBlueId())) {
            return true;
        }

        String leftKind = BexValues.kind(left);
        String rightKind = BexValues.kind(right);
        if (isNumeric(leftKind) && isNumeric(rightKind)) {
            return BexGasWork.compareNumbers(
                    frame, left, right, true) == 0;
        }
        if (!leftKind.equals(rightKind)) {
            return false;
        }
        if ("text".equals(leftKind)) {
            return BexGasWork.equalText(
                    frame, left, right);
        }
        if ("boolean".equals(leftKind)) {
            return left.asBoolean() == right.asBoolean();
        }
        if ("list".equals(leftKind)) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!equal(frame,
                        left.get(String.valueOf(index)),
                        right.get(String.valueOf(index)))) {
                    return false;
                }
            }
            return true;
        }
        if ("object".equals(leftKind)) {
            List<String> leftKeys = left.keys();
            List<String> rightKeys = right.keys();
            if (!leftKeys.equals(rightKeys)) {
                return false;
            }
            for (String key : leftKeys) {
                if (!equal(frame, left.get(key), right.get(key))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isNumeric(String kind) {
        return "integer".equals(kind) || "double".equals(kind);
    }
}

final class CompareExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final CompareOp op;

    CompareExpr(List<CompiledExpression> expressions, CompareOp op) {
        this.expressions = ImmutableExpressionLists.copyOf(expressions);
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (expressions.size() != 2) throw new BexException("Comparison expects two operands");
        BexValue a = expressions.get(0).eval(frame);
        BexValue b = expressions.get(1).eval(frame);
        boolean result;
        if (op == CompareOp.EQ || op == CompareOp.NE) {
            result = MeteredEquality.equal(frame, a, b);
            if (op == CompareOp.NE) result = !result;
        } else {
            BexGasWork.charge(
                    frame, BexGasCounter.COMPARISON_NODE_VISITED);
            int compare = BexGasWork.compareNumbers(
                    frame, a, b, true);
            result = op == CompareOp.GT ? compare > 0 : op == CompareOp.GTE ? compare >= 0 : op == CompareOp.LT ? compare < 0 : compare <= 0;
        }
        return BexValues.scalar(result);
    }
}

final class LogicalExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final boolean and;

    LogicalExpr(List<CompiledExpression> expressions, boolean and) {
        this.expressions = ImmutableExpressionLists.copyOf(expressions);
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
        this.expressions = ImmutableExpressionLists.copyOf(expressions);
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
        this.expressions = ImmutableExpressionLists.copyOf(expressions);
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (expressions.isEmpty()) throw new BexException("Numeric operator needs operands");
        BigInteger result = BexGasWork.integerOperand(
                frame, expressions.get(0).eval(frame));
        if (op == NumericOp.ADD && expressions.size() == 1) return BexValues.scalar(result);
        for (int i = 1; i < expressions.size(); i++) {
            BigInteger next = BexGasWork.integerOperand(
                    frame, expressions.get(i).eval(frame));
            long leftLimbs = BexGasWork.integerLimbs(result);
            long rightLimbs = BexGasWork.integerLimbs(next);
            switch (op) {
                case ADD:
                    BexGasWork.charge(frame, BexGasCounter.INTEGER_LIMB_OPERATION,
                            Math.max(leftLimbs, rightLimbs) + 1L);
                    result = result.add(next);
                    break;
                case SUBTRACT:
                    BexGasWork.charge(frame, BexGasCounter.INTEGER_LIMB_OPERATION,
                            Math.max(leftLimbs, rightLimbs) + 1L);
                    result = result.subtract(next);
                    break;
                case MULTIPLY:
                    BexGasWork.charge(frame, BexGasCounter.INTEGER_LIMB_OPERATION,
                            leftLimbs * rightLimbs);
                    result = result.multiply(next);
                    break;
                case DIVIDE:
                    if (BigInteger.ZERO.equals(next)) throw new BexException("Division by zero");
                    BexGasWork.charge(frame, BexGasCounter.INTEGER_LIMB_OPERATION,
                            leftLimbs * rightLimbs);
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

final class ImmutableExpressionLists {
    private ImmutableExpressionLists() {
    }

    static List<CompiledExpression> copyOf(
            List<CompiledExpression> expressions) {
        return Collections.unmodifiableList(new ArrayList<>(expressions));
    }
}
