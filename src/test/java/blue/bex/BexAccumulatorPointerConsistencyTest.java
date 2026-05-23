package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexAccumulatorPointerConsistencyTest {
    @Test
    void knownConstantWorksAndUnknownConstantFailsAtCompileTime() {
        assertEquals(BigInteger.valueOf(400), simple(runStep(obj(
                "type", "Blue/BEX Program",
                "constants", obj("amount", 400),
                "expr", op("$const", "amount")
        ), defaultContext()).value()));

        assertThrows(BexException.class, () -> compile(obj(
                "type", "Blue/BEX Program",
                "constants", obj("amount", 400),
                "expr", op("$const", "amuont")
        )));
    }

    @Test
    void literalPayloadMayContainUnknownConstant() {
        BexExecutionResult result = runStep(obj(
                "type", "Blue/BEX Program",
                "expr", op("$literal", op("$const", "missing"))
        ), defaultContext());

        assertEquals(m("$const", "missing"), simple(result.value()));
    }

    @Test
    void valueLocalPointersIgnoreDocumentScope() {
        BexExecutionContext context = scopedContext();

        assertEquals(BigInteger.valueOf(7), simple(runStep(stepExpr(op("$event", "message/request/amount")), context).value()));
        assertEquals("main", simple(runStep(stepExpr(op("$currentContract", "channel/name")), context).value()));
        assertEquals(l(m("op", "replace", "path", "/status", "val", "ready")),
                simple(runStep(stepExpr(op("$steps", obj("step", "Build", "path", "changeset"))), context).value()));
        assertEquals(BigInteger.ONE, simple(runStep(stepExpr(op("$pointerGet", obj(
                "object", obj("a", obj("b", 1)),
                "path", "a/b"
        ))), context).value()));
        assertEquals(m("a", m("b", BigInteger.ONE, "c", BigInteger.valueOf(2))),
                simple(runStep(stepExpr(op("$pointerSet", obj(
                        "object", obj("a", obj("b", 1)),
                        "path", "a/c",
                        "val", 2
                ))), context).value()));
    }

    @Test
    void documentPointersRemainDocumentScopeRelative() {
        BexExecutionContext context = scopedContext();

        assertEquals("scoped", simple(runStep(stepExpr(op("$document", "status")), context).value()));

        BexExecutionResult result = runStep(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "status", "val", "done")),
                op("$appendChanges", list(obj("op", "replace", "path", "status", "val", "batch"))),
                op("$return", obj())
        )), context);

        assertEquals("/contracts/current/status", result.changeset().entries().get(0).absolutePath());
        assertEquals("/contracts/current/status", result.changeset().entries().get(1).absolutePath());
    }

    @Test
    void dynamicPointerOperandsRejectNullAndUndefined() {
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendChange", obj(
                        "op", "replace",
                        "path", op("$document", "/missing"),
                        "val", "done"
                ))
        )), defaultContext()));
        assertThrows(BexException.class, () -> runExpr(op("$pointerGet", obj(
                "object", obj("a", 1),
                "path", op("$document", "/missing")
        ))));
        assertThrows(BexException.class, () -> runExpr(op("$event", op("$document", "/missing"))));
        assertThrows(BexException.class, () -> runExpr(op("$document", op("$literal", null))));
        assertThrows(BexException.class, () -> runExpr(op("$pointerSet", obj(
                "object", obj("a", 1),
                "path", op("$literal", null),
                "val", 2
        ))));
    }

    @Test
    void pointerJoinEscapesSegmentsAndWorksForPatchPaths() {
        assertEquals("/orders/abc~1def~0ghi/status", simple(runExpr(op("$pointerJoin", list(
                "orders",
                "abc/def~ghi",
                "status"
        ))).value()));
        assertEquals("/", simple(runExpr(op("$pointerJoin", list())).value()));

        BexExecutionResult result = runStep(stepDo(list(
                op("$let", obj("name", "id", "expr", "abc/def~ghi")),
                op("$appendChange", obj(
                        "op", "replace",
                        "path", op("$pointerJoin", list("orders", op("$var", "id"), "status")),
                        "val", "confirmed"
                )),
                op("$return", obj())
        )), defaultContext());

        assertEquals("/orders/abc~1def~0ghi/status", result.changeset().entries().get(0).absolutePath());
    }

    @Test
    void appendChangeAndAppendChangesValidatePatchEntriesConsistently() {
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendChange", obj("op", "move", "path", "/x", "val", 1))
        )), defaultContext()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendChanges", list(obj("op", "move", "path", "/x", "val", 1)))
        )), defaultContext()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/x"))
        )), defaultContext()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendChanges", list(obj("op", "replace", "path", "/x")))
        )), defaultContext()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendChanges", list("not-an-object"))
        )), defaultContext()));
    }

    @Test
    void removePatchesDoNotRequireValuesAndSingleRemoveDoesNotEvaluateVal() {
        BexExecutionResult single = runStep(stepDo(list(
                op("$appendChange", obj("op", "remove", "path", "/x", "val", op("$divide", list(1, 0)))),
                op("$return", obj())
        )), defaultContext());
        BexExecutionResult batch = runStep(stepDo(list(
                op("$appendChanges", list(obj("op", "remove", "path", "/x"))),
                op("$return", obj())
        )), defaultContext());

        assertEquals("remove", single.changeset().entries().get(0).op());
        assertTrue(single.changeset().entries().get(0).val().isUndefined());
        assertEquals("remove", batch.changeset().entries().get(0).op());
        assertTrue(batch.changeset().entries().get(0).val().isUndefined());
        assertEquals(l(m("op", "remove", "path", "/x")), simple(single.changeset().asValue()));
        assertEquals(l(m("op", "remove", "path", "/x")), simple(batch.changeset().asValue()));
    }

    @Test
    void appendEventAndAppendEventsValidateAndPreserveOrder() {
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendEvent", op("$document", "/missing"))
        )), defaultContext()));
        assertThrows(BexException.class, () -> runStep(stepDo(list(
                op("$appendEvents", list(obj("ok", true), op("$document", "/missing")))
        )), defaultContext()));

        BexExecutionResult result = runStep(stepDo(list(
                op("$appendEvents", list(obj("kind", "A"), obj("kind", "B"))),
                op("$return", obj())
        )), defaultContext());

        assertEquals(l(m("kind", "A"), m("kind", "B")), simple(result.events().asValue()));
    }

    @Test
    void appendOutputGasScalesWithValueSizeAndEntryCount() {
        long smallEventGas = runStep(stepDo(list(
                op("$appendEvents", list(obj("kind", "A"))),
                op("$return", obj())
        )), defaultContext()).gasUsed();
        long largeEventGas = runStep(stepDo(list(
                op("$appendEvents", list(largeObject(150))),
                op("$return", obj())
        )), defaultContext()).gasUsed();
        long onePatchGas = runStep(stepDo(list(
                op("$appendChanges", list(obj("op", "replace", "path", "/a", "val", "x"))),
                op("$return", obj())
        )), defaultContext()).gasUsed();
        long twoPatchGas = runStep(stepDo(list(
                op("$appendChanges", list(
                        obj("op", "replace", "path", "/a", "val", "x"),
                        obj("op", "replace", "path", "/b", "val", "x")
                )),
                op("$return", obj())
        )), defaultContext()).gasUsed();

        assertTrue(largeEventGas > smallEventGas);
        assertTrue(twoPatchGas > onePatchGas);
    }

    private static void compile(Node step) {
        BexEngine.builder().build().compile(BexProgramSource.inline(frozen(step)));
    }

    private static BexExecutionContext scopedContext() {
        Node document = obj("contracts", obj("current", obj("status", "scoped")));
        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(frozen(document), frozen(document), "/contracts/current"))
                .event(BexValues.nodeSnapshot(obj("message", obj("request", obj("amount", 7)))))
                .currentContract(BexValues.nodeSnapshot(obj("channel", obj("name", "main"))))
                .steps(BexStepResults.builder()
                        .put("Build", BexValues.fromSimple(m("changeset", l(m("op", "replace", "path", "/status", "val", "ready")))))
                        .build())
                .gasLimit(1_000_000)
                .build();
    }
}
