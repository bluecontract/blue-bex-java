package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.gas.BexGasCharge;
import blue.bex.gas.BexGasCounter;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.result.BexExecutionResult;
import blue.language.model.Node;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BexExactGasRuleTest {

    @Test
    void prefixOperatorsChargeOnlyCodePointBlocksActuallyCompared() {
        String longText = "x" + repeated('a', 190);
        String firstBlockMiss = "y" + repeated('a', 190);

        BexExecutionResult startsWith = runWithConstants(
                op("$startsWith", list(
                        op("$const", "text"),
                        op("$const", "prefix"))),
                "text", longText,
                "prefix", firstBlockMiss);

        assertEquals(false, startsWith.value().toSimple());
        assertEquals(2L, quantity(startsWith, BexGasCounter.TEXT_BLOCK_EXAMINED));

        BexExecutionResult missedSlice = runWithConstants(
                op("$sliceAfter", list(
                        op("$const", "text"),
                        op("$const", "prefix"))),
                "text", longText,
                "prefix", firstBlockMiss);

        assertEquals("", missedSlice.value().toSimple());
        assertEquals(2L, quantity(missedSlice, BexGasCounter.TEXT_BLOCK_EXAMINED));
        assertEquals(0L, quantity(missedSlice, BexGasCounter.TEXT_BLOCK_CONSTRUCTED));

        String matchingPrefix = repeated('p', 65);
        String suffix = repeated('s', 130);
        BexExecutionResult matchedSlice = runWithConstants(
                op("$sliceAfter", list(
                        op("$const", "text"),
                        op("$const", "prefix"))),
                "text", matchingPrefix + suffix,
                "prefix", matchingPrefix);

        assertEquals(suffix, matchedSlice.value().toSimple());
        assertEquals(4L, quantity(matchedSlice, BexGasCounter.TEXT_BLOCK_EXAMINED));
        assertEquals(3L, quantity(matchedSlice, BexGasCounter.TEXT_BLOCK_CONSTRUCTED));
    }

    @Test
    void exactDecimalToIntegerIncludesScaleAlignmentAfterFullMagnitudeAdmission() {
        BexExecutionResult result = runWithConstants(
                op("$integer", op("$const", "decimal")),
                "decimal", new BigDecimal("4294967296.0"));

        assertEquals(
                new BigInteger("4294967296"),
                result.value().toSimple());
        assertEquals(
                3L,
                quantity(
                        result,
                        BexGasCounter.INTEGER_LIMB_OPERATION));
    }

    @Test
    void textArithmeticOperandsAreConvertedBeforeArithmeticWork() {
        BexExecutionResult result = runWithConstants(
                op("$add", list(
                        op("$const", "left"),
                        op("$const", "right"))),
                "left", "4294967296",
                "right", BigInteger.ONE);

        assertEquals(
                new BigInteger("4294967297"),
                result.value().toSimple());
        assertEquals(
                5L,
                quantity(
                        result,
                        BexGasCounter.INTEGER_LIMB_OPERATION));
    }

    @Test
    void mergeChargesProductionOnlyForRetainedFields() {
        BexExecutionResult result = runWithConstants(
                op("$merge", list(
                        op("$const", "left"),
                        op("$const", "right"))),
                "left", obj("a", 1, "b", 2),
                "right", obj("b", 3, "c", 4));

        assertEquals(map("a", integer(1), "b", integer(3), "c", integer(4)),
                result.value().toSimple());
        assertEquals(4L, quantity(result, BexGasCounter.COLLECTION_ITEM_VISITED));
        assertEquals(4L, quantity(result, BexGasCounter.OBJECT_MEMBER_READ));
        assertEquals(3L, quantity(result, BexGasCounter.COLLECTION_ITEM_PRODUCED));
        assertEquals(3L,
                quantity(result, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED));
    }

    @Test
    void objectFromEntriesMetersOrderedReadsKeyConversionAndRetainedFields() {
        String longKey = repeated('k', 65);
        Node entries = list(
                obj("key", longKey, "val", 1),
                obj("key", "drop", "val", 2),
                obj("key", longKey, "val", 3),
                obj("key", "drop"),
                obj("key", 7, "val", 4));

        BexExecutionResult result = runWithConstants(
                op("$objectFromEntries", op("$const", "entries")),
                "entries", entries);

        assertEquals(map("7", integer(4), longKey, integer(3)),
                result.value().toSimple());
        assertEquals(5L, quantity(result, BexGasCounter.COLLECTION_ITEM_VISITED));
        assertEquals(5L, quantity(result, BexGasCounter.LIST_ITEM_READ));
        assertEquals(10L, quantity(result, BexGasCounter.OBJECT_MEMBER_READ));
        assertEquals(9L, quantity(result, BexGasCounter.TEXT_BLOCK_EXAMINED));
        assertEquals(7L, quantity(result, BexGasCounter.TEXT_BLOCK_CONSTRUCTED));
        assertEquals(1L, quantity(
                result, BexGasCounter.SORT_COMPARISON));
        assertEquals(1L, quantity(
                result,
                BexGasCounter.COMPARISON_NODE_VISITED));
        assertEquals(2L, quantity(result, BexGasCounter.COLLECTION_ITEM_PRODUCED));
        assertEquals(2L,
                quantity(result, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED));

        List<String> operatorCounters = result.gasTrace().stream()
                .filter(charge -> "$objectFromEntries".equals(charge.operator()))
                .map(BexGasCharge::counterName)
                .collect(Collectors.toList());
        int firstVisit = operatorCounters.indexOf(
                BexGasCounter.COLLECTION_ITEM_VISITED.canonicalName());
        assertEquals(Arrays.asList(
                        "collectionItemVisited",
                        "listItemRead",
                        "objectMemberRead",
                        "textBlockExamined",
                        "textBlockExamined",
                        "textBlockConstructed",
                        "objectMemberRead"),
                operatorCounters.subList(firstVisit, firstVisit + 7));
    }

    @Test
    void objectFromEntriesDoesNotAdmitValueReadAfterInvalidKey() {
        RecordingGasHost gasHost = new RecordingGasHost();
        Node program = program(
                op("$objectFromEntries", op("$const", "entries")),
                "entries", list(obj("val", 1)));
        BexExecutionContext context = context(gasHost);

        assertThrows(BexException.class, () -> BexEngine.builder().build()
                .compileAndExecute(
                        BexProgramSource.inline(frozen(program)),
                        context));

        assertEquals(1L, gasHost.quantity("objectMemberRead"));
        assertEquals(0L, gasHost.quantity("textBlockExamined"));
        assertEquals(0L, gasHost.quantity("collectionItemProduced"));
    }

    private static long quantity(
            BexExecutionResult result, BexGasCounter counter) {
        return result.gasLedger().quantity(counter);
    }

    private static BexExecutionResult runWithConstants(
            Node expression, Object... constants) {
        return BexEngine.builder().build().compileAndExecute(
                BexProgramSource.inline(frozen(program(expression, constants))),
                context(null));
    }

    private static Node program(Node expression, Object... constants) {
        return obj(
                "type", "Blue/BEX Program",
                "constants", obj(constants),
                "expr", expression);
    }

    private static BexExecutionContext context(BexGasLedgerHost gasHost) {
        BexExecutionContext.Builder builder = BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(frozen(obj())))
                .gasLimit(1_000_000L);
        if (gasHost != null) {
            builder.gasLedgerHost(gasHost)
                    .semanticIdentityBoundary(
                            BexSemanticIdentityBoundary.STANDALONE);
        }
        return builder.build();
    }

    private static FrozenNode frozen(Node node) {
        return FrozenNode.fromResolvedNode(node);
    }

    private static Node op(String name, Object body) {
        return obj(name, body);
    }

    private static Node obj(Object... keysAndValues) {
        return new Node().properties(properties(keysAndValues));
    }

    private static Map<String, Node> properties(Object... keysAndValues) {
        Map<String, Node> properties = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            properties.put(
                    (String) keysAndValues[index],
                    node(keysAndValues[index + 1]));
        }
        return properties;
    }

    private static Node list(Object... values) {
        List<Node> items = new ArrayList<>();
        for (Object value : values) {
            items.add(node(value));
        }
        return new Node().items(items);
    }

    private static Node node(Object value) {
        if (value instanceof Node) {
            return (Node) value;
        }
        if (value instanceof Integer) {
            return new Node().value(((Integer) value).longValue());
        }
        if (value instanceof Long
                || value instanceof String
                || value instanceof Boolean
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return new Node().value(value);
        }
        if (value == null) {
            return new Node();
        }
        throw new IllegalArgumentException(
                "Unsupported test value " + value.getClass().getName());
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            values.put(
                    (String) keysAndValues[index],
                    keysAndValues[index + 1]);
        }
        return values;
    }

    private static BigInteger integer(long value) {
        return BigInteger.valueOf(value);
    }

    private static String repeated(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
    }

    private static final class RecordingGasHost
            implements BexGasLedgerHost {
        private final GasMeter parent =
                new GasMeter(GasSchedule.contracts10());
        private GasMeter.ChildGasLedger child;

        @Override
        public GasMeter.ChildGasLedger open(
                String namespace, Map<String, Long> counterWeights) {
            child = parent.childLedger(namespace, counterWeights);
            return child;
        }

        @Override
        public void submit(GasMeter.ChildGasLedger ledger) {
            parent.merge(ledger);
        }

        @Override
        public void failedDeterministically(
                GasMeter.ChildGasLedger ledger) {
            parent.merge(ledger);
        }

        @Override
        public void evidenceUnavailable(
                GasMeter.ChildGasLedger ledger) {
            // Detached child ledgers reserve nothing until merge.
        }

        private long quantity(String counter) {
            long total = 0L;
            for (GasTraceEntry entry : parent.trace()) {
                if ("bex".equals(entry.namespace())
                        && counter.equals(entry.counter())) {
                    total += entry.quantity();
                }
            }
            return total;
        }
    }
}
