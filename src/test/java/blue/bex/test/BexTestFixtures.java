package blue.bex.test;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BexTestFixtures {
    private BexTestFixtures() {
    }

    public static BexExecutionResult runExpr(Node expr) {
        return runStep(stepExpr(expr), defaultContext());
    }

    public static BexExecutionResult runStep(Node step, BexExecutionContext context) {
        return BexEngine.builder().build().compileAndExecute(BexProgramSource.inline(frozen(step)), context);
    }

    public static BexExecutionContext defaultContext() {
        return BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeSnapshot(obj("kind", "Created", "message", obj("request", obj("id", "r1")))))
                .currentContract(BexValues.nodeSnapshot(obj("channel", "main")))
                .steps(BexStepResults.builder()
                        .put("Build", BexValues.fromSimple(m("changeset", l(m("op", "replace", "path", "/status", "val", "ready")),
                                "events", l(m("kind", "Built")))))
                        .build())
                .gasLimit(1_000_000)
                .build();
    }

    public static FrozenBexDocumentView defaultDocumentView() {
        return new FrozenBexDocumentView(frozen(defaultDocument()), frozen(defaultDocument()), "/");
    }

    public static Node defaultDocument() {
        return new Node().name("Root").properties(props(
                "status", v("active"),
                "count", v(5),
                "state", obj("ready", false),
                "nested", obj("name", "node", "flag", true),
                "list", list("a", "b")
        ));
    }

    public static Node stepExpr(Node expr) {
        return obj("type", "Blue/BEX Program", "constants", obj("limit", 10), "expr", expr);
    }

    public static Node stepDo(Node statements) {
        return obj("type", "Blue/BEX Program", "do", statements);
    }

    public static FrozenNode frozen(Node node) {
        return FrozenNode.fromResolvedNode(node);
    }

    public static Node op(String name, Object body) {
        return obj(name, body);
    }

    public static Node emptyStatement() {
        return obj("$empty", true);
    }

    public static Node obj(Object... keysAndValues) {
        return new Node().properties(props(keysAndValues));
    }

    public static Map<String, Node> props(Object... keysAndValues) {
        Map<String, Node> props = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            props.put((String) keysAndValues[i], n(keysAndValues[i + 1]));
        }
        return props;
    }

    public static Node list(Object... items) {
        List<Node> nodes = new ArrayList<>();
        for (Object item : items) {
            nodes.add(n(item));
        }
        return new Node().items(nodes);
    }

    public static Node largeObject(int size) {
        Map<String, Node> props = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            props.put("k" + i, v(i));
        }
        return new Node().properties(props);
    }

    public static Node n(Object value) {
        if (value instanceof Node) {
            return (Node) value;
        }
        if (value instanceof Integer) {
            return new Node().value(((Integer) value).longValue());
        }
        if (value instanceof Long) {
            return new Node().value(((Long) value).longValue());
        }
        if (value instanceof Boolean || value instanceof String || value instanceof BigInteger) {
            return new Node().value(value);
        }
        if (value == null) {
            return new Node();
        }
        throw new IllegalArgumentException("Unsupported test value: " + value);
    }

    public static Node v(Object value) {
        return n(value);
    }

    public static Object simple(BexValue value) {
        return value.toSimple();
    }

    public static Map<String, Object> m(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    public static List<Object> l(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    public static BigInteger bi(long value) {
        return BigInteger.valueOf(value);
    }
}
