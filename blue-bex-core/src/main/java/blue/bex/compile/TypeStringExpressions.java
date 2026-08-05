package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                while (value.isObject()) {
                    BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
                    BexValue next = value.get("value");
                    if (next.isUndefined()) {
                        break;
                    }
                    value = next;
                }
                return value;
            case TEXT:
                BexGasWork.MeteredText text =
                        BexGasWork.constructedText(frame, value);
                return BexValues.scalar(text.text());
            case INTEGER:
                return BexValues.scalar(
                        BexGasWork.convertInteger(frame, value));
            case NUMBER:
                return BexValues.scalar(
                        BexGasWork.convertNumber(frame, value));
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
                for (String key : value.keys()) {
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
                    BexGasWork.charge(frame, BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
                    BexGasWork.chargeTextConstruction(
                            frame, key);
                    keys.add(BexValues.scalar(key));
                }
                return BexValues.list(keys);
            case ENTRIES:
                List<BexValue> entries = new ArrayList<>();
                for (String key : value.keys()) {
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
                    BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
                    Map<String, BexValue> entry = new LinkedHashMap<>();
                    BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED, 2L);
                    entry.put("key", BexValues.scalar(key));
                    entry.put("val", value.get(key));
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
                    BexGasWork.charge(frame, BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
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
        return BexValues.scalar(frame.machine().matchesType(
                value,
                pattern,
                frame.sourcePath()));
    }
}

enum VariadicOp { CONCAT, LIST_CONCAT, MERGE }

final class VariadicExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final VariadicOp op;

    VariadicExpr(List<CompiledExpression> expressions, VariadicOp op) {
        this.expressions = ImmutableExpressionLists.copyOf(expressions);
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (op == VariadicOp.CONCAT) {
            List<BexGasWork.MeteredText> items =
                    new ArrayList<>(expressions.size());
            TextConstructionShape resultShape =
                    new TextConstructionShape();
            for (CompiledExpression expression : expressions) {
                BexGasWork.MeteredText item =
                        BexGasWork.fullText(
                                frame,
                                expression.eval(frame));
                resultShape.append(item);
                items.add(item);
            }
            BexGasWork.charge(frame, BexGasCounter.TEXT_BLOCK_CONSTRUCTED,
                    BexGasWork.textBlocksForCodePoints(
                            resultShape.codePoints()));

            /*
             * The aggregate construction charge is now admitted before any
             * result buffer allocation, append, or toString work.
             */
            StringBuilder builder = new StringBuilder();
            for (BexGasWork.MeteredText item : items) {
                builder.append(item.text());
            }
            String result = builder.toString();
            return BexValues.scalar(result);
        }
        if (op == VariadicOp.LIST_CONCAT) {
            List<BexValue> out = new ArrayList<>();
            for (CompiledExpression expression : expressions) {
                BexValue list = expression.eval(frame);
                if (!list.isList()) throw new BexException("$listConcat operand must be list");
                for (int i = 0; i < list.size(); i++) {
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
                    BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
                    BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
                    BexGasWork.charge(frame, BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
                    out.add(list.get(String.valueOf(i)));
                }
            }
            return BexValues.list(out);
        }
        Map<String, BexValue> retained = new LinkedHashMap<>();
        for (CompiledExpression expression : expressions) {
            BexValue object = expression.eval(frame);
            if (!object.isObject()) throw new BexException("$merge operand must be object");
            for (String key : object.keys()) {
                BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
                BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
                retained.put(key, object.get(key));
            }
        }
        Map<String, BexValue> out = new LinkedHashMap<>();
        for (String key : BexUnicodeOrder.sortedCopy(
                retained.keySet(),
                new BexUnicodeOrder.Comparison() {
                    @Override
                    public int compare(
                            String left,
                            String right) {
                        BexGasWork.charge(
                                frame,
                                BexGasCounter.SORT_COMPARISON);
                        BexGasWork.charge(
                                frame,
                                BexGasCounter.COMPARISON_NODE_VISITED);
                        return BexGasWork.compareText(
                                frame, left, right);
                    }
                })) {
            BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_PRODUCED);
            BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
            out.put(key, retained.get(key));
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
        BexGasWork.MeteredText sep = BexGasWork.fullText(
                frame, separator.eval(frame));
        List<BexGasWork.MeteredText> values =
                new ArrayList<>();
        TextConstructionShape resultShape =
                new TextConstructionShape();
        for (int i = 0; i < list.size(); i++) {
            BexGasWork.charge(frame, BexGasCounter.COLLECTION_ITEM_VISITED);
            BexGasWork.charge(frame, BexGasCounter.LIST_ITEM_READ);
            if (i > 0) {
                resultShape.append(sep);
            }
            BexGasWork.MeteredText item =
                    BexGasWork.fullText(
                            frame,
                            list.get(String.valueOf(i)));
            resultShape.append(item);
            values.add(item);
        }
        BexGasWork.charge(frame, BexGasCounter.TEXT_BLOCK_CONSTRUCTED,
                BexGasWork.textBlocksForCodePoints(
                        resultShape.codePoints()));

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(sep.text());
            }
            out.append(values.get(i).text());
        }
        String result = out.toString();
        return BexValues.scalar(result);
    }
}

