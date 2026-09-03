package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexAdmittedValue;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexOutputKind;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetricsRecorder;
import blue.bex.result.BexPatchEntry;
import blue.bex.result.BexResultOverlay;
import blue.bex.runtime.BexExecutionAccumulator;
import blue.bex.runtime.BexRuntime;
import blue.bex.test.TestBlue;
import blue.bex.value.BexBlueNodeWriter;
import blue.bex.value.BexNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.bex.test.BexTestFixtures.defaultContext;
import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexBlueOutputNullBoundaryTest {

    @Test
    void cBexNull02RecursivelyNormalizesNullByStructuralPosition() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("omitted", null);
        source.put("empty", Collections.emptyMap());
        source.put("nested", Collections.singletonMap("omitted", null));
        source.put("values", Arrays.asList(
                null,
                Collections.emptyMap(),
                Collections.emptyList()));
        source.put("type", null);
        source.put("request", null);

        Node output = BexBlueNodeWriter.toNode(
                BexValues.fromSimple(source));

        assertNull(output.getType());
        assertNotNull(output.getProperties());
        assertFalse(output.getProperties().containsKey("omitted"));
        assertFalse(output.getProperties().containsKey("request"));
        assertExactEmptyObject(output.getProperties().get("empty"));
        assertExactEmptyObject(output.getProperties().get("nested"));

        Node values = output.getProperties().get("values");
        assertEquals(3, values.getItems().size());
        assertTrue(Nodes.isEmptyPlaceholder(values.getItems().get(0)));
        assertExactEmptyObject(values.getItems().get(1));
        assertFalse(Nodes.isEmptyPlaceholder(values.getItems().get(1)));
        assertNotNull(values.getItems().get(2).getItems());
        assertTrue(values.getItems().get(2).getItems().isEmpty());
    }

    @Test
    void cBexNull03ExplicitPlaceholderConvergesWithListNullButEmptyObjectDoesNot() {
        BexValue values = BexValues.fromSimple(Arrays.asList(
                null,
                Collections.singletonMap("$empty", true),
                Collections.emptyMap()));

        Node output = BexNodeWriter.toNode(values);

        assertTrue(Nodes.isEmptyPlaceholder(output.getItems().get(0)));
        assertTrue(Nodes.isEmptyPlaceholder(output.getItems().get(1)));
        assertFalse(Nodes.isEmptyPlaceholder(output.getItems().get(2)));
    }

    @Test
    void cBexNull04RetainsNestedContainerAndRejectsScalarTypedEmptyObject() {
        Node admitted = BexBlueNodeWriter.toNode(BexValues.fromSimple(
                Collections.singletonMap("x", Collections.singletonMap(
                        "y", null))));

        Node x = admitted.getProperties().get("x");
        assertExactEmptyObject(x);

        try (BlueLanguage blue = BlueLanguage.builder().build()) {
            Node scalarTyped = x.clone().type(
                    new Node().blueId(TEXT_TYPE_BLUE_ID));

            assertThrows(RuntimeException.class,
                    () -> blue.snapshots().resolve(scalarTyped));
        }
    }

    @Test
    void nullReservedAndForbiddenMembersAreOmittedBeforeValidation() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("blueId", null);
        source.put("blue", null);
        source.put("properties", null);
        source.put("constraints", null);
        source.put("schema", Collections.singletonMap("unknown", null));

        Node output = BexNodeWriter.toNode(
                BexValues.fromSimple(source));

        assertNull(output.getBlueId());
        assertNull(output.getBlue());
        assertNull(output.getProperties());
        assertNotNull(output.getSchema());
    }

    @Test
    void emptyTypeObjectIsPresentWhileNullTypeIsAbsent() {
        Node absentType = BexNodeWriter.toNode(BexValues.fromSimple(
                Collections.singletonMap("type", null)));
        Node emptyType = BexNodeWriter.toNode(BexValues.fromSimple(
                Collections.singletonMap("type", Collections.emptyMap())));

        assertNull(absentType.getType());
        assertNotNull(emptyType.getType());
        assertExactEmptyObject(emptyType.getType());
        assertFalse(emptyType.getType().isInlineValue());
    }

    @Test
    void rootNullAndUndefinedFailEveryExactBlueAdmissionKind() {
        AtomicInteger identityCalls = new AtomicInteger();
        BexOutputAdmission admission = new BexOutputAdmission(
                new BexGasMeter(BexGasSchedule.defaults(), 1_000_000L),
                node -> {
                    identityCalls.incrementAndGet();
                    throw new AssertionError("identity boundary must not run");
                });

        for (BexOutputKind kind : BexOutputKind.values()) {
            BexException nullFailure = assertThrows(
                    BexException.class,
                    () -> admission.admit(BexValues.nullValue(), kind));
            assertTrue(nullFailure.getMessage().contains("root value is null"));
            assertThrows(BexException.class,
                    () -> admission.admit(BexValues.undefined(), kind));
        }

        assertEquals(0, identityCalls.get());
        assertEquals(0L, admission.semanticIdentityMergeCount());
    }

    @Test
    void nullPatchAndEventFailBeforeAccumulatorMutation() {
        BexOutputAdmission admission = new BexOutputAdmission(
                new BexGasMeter(BexGasSchedule.defaults(), 1_000_000L),
                null);
        BexExecutionAccumulator accumulator = new BexExecutionAccumulator(
                new BexResultOverlay(
                        defaultDocumentView(),
                        new BexMetricsRecorder()),
                admission);

        assertThrows(BexException.class, () -> accumulator.appendChange(
                new BexPatchEntry(
                        "replace",
                        "/state",
                        "/state",
                        BexValues.nullValue())));
        assertTrue(accumulator.changeset().entries().isEmpty());

        assertThrows(BexException.class,
                () -> accumulator.appendEvent(BexValues.nullValue()));
        assertTrue(accumulator.events().events().isEmpty());
        assertTrue(accumulator.events().admittedEvents().isEmpty());
    }

    @Test
    void cBexNull01FailureDiscardsPreviouslyBufferedPatchAndEvent() {
        Node program = obj(
                "type", "Blue/BEX Program",
                "do", list(
                        op("$appendChange", obj(
                                "op", "replace",
                                "path", "/state",
                                "val", "buffered")),
                        op("$appendEvent", obj("kind", "buffered")),
                        op("$appendEvent", op("$null", true))));

        try (BlueLanguage blue = BlueLanguage.builder().build();
             BexEngine engine = BexEngine.builder().language(blue).build()) {
            BexCompiledProgram compiled = engine.compile(
                    BexProgramSource.inline(frozen(program)));
            BexRuntime runtime = new BexRuntime(
                    compiled,
                    defaultContext(),
                    blue,
                    BexGasSchedule.defaults(),
                    new BexMetricsRecorder(),
                    new BexPointerCache());

            assertThrows(BexException.class, runtime::execute);
            assertTrue(runtime.accumulator().changeset().entries().isEmpty());
            assertTrue(runtime.accumulator().events().events().isEmpty());
            assertTrue(runtime.accumulator().events().admittedEvents().isEmpty());
        }
    }

    @Test
    void compiledNullAndEmptyObjectStayDistinctUntilBlueAdmission() {
        TestBlue blue = new TestBlue();
        Node program = blue.parseSourceYaml(String.join("\n",
                "type: Blue/BEX Program",
                "expr:",
                "  nil:",
                "    $null: true",
                "  empty: {}"));

        BexExecutionResult result = BexEngine.builder().build()
                .compileAndExecute(
                        BexProgramSource.inline(frozen(program)),
                        defaultContext());

        assertTrue(result.value().get("nil").isNull());
        assertTrue(result.value().get("empty").isObject());
        assertFalse(result.output().node().getProperties()
                .containsKey("nil"));
        assertExactEmptyObject(result.output().node()
                .getProperties().get("empty"));
    }

    @Test
    void nullRootAndNodeBlueIdFailThroughTheRuntime() {
        assertThrows(BexException.class, () -> BexEngine.builder().build()
                .compileAndExecute(
                        BexProgramSource.inline(frozen(
                                stepExpr(op("$null", true)))),
                        defaultContext()));

        BexException identityFailure = assertThrows(
                BexException.class,
                () -> BexEngine.builder().build().compileAndExecute(
                        BexProgramSource.inline(frozen(stepExpr(
                                op("$nodeBlueId",
                                        op("$null", true))))),
                        defaultContext()));
        assertTrue(identityFailure.getMessage().contains(
                "root value is null"));
    }

    @Test
    void nullExactIntrinsicInputFailsBeforeProcessorCanUseIt() {
        AtomicInteger processorCalls = new AtomicInteger();
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        "TestExactNullIntrinsic",
                        "test-exact-null/1",
                        Collections.singletonMap("work", 1L),
                        invocation -> {
                            processorCalls.incrementAndGet();
                            invocation.exactField("payload");
                            return BexValues.scalar("unreachable");
                        })
                .build();
        Node program = stepExpr(op("$intrinsic", obj(
                "type", obj("blueId", "TestExactNullIntrinsic"),
                "payload", op("$null", true))));

        assertThrows(BexException.class, () -> engine.compileAndExecute(
                BexProgramSource.inline(frozen(program)),
                defaultContext()));
        assertEquals(1, processorCalls.get());
    }

    @Test
    void exactEmptyBlueNodeIsAnObjectRatherThanBexNull() {
        BexValue exactEmpty = BexValues.nodeSnapshot(Nodes.emptyObject());

        assertTrue(exactEmpty.isExact());
        assertTrue(exactEmpty.isObject());
        assertFalse(exactEmpty.isNull());
        assertEquals("object", BexValues.kind(exactEmpty));
        assertEquals(Collections.emptyMap(), exactEmpty.toSimple());
    }

    private static void assertExactEmptyObject(Node node) {
        assertNotNull(node);
        assertNotNull(node.getProperties());
        assertTrue(node.getProperties().isEmpty());
        assertFalse(node.isInlineValue());
    }
}
