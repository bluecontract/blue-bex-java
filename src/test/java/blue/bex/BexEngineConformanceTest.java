package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.gas.BexGasSchedule;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.value.BexValue;
import blue.bex.value.BexFrozenWriter;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BexEngineConformanceTest {

    @TestFactory
    Collection<DynamicTest> expressionOperators() {
        List<Case> cases = new ArrayList<>();
        cases.add(c("literal text", v("ok"), "ok"));
        cases.add(c("literal integer", v(7), BigInteger.valueOf(7)));
        cases.add(c("literal object", obj("a", 1, "b", "x"), m("a", bi(1), "b", "x")));
        cases.add(c("literal list", list("a", "b"), l("a", "b")));
        cases.add(c("literal escape", op("$literal", obj("$document", "x")), m("$document", "x")));
        cases.add(c("normal object with nested operator", obj("status", op("$document", "/status")), m("status", "active")));
        cases.add(c("multi field dollar object is literal", obj("$document", "not-op", "x", 1), m("$document", "not-op", "x", bi(1))));
        cases.add(c("document root", op("$document", "/"), docSimple()));
        cases.add(c("document literal path", op("$document", "/status"), "active"));
        cases.add(c("document dynamic path", op("$document", obj("path", op("$concat", list("/sta", "tus")))), "active"));
        cases.add(c("document resolved view", op("$document", obj("path", "/status", "view", "resolved")), "active"));
        cases.add(c("document missing", op("$document", "/missing"), null));
        cases.add(c("document metadata name", op("$document", "/name"), "Root"));
        cases.add(c("document value metadata", op("$document", "/status/value"), "active"));
        cases.add(c("event literal path", op("$event", "/kind"), "Created"));
        cases.add(c("event dynamic path", op("$event", obj("path", op("$concat", list("/ki", "nd")))), "Created"));
        cases.add(c("binding reads event short form", op("$binding", "event/kind"), "Created"));
        cases.add(c("binding reads custom binding", op("$binding", "policy/decision"), "allow"));
        cases.add(c("binding missing returns undefined", op("$binding", "missing/value"), null));
        cases.add(c("binding dynamic path", op("$binding", obj("name", "event", "path", op("$concat", list("/ki", "nd")))), "Created"));
        cases.add(c("binding dynamic name", op("$binding", obj("name", op("$concat", list("current", "Contract")), "path", "/channel")), "main"));
        cases.add(c("current contract", op("$currentContract", "/channel"), "main"));
        cases.add(c("steps object", op("$steps", obj("step", "Build", "path", "/changeset")), l(m("op", "replace", "path", "/status", "val", "ready"))));
        cases.add(c("steps short", op("$steps", "Build.changeset"), l(m("op", "replace", "path", "/status", "val", "ready"))));
        cases.add(c("binding reads steps object", op("$binding", "steps/Build/events/0/kind"), "Built"));
        cases.add(c("const", op("$const", "limit"), BigInteger.valueOf(10)));
        cases.add(c("get field", op("$get", obj("object", obj("a", "b"), "key", "a")), "b"));
        cases.add(c("unwrap scalar wrapper", op("$unwrap", obj("value", obj("value", "x"))), "x"));
        cases.add(c("text on integer", op("$text", 12), "12"));
        cases.add(c("text on undefined", op("$text", op("$document", "/none")), ""));
        cases.add(c("integer on integer text", op("$integer", "12345678901234567890"), new BigInteger("12345678901234567890")));
        cases.add(c("boolean true text", op("$boolean", "true"), true));
        cases.add(c("boolean false text", op("$boolean", "false"), false));
        cases.add(c("object undefined", op("$object", op("$document", "/none")), m()));
        cases.add(c("list undefined", op("$list", op("$document", "/none")), l()));
        cases.add(c("concat", op("$concat", list("a", op("$document", "/status"))), "aactive"));
        cases.add(c("join", op("$join", obj("list", list("a", "b"), "separator", ":")), "a:b"));
        cases.add(c("split basic", op("$split", obj("text", "a:b:c", "separator", ":")), l("a", "b", "c")));
        cases.add(c("split empty segment", op("$split", obj("text", "a::c", "separator", ":")), l("a", "", "c")));
        cases.add(c("split trailing empty", op("$split", obj("text", "a:", "separator", ":")), l("a", "")));
        cases.add(c("split limit", op("$split", obj("text", "a:b:c", "separator", ":", "limit", 2)), l("a", "b:c")));
        cases.add(c("startsWith true", op("$startsWith", list("abc", "ab")), true));
        cases.add(c("startsWith false", op("$startsWith", list("abc", "bc")), false));
        cases.add(c("sliceAfter success", op("$sliceAfter", list("prefix:value", "prefix:")), "value"));
        cases.add(c("sliceAfter miss", op("$sliceAfter", list("prefix:value", "other:")), ""));
        cases.add(c("eq scalar", op("$eq", list("a", "a")), true));
        cases.add(c("ne scalar", op("$ne", list("a", "b")), true));
        cases.add(c("deep eq object", op("$eq", list(obj("a", list(1, 2)), obj("a", list(1, 2)))), true));
        cases.add(c("deep eq list", op("$eq", list(list(1, 2), list(1, 2))), true));
        cases.add(c("gt", op("$gt", list(3, 2)), true));
        cases.add(c("gte equal", op("$gte", list(2, 2)), true));
        cases.add(c("lt", op("$lt", list(1, 2)), true));
        cases.add(c("lte equal", op("$lte", list(2, 2)), true));
        cases.add(c("and short false", op("$and", list(false, op("$divide", list(1, 0)))), false));
        cases.add(c("or short true", op("$or", list(true, op("$divide", list(1, 0)))), true));
        cases.add(c("not", op("$not", true), false));
        cases.add(c("truthy nonempty", op("$truthy", "x"), true));
        cases.add(c("empty text", op("$empty", ""), true));
        cases.add(c("coalesce", op("$coalesce", list(op("$document", "/missing"), "", "x")), "x"));
        cases.add(c("add", op("$add", list(1, 2, 3)), BigInteger.valueOf(6)));
        cases.add(c("subtract", op("$subtract", list(10, 3)), BigInteger.valueOf(7)));
        cases.add(c("multiply", op("$multiply", list(6, 7)), BigInteger.valueOf(42)));
        cases.add(c("divide", op("$divide", list(84, 2)), BigInteger.valueOf(42)));
        cases.add(c("big integer add", op("$add", list("9007199254740993", 1)), new BigInteger("9007199254740994")));
        cases.add(c("keys sorted", op("$keys", obj("b", 2, "a", 1)), l("a", "b")));
        cases.add(c("entries sorted", op("$entries", obj("b", 2, "a", 1)), l(m("key", "a", "val", bi(1)), m("key", "b", "val", bi(2)))));
        cases.add(c("size list", op("$size", list(1, 2, 3)), BigInteger.valueOf(3)));
        cases.add(c("size object", op("$size", obj("a", 1, "b", 2)), BigInteger.valueOf(2)));
        cases.add(c("listGet hit", op("$listGet", obj("list", list("a", "b"), "index", 1)), "b"));
        cases.add(c("listGet default", op("$listGet", obj("list", list("a"), "index", 4, "default", "x")), "x"));
        cases.add(c("listGet undefined", op("$listGet", obj("list", list("a"), "index", 4)), null));
        cases.add(c("listConcat", op("$listConcat", list(list("a"), list("b", "c"))), l("a", "b", "c")));
        cases.add(c("merge right wins", op("$merge", list(obj("a", 1, "b", 2), obj("b", 3))), m("a", bi(1), "b", bi(3))));
        cases.add(c("objectSet dynamic", op("$objectSet", obj("object", obj("a", 1), "key", op("$concat", list("b")), "val", true)), m("a", bi(1), "b", true)));
        cases.add(c("pointerGet object", op("$pointerGet", obj("object", obj("a", obj("b", "c")), "path", "/a/b")), "c"));
        cases.add(c("pointerGet list", op("$pointerGet", obj("object", list("a", "b"), "path", "/1")), "b"));
        cases.add(c("pointerGet default", op("$pointerGet", obj("object", obj("a", 1), "path", "/b", "default", "x")), "x"));
        cases.add(c("pointerSet nested", op("$pointerSet", obj("object", obj("a", obj("b", 1)), "path", "/a/c", "val", 2)), m("a", m("b", bi(1), "c", bi(2)))));
        cases.add(c("pointerSet create", op("$pointerSet", obj("object", op("$object", op("$document", "/none")), "path", "/a/b", "val", "x")), m("a", m("b", "x"))));
        cases.add(c("pointerSet remove", op("$pointerSet", obj("object", obj("a", 1, "b", 2), "op", "remove", "path", "/a")), m("b", bi(2))));
        cases.add(c("choose then", op("$choose", obj("cond", true, "then", "a", "else", "b")), "a"));
        cases.add(c("choose else", op("$choose", obj("cond", false, "then", "a", "else", "b")), "b"));
        cases.add(c("resultValue document fallback", op("$resultValue", "/status"), "active"));
        cases.add(c("document relative path", op("$document", "status"), "active"));
        cases.add(c("event nested path", op("$event", "/message/request/id"), "r1"));
        cases.add(c("current contract missing path", op("$currentContract", "/missing"), null));
        cases.add(c("get missing field", op("$get", obj("object", obj("a", "b"), "key", "z")), null));
        cases.add(c("text on boolean", op("$text", true), "true"));
        cases.add(c("boolean on boolean", op("$boolean", true), true));
        cases.add(c("truthy zero integer", op("$truthy", 0), true));
        cases.add(c("empty missing document", op("$empty", op("$document", "/none")), true));
        cases.add(c("coalesce skips empty string", op("$coalesce", list("", "fallback")), "fallback"));
        cases.add(c("add negative integers", op("$add", list(-3, 2)), BigInteger.valueOf(-1)));
        cases.add(c("subtract multiple integers", op("$subtract", list(10, 3, 2)), BigInteger.valueOf(5)));
        cases.add(c("multiply multiple integers", op("$multiply", list(2, 3, 4)), BigInteger.valueOf(24)));
        cases.add(c("divide exact chain", op("$divide", list(100, 5, 2)), BigInteger.valueOf(10)));
        cases.add(c("keys empty object", op("$keys", obj()), l()));
        cases.add(c("entries empty object", op("$entries", obj()), l()));
        cases.add(c("size scalar", op("$size", "abc"), BigInteger.valueOf(1)));
        cases.add(c("listGet numeric text index", op("$listGet", obj("list", list("a", "b"), "index", "1")), "b"));
        cases.add(c("listConcat with empty list", op("$listConcat", list(list(), list("x"))), l("x")));
        cases.add(c("merge ignores undefined object source", op("$merge", list(obj("a", 1), op("$object", op("$document", "/none")))), m("a", bi(1))));
        cases.add(c("objectSet replaces key without mutating result shape", op("$objectSet", obj("object", obj("a", 1), "key", "a", "val", 2)), m("a", bi(2))));
        cases.add(c("pointerGet root", op("$pointerGet", obj("object", obj("a", 1), "path", "/")), m("a", bi(1))));
        cases.add(c("pointerSet replaces root", op("$pointerSet", obj("object", obj("a", 1), "path", "/", "val", obj("b", 2))), m("b", bi(2))));
        cases.add(c("pointerSet list item", op("$pointerSet", obj("object", list("a", "b"), "path", "/1", "val", "z")), l("a", "z")));
        cases.add(c("split no separator present", op("$split", obj("text", "abc", "separator", ":")), l("abc")));
        cases.add(c("startsWith empty prefix", op("$startsWith", list("abc", "")), true));
        cases.add(c("sliceAfter empty prefix", op("$sliceAfter", list("abc", "")), "abc"));
        cases.add(c("deep ne object", op("$ne", list(obj("a", list(1, 2)), obj("a", list(1, 3)))), true));
        cases.add(c("deep ne list length", op("$ne", list(list(1, 2), list(1, 2, 3))), true));
        cases.add(c("gte greater", op("$gte", list(3, 2)), true));
        cases.add(c("lte lesser", op("$lte", list(1, 2)), true));
        cases.add(c("and all true", op("$and", list(true, "x", 1)), true));
        cases.add(c("or all false", op("$or", list(false, "", op("$document", "/missing"))), false));

        List<DynamicTest> tests = new ArrayList<>();
        for (final Case testCase : cases) {
            tests.add(DynamicTest.dynamicTest(testCase.name, () -> assertEquals(testCase.expected, simple(runExpr(testCase.expr).value()))));
        }
        return tests;
    }

    @Test
    void statementsAppendResultsAndPreservePatchOrder() {
        Node step = stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/status", "val", "ready")),
                op("$appendChange", obj("op", "replace", "path", "/status", "val", "done")),
                op("$appendEvent", obj("kind", "Calculated")),
                op("$return", obj())
        ));

        BexExecutionResult result = runStep(step, defaultContext());

        assertEquals(2, result.changeset().entries().size());
        assertEquals("ready", simple(result.changeset().entries().get(0).val()));
        assertEquals("done", simple(result.changeset().entries().get(1).val()));
        assertEquals(l(m("kind", "Calculated")), simple(result.events().asValue()));
        assertEquals(m("changeset", l(
                m("op", "replace", "path", "/status", "val", "ready"),
                m("op", "replace", "path", "/status", "val", "done")),
                "events", l(m("kind", "Calculated"))), simple(result.value()));
    }

    @Test
    void resultValueUsesLatestPatchAndAncestorOverlay() {
        Node step = stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/state", "val", obj("ready", false, "nested", obj("x", 1)))),
                op("$appendChange", obj("op", "replace", "path", "/state/ready", "val", true)),
                op("$return", obj("ready", op("$resultValue", "/state/ready"), "x", op("$resultValue", "/state/nested/x")))
        ));

        BexExecutionResult result = runStep(step, defaultContext());

        assertEquals(m("ready", true, "x", bi(1)), simple(result.value()));
        assertEquals(1, result.metrics().resultOverlayExactHits());
        assertEquals(1, result.metrics().resultOverlayAncestorHits());
    }

    @Test
    void functionsUseSlotFramesAndDoNotLeakVariables() {
        Node function = obj(
                "args", obj("x", obj()),
                "do", list(
                        op("$let", obj("name", "y", "expr", op("$add", list(op("$var", "x"), 1)))),
                        op("$return", op("$var", "y"))
                )
        );
        Node step = obj(
                "type", "Blue/BEX Program",
                "functions", obj("inc", function),
                "do", list(
                        op("$let", obj("name", "x", "expr", 10)),
                        op("$return", op("$call", obj("function", "inc", "args", obj("x", 2))))
                )
        );

        assertEquals(BigInteger.valueOf(3), simple(runStep(step, defaultContext()).value()));
    }

    @Test
    void functionArgsUseSlotPassingWithoutMapAllocation() {
        Node sum = obj(
                "args", obj("a", obj(), "b", obj(), "c", obj()),
                "expr", op("$add", list(op("$var", "a"), op("$var", "b"), op("$var", "c")))
        );
        Node missing = obj(
                "args", obj("a", obj(), "b", obj()),
                "expr", op("$coalesce", list(op("$var", "b"), "missing"))
        );
        Node step = obj(
                "type", "Blue/BEX Program",
                "functions", obj("sum", sum, "missing", missing),
                "do", list(
                        op("$let", obj("name", "first", "expr", op("$call", obj("function", "sum", "args", obj("a", 1, "b", 2, "c", 3))))),
                        op("$let", obj("name", "second", "expr", op("$call", obj("function", "missing", "args", obj("a", "set", "b", op("$document", "/missing")))))),
                        op("$return", obj("first", op("$var", "first"), "second", op("$var", "second")))
                )
        );

        BexExecutionResult result = runStep(step, defaultContext());

        assertEquals(m("first", bi(6), "second", "missing"), simple(result.value()));
        assertEquals(0, result.metrics().functionArgMapAllocations());
    }

    @Test
    void nodeBexValueReadsEventCursorWithoutFreezingOrMaterializing() {
        Node event = obj("payload", obj("status", "before"), "kind", "Created");
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeCursorTrustedImmutable(event))
                .gasLimit(1_000_000)
                .build();

        BexExecutionResult result = runStep(stepExpr(op("$event", "/payload/status")), context);

        assertEquals("before", simple(result.value()));
        assertEquals(1, result.metrics().eventReads());
        assertEquals(0, result.metrics().nodeMaterializations());
    }

    @Test
    void nodeSnapshotFreezesBoundaryValueForDeterministicContexts() {
        Node event = obj("payload", obj("status", "before"), "kind", "Created");
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeSnapshot(event))
                .gasLimit(1_000_000)
                .build();
        event.getProperties().put("payload", obj("status", "after"));

        BexExecutionResult result = runStep(stepExpr(op("$event", "/payload/status")), context);

        assertEquals("before", simple(result.value()));
        assertEquals(1, result.metrics().eventReads());
        assertEquals(0, result.metrics().nodeMaterializations());
    }

    @Test
    void nodeBexValueMetadataReadsWork() {
        Node type = new Node().value("EventType");
        Node event = new Node()
                .name("EventRoot")
                .description("metadata")
                .blueId("event-blue-id")
                .type(type)
                .value("payload-value")
                .properties(props("kind", "Created"));
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeCursor(event))
                .gasLimit(1_000_000)
                .build();
        Node step = stepExpr(obj(
                "name", op("$event", "/name"),
                "description", op("$event", "/description"),
                "blueId", op("$event", "/blueId"),
                "value", op("$event", "/value"),
                "type", op("$event", "/type/value")
        ));

        assertEquals(m("blueId", "event-blue-id", "description", "metadata", "name", "EventRoot", "type", "EventType", "value", "payload-value"),
                simple(runStep(step, context).value()));
    }

    @Test
    void recursionRejectedAtCompileTime() {
        Node step = obj(
                "type", "Blue/BEX Program",
                "functions", obj(
                        "a", obj("do", list(op("$return", op("$call", obj("function", "b"))))),
                        "b", obj("do", list(op("$return", op("$call", obj("function", "a")))))
                ),
                "entry", "a"
        );

        assertThrows(BexException.class, () -> BexEngine.builder().build().compile(BexProgramSource.inline(frozen(step))));
    }

    @Test
    void directRecursionRejectedAtCompileTime() {
        Node step = obj(
                "type", "Blue/BEX Program",
                "functions", obj("loop", obj("do", list(op("$return", op("$call", obj("function", "loop")))))),
                "entry", "loop"
        );

        assertThrows(BexException.class, () -> BexEngine.builder().build().compile(BexProgramSource.inline(frozen(step))));
    }

    @Test
    void literalPayloadDoesNotParticipateInRecursionDetectionOrExecution() {
        Node literalCall = op("$literal", op("$call", obj("function", "loop")));
        Node step = obj(
                "type", "Blue/BEX Program",
                "functions", obj("loop", obj("do", list(op("$return", literalCall)))),
                "entry", "loop"
        );

        BexExecutionResult result = BexEngine.builder().build()
                .compileAndExecute(BexProgramSource.inline(frozen(step)), defaultContext());

        assertEquals(m("$call", m("function", "loop")), simple(result.value()));
        assertEquals(2, result.metrics().functionCalls());
        assertEquals(0, result.metrics().interpretedFallbacks());
    }

    @Test
    void literalPayloadMayContainUnknownOperatorWithoutCompilation() {
        BexExecutionResult result = runExpr(op("$literal", op("$unknown", true)));

        assertEquals(m("$unknown", true), simple(result.value()));
        assertEquals(0, result.metrics().interpretedFallbacks());
    }

    @Test
    void cacheCompilesSelectedStepOnce() {
        LruBexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        BexEngine engine = BexEngine.builder().cache(cache).build();
        BexProgramSource source = BexProgramSource.inline(frozen(stepExpr(op("$document", "/status"))));

        BexCompiledProgram first = engine.compile(source);
        BexCompiledProgram second = engine.compile(source);

        assertSame(first, second);
    }

    @Test
    void compileAndExecuteResultIncludesCompileCacheMetrics() {
        LruBexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        BexEngine engine = BexEngine.builder().cache(cache).build();
        BexProgramSource source = BexProgramSource.inline(frozen(stepExpr(op("$document", "/status"))));

        BexExecutionResult first = engine.compileAndExecute(source, defaultContext());
        BexExecutionResult second = engine.compileAndExecute(source, defaultContext());

        assertEquals("active", simple(first.value()));
        assertEquals("active", simple(second.value()));
        assertEquals(1, first.metrics().compileCacheMisses());
        assertEquals(0, first.metrics().compileCacheHits());
        assertEquals(0, second.metrics().compileCacheMisses());
        assertEquals(1, second.metrics().compileCacheHits());
        assertEquals(1, first.metrics().compiledExecutions());
        assertEquals(1, second.metrics().compiledExecutions());
    }

    @Test
    void gasIsDeterministicAndExhaustionFails() {
        Node expr = op("$add", list(1, 2, 3));
        BexExecutionResult a = runExpr(expr);
        BexExecutionResult b = runExpr(expr);

        assertEquals(a.gasUsed(), b.gasUsed());

        BexEngine engine = BexEngine.builder()
                .gasSchedule(BexGasSchedule.builder().expressionBase(100).build())
                .build();
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .gasLimit(1)
                .build();
        assertThrows(BexException.class, () -> engine.compileAndExecute(BexProgramSource.inline(frozen(stepExpr(expr))), context));
    }

    @Test
    void gasSizeEstimatorCachesFrozenValues() {
        Node large = largeObject(300);
        Node step = obj(
                "type", "Blue/BEX Program",
                "constants", obj("large", large),
                "do", list(
                        op("$appendEvent", op("$const", "large")),
                        op("$appendEvent", op("$const", "large")),
                        op("$return", obj())
                )
        );

        BexExecutionResult result = runStep(step, defaultContext());

        assertTrue(result.metrics().sizeEstimateCalls() >= 2);
        assertTrue(result.metrics().sizeEstimateCacheMisses() > 0);
        assertTrue(result.metrics().sizeEstimateCacheHits() > 0);
    }

    @Test
    void frozenWriterTracksChildNodeRoundTripsSeparatelyFromGenericFallback() {
        BexMetrics metrics = new BexMetrics();
        BexValue computed = BexValues.pointerSet(
                BexValues.overlay(BexValues.fromSimple(m("a", bi(1))), "b", BexValues.list(Arrays.asList(BexValues.scalar("x")))),
                Arrays.asList("c", "d"),
                BexValues.scalar(true),
                "set"
        );

        FrozenNode frozen = BexFrozenWriter.toFrozen(computed, metrics);

        assertEquals(m("a", bi(1), "b", l("x"), "c", m("d", true)), simple(BexValues.frozen(frozen)));
        assertEquals(1, metrics.frozenOutputConversions());
        assertEquals(0, metrics.frozenWriterNodeFallbacks());
        assertTrue(metrics.frozenWriterChildNodeRoundTrips() > 0);
    }

    @Test
    void undefinedCannotBeEmittedAsEventOrPatchValue() {
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendEvent", op("$document", "/missing"))
        )), defaultContext()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/status", "val", op("$document", "/missing")))
        )), defaultContext()));
    }

    @Test
    void materializationCountersStayLowForFrozenReadsAndOverlays() {
        Node huge = obj("status", "active", "data", largeObject(1500));
        BexExecutionContext context = BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(frozen(huge)))
                .build();
        Node step = stepDo(list(
                op("$let", obj("name", "status", "expr", op("$document", "/status"))),
                op("$let", obj("name", "copy", "expr", op("$objectSet", obj("object", op("$document", "/data"), "key", "extra", "val", true)))),
                op("$appendChange", obj("op", "replace", "path", "/status", "val", op("$var", "status"))),
                op("$return", obj("status", op("$resultValue", "/status")))
        ));

        BexExecutionResult result = runStep(step, context);

        assertEquals(m("status", "active"), simple(result.value()));
        assertEquals(2, result.metrics().frozenDocumentReads());
        assertEquals(0, result.metrics().nodeMaterializations());
        assertEquals(0, result.metrics().simpleMaterializations());
    }

    @Test
    void invalidProgramsFailClosed() {
        assertThrows(BexException.class, () -> runExpr(op("$unknown", true)));
        assertThrows(BexException.class, () -> runStep(stepDo(list(obj("$let", obj("name", "x", "expr", 1), "$set", obj("name", "x", "expr", 2)))), defaultContext()));
        assertThrows(BexException.class, () -> runExpr(op("$split", obj("text", "abc", "separator", ""))));
        assertThrows(BexException.class, () -> runExpr(op("$integer", "not-int")));
        assertThrows(BexException.class, () -> runExpr(op("$pointerSet", obj("object", obj("a", 1), "path", "/a/b", "val", 2))));
    }

    private static BexExecutionResult runExpr(Node expr) {
        return runStep(stepExpr(expr), defaultContext());
    }

    private static BexExecutionResult runStep(Node step, BexExecutionContext context) {
        BexEngine engine = BexEngine.builder().build();
        return engine.compileAndExecute(BexProgramSource.inline(frozen(step)), context);
    }

    private static BexExecutionContext defaultContext() {
        return BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeSnapshot(obj("kind", "Created", "message", obj("request", obj("id", "r1")))))
                .currentContract(BexValues.nodeSnapshot(obj("channel", "main")))
                .steps(BexStepResults.builder()
                        .put("Build", BexValues.fromSimple(m("changeset", l(m("op", "replace", "path", "/status", "val", "ready")),
                                "events", l(m("kind", "Built")))))
                        .build())
                .binding("policy", BexValues.fromSimple(m("decision", "allow")))
                .gasLimit(1_000_000)
                .build();
    }

    private static FrozenBexDocumentView defaultDocumentView() {
        return new FrozenBexDocumentView(frozen(defaultDocument()), frozen(defaultDocument()), "/");
    }

    private static Node defaultDocument() {
        return new Node().name("Root").properties(props(
                "status", v("active"),
                "count", v(5),
                "state", obj("ready", false),
                "nested", obj("name", "node", "flag", true),
                "list", list("a", "b")
        ));
    }

    private static Node largeObject(int size) {
        Map<String, Node> props = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            props.put("k" + i, v(i));
        }
        return new Node().properties(props);
    }

    private static Node statementExpr(Node doList, Node expr) {
        return stepDo(list(op("$return", expr)));
    }

    private static Node stepExpr(Node expr) {
        return obj("type", "Blue/BEX Program", "constants", obj("limit", 10), "expr", expr);
    }

    private static Node stepDo(Node statements) {
        return obj("type", "Blue/BEX Program", "do", statements);
    }

    private static FrozenNode frozen(Node node) {
        return FrozenNode.fromResolvedNode(node);
    }

    private static Node op(String name, Object body) {
        return obj(name, body);
    }

    private static Node obj(Object... keysAndValues) {
        return new Node().properties(props(keysAndValues));
    }

    private static Map<String, Node> props(Object... keysAndValues) {
        Map<String, Node> props = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            props.put((String) keysAndValues[i], n(keysAndValues[i + 1]));
        }
        return props;
    }

    private static Node list(Object... items) {
        List<Node> nodes = new ArrayList<>();
        for (Object item : items) {
            nodes.add(n(item));
        }
        return new Node().items(nodes);
    }

    private static Node n(Object value) {
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

    private static Node v(Object value) {
        return n(value);
    }

    private static Object simple(BexValue value) {
        return value.toSimple();
    }

    private static Map<String, Object> docSimple() {
        return m("count", bi(5), "list", l("a", "b"), "nested", m("flag", true, "name", "node"), "state", m("ready", false), "status", "active");
    }

    private static Map<String, Object> m(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static List<Object> l(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static BigInteger bi(long value) {
        return BigInteger.valueOf(value);
    }

    private static Case c(String name, Node expr, Object expected) {
        return new Case(name, expr, expected);
    }

    private static final class Case {
        private final String name;
        private final Node expr;
        private final Object expected;

        private Case(String name, Node expr, Object expected) {
            this.name = name;
            this.expr = expr;
            this.expected = expected;
        }
    }
}