final class PointerJoinExpr extends Expr {
    private final List<CompiledExpression> segments;

    PointerJoinExpr(List<CompiledExpression> segments) {
        this.segments = ImmutableExpressionLists.copyOf(segments);
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (segments.isEmpty()) {
            BexGasWork.charge(frame, BexGasCounter.TEXT_BLOCK_CONSTRUCTED, 1L);
            return BexValues.scalar("/");
        }
        List<BexGasWork.MeteredText> items =
                new ArrayList<>(segments.size());
        long resultCodePoints = segments.size();
        for (CompiledExpression expression : segments) {
            BexValue value = expression.eval(frame);
            if (value.isUndefined() || value.isNull()) {
                throw new BexException("$pointerJoin segment cannot be null or undefined");
            }
            BexGasWork.MeteredText segment =
                    BexGasWork.fullText(frame, value);
            resultCodePoints = Math.addExact(
                    resultCodePoints,
                    Math.addExact(
                            segment.codePoints(),
                            segment.pointerEscapeExpansions()));
            items.add(segment);
        }
        BexGasWork.charge(frame, BexGasCounter.TEXT_BLOCK_CONSTRUCTED,
                BexGasWork.textBlocksForCodePoints(
                        resultCodePoints));

        StringBuilder out = new StringBuilder();
        for (BexGasWork.MeteredText segment : items) {
            out.append('/');
            appendEscaped(out, segment.text());
        }
        String result = out.toString();
        return BexValues.scalar(result);
    }

    private void appendEscaped(
            StringBuilder destination,
            String segment) {
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character == '~') {
                destination.append("~0");
            } else if (character == '/') {
                destination.append("~1");
            } else {
                destination.append(character);
            }
        }
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
        String input = BexGasWork.fullText(
                frame, text.eval(frame)).text();
        String sep = BexGasWork.fullText(
                frame, separator.eval(frame)).text();
        if (sep.isEmpty()) throw new BexException("$split separator must not be empty");
        int max = limit != null ? limit.eval(frame).asInteger().intValueExact() : -1;
        if (max == 0 || max < -1) throw new BexException("$split limit must be positive");

        List<BexValue> out = new ArrayList<>();
        int start = 0;
        int produced = 0;
        while (max == -1 || produced < max - 1) {
            int match = input.indexOf(sep, start);
            if (match < 0) {
                break;
            }
            addPart(frame, out, input, start, match);
            produced++;
            start = match + sep.length();
        }
        addPart(frame, out, input, start, input.length());
        return BexValues.list(out);
    }

    private void addPart(
            CompiledFrame frame,
            List<BexValue> out,
            String input,
            int start,
            int end) {
        BexGasWork.chargeSubstringConstruction(
                frame, input, start, end);
        BexGasWork.charge(
                frame,
                BexGasCounter.COLLECTION_ITEM_PRODUCED);
        BexGasWork.charge(
                frame,
                BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
        String part = input.substring(start, end);
        out.add(BexValues.scalar(part));
    }
}

enum BinaryTextOp { STARTS_WITH, SLICE_AFTER }

final class BinaryTextExpr extends Expr {
    private final List<CompiledExpression> expressions;
    private final BinaryTextOp op;

    BinaryTextExpr(List<CompiledExpression> expressions, BinaryTextOp op) {
        this.expressions = ImmutableExpressionLists.copyOf(expressions);
        this.op = op;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (expressions.size() != 2) throw new BexException("Text operator expects two operands");
        BexGasWork.PrefixResult result =
                BexGasWork.comparePrefix(
                        frame,
                        expressions.get(0).eval(frame),
                        expressions.get(1).eval(frame));
        if (op == BinaryTextOp.STARTS_WITH) {
            return BexValues.scalar(result.matched());
        }
        return BexValues.scalar(
                result.constructSuffix(frame));
    }
}

final class TextConstructionShape {
    private long codePoints;
    private boolean endsWithHighSurrogate;

    void append(BexGasWork.MeteredText value) {
        String text = value.text();
        if (endsWithHighSurrogate
                && !text.isEmpty()
                && Character.isLowSurrogate(
                text.charAt(0))) {
            codePoints--;
        }
        codePoints = Math.addExact(
                codePoints, value.codePoints());
        if (!text.isEmpty()) {
            endsWithHighSurrogate =
                    Character.isHighSurrogate(
                            text.charAt(
                                    text.length() - 1));
        }
    }

    long codePoints() {
        return codePoints;
    }
}
