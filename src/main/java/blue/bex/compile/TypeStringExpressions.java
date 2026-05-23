package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

enum UnaryOp { UNWRAP, TEXT, INTEGER, NUMBER, BOOLEAN, OBJECT, LIST, TRUTHY, EMPTY, EXISTS, KEYS, ENTRIES, SIZE }

final class UnaryExpr extends Expr {
    private final CompiledExpression expression;
    private final UnaryOp op;

    UnaryExpr(CompiledExpression expression, UnaryOp op) {
        this.expression = expression;
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue value = expression.eval(frame);
        switch (op) {
            case UNWRAP:
                while (value.isObject() && !value.get("value").isUndefined()) {
                    value = value.get("value");
                }
                return value;
            case TEXT:
                return BexValues.scalar(value.asText());
            case INTEGER:
                return BexValues.scalar(value.asInteger());
            case NUMBER:
                return BexValues.scalar(value.asNumber());
            case BOOLEAN:
                return BexValues.scalar(value.asBoolean());
            case OBJECT:
                if (value.isUndefined() || value.isNull()) return BexValues.map(Collections.<String, BexValue>emptyMap());
                if (!value.isObject()) throw new BexException("Value is not an object");
                return value;
            case LIST:
                if (value.isUndefined() || value.isNull()) return BexValues.list(Collections.<BexValue>emptyList());
                if (!value.isList()) throw new BexException("Value is not a list");
                return value;
            case TRUTHY:
                return BexValues.scalar(BexValues.truthy(value));
            case EMPTY:
                return BexValues.scalar(BexValues.empty(value));
            case EXISTS:
                return BexValues.scalar(!value.isUndefined());
            case KEYS:
                List<BexValue> keys = new ArrayList<>();
                for (String key : value.keys()) keys.add(BexValues.scalar(key));
                return BexValues.list(keys);
            case ENTRIES:
                List<BexValue> entries = new ArrayList<>();
                for (String key : value.keys()) {
                    Map<String, BexValue> entry = new LinkedHashMap<>();
                    entry.put("key", BexValues.scalar(key));
                    entry.put("val", value.get(key));
                    entries.add(BexValues.map(entry));
                }
                return BexValues.list(entries);
            case SIZE:
                return BexValues.scalar(BigInteger.valueOf(value.size()));
            default:
                throw new BexException("Unknown unary op");
        }
    }
}

final class IsExpr extends Expr {
    private final CompiledExpression valueExpression;
    private final FrozenNode pattern;

    IsExpr(CompiledExpression valueExpression, FrozenNode pattern) {
        this.valueExpression = valueExpression;
        this.pattern = pattern;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue value = valueExpression.eval(frame);
        return BexValues.scalar(frame.runtime().typeMatcher().matches(value, pattern));
    }
}

enum VariadicOp { CONCAT, LIST_CONCAT, MERGE }

final class VariadicExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final VariadicOp op;

    VariadicExpr(List<CompiledExpression> expressions, VariadicOp op) {
        this.expressions = expressions;
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (op == VariadicOp.CONCAT) {
            StringBuilder builder = new StringBuilder();
            for (CompiledExpression expression : expressions) builder.append(expression.eval(frame).asText());
            return BexValues.scalar(builder.toString());
        }
        if (op == VariadicOp.LIST_CONCAT) {
            List<BexValue> out = new ArrayList<>();
            for (CompiledExpression expression : expressions) {
                BexValue list = expression.eval(frame);
                if (!list.isList()) throw new BexException("$listConcat operand must be list");
                for (int i = 0; i < list.size(); i++) out.add(list.get(String.valueOf(i)));
            }
            return BexValues.list(out);
        }
        Map<String, BexValue> out = new LinkedHashMap<>();
        for (CompiledExpression expression : expressions) {
            BexValue object = expression.eval(frame);
            if (!object.isObject()) throw new BexException("$merge operand must be object");
            for (String key : object.keys()) out.put(key, object.get(key));
        }
        return BexValues.map(out);
    }
}

final class JoinExpr extends Expr {
    private final CompiledExpression items;
    private final CompiledExpression separator;

    JoinExpr(CompiledExpression items, CompiledExpression separator) {
        this.items = items;
        this.separator = separator;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue list = items.eval(frame);
        if (!list.isList()) throw new BexException("$join list must be a list");
        String sep = separator.eval(frame).asText();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(sep);
            out.append(list.get(String.valueOf(i)).asText());
        }
        return BexValues.scalar(out.toString());
    }
}

final class PointerJoinExpr extends Expr {
    private final List<CompiledExpression> segments;

    PointerJoinExpr(List<CompiledExpression> segments) {
        this.segments = segments;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (segments.isEmpty()) {
            return BexValues.scalar("/");
        }
        StringBuilder out = new StringBuilder();
        for (CompiledExpression expression : segments) {
            BexValue value = expression.eval(frame);
            if (value.isUndefined() || value.isNull()) {
                throw new BexException("$pointerJoin segment cannot be null or undefined");
            }
            out.append('/');
            out.append(escapeSegment(value.asText()));
        }
        return BexValues.scalar(out.toString());
    }

    private String escapeSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}

final class SplitExpr extends Expr {
    private final CompiledExpression text;
    private final CompiledExpression separator;
    private final CompiledExpression limit;

    SplitExpr(CompiledExpression text, CompiledExpression separator, CompiledExpression limit) {
        this.text = text;
        this.separator = separator;
        this.limit = limit;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        String input = text.eval(frame).asText();
        String sep = separator.eval(frame).asText();
        if (sep.isEmpty()) throw new BexException("$split separator must not be empty");
        int max = limit != null ? limit.eval(frame).asInteger().intValueExact() : -1;
        if (max == 0 || max < -1) throw new BexException("$split limit must be positive");
        String[] parts = input.split(Pattern.quote(sep), max == -1 ? -1 : max);
        List<BexValue> out = new ArrayList<>();
        for (String part : parts) out.add(BexValues.scalar(part));
        return BexValues.list(out);
    }
}

enum BinaryTextOp { STARTS_WITH, SLICE_AFTER }

final class BinaryTextExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final BinaryTextOp op;

    BinaryTextExpr(List<CompiledExpression> expressions, BinaryTextOp op) {
        this.expressions = expressions;
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (expressions.size() != 2) throw new BexException("Text operator expects two operands");
        String text = expressions.get(0).eval(frame).asText();
        String prefix = expressions.get(1).eval(frame).asText();
        if (op == BinaryTextOp.STARTS_WITH) return BexValues.scalar(text.startsWith(prefix));
        return BexValues.scalar(text.startsWith(prefix) ? text.substring(prefix.length()) : "");
    }
}
