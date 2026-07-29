package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.result.BexExecutionResult;
import blue.bex.type.BexBlueTypeMatcher;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.bex.test.BexTestFixtures.defaultContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexBlueTypeMatchingGasTest {
    private final Blue blue = new Blue();
    private final BexEngine engine =
            BexEngine.builder().blue(blue).build();

    @Test
    void structuralIsChargesEveryComparedSemanticOccurrence() {
        BexExecutionResult result = run(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      request:",
                "        nights: 2",
                "    pattern:",
                "      request:",
                "        nights:",
                "          type: Integer");

        assertEquals(true, result.value().toSimple());
        assertEquals(3L, result.gasLedger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
    }

    @Test
    void typedFunctionArgumentUsesTheSameDeepMeteredBoundary() {
        BexExecutionResult result = run(
                "type: Blue/BEX Program",
                "functions:",
                "  accept:",
                "    args:",
                "      request:",
                "        stay:",
                "          nights:",
                "            type: Integer",
                "    expr: true",
                "expr:",
                "  $call:",
                "    function: accept",
                "    args:",
                "      request:",
                "        stay:",
                "          nights: 2");

        assertEquals(true, result.value().toSimple());
        assertEquals(3L, result.gasLedger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
    }

    @Test
    void objectMatchingUsesCanonicalKeyOrderAndStopsAtFirstDifference() {
        BexExecutionResult result = run(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      z: 1",
                "      a: wrong",
                "    pattern:",
                "      z:",
                "        type: Integer",
                "        schema:",
                "          required: true",
                "      a:",
                "        type: Integer",
                "        schema:",
                "          required: true");

        assertEquals(false, result.value().toSimple());
        assertEquals(2L, result.gasLedger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
    }

    @Test
    void listMatchingVisitsPositionsInOrderAndStopsAtFirstDifference() {
        BexExecutionResult result = run(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      - 1",
                "      - wrong",
                "      - 3",
                "    pattern:",
                "      - type: Integer",
                "      - type: Integer",
                "      - type: Integer");

        assertEquals(false, result.value().toSimple());
        assertEquals(3L, result.gasLedger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
    }

    @Test
    void scalarPatternComparisonsAddTextAndNumericWork() {
        String text = repeat('x', 130);
        BexExecutionResult textResult = run(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      payload: \"" + text + "\"",
                "    pattern:",
                "      payload: \"" + text + "\"");

        assertEquals(true, textResult.value().toSimple());
        assertEquals(2L, textResult.gasLedger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
        assertEquals(6L, textResult.gasLedger().quantity(
                BexGasCounter.TEXT_BLOCK_EXAMINED));

        BexExecutionResult integerResult = run(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      payload:",
                "        $literal:",
                "          type: Integer",
                "          value: \"18446744073709551616\"",
                "    pattern:",
                "      payload:",
                "        type: Integer",
                "        value: \"18446744073709551616\"");

        assertEquals(true, integerResult.value().toSimple());
        assertEquals(2L, integerResult.gasLedger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
        assertEquals(6L, integerResult.gasLedger().quantity(
                BexGasCounter.INTEGER_LIMB_OPERATION));
    }

    @Test
    void recursiveMatchIsAdmittedBeforeTheChildComparison() {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("child", 1);
        FrozenNode pattern = FrozenNode.fromResolvedNode(blue.yamlToNode(
                "child:\n"
                        + "  type: Integer\n"));
        BexGasMeter meter = new BexGasMeter(
                BexGasSchedule.defaults(), 1L);

        assertThrows(BexGasLimitExceededException.class,
                () -> new BexBlueTypeMatcher(blue).matches(
                        BexValues.fromSimple(candidate),
                        pattern,
                        meter,
                        null));
        assertEquals(1L, meter.ledger().quantity(
                BexGasCounter.COMPARISON_NODE_VISITED));
        assertEquals(1, meter.trace().size());
        assertTrue(meter.trace().get(0).reason().contains(
                "comparisonNodeVisited"));
    }

    @Test
    void unavailableNestedExactEvidenceIsNotConvertedToATypeMismatch() {
        Node content = new Node().properties(
                Collections.singletonMap(
                        "value",
                        new Node().value("known")));
        String blueId =
                BlueIdCalculator.calculateBlueId(content);
        NodeProvider provider = providerReturning(
                NodeProviderResult.unavailable(
                        "type evidence is offline"));

        try (Blue unavailableBlue = new Blue(provider)) {
            BexGasMeter meter = new BexGasMeter(
                    BexGasSchedule.defaults(),
                    1_000_000L);
            ExecutionEvidenceUnavailableException failure =
                    assertThrows(
                            ExecutionEvidenceUnavailableException.class,
                            () -> new BexBlueTypeMatcher(
                                    unavailableBlue)
                                    .matches(
                                            transientWithReference(
                                                    unavailableBlue,
                                                    blueId),
                                            nonEmptyPattern(),
                                            meter,
                                            null));

            assertEquals(
                    Collections.singletonList(blueId),
                    failure.requiredExactBlueIds());
            assertEquals(
                    "type evidence is offline",
                    failure.getMessage());
            assertEquals(
                    2L,
                    meter.ledger().quantity(
                            BexGasCounter
                                    .COMPARISON_NODE_VISITED));
            assertEquals(2, meter.trace().size());
        }
    }

    @Test
    void invalidNestedExactEvidenceIsNotConvertedToATypeMismatch() {
        Node content = new Node().properties(
                Collections.singletonMap(
                        "value",
                        new Node().value("known")));
        String blueId =
                BlueIdCalculator.calculateBlueId(content);
        NodeProvider provider = providerReturning(
                NodeProviderResult.invalidEvidence(
                        "type evidence is invalid"));

        try (Blue invalidBlue = new Blue(provider)) {
            BexGasMeter meter = new BexGasMeter(
                    BexGasSchedule.defaults(),
                    1_000_000L);
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> new BexBlueTypeMatcher(
                                    invalidBlue)
                                    .matches(
                                            transientWithReference(
                                                    invalidBlue,
                                                    blueId),
                                            nonEmptyPattern(),
                                            meter,
                                            null));

            assertEquals(
                    "type evidence is invalid",
                    failure.getMessage());
            assertEquals(
                    2L,
                    meter.ledger().quantity(
                            BexGasCounter
                                    .COMPARISON_NODE_VISITED));
            assertEquals(2, meter.trace().size());
        }
    }

    @Test
    void malformedLocalBlueShapeRemainsATypeMismatch() {
        String blueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().value("referenced"));
        Map<String, Object> malformed =
                new LinkedHashMap<>();
        malformed.put("blueId", blueId);
        malformed.put("sibling", 1);
        BexGasMeter meter = new BexGasMeter(
                BexGasSchedule.defaults(),
                1_000_000L);

        assertFalse(new BexBlueTypeMatcher(blue)
                .matches(
                        BexValues.fromSimple(malformed),
                        nonEmptyPattern(),
                        meter,
                        null));
        assertEquals(
                1L,
                meter.ledger().quantity(
                        BexGasCounter
                                .COMPARISON_NODE_VISITED));
        assertEquals(1, meter.trace().size());
    }

    @Test
    void isPropagatesAnArbitraryReferenceProviderFailure() {
        Node content = new Node().properties(
                Collections.singletonMap(
                        "value",
                        new Node().value("known")));
        String blueId =
                BlueIdCalculator.calculateBlueId(content);
        IllegalStateException expected =
                new IllegalStateException(
                        "reference provider implementation defect");
        NodeProvider provider = ignored -> {
            throw expected;
        };

        try (Blue providerBlue = new Blue(provider)) {
            IllegalStateException observed = assertThrows(
                    IllegalStateException.class,
                    () -> executeProviderBackedIs(
                            providerBlue, blueId));

            assertSame(expected, observed);
        }
    }

    @Test
    void deterministicProcessorFailureWinsOverNestedUnavailabilityInIs() {
        Node content = new Node().properties(
                Collections.singletonMap(
                        "value",
                        new Node().value("known")));
        String blueId =
                BlueIdCalculator.calculateBlueId(content);
        ExecutionEvidenceUnavailableException nested =
                new ExecutionEvidenceUnavailableException(
                        "nested evidence detail");
        ProcessorFailureException expected =
                new ProcessorFailureException(
                        ProcessorErrorCategory
                                .RuntimeExecutionFailure,
                        "deterministic provider rejection",
                        nested);
        NodeProvider provider = ignored -> {
            throw expected;
        };

        try (Blue providerBlue = new Blue(provider)) {
            ProcessorFailureException observed = assertThrows(
                    ProcessorFailureException.class,
                    () -> executeProviderBackedIs(
                            providerBlue, blueId));

            assertSame(expected, observed);
        }
    }

    @Test
    void wideTransientCandidateDoesNotDemandARejectedLaterChild() {
        AtomicInteger providerDemands =
                new AtomicInteger();
        Node content = new Node().value("known");
        String blueId =
                BlueIdCalculator.calculateBlueId(content);
        NodeProvider provider = countingProvider(
                providerDemands,
                NodeProviderResult.unavailable(
                        "later child is offline"));

        try (Blue unavailableBlue = new Blue(provider)) {
            Map<String, BexValue> wide =
                    new LinkedHashMap<>();
            for (int index = 0; index < 256; index++) {
                wide.put(
                        String.format("field%03d", index),
                        BexValues.scalar(index));
            }
            wide.put(
                    "zzLater",
                    exactReference(
                            unavailableBlue, blueId));
            FrozenNode pattern =
                    FrozenNode.fromResolvedNode(
                            new Node().properties(
                                    Collections.singletonMap(
                                            "zzLater",
                                            new Node().value(
                                                    "expected"))));
            BexGasMeter meter = new BexGasMeter(
                    BexGasSchedule.defaults(), 1L);

            assertThrows(
                    BexGasLimitExceededException.class,
                    () -> new BexBlueTypeMatcher(
                            unavailableBlue).matches(
                                    BexValues.map(wide),
                                    pattern,
                                    meter,
                                    null));
            assertEquals(0, providerDemands.get());
            assertEquals(1L, meter.ledger().quantity(
                    BexGasCounter
                            .COMPARISON_NODE_VISITED));
        }
    }

    @Test
    void deepTransientCandidateStopsBeforeRejectedExactLeaf() {
        AtomicInteger providerDemands =
                new AtomicInteger();
        Node content = new Node().value("known");
        String blueId =
                BlueIdCalculator.calculateBlueId(content);
        NodeProvider provider = countingProvider(
                providerDemands,
                NodeProviderResult.unavailable(
                        "deep leaf is offline"));

        try (Blue unavailableBlue = new Blue(provider)) {
            int depth = 32;
            BexValue candidate = exactReference(
                    unavailableBlue, blueId);
            Node target = new Node().value(
                    "expected");
            for (int index = depth - 1;
                 index >= 0;
                 index--) {
                String key = String.format(
                        "level%02d", index);
                candidate = BexValues.map(
                        Collections.singletonMap(
                                key, candidate));
                target = new Node().properties(
                        Collections.singletonMap(
                                key, target));
            }
            FrozenNode pattern =
                    FrozenNode.fromResolvedNode(target);
            BexGasMeter meter = new BexGasMeter(
                    BexGasSchedule.defaults(),
                    depth);
            final BexValue deepCandidate =
                    candidate;

            assertThrows(
                    BexGasLimitExceededException.class,
                    () -> new BexBlueTypeMatcher(
                            unavailableBlue).matches(
                                    deepCandidate,
                                    pattern,
                                    meter,
                                    null));
            assertEquals(0, providerDemands.get());
            assertEquals(
                    depth,
                    meter.ledger().quantity(
                            BexGasCounter
                                    .COMPARISON_NODE_VISITED));
        }
    }

    @Test
    void exactStructuralCursorDoesNotDemandRejectedNestedReference() {
        AtomicInteger providerDemands =
                new AtomicInteger();
        Node content = new Node().value("known");
        String blueId =
                BlueIdCalculator.calculateBlueId(content);
        NodeProvider provider = countingProvider(
                providerDemands,
                NodeProviderResult.unavailable(
                        "nested exact child is offline"));

        try (Blue unavailableBlue = new Blue(provider)) {
            FrozenNode exactRoot =
                    FrozenNode.fromResolvedNode(
                            new Node().properties(
                                    Collections.singletonMap(
                                            "child",
                                            new Node().blueId(
                                                    blueId))));
            BexValue candidate =
                    BexValues.referenceBacked(
                            BexValues.frozen(exactRoot),
                            unavailableBlue);
            FrozenNode pattern =
                    FrozenNode.fromResolvedNode(
                            new Node().properties(
                                    Collections.singletonMap(
                                            "child",
                                            new Node().value(
                                                    "expected"))));
            BexGasMeter meter = new BexGasMeter(
                    BexGasSchedule.defaults(), 1L);

            assertThrows(
                    BexGasLimitExceededException.class,
                    () -> new BexBlueTypeMatcher(
                            unavailableBlue).matches(
                                    candidate,
                                    pattern,
                                    meter,
                                    null));
            assertEquals(0, providerDemands.get());
            assertEquals(1L, meter.ledger().quantity(
                    BexGasCounter
                            .COMPARISON_NODE_VISITED));
        }
    }

    private BexExecutionResult run(String... lines) {
        Node program = blue.yamlToNode(join(lines));
        return engine.compileAndExecute(
                BexProgramSource.inline(
                        FrozenNode.fromResolvedNode(program)),
                defaultContext());
    }

    private static void executeProviderBackedIs(
            Blue blue,
            String blueId) {
        FrozenNode document = FrozenNode.fromResolvedNode(
                new Node().properties(
                        Collections.singletonMap(
                                "x",
                                new Node().blueId(blueId))));
        BexExecutionContext context =
                BexExecutionContext.builder()
                        .document(new FrozenBexDocumentView(
                                document))
                        .build();
        Node program = blue.yamlToNode(join(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      $document: /x",
                "    pattern:",
                "      value: expected"));

        BexEngine.builder()
                .blue(blue)
                .build()
                .compileAndExecute(
                        BexProgramSource.inline(
                                FrozenNode.fromResolvedNode(
                                        program)),
                        context);
    }

    private static String join(String... lines) {
        StringBuilder yaml = new StringBuilder();
        for (String line : lines) {
            if (yaml.length() > 0) {
                yaml.append('\n');
            }
            yaml.append(line);
        }
        return yaml.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            out.append(value);
        }
        return out.toString();
    }

    private static BexValue transientWithReference(
            Blue blue,
            String blueId) {
        Map<String, BexValue> candidate =
                new LinkedHashMap<>();
        candidate.put(
                "child",
                BexValues.referenceBacked(
                        BexValues.frozen(
                                FrozenNode.fromNode(
                                        new Node().blueId(
                                                blueId))),
                        blue));
        return BexValues.map(candidate);
    }

    private static BexValue exactReference(
            Blue blue,
            String blueId) {
        return BexValues.referenceBacked(
                BexValues.frozen(
                        FrozenNode.fromNode(
                                new Node().blueId(
                                        blueId))),
                blue);
    }

    private static FrozenNode nonEmptyPattern() {
        return FrozenNode.fromResolvedNode(
                new Node().properties(
                        Collections.singletonMap(
                                "child",
                                new Node().value(
                                        "expected"))));
    }

    private static NodeProvider providerReturning(
            NodeProviderResult result) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(
                    String blueId) {
                return Collections.emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String blueId) {
                return result;
            }
        };
    }

    private static NodeProvider countingProvider(
            AtomicInteger demands,
            NodeProviderResult result) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(
                    String blueId) {
                demands.incrementAndGet();
                return Collections.emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String blueId) {
                demands.incrementAndGet();
                return result;
            }
        };
    }
}
