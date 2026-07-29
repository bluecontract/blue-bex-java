package blue.bex;

import blue.bex.api.FrozenBexDocumentView;
import blue.bex.gas.BexGasCounter;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.result.BexPatchEntry;
import blue.bex.result.BexResultOverlay;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexPointerSet20Test {
    @Test
    void directListSetAndRemoveRemainDenseAcrossEveryValueView() {
        BexValue base = BexValues.fromSimple(l("a", "b", "c"));

        BexValue set = BexValues.pointerSet(
                base, Collections.singletonList("1"),
                BexValues.scalar("z"), "set");
        assertTrue(set.isList());
        assertEquals(3, set.size());
        assertEquals(Collections.emptyList(), set.keys());
        assertEquals("z", set.get("1").toSimple());
        assertEquals(l("a", "z", "c"), set.toSimple());

        BexValue removed = BexValues.pointerSet(
                base, Collections.singletonList("1"),
                BexValues.undefined(), "remove");
        assertTrue(removed.isList());
        assertEquals(2, removed.size());
        assertEquals(Collections.emptyList(), removed.keys());
        assertEquals("c", removed.get("1").toSimple());
        assertTrue(removed.get("2").isUndefined());
        assertEquals(l("a", "c"), removed.toSimple());
        assertEquals(2, removed.toNode().getItems().size());
    }

    @Test
    void directListWritesRejectNonIndexesAndOutOfRangeIndexesWithoutHoles() {
        BexValue base = BexValues.fromSimple(l("a", "b"));

        assertThrows(BexException.class, () -> BexValues.pointerSet(
                base, Collections.singletonList("2"),
                BexValues.scalar("z"), "set"));
        assertThrows(BexException.class, () -> BexValues.pointerSet(
                base, Collections.singletonList("2"),
                BexValues.undefined(), "remove"));
        assertThrows(BexException.class, () -> BexValues.pointerSet(
                base, Collections.singletonList("-1"),
                BexValues.scalar("z"), "set"));
        assertThrows(BexException.class, () -> BexValues.pointerSet(
                base, Arrays.asList("2", "nested"),
                BexValues.scalar("z"), "set"));

        assertThrows(BexException.class, () -> runExpr(op(
                "$pointerSet",
                obj("object", list("a", "b"),
                        "path", "/2",
                        "val", "z"))));
        assertEquals(
                m("parent", m("child", "z")),
                BexValues.pointerSet(
                        BexValues.fromSimple(m("parent", null)),
                        Arrays.asList("parent", "child"),
                        BexValues.scalar("z"),
                        "set").toSimple());
    }

    @Test
    void resultOverlayAloneKeepsNonShiftingSparseListRemoval() {
        FrozenBexDocumentView document = new FrozenBexDocumentView(
                frozen(obj("items", list("a", "b", "c"))));
        BexResultOverlay overlay =
                new BexResultOverlay(document, new BexMetrics());
        overlay.append(new BexPatchEntry(
                "remove",
                "/items/1",
                "/items/1",
                BexValues.undefined()));

        BexValue sparse = overlay.valueAt(
                "/items", Collections.singletonList("items"));
        assertTrue(sparse.isList());
        assertEquals(3, sparse.size());
        assertEquals(Collections.emptyList(), sparse.keys());
        assertEquals("a", sparse.get("0").toSimple());
        assertTrue(sparse.get("1").isUndefined());
        assertEquals("c", sparse.get("2").toSimple());
        assertThrows(BexException.class, sparse::toSimple);
        assertThrows(BexException.class, sparse::toNode);

        overlay.append(new BexPatchEntry(
                "replace",
                "/items/1",
                "/items/1",
                BexValues.scalar("z")));
        assertEquals(
                l("a", "z", "c"),
                overlay.valueAt(
                        "/items",
                        Collections.singletonList("items")).toSimple());
    }

    @Test
    void pointerSetChargesProducedContainerKindAlongTheActualPath() {
        BexExecutionResult listSet = runExpr(op(
                "$pointerSet",
                obj("object", op("$document", "/list"),
                        "path", "/1",
                        "val", "z")));
        assertEquals(
                1L,
                listSet.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED));
        assertEquals(
                0L,
                listSet.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED));

        BexExecutionResult objectSet = runExpr(op(
                "$pointerSet",
                obj("object", op("$document", "/state"),
                        "path", "/ready",
                        "val", true)));
        assertEquals(
                0L,
                objectSet.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED));
        assertEquals(
                1L,
                objectSet.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED));

        BexExecutionResult createdObjects = runExpr(op(
                "$pointerSet",
                obj("object", op("$document", "/"),
                        "path", "/missing/child",
                        "val", true)));
        assertEquals(
                2L,
                createdObjects.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED));
        assertEquals(
                2L,
                createdObjects.gasLedger().quantity(
                        BexGasCounter.POINTER_SEGMENT_WRITTEN));
    }

    @Test
    void pointerSetRemovalDoesNotChargeAnOmittedTerminalMemberOrItem() {
        BexExecutionResult listRemove = runExpr(op(
                "$pointerSet",
                obj("object", op("$document", "/list"),
                        "op", "remove",
                        "path", "/1")));
        assertEquals(l("a"), simple(listRemove.value()));
        assertEquals(
                0L,
                listRemove.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED));
        assertEquals(
                0L,
                listRemove.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED));

        BexExecutionResult objectRemove = runExpr(op(
                "$pointerSet",
                obj("object", op("$document", "/state"),
                        "op", "remove",
                        "path", "/ready")));
        assertEquals(m(), simple(objectRemove.value()));
        assertEquals(
                0L,
                objectRemove.gasLedger().quantity(
                        BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED));
    }
}
