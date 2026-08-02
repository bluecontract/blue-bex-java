package blue.bex.conformance;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexOutputKind;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.result.BexExecutionResult;
import blue.bex.test.TestBlue;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.runExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded deterministic properties for modernization-sensitive boundaries. */
class BexModernizationPropertyTest {
    @Test
    void escapedPointerSegmentsRoundTripThroughBex() {
        List<String> segments = Arrays.asList(
                "plain",
                "slash/inside",
                "tilde~inside",
                "both~/inside",
                "emoji-\uD83D\uDE80",
                "space inside",
                "line\nbreak");
        Random random = new Random(0xBEE2201L);

        for (int example = 0; example < 64; example++) {
            String segment = segments.get(
                    random.nextInt(segments.size()))
                    + '-' + example;
            String expected = "value-" + example;
            String pointer = JsonPointer.toPointer(
                    Collections.singletonList(segment));
            BexExecutionResult first = runExpr(op(
                    "$pointerGet",
                    obj(
                            "object", obj(segment, expected),
                            "path", pointer)));
            BexExecutionResult second = runExpr(op(
                    "$pointerGet",
                    obj(
                            "object", obj(segment, expected),
                            "path", pointer)));

            assertEquals(expected, first.value().toSimple());
            assertEquivalent(first, second);
        }
    }

    @Test
    void generatedUnicodeTextHasStableResultAndGasIdentity() {
        int[] alphabet = {
                'A', 'z', 0x00E9, 0x03A9, 0x4E2D,
                0x1F680, 0x1F642, 0x10437
        };
        Random random = new Random(0xBEE2202L);

        for (int example = 0; example < 48; example++) {
            String left = unicode(random, alphabet, 1 + random.nextInt(10));
            String right = unicode(random, alphabet, 1 + random.nextInt(10));
            BexProgramSource source = BexProgramSource.expression(
                    frozen(op("$concat", list(left, "|", right))));
            BexEngine engine = BexEngine.builder().build();
            BexExecutionResult first = engine.compileAndExecute(
                    source, defaultContext());
            BexExecutionResult second = engine.compileAndExecute(
                    source, defaultContext());

            assertEquals(left + '|' + right,
                    first.value().toSimple());
            assertEquivalent(first, second);
        }
    }

    @Test
    void generatedIntegerAndDecimalKindsRemainDistinct() {
        Random random = new Random(0xBEE2203L);
        for (int example = 0; example < 48; example++) {
            long value = random.nextInt(2_000_001) - 1_000_000L;
            BexExecutionResult result = runExpr(obj(
                    "integer", op("$kind", value),
                    "decimal", op("$kind",
                            new Node().value(
                                    BigDecimal.valueOf(value)
                                            .setScale(1)))));
            assertEquals(m(
                            "decimal", "double",
                            "integer", "integer"),
                    result.value().toSimple());
        }
    }

    @Test
    void allLazyOperatorFamiliesSkipUnselectedFailuresAndGas() {
        List<Node[]> pairs = Arrays.asList(
                new Node[]{
                        op("$or", list(
                                true,
                                op("$integer", "must-not-run"))),
                        op("$or", list(true, false))},
                new Node[]{
                        op("$and", list(
                                false,
                                op("$integer", "must-not-run"))),
                        op("$and", list(false, true))},
                new Node[]{
                        op("$coalesce", list(
                                "chosen",
                                op("$integer", "must-not-run"))),
                        op("$coalesce", list("chosen", "unused"))},
                new Node[]{
                        op("$choose", obj(
                                "cond", true,
                                "then", "chosen",
                                "else", op("$integer", "must-not-run"))),
                        op("$choose", obj(
                                "cond", true,
                                "then", "chosen",
                                "else", "unused"))});

        for (Node[] pair : pairs) {
            BexExecutionResult failingBranch = runExpr(pair[0]);
            BexExecutionResult harmlessBranch = runExpr(pair[1]);
            assertEquivalent(failingBranch, harmlessBranch);
        }
    }

    @Test
    void generatedProgramsAreInvariantAcrossColdMissAndCacheHit() {
        Random random = new Random(0xBEE2204L);
        BexEngine cached = BexEngine.builder()
                .cache(new LruBexCompiledProgramCache())
                .build();

        for (int example = 0; example < 32; example++) {
            int width = 2 + random.nextInt(8);
            Object[] operands = new Object[width];
            for (int index = 0; index < width; index++) {
                operands[index] = random.nextInt(2_000_001)
                        - 1_000_000L;
            }
            BexProgramSource source = BexProgramSource.expression(
                    frozen(op("$add", list(operands))));
            BexExecutionResult cold = BexEngine.builder().build()
                    .compileAndExecute(source, defaultContext());
            BexExecutionResult miss = cached.compileAndExecute(
                    source, defaultContext());
            BexExecutionResult hit = cached.compileAndExecute(
                    source, defaultContext());

            assertEquivalent(cold, miss);
            assertEquivalent(miss, hit);
        }
    }

