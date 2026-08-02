package blue.bex.benchmark;

import blue.bex.api.BexExecutionContext;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.gas.BexGasLedger;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared correctness and exact-gas checks used by the JMH corpus. */
public final class BexBenchmarkSupport {
    private static final FrozenNode EMPTY_DOCUMENT =
            FrozenNode.fromResolvedNode(new Node());

    private BexBenchmarkSupport() {
    }

    public static BexExecutionContext context() {
        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(EMPTY_DOCUMENT))
                .gasLimit(100_000_000L)
                .build();
    }

    public static BexExecutionContext context(
            String name, BexValue value) {
        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(EMPTY_DOCUMENT))
                .binding(name, value)
                .gasLimit(100_000_000L)
                .build();
    }

    public static ExpectedOutcome expected(
            BexExecutionResult result) {
        if (result == null || result.output() == null) {
            throw new IllegalStateException(
                    "benchmark scenario did not admit root output");
        }
        return new ExpectedOutcome(
                result.output().nodeBlueId(),
                result.gasLedger());
    }

    public static BexExecutionResult verify(
            BexExecutionResult result,
            ExpectedOutcome expected) {
        if (result == null || result.output() == null) {
            throw new IllegalStateException(
                    "benchmark execution did not admit root output");
        }
        if (!expected.outputBlueId.equals(
                result.output().nodeBlueId())) {
            throw new IllegalStateException(
                    "benchmark result identity changed: expected "
                            + expected.outputBlueId + " but was "
                            + result.output().nodeBlueId());
        }
        if (!expected.gasLedger.equals(result.gasLedger())) {
            throw new IllegalStateException(
                    "benchmark gas identity changed: expected "
                            + expected.gasLedger + " but was "
                            + result.gasLedger());
        }
        return result;
    }

    public static Node op(String name, Object body) {
        return obj(name, body);
    }

    public static Node obj(Object... keysAndValues) {
        return new Node().properties(props(keysAndValues));
    }

    public static Node list(Object... values) {
        List<Node> items = new ArrayList<Node>(values.length);
        for (Object value : values) {
            items.add(node(value));
        }
        return new Node().items(items);
    }

    public static Node integerList(int size) {
        List<Node> items = new ArrayList<Node>(size);
        for (int index = 0; index < size; index++) {
            items.add(new Node().value((long) index));
        }
        return new Node().items(items);
    }

    public static FrozenNode frozen(Node node) {
        return FrozenNode.fromResolvedNode(node);
    }

    public static Node node(Object value) {
        if (value instanceof Node) {
            return (Node) value;
        }
        if (value == null) {
            return new Node();
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return new Node().value(((Number) value).longValue());
        }
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return new Node().value(value);
        }
        throw new IllegalArgumentException(
                "unsupported benchmark node value "
                        + value.getClass().getName());
    }

    private static Map<String, Node> props(
            Object... keysAndValues) {
        if ((keysAndValues.length & 1) != 0) {
            throw new IllegalArgumentException(
                    "property keys and values must be paired");
        }
        LinkedHashMap<String, Node> properties =
                new LinkedHashMap<String, Node>();
        for (int index = 0;
             index < keysAndValues.length;
             index += 2) {
            properties.put(
                    Objects.requireNonNull(
                            (String) keysAndValues[index],
                            "property name"),
                    node(keysAndValues[index + 1]));
        }
        return properties;
    }

    /** Exact benchmark oracle: admitted result BlueId plus full named trace. */
    public static final class ExpectedOutcome {
        private final String outputBlueId;
        private final BexGasLedger gasLedger;

        private ExpectedOutcome(
                String outputBlueId,
                BexGasLedger gasLedger) {
            this.outputBlueId = Objects.requireNonNull(
                    outputBlueId, "outputBlueId");
            this.gasLedger = Objects.requireNonNull(
                    gasLedger, "gasLedger");
        }

        public String outputBlueId() {
            return outputBlueId;
        }

        public BexGasLedger gasLedger() {
            return gasLedger;
        }
    }
}
