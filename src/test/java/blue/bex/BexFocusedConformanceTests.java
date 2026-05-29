package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.gas.BexGasSchedule;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexLiteralAndOperatorSyntaxTest {
    @Test
    void unknownSingleOperatorFailsButLiteralPreservesNestedCall() {
        assertThrows(BexException.class, () -> runExpr(op("$unknown", true)));
        assertEquals(m("$call", m("function", "missing")), simple(runExpr(op("$literal", op("$call", obj("function", "missing")))).value()));
    }

    @Test
    void statementWithTwoOperatorsFailsAndMixedDollarObjectIsLiteral() {
        assertThrows(BexException.class, () -> runStep(stepDo(list(obj("$let", obj("name", "x", "expr", 1), "$set", obj("name", "x", "expr", 2)))), defaultContext()));
        assertEquals(m("$document", "not-op", "x", BigInteger.ONE), simple(runExpr(obj("$document", "not-op", "x", 1)).value()));
    }
}

class BexTypeConversionTest {
    @Test
    void integerConversionIsExactAndDecimalIntegerFails() {
        assertEquals(new BigInteger("12345678901234567890"), simple(runExpr(op("$integer", "12345678901234567890")).value()));
        assertThrows(BexException.class, () -> runExpr(op("$integer", "1.5")));
    }

    @Test
    void numberConversionReturnsExactDecimalValue() {
        assertEquals("1.5", simple(runExpr(op("$text", op("$number", "1.5"))).value()));
    }
}

class BexStringOperatorTest {
    @Test
    void splitLimitKeepsRemainderInLastSlot() {
        assertEquals(l("a", "b:c"), simple(runExpr(op("$split", obj("text", "a:b:c", "separator", ":", "limit", 2))).value()));
    }
}

class BexLogicOperatorTest {
    @Test
    void zeroIsTruthyAndEmptyObjectIsFalsy() {
        assertEquals(true, simple(runExpr(op("$truthy", 0)).value()));
        assertEquals(false, simple(runExpr(op("$truthy", obj())).value()));
    }
}

class BexNumericOperatorTest {
    @Test
    void largeIntegerArithmeticWorksAndNonExactDivisionFails() {
        assertEquals(new BigInteger("9007199254740994"), simple(runExpr(op("$add", list("9007199254740993", 1))).value()));
        assertThrows(BexException.class, () -> runExpr(op("$divide", list(5, 2))));
    }
}

class BexObjectListOperatorTest {
    @Test
    void objectSetUndefinedOmitRemovesExistingKeyAndScalarBaseFails() {
        assertEquals(m("b", BigInteger.valueOf(2)), simple(runExpr(op("$objectSet", obj("object", obj("a", 1, "b", 2), "key", "a", "val", op("$document", "/missing")))).value()));
        assertThrows(BexException.class, () -> runExpr(op("$objectSet", obj("object", "scalar", "key", "a", "val", 1))));
    }
}

class BexPointerOperatorTest {
    @Test
    void pointerSetCreatesIntermediateObjectsAndRejectsScalarIntermediates() {
        assertEquals(m("a", m("b", "x")), simple(runExpr(op("$pointerSet", obj("object", op("$object", op("$document", "/missing")), "path", "/a/b", "val", "x"))).value()));
        assertThrows(BexException.class, () -> runExpr(op("$pointerSet", obj("object", obj("a", 1), "path", "/a/b", "val", 2))));
    }
}

class BexStatementTest {
    @Test
    void appendChangesPreservesDuplicatePathOrder() {
        BexExecutionResult result = runStep(stepDo(list(
                op("$appendChanges", list(
                        obj("op", "replace", "path", "/status", "val", "first"),
                        obj("op", "replace", "path", "/status", "val", "second")
                )),
                emptyStatement()
        )), defaultContext());

        assertEquals("first", simple(result.changeset().entries().get(0).val()));
        assertEquals("second", simple(result.changeset().entries().get(1).val()));
    }