    @Test
    void malformedBlueOutputFuzzFailsBeforeIdentityEstablishment() {
        String blueId = FrozenNode.fromResolvedNode(
                new Node().value("target")).blueId();
        Random random = new Random(0xBEE2205L);

        for (int example = 0; example < 60; example++) {
            String sibling = "field-" + random.nextInt(10_000);
            Object malformed;
            switch (example % 6) {
                case 0:
                    malformed = m(
                            "blueId", blueId,
                            sibling, example);
                    break;
                case 1:
                    malformed = m(
                            "value", example,
                            sibling, example + 1);
                    break;
                case 2:
                    malformed = m(
                            "properties", m(sibling, example));
                    break;
                case 3:
                    malformed = m(
                            "schema", m(
                                    "unsupported-" + example,
                                    true));
                    break;
                case 4:
                    malformed = m("blue", "forbidden-" + example);
                    break;
                default:
                    malformed = Collections.singletonList(m(
                            "$pos", example,
                            "value", example));
                    break;
            }

            BexGasMeter gas = new BexGasMeter(
                    BexGasSchedule.defaults(),
                    1_000_000L);
            AtomicInteger identityCalls = new AtomicInteger();
            BexOutputAdmission admission = new BexOutputAdmission(
                    gas,
                    node -> {
                        identityCalls.incrementAndGet();
                        return BexSemanticIdentityBoundary.STANDALONE
                                .establishIdentity(node);
                    });

            assertThrows(BexException.class,
                    () -> admission.admit(
                            BexValues.fromSimple(malformed),
                            BexOutputKind.ROOT_RESULT));
            assertEquals(0, identityCalls.get(),
                    "malformed output reached identity boundary");
            assertEquals(1, gas.trace().size());
            assertEquals(BexGasCounter.BLUE_OUTPUT_BOUNDARY,
                    gas.trace().get(0).counter());
        }
    }

    @Test
    void generatedInlineAndReferenceCursorsRemainRepresentationBlind() {
        Map<String, Node> providerNodes =
                new LinkedHashMap<String, Node>();
        try (TestBlue blue = new TestBlue(blueId -> {
            Node provided = providerNodes.get(blueId);
            return provided != null
                    ? Collections.singletonList(provided.clone())
                    : Collections.<Node>emptyList();
        })) {
            BexEngine engine = BexEngine.builder()
                    .language(blue.runtime())
                    .build();
            Node observation = obj(
                    "identity", op("$nodeBlueId",
                            op("$binding", "subject")),
                    "kind", op("$kind",
                            op("$binding", "subject")),
                    "nested", op("$pointerGet", obj(
                            "object", op("$binding", "subject"),
                            "path", "/nested/value")),
                    "same", op("$eq", list(
                            op("$binding", "subject"),
                            op("$binding", "peer"))),
                    "size", op("$size",
                            op("$binding", "subject")));
            BexProgramSource source = BexProgramSource.expression(
                    frozen(observation));
            BexCompiledProgram program = engine.compile(source);

            for (int example = 0; example < 32; example++) {
                Node logical = blue.resolveToSnapshot(obj(
                        "nested", obj(
                                "value", "value-" + example),
                        "number", example,
                        "values", list(example, example + 1)))
                        .frozenResolvedRoot()
                        .toNode();
                ExactNodeGraphFragments graph =
                        ExactNodeGraphFragments.split(
                                logical,
                                Collections.singletonList(""));
                ExactNodeGraphFragments.RootRepresentation root =
                        graph.roots().get(0);
                providerNodes.putAll(graph.fragments());
                FrozenNode materialized = FrozenNode.fromResolvedNode(
                        root.original());
                String blueId = root.blueId();
                FrozenNode reference = FrozenNode.fromNode(
                        root.pureReference());
                BexValue inline = BexValues.exact(
                        FrozenNode.fromNode(root.original()),
                        materialized,
                        blueId);
                BexValue referenced = BexValues.exact(
                        reference, materialized, blueId);

                BexExecutionResult inlineResult = engine.execute(
                        program,
                        exactContext(inline));
                BexExecutionResult referenceResult = engine.execute(
                        program,
                        exactContext(referenced));

                assertEquivalent(inlineResult, referenceResult);
                assertEquals(blueId,
                        valueMap(inlineResult).get("identity"));
                assertEquals(true,
                        valueMap(inlineResult).get("same"));
            }
        }
    }

    private static BexExecutionContext defaultContext() {
        return BexExecutionContext.builder()
                .document(defaultDocumentView())
                .gasLimit(10_000_000L)
                .build();
    }

    private static BexExecutionContext exactContext(
            BexValue value) {
        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(
                        FrozenNode.fromResolvedNode(new Node())))
                .binding("subject", value)
                .binding("peer", value)
                .gasLimit(10_000_000L)
                .build();
    }

    private static void assertEquivalent(
            BexExecutionResult left,
            BexExecutionResult right) {
        assertNotNull(left.output());
        assertNotNull(right.output());
        assertEquals(left.value().toSimple(),
                right.value().toSimple());
        assertEquals(left.output().nodeBlueId(),
                right.output().nodeBlueId());
        assertEquals(left.gasLedger(), right.gasLedger());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> valueMap(
            BexExecutionResult result) {
        return (Map<String, Object>) result.value().toSimple();
    }

    private static String unicode(
            Random random,
            int[] alphabet,
            int length) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < length; index++) {
            value.appendCodePoint(alphabet[
                    random.nextInt(alphabet.length)]);
        }
        return value.toString();
    }
}
