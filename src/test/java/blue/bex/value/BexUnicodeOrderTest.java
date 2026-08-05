package blue.bex.value;

import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BexUnicodeOrderTest {
    @Test
    void usesTheCanonicalStableBottomUpMergeTrace() {
        List<String> trace = new ArrayList<>();

        List<String> sorted = BexUnicodeOrder.sortedCopy(
                Arrays.asList("d", "b", "c", "a"),
                (left, right) -> {
                    trace.add(left + ":" + right);
                    return BexUnicodeOrder.compareCodePoints(
                            left, right);
                });

        assertEquals(
                Arrays.asList("a", "b", "c", "d"),
                sorted);
        assertEquals(
                Arrays.asList(
                        "d:b",
                        "c:a",
                        "b:a",
                        "b:c",
                        "d:c"),
                trace);

        String first = new String("same");
        String second = new String("same");
        List<String> equal = BexUnicodeOrder.sortedCopy(
                Arrays.asList(first, second),
                BexUnicodeOrder::compareCodePoints);
        assertSame(first, equal.get(0));
        assertSame(second, equal.get(1));
    }

    @Test
    void rejectedSortAdmissionPerformsNoComparatorWork() {
        BexGasMeter meter = new BexGasMeter(
                BexGasSchedule.defaults(), 4L);
        AtomicInteger completedComparisons =
                new AtomicInteger();

        assertThrows(
                BexGasLimitExceededException.class,
                () -> BexUnicodeOrder.sortedCopy(
                        Arrays.asList(
                                "d", "b", "c", "a"),
                        (left, right) -> {
                            meter.charge(
                                    BexGasCounter
                                            .SORT_COMPARISON);
                            meter.charge(
                                    BexGasCounter
                                            .COMPARISON_NODE_VISITED);
                            meter.charge(
                                    BexGasCounter
                                            .TEXT_BLOCK_EXAMINED,
                                    2L);
                            completedComparisons
                                    .incrementAndGet();
                            return BexUnicodeOrder
                                    .compareCodePoints(
                                            left, right);
                        }));

        assertEquals(1, completedComparisons.get());
        assertEquals(1L, meter.ledger().quantity(
                BexGasCounter.SORT_COMPARISON));
        assertEquals(1L, meter.ledger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
        assertEquals(2L, meter.ledger().quantity(
                BexGasCounter.TEXT_BLOCK_EXAMINED));
        assertEquals(3, meter.trace().size());
    }

    @Test
    void valueImplementationsExposeOneEstablishedCanonicalCursor() {
        String supplementary = "\uD800\uDC00";
        Map<String, BexValue> fields =
                new LinkedHashMap<>();
        fields.put(supplementary, BexValues.scalar(3));
        fields.put("z", BexValues.scalar(1));
        fields.put("\uE000", BexValues.scalar(2));
        List<String> expected = Arrays.asList(
                "z", "\uE000", supplementary);

        BexValue map = BexValues.map(fields);
        assertEquals(expected, map.keys());
        assertSame(map.keys(), map.keys());

        BexValue overlay = BexValues.overlay(
                map, "a", BexValues.scalar(0));
        assertEquals(
                Arrays.asList(
                        "a", "z", "\uE000",
                        supplementary),
                overlay.keys());
        assertSame(overlay.keys(), overlay.keys());

        BexValue pointer = BexValues.pointerSet(
                map,
                Arrays.asList("a"),
                BexValues.scalar(0),
                "set");
        assertEquals(
                Arrays.asList(
                        "a", "z", "\uE000",
                        supplementary),
                pointer.keys());
        assertSame(pointer.keys(), pointer.keys());

        Map<String, Node> nodeFields =
                new LinkedHashMap<>();
        nodeFields.put(
                supplementary,
                new Node().value(3));
        nodeFields.put("z", new Node().value(1));
        nodeFields.put(
                "\uE000", new Node().value(2));
        BexValue node = BexValues
                .nodeCursorTrustedImmutable(
                        new Node().properties(
                                nodeFields));
        assertEquals(expected, node.keys());
        assertSame(node.keys(), node.keys());

        BexValue frozen = BexValues.frozen(
                FrozenNode.fromResolvedNode(
                        new Node().properties(
                                nodeFields)));
        assertEquals(expected, frozen.keys());
        assertSame(frozen.keys(), frozen.keys());
    }
}