    @Test
    void emptyPlaceholderStatementReturnsDefaultResult() {
        BexExecutionResult result = runStep(stepDo(list(
                op("$appendEvent", obj("kind", "Calculated")),
                emptyStatement()
        )), defaultContext());

        assertEquals(l(m("kind", "Calculated")), simple(result.events().asValue()));
        assertEquals(m("changeset", l(), "events", l(m("kind", "Calculated"))), simple(result.value()));
    }

    @Test
    void invalidEmptyPlaceholderStatementFails() {
        assertThrows(RuntimeException.class, () -> runStep(stepDo(list(obj("$empty", false))), defaultContext()));
    }
}

class BexErgonomicsCoreTest {
    @Test
    void guardsAreLazyAndReturnFromRoot() {
        BexExecutionResult result = runStep(stepDo(list(
                op("$returnIf", obj("cond", false, "expr", op("$integer", "not-an-integer"))),
                op("$failIf", obj("cond", false, "message", op("$integer", "not-an-integer"))),
                op("$returnIf", obj("cond", true, "expr", "done")),
                op("$fail", "unreachable")
        )), defaultContext());

        assertEquals("done", simple(result.value()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$failIf", obj("cond", true, "message", "boom"))
        )), defaultContext()));
    }

    @Test
    void kindAndIsKindUseBexRuntimeKinds() {
        BexExecutionResult result = runExpr(obj(
                "missing", op("$kind", op("$document", "/missing")),
                "nil", op("$kind", op("$null", true)),
                "text", op("$kind", "hello"),
                "integer", op("$kind", 7),
                "double", op("$kind", op("$number", "1.5")),
                "boolean", op("$kind", true),
                "object", op("$kind", obj("a", 1)),
                "list", op("$kind", list(1)),
                "numeric", op("$isKind", obj("val", 7, "kind", list("integer", "double"))),
                "notText", op("$isKind", obj("val", 7, "kind", "text"))
        ));

        assertEquals(m(
                "boolean", "boolean",
                "double", "double",
                "integer", "integer",
                "list", "list",
                "missing", "undefined",
                "nil", "null",
                "notText", false,
                "numeric", true,
                "object", "object",
                "text", "text"
        ), simple(result.value()));
    }

    @Test
    void pathAwareVarAndConstReadByJsonPointerWithoutNameShorthand() {
        BexExecutionResult result = runStep(obj(
                "type", "Blue/BEX Program",
                "constants", obj("Policy/minimum", obj("amount", 100)),
                "do", list(
                        op("$let", obj("name", "request/summary", "expr", "legacy-name")),
                        op("$let", obj("name", "request", "expr", obj("summary", "new-path"))),
                        op("$return", obj(
                                "legacy", op("$var", "request/summary"),
                                "path", op("$var", obj("name", "request", "path", "/summary")),
                                "const", op("$const", obj("name", "Policy/minimum", "path", "/amount"))
                        ))
                )
        ), defaultContext());

        assertEquals(m("const", bi(100), "legacy", "legacy-name", "path", "new-path"), simple(result.value()));
    }

    @Test
    void multiLetUnorderedIsParallelAndOrderedIsSequential() {
        BexExecutionResult parallel = runStep(stepDo(list(
                op("$let", obj("name", "a", "expr", "old")),
                op("$let", obj("vars", obj(
                        "a", "new",
                        "b", op("$var", "a")
                ))),
                op("$return", obj("a", op("$var", "a"), "b", op("$var", "b")))
        )), defaultContext());
        assertEquals(m("a", "new", "b", "old"), simple(parallel.value()));

        BexExecutionResult sequential = runStep(stepDo(list(
                op("$let", obj("name", "a", "expr", "old")),
                op("$let", obj("order", list("a", "b"), "vars", obj(
                        "a", "new",
                        "b", op("$var", "a")
                ))),
                op("$return", obj("a", op("$var", "a"), "b", op("$var", "b")))
        )), defaultContext());
        assertEquals(m("a", "new", "b", "new"), simple(sequential.value()));

        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$let", obj("vars", obj("a", 1, "b", op("$var", "a"))))
        )), defaultContext()));
    }

    @Test
    void collectionQueriesCoverProjectionSelectionFanoutAndAggregation() {
        BexExecutionResult result = runExpr(obj(
                "mapped", op("$map", obj(
                        "in", list(1, 2, 3),
                        "item", "x",
                        "expr", op("$multiply", list(op("$var", "x"), 2))
                )),
                "filtered", op("$filter", obj(
                        "in", list(1, 2, 3, 4),
                        "item", "x",
                        "where", op("$gt", list(op("$var", "x"), 2))
                )),
                "flat", op("$flatMap", obj(
                        "in", list(1, 2),
                        "item", "x",
                        "expr", list(op("$var", "x"), op("$add", list(op("$var", "x"), 10)))
                )),
                "sum", op("$reduce", obj(
                        "in", list(1, 2, 3),
                        "acc", "total",
                        "init", 0,
                        "item", "x",
                        "expr", op("$add", list(op("$var", "total"), op("$var", "x")))
                )),
                "some", op("$some", obj(
                        "in", list(1, 2, 3),
                        "item", "x",
                        "where", op("$eq", list(op("$var", "x"), 2))
                )),
                "found", op("$find", obj(
                        "in", list("a", "bb", "ccc"),
                        "item", "x",
                        "where", op("$eq", list(op("$var", "x"), "bb"))
                )),
                "entry", op("$findEntry", obj(
                        "in", obj("b", 2, "a", 1),
                        "item", "v",
                        "key", "k",
                        "index", "i",
                        "where", op("$gt", list(op("$var", "v"), 1))
                )),
                "objectFiltered", op("$filter", obj(
                        "in", obj("b", 2, "a", 1, "c", 3),
                        "item", "v",
                        "key", "k",
                        "where", op("$ne", list(op("$var", "k"), "b"))
                )),
                "includes", op("$includes", obj("list", list("add", "replace", "remove"), "val", "replace")),
                "hasKey", op("$hasKey", obj("object", obj("a", 1), "key", "a")),
                "builtObject", op("$objectFromEntries", op("$map", obj(
                        "in", op("$entries", obj("b", 2, "a", 1)),
                        "item", "entry",
                        "expr", obj(
                                "key", op("$var", obj("name", "entry", "path", "/key")),
                                "val", op("$multiply", list(op("$var", obj("name", "entry", "path", "/val")), 10))
                        )
                )))
        ));

        assertEquals(m(
                "builtObject", m("a", bi(10), "b", bi(20)),
                "entry", m("index", bi(1), "key", "b", "val", bi(2)),
                "filtered", l(bi(3), bi(4)),
                "flat", l(bi(1), bi(11), bi(2), bi(12)),
                "found", "bb",
                "hasKey", true,
                "includes", true,
                "mapped", l(bi(2), bi(4), bi(6)),
                "objectFiltered", m("a", bi(1), "c", bi(3)),
                "some", true,
                "sum", bi(6)
        ), simple(result.value()));
    }

    @Test
    void objectFromEntriesRejectsBadKeysAndUndefinedValueOmitsKey() {
        assertEquals(m("a", bi(1)), simple(runExpr(op("$objectFromEntries", list(
                obj("key", "b", "val", 2),
                obj("key", "a", "val", 1),
                obj("key", "b", "val", op("$document", "/missing"))
        ))).value()));

        assertThrows(BexException.class, () -> runExpr(op("$objectFromEntries", list(
                obj("key", op("$null", true), "val", 1)
        ))));
    }

    @Test
    void shortCircuitingAvoidsLaterFailuresAndGas() {
        BexExecutionResult shortCircuit = runExpr(op("$some", obj(
                "in", list(1, 2, 3),
                "item", "x",
                "where", op("$eq", list(op("$var", "x"), 1))
        )));
        BexExecutionResult fullScan = runExpr(op("$some", obj(
                "in", list(1, 2, 3),
                "item", "x",
                "where", op("$eq", list(op("$var", "x"), 3))
        )));
        assertEquals(true, simple(shortCircuit.value()));
        assertTrue(shortCircuit.gasUsed() < fullScan.gasUsed());

        assertEquals("a", simple(runExpr(op("$find", obj(
                "in", list("a", "b"),
                "item", "x",
                "where", op("$or", list(
                        op("$eq", list(op("$var", "x"), "a")),
                        op("$integer", "not-an-integer")
                ))
        ))).value()));
    }

    @Test
    void collectionQueryBindingsDoNotLeakAndReturnIfValueIsRejected() {
        BexExecutionResult result = runStep(stepDo(list(
                op("$let", obj("name", "x", "expr", "outer")),
                op("$let", obj("name", "ignored", "expr", op("$map", obj(
                        "in", list("inner"),
                        "item", "x",
                        "expr", op("$var", "x")
                )))),
                op("$return", op("$var", "x"))
        )), defaultContext());

        assertEquals("outer", simple(result.value()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$returnIf", obj("cond", true, "value", "wrong-key"))
        )), defaultContext()));
    }
}

