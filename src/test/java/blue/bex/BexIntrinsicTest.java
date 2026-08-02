package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgramCache;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexAdmittedValue;
import blue.bex.output.BexEstablishedIdentity;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.test.TestBlue;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.bex.test.BexTestFixtures.bi;
import static blue.bex.test.BexTestFixtures.defaultContext;
import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.simple;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexIntrinsicTest {
    private static final TestBlue BLUE = new TestBlue();
    private static final String ECHO_BLUE_ID = "TestIntrinsicEcho";
    private static final String GAS_BLUE_ID = "TestIntrinsicGas";
    private static final String TEST_REGISTRY_IDENTITY = "test-intrinsics/1";
    private static final String CONSTANT_WORK = "constantWork";
    private static final Map<String, Long> CONSTANT_WORK_WEIGHTS =
            Collections.singletonMap(CONSTANT_WORK, 1L);

    @Test
    void registeredIntrinsicReceivesBlueIdAndEvaluatedFields() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        ECHO_BLUE_ID,
                        TEST_REGISTRY_IDENTITY,
                        Collections.singletonMap("echoWork", 1L),
                        invocation -> {
                    invocation.charge("echoWork", 7, "test-echo");
                    Map<String, BexValue> out = new LinkedHashMap<>();
                    out.put("intrinsicBlueId",
                            BexValues.scalar(invocation.blueId()));
                    out.put("payload", invocation.field("payload"));
                    out.put("missingIsUndefined", BexValues.scalar(invocation.field("missing").isUndefined()));
                    out.put("fieldCount", BexValues.scalar(invocation.fields().size()));
                    return BexValues.map(out);
                })
                .build();

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho",
                "    payload:",
                "      $concat:",
                "        - hel",
                "        - lo",
                "    omitted:",
                "      $document: /missing"), defaultContext());

        assertEquals(m("intrinsicBlueId", ECHO_BLUE_ID,
                "payload", "hello",
                "missingIsUndefined", true,
                "fieldCount", bi(1)), simple(result.value()));
        assertTrue(result.gasUsed() >= 7);
    }

    @Test
    void intrinsicCanBeRegisteredByAnnotatedTypeClass() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        AnnotatedEchoIntrinsic.class,
                        TEST_REGISTRY_IDENTITY,
                        CONSTANT_WORK_WEIGHTS,
                        invocation -> {
                            invocation.charge(
                                    CONSTANT_WORK, 1L, "annotated-echo");
                            return invocation.field("payload");
                        })
                .build();

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho",
                "    payload: ok"), defaultContext());

        assertEquals("ok", simple(result.value()));
    }

    @Test
    void intrinsicTypeClassRegistrationRequiresTypeBlueId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> BexEngine.builder()
                .intrinsic(
                        UnannotatedIntrinsic.class,
                        TEST_REGISTRY_IDENTITY,
                        CONSTANT_WORK_WEIGHTS,
                        invocation -> BexValues.scalar(true)));

        assertTrue(ex.getMessage().contains("@TypeBlueId"));
    }

    @Test
    void duplicateIntrinsicBlueIdIsRejectedInsteadOfReplacingRegistration() {
        BexEngine.Builder builder = BexEngine.builder()
                .intrinsic(
                        ECHO_BLUE_ID,
                        "first-registry",
                        Collections.singletonMap("first", 1L),
                        invocation -> BexValues.scalar("first"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> builder.intrinsic(
                        ECHO_BLUE_ID,
                        "second-registry",
                        Collections.singletonMap("second", 1L),
                        invocation -> BexValues.scalar("second")));

        assertTrue(failure.getMessage().contains(ECHO_BLUE_ID));
        assertTrue(failure.getMessage().contains("already registered"));
    }

    @Test
    void intrinsicCatalogsRejectEmptyAndNonPositiveWeightsAtEveryBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> blue.bex.api.BexIntrinsicRegistry.builder()
                        .register(
                                ECHO_BLUE_ID,
                                TEST_REGISTRY_IDENTITY,
                                Collections.emptyMap(),
                                invocation -> BexValues.scalar(true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> blue.bex.api.BexIntrinsicRegistry.builder()
                        .register(
                                ECHO_BLUE_ID,
                                TEST_REGISTRY_IDENTITY,
                                Collections.singletonMap("work", 0L),
                                invocation -> BexValues.scalar(true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> blue.bex.api.BexIntrinsicRegistry.builder()
                        .register(
                                ECHO_BLUE_ID,
                                TEST_REGISTRY_IDENTITY,
                                Collections.singletonMap("work", -1L),
                                invocation -> BexValues.scalar(true)));

        String qualified = BexGasMeter.qualifiedCounterName(
                "intrinsic-" + ECHO_BLUE_ID, "work");
        assertThrows(
                IllegalArgumentException.class,
                () -> new BexGasMeter(
                        BexGasSchedule.defaults(),
                        100L,
                        BexGasMeter.NO_LOCAL_LIMIT,
                        Collections.singletonMap(qualified, 0L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BexGasMeter(
                        BexGasSchedule.defaults(),
                        100L,
                        BexGasMeter.NO_LOCAL_LIMIT,
                        Collections.singletonMap(qualified, -1L)));
    }

    @Test
    void constantIntrinsicChargesItsDeclaredNamedCounterBeforeWork() {
        AtomicInteger completedWork = new AtomicInteger();
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        ECHO_BLUE_ID,
                        TEST_REGISTRY_IDENTITY,
                        CONSTANT_WORK_WEIGHTS,
                        invocation -> {
                            invocation.charge(
                                    CONSTANT_WORK,
                                    1L,
                                    "constant-intrinsic-work");
                            completedWork.incrementAndGet();
                            return BexValues.scalar(true);
                        })
                .build();

        BexExecutionResult result = engine.compileAndExecute(
                source(
                        "type: Blue/BEX Program",
                        "expr:",
                        "  $intrinsic:",
                        "    type:",
                        "      blueId: TestIntrinsicEcho"),
                defaultContext());

        assertEquals(1, completedWork.get());
        assertEquals(
                1L,
                result.gasLedger().quantity(
                        "intrinsic-" + ECHO_BLUE_ID,
                        CONSTANT_WORK));
        assertEquals(
                1L,
                result.gasLedger().quantity(
                        BexGasCounter.INTRINSIC_CALLED));
    }

    @Test
    void registryIdentityEncodingAndRuntimeNamespacesAreUnambiguous() {
        blue.bex.api.BexIntrinsicRegistry first =
                blue.bex.api.BexIntrinsicRegistry.builder()
                        .register(
                                "type",
                                "r:n",
                                "s",
                                Collections.singletonMap("work", 1L),
                                invocation -> BexValues.scalar(true))
                        .build();
        blue.bex.api.BexIntrinsicRegistry second =
                blue.bex.api.BexIntrinsicRegistry.builder()
                        .register(
                                "type",
                                "r",
                                "n:s",
                                Collections.singletonMap("work", 1L),
                                invocation -> BexValues.scalar(true))
                        .build();

        assertNotEquals(first.identity(), second.identity());
        assertThrows(
                IllegalArgumentException.class,
                () -> blue.bex.api.BexIntrinsicRegistry.builder()
                        .register(
                                "type",
                                "registry",
                                "nested/namespace",
                                Collections.singletonMap("work", 1L),
                                invocation -> BexValues.scalar(true)));
    }

    @Test
    void intrinsicCounterCatalogAndInvocationFieldsAreImmutableSnapshots() {
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put("work", 3L);
        AtomicInteger invocations = new AtomicInteger();
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        ECHO_BLUE_ID,
                        TEST_REGISTRY_IDENTITY,
                        weights,
                        invocation -> {
                            invocation.charge(
                                    "work", 1L, "immutable-snapshot-work");
                            invocations.incrementAndGet();
                            assertEquals(
                                    Collections.singletonMap("work", 3L),
                                    invocation.namedCounterWeights());
                            assertThrows(
                                    UnsupportedOperationException.class,
                                    () -> invocation.namedCounterWeights()
                                            .put("late", 1L));
                            assertThrows(
                                    UnsupportedOperationException.class,
                                    () -> invocation.fields()
                                            .put("late", BexValues.scalar(true)));
                            return invocation.field("payload");
                        })
                .build();
        weights.clear();
        weights.put("mutated", 99L);

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho",
                "    payload: stable"), defaultContext());

        assertEquals("stable", simple(result.value()));
        assertEquals(1, invocations.get());
    }

    @Test
    void intrinsicExactFieldUsesSharedSemanticAdmissionAndMemoization() {
        AtomicInteger boundaryCalls = new AtomicInteger();
        BexSemanticIdentityBoundary boundary = node -> {
            boundaryCalls.incrementAndGet();
            Node exact = node.clone();
            return new BexEstablishedIdentity(
                    DirectBlueIdCalculator.calculateBlueId(exact),
                    FrozenNode.fromResolvedNode(exact));
        };
        BexValue[] exactFromIntrinsic = new BexValue[1];
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        ECHO_BLUE_ID,
                        TEST_REGISTRY_IDENTITY,
                        CONSTANT_WORK_WEIGHTS,
                        invocation -> {
                            BexAdmittedValue first =
                                    invocation.exactField("payload");
                            BexAdmittedValue repeated =
                                    invocation.exactField("payload");
                            assertSame(first, repeated);
                            assertTrue(first.value().isExact());
                            exactFromIntrinsic[0] = first.value();
                            return first.value();
                        })
                .build();
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .semanticIdentityBoundary(boundary)
                .gasLimit(1_000_000L)
                .build();

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho",
                "    payload:",
                "      message: admitted"), context);

        assertEquals(1, boundaryCalls.get());
        assertSame(exactFromIntrinsic[0], result.value());
        assertTrue(result.value().isExact());
        assertEquals(
                result.output().nodeBlueId(),
                result.value().exactBlueId());
        assertEquals(
                m("message", "admitted"),
                simple(result.value()));
    }

    @Test
    void rejectedNamedChargePreventsAllLaterIntrinsicWork() {
        Map<String, Long> weights =
                Collections.singletonMap("work", 7L);
        AtomicInteger workAfterCharge = new AtomicInteger();
        blue.bex.api.BexIntrinsicRegistry registry =
                blue.bex.api.BexIntrinsicRegistry.builder()
                        .register(
                                GAS_BLUE_ID,
                                TEST_REGISTRY_IDENTITY,
                                weights,
                                invocation -> {
                                    invocation.charge(
                                            "work", 2L, "exact-exhaustion");
                                    workAfterCharge.incrementAndGet();
                                    return BexValues.scalar(true);
                                })
                        .build();
        BexGasMeter gas = new BexGasMeter(
                BexGasSchedule.defaults(),
                13L,
                BexGasMeter.NO_LOCAL_LIMIT,
                registry.registeredNamedWeights());
        BexOutputAdmission admission = new BexOutputAdmission(
                gas, BexSemanticIdentityBoundary.STANDALONE);

        BexGasLimitExceededException failure = assertThrows(
                BexGasLimitExceededException.class,
                () -> registry.invoke(
                        GAS_BLUE_ID,
                        BexValues.map(Collections.emptyMap()),
                        Collections.emptyMap(),
                        gas,
                        admission));

        assertEquals("intrinsic-" + GAS_BLUE_ID, failure.namespace());
        assertEquals("work", failure.counterName());
        assertEquals(2L, failure.quantity());
        assertEquals(7L, failure.weight());
        assertEquals(0L, failure.admittedGas());
        assertEquals(13L, failure.effectiveBudget());
        assertEquals(0, workAfterCharge.get());
        assertEquals(0L, gas.totalGas());
        assertTrue(gas.trace().isEmpty());
    }

    @Test
    void intrinsicTypeCanBeInlineBlueTypeDefinition() {
        Node typeNode = BLUE.yamlToNode(yaml(
                "description: Test intrinsic operation shape",
                "x:",
                "  type: Text"));
        String inlineBlueId = FrozenNode.fromResolvedNode(typeNode).blueId();
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        inlineBlueId,
                        TEST_REGISTRY_IDENTITY,
                        CONSTANT_WORK_WEIGHTS,
                        invocation -> {
                    invocation.charge(
                            CONSTANT_WORK, 1L, "inline-type-work");
                    Map<String, BexValue> out = new LinkedHashMap<>();
                    out.put("intrinsicBlueId",
                            BexValues.scalar(invocation.blueId()));
                    out.put("x", invocation.field("x"));
                    return BexValues.map(out);
                })
                .build();

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      description: Test intrinsic operation shape",
                "      x:",
                "        type: Text",
                "    x:",
                "      $literal: ok"), defaultContext());

        assertEquals(
                m("intrinsicBlueId", inlineBlueId, "x", "ok"),
                simple(result.value()));
    }

    @Test
    void unsupportedIntrinsicFailsAtCompileTime() {
        BexException ex = assertThrows(BexException.class, () -> BexEngine.builder().build().compile(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho")));

        assertTrue(ex.getMessage().contains("Unsupported intrinsic BlueId: " + ECHO_BLUE_ID));
    }

    @Test
    void cachedProgramStillRequiresSupportInCurrentEngine() {
        BexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        BexProgramSource source = source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho");
        BexEngine withSupport = BexEngine.builder()
                .cache(cache)
                .intrinsic(
                        ECHO_BLUE_ID,
                        TEST_REGISTRY_IDENTITY,
                        CONSTANT_WORK_WEIGHTS,
                        invocation -> {
                            invocation.charge(
                                    CONSTANT_WORK, 1L, "cached-program-work");
                            return BexValues.scalar(true);
                        })
                .build();
        withSupport.compile(source);

        BexEngine withoutSupport = BexEngine.builder()
                .cache(cache)
                .build();
        BexException ex = assertThrows(BexException.class, () -> withoutSupport.compile(source));
        assertTrue(ex.getMessage().contains("Unsupported intrinsic BlueId: " + ECHO_BLUE_ID));
    }

    @Test
    void intrinsicProcessorGasChargeIsEnforced() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        GAS_BLUE_ID,
                        TEST_REGISTRY_IDENTITY,
                        Collections.singletonMap("gasWork", 1L),
                        invocation -> {
                    invocation.charge("gasWork", 25, "test-limit");
                    return BexValues.scalar(true);
                })
                .build();
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .gasLimit(10)
                .build();

        BexException ex = assertThrows(BexException.class, () -> engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicGas"), context));

        assertTrue(ex.getMessage().contains("BEX gas exhausted"));
    }

    @Test
    void intrinsicTypeMustBeStatic() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        ECHO_BLUE_ID,
                        TEST_REGISTRY_IDENTITY,
                        CONSTANT_WORK_WEIGHTS,
                        invocation -> {
                            invocation.charge(
                                    CONSTANT_WORK, 1L, "static-type-work");
                            return BexValues.scalar(true);
                        })
                .build();
        Node program = stepExpr(op("$intrinsic", obj(
                "type", obj("blueId", op("$concat", list("TestIntrinsic", "Echo"))))));

        BexException ex = assertThrows(BexException.class,
                () -> engine.compile(BexProgramSource.inline(frozen(program))));

        assertTrue(ex.getMessage().contains("$intrinsic.type"));
    }

    private static BexProgramSource source(String... lines) {
        Node node = BLUE.yamlToNode(yaml(lines));
        return BexProgramSource.inline(FrozenNode.fromResolvedNode(node));
    }

    private static String yaml(String... lines) {
        return String.join("\n", lines);
    }

    @TypeBlueId(ECHO_BLUE_ID)
    private static final class AnnotatedEchoIntrinsic {
    }

    private static final class UnannotatedIntrinsic {
    }
}
