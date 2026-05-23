package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static blue.bex.test.BexTestFixtures.bi;
import static blue.bex.test.BexTestFixtures.defaultContext;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.l;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.simple;
import static blue.bex.test.BexTestFixtures.stepDo;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BexUseCaseConformanceTest {
    private final Blue blue = new Blue();
    private final BexEngine engine = BexEngine.builder().blue(blue).build();

    @Test
    void existsDistinguishesMissingFromPresentFalsyValues() {
        assertEquals(false, simple(run(stepExpr(op("$exists", op("$document", "/missing"))), defaultContext()).value()));
        assertEquals(true, simple(run(stepExpr(op("$exists", op("$literal", null))), defaultContext()).value()));
        assertEquals(true, simple(run(stepExpr(op("$exists", "")), defaultContext()).value()));
        assertEquals(true, simple(run(stepExpr(op("$exists", false)), defaultContext()).value()));
        assertEquals(true, simple(run(stepExpr(op("$exists", list())), defaultContext()).value()));
        assertEquals(true, simple(run(stepExpr(op("$exists", obj())), defaultContext()).value()));
    }

    @Test
    void optionalValidationUsesExistsAndBluePatternMatching() {
        String program = yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $or:",
                "    - $not:",
                "        $exists:",
                "          $event: /message/request/note",
                "    - $is:",
                "        node:",
                "          $event: /message/request/note",
                "        pattern:",
                "          type: Text");

        assertEquals(true, simple(runYaml(program, eventRequest(obj())).value()));
        assertEquals(true, simple(runYaml(program, eventRequest(obj("note", "hello"))).value()));
        assertEquals(false, simple(runYaml(program, eventRequest(obj("note", 7))).value()));
    }

    @Test
    void forEachListCanExposeIndexForPatchPaths() {
        BexExecutionResult result = run(stepDo(list(
                op("$forEach", obj(
                        "in", op("$event", "message/request/orders"),
                        "item", "order",
                        "index", "i",
                        "do", list(op("$appendChange", obj(
                                "op", "replace",
                                "path", op("$pointerJoin", list("orders", op("$var", "i"), "status")),
                                "val", "received"))))),
                op("$return", op("$changeset", true))
        )), eventRequest(obj("orders", list(obj("id", "a"), obj("id", "b")))));

        assertEquals(l(
                m("op", "replace", "path", "/orders/0/status", "val", "received"),
                m("op", "replace", "path", "/orders/1/status", "val", "received")),
                simple(result.value()));
    }

    @Test
    void forEachObjectCanExposeKeyAndItemValueSeparately() {
        BexExecutionResult result = run(stepDo(list(
                op("$forEach", obj(
                        "in", op("$event", "message/request/ordersById"),
                        "key", "orderId",
                        "item", "order",
                        "do", list(op("$appendChange", obj(
                                "op", "replace",
                                "path", op("$pointerJoin", list("orders", op("$var", "orderId"), "status")),
                                "val", op("$pointerGet", obj("object", op("$var", "order"), "path", "status"))))))),
                op("$return", op("$changeset", true))
        )), eventRequest(obj("ordersById", obj("abc/def~ghi", obj("status", "new")))));

        assertEquals(l(m("op", "replace", "path", "/orders/abc~1def~0ghi/status", "val", "new")),
                simple(result.value()));
    }

    @Test
    void forEachObjectKeepsOldEntryShapeWhenOnlyItemIsDeclared() {
        BexExecutionResult result = run(stepDo(list(
                op("$forEach", obj(
                        "in", op("$event", "message/request/ordersById"),
                        "item", "entry",
                        "do", list(op("$appendEvent", obj(
                                "orderId", op("$pointerGet", obj("object", op("$var", "entry"), "path", "key")),
                                "status", op("$pointerGet", obj(
                                        "object", op("$pointerGet", obj("object", op("$var", "entry"), "path", "val")),
                                        "path", "status"))))))),
                op("$return", op("$events", true))
        )), eventRequest(obj("ordersById", obj("a", obj("status", "new")))));

        assertEquals(l(m("orderId", "a", "status", "new")), simple(result.value()));
    }

    @Test
    void dynamicTextOperandsRejectMissingValuesButStaticEmptyKeyStillWorks() {
        assertThrows(BexException.class, () -> run(stepExpr(op("$get", obj(
                "object", obj("a", 1),
                "key", op("$document", "/missing")
        ))), defaultContext()));
        assertThrows(BexException.class, () -> run(stepExpr(op("$objectSet", obj(
                "object", obj(),
                "key", op("$document", "/missing"),
                "val", 1
        ))), defaultContext()));
        assertThrows(BexException.class, () -> run(stepExpr(op("$binding", obj(
                "name", op("$document", "/missing"),
                "path", "/"
        ))), defaultContext()));
        assertThrows(BexException.class, () -> run(stepExpr(op("$steps", obj(
                "step", op("$document", "/missing"),
                "path", "/"
        ))), defaultContext()));
        assertThrows(BexException.class, () -> run(stepDo(list(
                op("$appendChange", obj(
                        "op", op("$document", "/missing"),
                        "path", "/x",
                        "val", 1))
        )), defaultContext()));
        assertThrows(BexException.class, () -> run(stepExpr(op("$pointerSet", obj(
                "object", obj("a", 1),
                "op", op("$document", "/missing"),
                "path", "/a",
                "val", 2
        ))), defaultContext()));

        assertEquals(m("", bi(1)), simple(run(stepExpr(op("$objectSet", obj(
                "object", obj(),
                "key", "",
                "val", 1
        ))), defaultContext()).value()));
    }

    @Test
    void resultValueMaterializesDescendantObjectPatchWhenReadingParent() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/hotelOrder/status", "val", "confirmed")),
                op("$return", obj("hotelOrder", op("$resultValue", "/hotelOrder")))
        )), documentContext(obj("hotelOrder", obj("status", "pending", "amount", 400))));

        assertEquals(m("hotelOrder", m("amount", bi(400), "status", "confirmed")), simple(result.value()));
    }

    @Test
    void resultValueAppliesParentAndChildPatchesInOrder() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/hotelOrder", "val", obj("status", "pending", "amount", 400))),
                op("$appendChange", obj("op", "replace", "path", "/hotelOrder/status", "val", "confirmed")),
                op("$return", obj("hotelOrder", op("$resultValue", "/hotelOrder")))
        )), documentContext(obj()));

        assertEquals(m("hotelOrder", m("amount", bi(400), "status", "confirmed")), simple(result.value()));
    }

    @Test
    void resultValueMaterializesChildRemoveWhenReadingParent() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "remove", "path", "/hotelOrder/status")),
                op("$return", obj("hotelOrder", op("$resultValue", "/hotelOrder")))
        )), documentContext(obj("hotelOrder", obj("status", "pending", "amount", 400))));

        assertEquals(m("hotelOrder", m("amount", bi(400))), simple(result.value()));
    }

    @Test
    void resultValueExactPathAndListIndexReplacementStillWork() {
        BexExecutionResult exact = run(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/hotelOrder/status", "val", "confirmed")),
                op("$return", obj("status", op("$resultValue", "/hotelOrder/status")))
        )), documentContext(obj("hotelOrder", obj("status", "pending"))));
        BexExecutionResult listReplacement = run(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/orders/1/status", "val", "confirmed")),
                op("$return", obj("orders", op("$resultValue", "/orders")))
        )), documentContext(obj("orders", list(obj("status", "pending"), obj("status", "pending")))));

        assertEquals(m("status", "confirmed"), simple(exact.value()));
        assertEquals(m("orders", l(m("status", "pending"), m("status", "confirmed"))), simple(listReplacement.value()));
    }

    @Test
    void resultValueMaterializesScopedRelativePatchWhenReadingParent() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "status", "val", "confirmed")),
                op("$return", obj("current", op("$resultValue", "/contracts/current")))
        )), documentContext(obj("contracts", obj("current", obj("status", "pending", "amount", 400))),
                "/contracts/current"));

        assertEquals(m("current", m("amount", bi(400), "status", "confirmed")), simple(result.value()));
    }

    @Test
    void resultValueHandlesRootReplaceAndRootRemove() {
        BexExecutionResult replaced = run(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/", "val", obj("status", "confirmed"))),
                op("$return", obj("root", op("$resultValue", "/")))
        )), documentContext(obj("status", "pending")));
        BexExecutionResult removed = run(stepDo(list(
                op("$appendChange", obj("op", "remove", "path", "/")),
                op("$return", obj("root", op("$resultValue", "/")))
        )), documentContext(obj("status", "pending")));

        assertEquals(m("root", m("status", "confirmed")), simple(replaced.value()));
        assertEquals(m(), simple(removed.value()));
    }

    @Test
    void resultValueAppliesRemoveThenAddAtSamePath() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "remove", "path", "/hotelOrder/status")),
                op("$appendChange", obj("op", "add", "path", "/hotelOrder/status", "val", "confirmed")),
                op("$return", obj("hotelOrder", op("$resultValue", "/hotelOrder")))
        )), documentContext(obj("hotelOrder", obj("status", "pending", "amount", 400))));

        assertEquals(m("hotelOrder", m("amount", bi(400), "status", "confirmed")), simple(result.value()));
    }

    @Test
    void resultValueCreatesMissingParentForChildAdd() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "add", "path", "/hotelOrder/status", "val", "confirmed")),
                op("$return", obj("hotelOrder", op("$resultValue", "/hotelOrder")))
        )), documentContext(obj()));

        assertEquals(m("hotelOrder", m("status", "confirmed")), simple(result.value()));
    }

    @Test
    void resultValueListIndexRemoveIsNonShiftingOverlayBehavior() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "remove", "path", "/orders/1")),
                op("$return", obj("orders", op("$resultValue", "/orders")))
        )), documentContext(obj("orders", list("a", "b", "c"))));

        assertEquals(m("orders", l("a", null, "c")), simple(result.value()));
    }

    @Test
    void forEachRejectsDuplicateBindingNames() {
        assertThrows(BexException.class, () -> run(stepDo(list(
                op("$forEach", obj(
                        "in", list("a"),
                        "item", "x",
                        "index", "x",
                        "do", list()))
        )), defaultContext()));
        assertThrows(BexException.class, () -> run(stepDo(list(
                op("$forEach", obj(
                        "in", obj("a", 1),
                        "item", "x",
                        "key", "x",
                        "do", list()))
        )), defaultContext()));
    }

    @Test
    void hotelOrderConfirmationBuildsPatchAndEvent() {
        BexExecutionResult result = run(stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/status", "val", "confirmed")),
                op("$appendEvent", obj(
                        "type", "General/Event",
                        "kind", "Order Confirmed",
                        "partner", op("$document", "/hotelName"),
                        "amount", op("$document", "/amount"))),
                op("$return", obj(
                        "status", op("$resultValue", "/status"),
                        "events", op("$events", true)))
        )), documentContext(obj("status", "pending", "hotelName", "Blue Hotel", "amount", 120)));

        assertEquals(m(
                "events", l(m("amount", bi(120), "kind", "Order Confirmed", "partner", "Blue Hotel", "type", "General/Event")),
                "status", "confirmed"), simple(result.value()));
    }

    @Test
    void payNoteRequestsCaptureOnlyWhenBothPartnerOrdersAreConfirmed() {
        assertEquals(l(m(
                        "amount", bi(500),
                        "reason", "Both partner orders confirmed",
                        "type", "PayNote/Complete Payment Requested")),
                simple(runPayNote("confirmed", "confirmed").value()));
        assertEquals(l(), simple(runPayNote("confirmed", "pending").value()));
    }

    private BexExecutionResult runPayNote(String hotelStatus, String restaurantStatus) {
        return run(stepDo(list(
                op("$if", obj(
                        "cond", op("$and", list(
                                op("$eq", list(op("$document", "/hotelOrder/status"), "confirmed")),
                                op("$eq", list(op("$document", "/restaurantOrder/status"), "confirmed")))),
                        "then", list(op("$appendEvent", obj(
                                "type", "PayNote/Complete Payment Requested",
                                "amount", op("$document", "/amount/expectedTotal"),
                                "reason", "Both partner orders confirmed"))))),
                op("$return", op("$events", true))
        )), documentContext(obj(
                "hotelOrder", obj("status", hotelStatus),
                "restaurantOrder", obj("status", restaurantStatus),
                "amount", obj("expectedTotal", 500))));
    }

    private BexExecutionResult run(Node step, BexExecutionContext context) {
        return engine.compileAndExecute(BexProgramSource.inline(FrozenNode.fromResolvedNode(step)), context);
    }

    private BexExecutionResult runYaml(String yaml, BexExecutionContext context) {
        Node node = blue.yamlToNode(yaml);
        return engine.compileAndExecute(BexProgramSource.inline(FrozenNode.fromResolvedNode(node)), context);
    }

    private BexExecutionContext documentContext(Node document) {
        return documentContext(document, "/");
    }

    private BexExecutionContext documentContext(Node document, String scope) {
        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(frozen(document), frozen(document), scope))
                .event(BexValues.nodeSnapshot(obj()))
                .currentContract(BexValues.nodeSnapshot(obj()))
                .gasLimit(1_000_000)
                .build();
    }

    private BexExecutionContext eventRequest(Node request) {
        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(frozen(obj()), frozen(obj()), "/"))
                .event(BexValues.nodeSnapshot(obj("message", obj("request", request))))
                .currentContract(BexValues.nodeSnapshot(obj()))
                .gasLimit(1_000_000)
                .build();
    }

    private static String yaml(String... lines) {
        return String.join("\n", lines);
    }
}
