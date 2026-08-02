package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ObjectExpr extends Expr {
    private final Map<String, CompiledExpression> fields;

    ObjectExpr(Map<String, CompiledExpression> fields) {
        Map<String, CompiledExpression> sortedFields = new LinkedHashMap<>();
        for (String key : BexUnicodeOrder.sortedCopy(fields.keySet())) {
            sortedFields.put(key, fields.get(key));
        }
        this.fields = Collections.unmodifiableMap(sortedFields);
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        Map<String, BexValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledExpression> entry : fields.entrySet()) {
            BexValue value = entry.getValue().eval(frame);
            if (!value.isUndefined()) {
                BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
                out.put(entry.getKey(), value);
            }
        }
        return BexValues.map(out);
    }
}

final class ListExpr extends Expr {
    private final List<CompiledExpression> items;

    ListExpr(List<CompiledExpression> items) {
        this.items = ImmutableExpressionLists.copyOf(items);
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        List<BexValue> out = new ArrayList<>();
        for (CompiledExpression item : items) {
            BexValue value = item.eval(frame);
            if (value.isUndefined()) {
                throw new BexException(
                        "Undefined cannot appear in a Blue list");
            }
            BexGasWork.charge(frame, BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
            out.add(value);
        }
        return BexValues.list(out);
    }
}

final class IntrinsicExpr extends Expr {
    private final String blueId;
    private final BexValue type;
    private final Map<String, CompiledExpression> fields;

    IntrinsicExpr(String blueId, BexValue type, Map<String, CompiledExpression> fields) {
        this.blueId = blueId;
        this.type = type;
        Map<String, CompiledExpression> sortedFields = new LinkedHashMap<>();
        for (String key : BexUnicodeOrder.sortedCopy(fields.keySet())) {
            sortedFields.put(key, fields.get(key));
        }
        this.fields = Collections.unmodifiableMap(sortedFields);
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        Map<String, BexValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledExpression> entry : fields.entrySet()) {
            BexValue value = entry.getValue().eval(frame);
            if (!value.isUndefined()) {
                values.put(entry.getKey(), value);
            }
        }
        BexGasWork.charge(frame, BexGasCounter.INTRINSIC_CALLED);
        return frame.machine().invokeIntrinsic(blueId, type, values);
    }
}

final class FailExpr extends Expr {
    private final CompiledExpression message;

    FailExpr(CompiledExpression message) {
        this.message = message;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        throw new BexException(message.eval(frame).asText());
    }
}

final class NodeBlueIdExpr extends Expr {
    private final CompiledExpression expression;

    NodeBlueIdExpr(CompiledExpression expression) {
        this.expression = expression;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.machine().nodeBlueId(expression.eval(frame));
    }
}
