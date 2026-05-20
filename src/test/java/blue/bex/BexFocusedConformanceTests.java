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
                op("$return", obj())
        )), defaultContext());

        assertEquals("first", simple(result.changeset().entries().get(0).val()));
        assertEquals("second", simple(result.changeset().entries().get(1).val()));
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