class BexFunctionFrameTest {
    @Test
    void nestedFunctionCallShadowsCallerSlotWithoutLeakage() {
        BexExecutionResult result = runStep(obj(
                "type", "Blue/BEX Program",
                "functions", obj("id", obj("args", obj("x", obj()), "expr", op("$var", "x"))),
                "do", list(
                        op("$let", obj("name", "x", "expr", "caller")),
                        op("$let", obj("name", "inner", "expr", op("$call", obj("function", "id", "args", obj("x", "callee"))))),
                        op("$return", obj("x", op("$var", "x"), "inner", op("$var", "inner")))
                )
        ), defaultContext());

        assertEquals(m("inner", "callee", "x", "caller"), simple(result.value()));
    }
}

class BexResultOverlayTest {
    @Test
    void resultValueUsesLatestDuplicatePatchAndFallsBackToDocument() {
        BexExecutionResult result = runStep(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/status", "val", "first")),
                op("$appendChange", obj("op", "replace", "path", "/status", "val", "second")),
                op("$return", obj("status", op("$resultValue", "/status"), "count", op("$resultValue", "/count")))
        )), defaultContext());

        assertEquals(m("count", BigInteger.valueOf(5), "status", "second"), simple(result.value()));
    }
}

class BexGasTest {
    @Test
    void gasIsDeterministicAndExhaustionFailsClosed() {
        assertEquals(runExpr(op("$add", list(1, 2))).gasUsed(), runExpr(op("$add", list(1, 2))).gasUsed());

        BexEngine engine = BexEngine.builder().gasSchedule(BexGasSchedule.builder().expressionBase(100).build()).build();
        BexExecutionContext context = BexExecutionContext.builder().document(defaultDocumentView()).gasLimit(1).build();
        assertThrows(BexException.class, () -> engine.compileAndExecute(BexProgramSource.inline(frozen(stepExpr(op("$add", list(1, 2))))), context));
    }
}

class BexPerformanceCounterTest {
    @Test
    void eventCursorAndObjectSetAvoidMaterializationCounters() {
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeCursorTrustedImmutable(obj("kind", "Created")))
                .gasLimit(1_000_000)
                .build();
        BexExecutionResult result = runStep(stepExpr(op("$objectSet", obj("object", obj("a", 1), "key", op("$event", "/kind"), "val", true))), context);

        assertEquals(0, result.metrics().nodeMaterializations());
        assertEquals(0, result.metrics().simpleMaterializations());
        assertEquals(1, result.metrics().eventReads());
    }
}
