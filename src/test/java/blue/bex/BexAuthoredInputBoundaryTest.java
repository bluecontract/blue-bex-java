package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexFailureBoundary;
import blue.bex.api.BexProgramSource;
import blue.bex.contracts.BexContractsFailureBoundary;
import blue.bex.gas.BexGasCounter;
import blue.bex.result.BexExecutionResult;
import blue.bex.test.TestBlue;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.value.BexBlueNodeWriter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexAuthoredInputBoundaryTest {
    private static final BigInteger HUGE = BigInteger.TEN.pow(60);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

    @Test
    void malformedSchemaKeywordValuesAreDeterministicAtEveryRealOutputBoundary() {
        for (String keyword : Arrays.asList("required", "uniqueItems", "minLength", "maxLength",
                "minItems", "maxItems", "minFields", "maxFields", "minimum", "maximum",
                "exclusiveMinimum", "exclusiveMaximum", "multipleOf")) {
            for (Node invalid : Arrays.asList(v("bogus"), list(true), obj("unexpected", true))) {
                assertInvalidSchemaOutput(dynamicSchema(keyword, invalid));
            }
        }
        for (String keyword : Arrays.asList("minLength", "maxLength", "minItems", "maxItems", "minFields", "maxFields")) {
            assertInvalidSchemaOutput(dynamicSchema(keyword, new Node().value(new BigDecimal("0.5"))));
        }
        assertInvalidSchemaOutput(obj("child", dynamicSchema("required", "bogus")));
    }

    @Test
    void legalSchemaValuesAndExactReferencesRetainTheirOutputIdentity() {
        for (Node legal : Arrays.asList(dynamicSchema("required", false), dynamicSchema("uniqueItems", true),
                dynamicSchema("minItems", new Node().value(new BigDecimal("2.00"))),
                dynamicSchema("minimum", new Node().value(new BigDecimal("0.5"))))) {
            BexExecutionResult identity = runExpr(op("$nodeBlueId", legal));
            assertNotNull(identity.value().asText());
            assertEquals(1, runStep(stepDo(list(op("$appendEvent", legal))), defaultContext()).events().events().size());
        }
        BexValue exactSchema = BexValues.frozen(frozen(obj("minimum", new Node().value(new BigDecimal("0.5")))));
        BexExecutionContext context = BexExecutionContext.builder().document(defaultDocumentView())
                .binding("schema", exactSchema).build();
        Node fromExact = obj("schema", op("$binding", "schema"));
        Node fromReference = obj("schema", dynamicReference(exactSchema.exactBlueId()));
        assertEquals(runExpr(op("$nodeBlueId", fromReference)).value().asText(),
                runStep(stepExpr(op("$nodeBlueId", fromExact)), context).value().asText());
    }

    @Test
    void numericSchemaKeywordRetainsItsLegalExactReference() {
        BexValue minimum = BexValues.frozen(frozen(new Node().value(new BigDecimal("0.5"))));
        BexExecutionContext context = BexExecutionContext.builder().document(defaultDocumentView())
                .binding("minimum", minimum).build();
        Node exact = dynamicSchema("minimum", op("$binding", "minimum"));
        Node reference = dynamicSchema("minimum", dynamicReference(minimum.exactBlueId()));
        assertEquals(runExpr(op("$nodeBlueId", reference)).value().asText(),
                runStep(stepExpr(op("$nodeBlueId", exact)), context).value().asText());
    }

    @Test
    void schemaNumericRepresentationFailureRemainsUnclassified() {
        BigDecimal oversized = new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE);
        // BEX emits an explicit Double node: its ordinary representation check
        // rejects this value before the schema's exact-integer getter is reached.
        RuntimeException representationFailure = assertThrows(RuntimeException.class,
                () -> blue.language.model.Nodes.doubleNode(oversized).getValue());
        BexValue output = BexValues.fromSimple(Collections.singletonMap("schema",
                Collections.singletonMap("minItems", oversized)));
        BexException failure = assertThrows(BexException.class, () -> BexBlueNodeWriter.toNode(output));
        assertEquals(representationFailure.getClass(), failure.getCause().getClass());
        assertEquals(representationFailure.getMessage(), failure.getCause().getMessage());
        assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED, BexFailureBoundary.STANDALONE.classify(failure));
        assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED, BexContractsFailureBoundary.INSTANCE.classify(failure));
    }

    private static Node dynamicSchema(String keyword, Object value) {
        Node schema = op("$objectSet", obj("object", op("$emptyObject", true), "key", keyword, "val", value));
        return op("$objectSet", obj("object", op("$emptyObject", true), "key", "schema", "val", schema));
    }

    private static void assertInvalidSchemaOutput(Node expression) {
        assertDeterministic(assertThrows(BexException.class,
                () -> runExpr(op("$nodeBlueId", expression))));
        assertDeterministic(assertThrows(BexException.class, () -> runStep(
                stepDo(list(op("$appendEvent", expression))), defaultContext())));
        assertDeterministic(assertThrows(BexException.class, () -> runStep(
                stepDo(list(op("$appendChange", obj("op", "replace", "path", "/status", "val", expression)))),
                defaultContext())));
    }

    @Test
    void dynamicallyConstructedInvalidReferencesAreDeterministicAtRealOutputBoundaries() {
        String master = DirectBlueIdCalculator.calculateBlueId(v("master"));
        for (String invalid : Arrays.asList("", "0", "1234", "this#0",
                master + "#01", master + "#-1", master + "#0")) {
            Node reference = dynamicReference(invalid);
            assertDeterministic(assertThrows(BexException.class, () -> runStep(
                    stepDo(list(op("$appendEvent", reference))), defaultContext())));
            assertDeterministic(assertThrows(BexException.class, () -> runExpr(
                    op("$nodeBlueId", reference))));
            assertDeterministic(assertThrows(BexException.class, () -> runStep(
                    stepDo(list(op("$appendChange", obj(
                            "op", "replace", "path", "/status", "val", reference)))),
                    defaultContext())));
        }
    }

    @Test
    void dynamicSchemaReferenceValidationIsAlsoAnAuthoredBoundary() {
        assertDeterministic(assertThrows(BexException.class, () -> runExpr(
                op("$nodeBlueId", obj("schema", dynamicReference("0"))))));
    }

    @Test
    void validDynamicReferenceRetainsIdentityButMixedReferenceFails() {
        String identity = DirectBlueIdCalculator.calculateBlueId(v("valid"));
        BexExecutionResult result = runExpr(op("$nodeBlueId", dynamicReference(identity)));
        assertEquals(identity, result.value().asText());
        Node mixed = op("$objectSet", obj("object", dynamicReference(identity),
                "key", "extra", "val", true));
        assertDeterministic(assertThrows(BexException.class, () -> runExpr(
                op("$nodeBlueId", mixed))));
    }

    @Test
    void largeListMissesPreserveDefaultAndTheCompleteReadTrace() {
        BexExecutionResult expected = runExpr(listGet(list(1), BigInteger.ONE, "missing"));
        for (BigInteger index : Arrays.asList(INT_MAX, INT_MAX.add(BigInteger.ONE), HUGE)) {
            BexExecutionResult result = runExpr(listGet(list(1), index, "missing"));
            assertEquals("missing", result.value().asText());
            assertEquals(expected.gasTrace(), result.gasTrace());
            assertEquals(1L, result.gasTrace().stream()
                    .filter(charge -> charge.counter() == BexGasCounter.LIST_ITEM_READ)
                    .mapToLong(charge -> charge.quantity()).sum());
            assertFalse(runExpr(op("$exists", op("$listGet", obj(
                    "list", list(1), "index", index)))).value().asBoolean());
        }
    }

    @Test
    void listDefaultIsLazyAndExactKnownListAbsenceIsEstablished() {
        AtomicInteger defaults = new AtomicInteger();
        BexValue exactList = BexValues.frozen(frozen(list(1)));
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .binding("values", exactList)
                .lazyBinding("fallback", () -> {
                    defaults.incrementAndGet();
                    return BexValues.scalar("missing");
                }).build();
        assertEquals(BigInteger.ONE, runStep(stepExpr(listGet(op("$binding", "values"),
                BigInteger.ZERO, op("$binding", "fallback"))), context).value().asInteger());
        assertEquals(0, defaults.get());
        assertEquals("missing", runStep(stepExpr(listGet(op("$binding", "values"),
                HUGE, op("$binding", "fallback"))), context).value().asText());
        assertEquals(1, defaults.get());
    }

    @Test
    void hugeListIndexDoesNotTurnUnavailableExactEvidenceIntoADefault() {
        String identity = DirectBlueIdCalculator.calculateBlueId(list(1));
        AtomicInteger defaults = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        NodeProvider provider = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String ignored) {
                return Collections.emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String ignored) {
                fetches.incrementAndGet();
                return NodeProviderResult.unavailable("list evidence offline");
            }
        };
        try (TestBlue blue = new TestBlue(provider);
             BexEngine engine = BexEngine.builder().language(blue.runtime()).build()) {
            BexValue reference = BexValues.referenceBacked(BexValues.frozen(
                    FrozenNode.fromNode(new Node().blueId(identity))), blue.runtime());
            BexExecutionContext context = BexExecutionContext.builder()
                    .document(defaultDocumentView()).binding("values", reference)
                    .lazyBinding("fallback", () -> {
                        defaults.incrementAndGet();
                        return BexValues.scalar("missing");
                    }).build();
            BexException failure = assertThrows(BexException.class,
                    () -> engine.compileAndExecute(BexProgramSource.expression(frozen(
                            listGet(op("$binding", "values"), HUGE,
                                    op("$binding", "fallback")))), context));
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertTrue(cause instanceof BexExecutionEvidenceUnavailableException);
            assertEquals(Collections.singletonList(identity),
                    ((BexExecutionEvidenceUnavailableException) cause).requiredExactBlueIds());
            assertEquals(BexFailureBoundary.Classification.EVIDENCE_UNAVAILABLE,
                    BexFailureBoundary.STANDALONE.classify(failure));
            assertEquals(BexFailureBoundary.Classification.EVIDENCE_UNAVAILABLE,
                    BexContractsFailureBoundary.INSTANCE.classify(failure));
            assertTrue(fetches.get() > 0);
            assertEquals(0, defaults.get());
        }
    }

    @Test
    void negativeListIndicesFailDeterministicallyWithoutEvaluatingDefault() {
        for (BigInteger index : Arrays.asList(BigInteger.valueOf(-1), HUGE.negate())) {
            BexException failure = assertThrows(BexException.class, () -> runExpr(
                    listGet(list(1), index, op("$divide", list(1, 0)))));
            assertTrue(failure.getMessage().contains("index must be non-negative"));
            assertDeterministic(failure);
        }
    }

    @Test
    void largeSplitLimitsPreserveTrailingPartsAndTheCompleteProductionTrace() {
        BexExecutionResult expected = runExpr(split("a,b,", BigInteger.valueOf(-1)));
        for (BigInteger limit : Arrays.asList(INT_MAX, INT_MAX.add(BigInteger.ONE), HUGE)) {
            BexExecutionResult result = runExpr(split("a,b,", limit));
            assertEquals(Arrays.asList("a", "b", ""), result.value().toSimple());
            assertEquals(expected.gasTrace(), result.gasTrace());
        }
        assertEquals(Collections.singletonList("a,b,"),
                runExpr(split("a,b,", BigInteger.ONE)).value().toSimple());
        assertEquals(Arrays.asList("a", "b,"),
                runExpr(split("a,b,", BigInteger.valueOf(2))).value().toSimple());
        assertEquals(Collections.singletonList(""), runExpr(split("", HUGE)).value().toSimple());
        assertEquals(Collections.singletonList("abc"), runExpr(split("abc", HUGE)).value().toSimple());
        assertEquals(expected.value().toSimple(), runExpr(op("$split", obj(
                "text", "a,b,", "separator", ","))).value().toSimple());
    }

    @Test
    void invalidSplitLimitsAreDeterministicAtEveryMagnitude() {
        for (BigInteger limit : Arrays.asList(BigInteger.ZERO, BigInteger.valueOf(-2), HUGE.negate())) {
            assertDeterministic(assertThrows(BexException.class, () -> runExpr(split("a,b", limit))));
        }
    }

    private static Node dynamicReference(String identity) {
        return op("$objectSet", obj("object", op("$emptyObject", true),
                "key", "blueId", "val", identity));
    }

    private static Node listGet(Node list, BigInteger index, Object fallback) {
        return op("$listGet", obj("list", list, "index", index, "default", fallback));
    }

    private static Node split(String text, BigInteger limit) {
        return op("$split", obj("text", text, "separator", ",", "limit", limit));
    }

    private static void assertDeterministic(RuntimeException failure) {
        assertEquals(BexFailureBoundary.Classification.DETERMINISTIC,
                BexFailureBoundary.STANDALONE.classify(failure));
        assertEquals(BexFailureBoundary.Classification.DETERMINISTIC,
                BexContractsFailureBoundary.INSTANCE.classify(failure));
    }
}
