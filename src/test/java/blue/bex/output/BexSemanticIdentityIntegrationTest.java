package blue.bex.output;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.contracts.BexContractsFailureBoundary;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetricsRecorder;
import blue.bex.result.BexPatchEntry;
import blue.bex.result.BexResultOverlay;
import blue.bex.runtime.BexExecutionAccumulator;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepDo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexSemanticIdentityIntegrationTest {

    @Test
    void standaloneAndCustomBoundariesReturnTheirFrozenExactResult() {
        BexOutputAdmission standalone = admission(
                BexSemanticIdentityBoundary.STANDALONE);
        BexAdmittedValue standaloneValue = standalone.admit(
                BexValues.scalar("standalone"),
                BexOutputKind.ROOT_RESULT);

        assertTrue(standaloneValue.value().isExact());
        assertEquals(
                standaloneValue.nodeBlueId(),
                DirectBlueIdCalculator.calculateBlueId(
                        standaloneValue.node()));

        Node hostNormalized =
                new Node().name("host-normalized").value("value");
        FrozenNode hostFrozen =
                FrozenNode.fromResolvedNode(hostNormalized);
        String hostBlueId =
                DirectBlueIdCalculator.calculateBlueId(hostNormalized);
        BexSemanticIdentityBoundary hosted = ignored ->
                new BexEstablishedIdentity(hostBlueId, hostFrozen);
        BexAdmittedValue hostedValue = admission(hosted).admit(
                BexValues.scalar("pre-normalized"),
                BexOutputKind.ROOT_RESULT);

        assertEquals("host-normalized", hostedValue.node().getName());
        assertEquals("value", hostedValue.node().getValue());
        assertEquals(hostBlueId, hostedValue.value().exactBlueId());
        assertEquals("host-normalized",
                hostedValue.value().get("name").asText());
        assertEquals("value", hostedValue.value().asText());
        assertTrue(hostedValue.semanticValue().isExact());
    }

    @Test
    void transientOutputRetainsAvailableCanonicalChildContentForHostAdmission() {
        Node workflow = obj("run", obj("steps", list(op("$unknownFutureOperator", true))));
        FrozenNode canonical = FrozenNode.fromNode(workflow);
        FrozenNode differentSemantic = FrozenNode.fromResolvedNode(obj("resolvedOnly", true));
        BexValue exactContracts = BexValues.exact(canonical, differentSemantic, canonical.blueId());
        BexValue constructed = BexValues.fromSimple(map("quantity", 7, "contracts", exactContracts));
        RecordingBoundary boundary = new RecordingBoundary();
        admission(boundary).admit(constructed, BexOutputKind.ROOT_RESULT);
        Node supplied = boundary.inputs.get(0).getContracts();
        assertFalse(supplied.isReferenceOnly());
        assertEquals(canonical.blueId(), DirectBlueIdCalculator.calculateBlueId(supplied));
    }

    @Test
    void returnedEventsRetainTheirInlineTypeSource() {
        BexEngine engine = BexEngine.builder().build();
        Node program = stepDo(list(
                op("$appendEvent", obj("type", obj("name", "Local Event"), "amount", 7)),
                op("$return", op("$events", true))));
        BexExecutionResult result = engine.compileAndExecute(
                BexProgramSource.inline(frozen(program)),
                BexExecutionContext.builder().document(defaultDocumentView()).build());
        assertEquals(1, result.events().events().size());
        assertEquals(result.events().events().get(0).exactBlueId(),
                result.value().at(Collections.singletonList("0")).exactBlueId());
        assertEquals("Local Event", result.value().at(Collections.singletonList("0")).get("type").get("name").asText());
    }

    @Test
    void canonicalPayloadRejectsConflictingTypeSourceEvidence() {
        try (blue.language.runtime.BlueLanguage language =
                blue.language.runtime.BlueLanguage.builder().build()) {
            blue.language.merge.ResolvedSnapshot first = language.snapshots().resolve(
                    new Node().type(new Node().name("First Type"))
                            .properties("amount", new Node().value(7)));
            blue.language.merge.ResolvedSnapshot second = language.snapshots().resolve(
                    new Node().type(new Node().name("Second Type"))
                            .properties("amount", new Node().value(7)));
            BexValue conflicting = BexValues.exact(first.frozenCanonicalRoot(),
                    second.frozenResolvedRoot(), first.blueId(), second.canonicalTypeIdentities());
            BexException failure = assertThrows(BexException.class, () -> admission(
                    BexSemanticIdentityBoundary.STANDALONE).admit(
                    BexValues.fromSimple(map("child", conflicting)), BexOutputKind.ROOT_RESULT));
            assertTrue(failure.getMessage().contains("type Source conflicts"));
        }
    }

    @Test
    void ordinaryNonCyclicExactRootBypassesHostSemanticBoundary() {
        Node content = obj(
                "kind", "ordinary-exact",
                "nested", obj("count", 7));
        FrozenNode frozen =
                FrozenNode.fromResolvedNode(content);
        String blueId =
                DirectBlueIdCalculator.calculateBlueId(content);
        BexValue exact =
                BexValues.exact(frozen, frozen, blueId);
        RecordingBoundary boundary = new RecordingBoundary();
        BexOutputAdmission admission = admission(boundary);

        BexAdmittedValue admitted = admission.admit(
                exact, BexOutputKind.ROOT_RESULT);

        assertEquals(0, boundary.calls);
        assertEquals(0L, admission.semanticIdentityMergeCount());
        assertSame(exact, admitted.value());
        assertSame(exact, admitted.semanticValue());
        assertFalse(admitted.reconstructed());
        assertEquals(blueId, admitted.nodeBlueId());
        assertEquals(blueId, admitted.node().getBlueId());
    }

    @Test
    void admittedScalarsRetainTheirRawKindsEqualityAndTruthiness() {
        RecordingBoundary boundary = new RecordingBoundary();
        BexOutputAdmission admission = admission(boundary);

        assertAdmittedScalar(
                admission,
                BexValues.scalar(false),
                "boolean",
                false);
        assertAdmittedScalar(
                admission,
                BexValues.scalar(BigInteger.valueOf(7L)),
                "integer",
                true);
        assertAdmittedScalar(
                admission,
                BexValues.scalar(new BigDecimal("7.5")),
                "double",
                true);
        assertAdmittedScalar(
                admission,
                BexValues.scalar("text"),
                "text",
                true);

        assertEquals(4, boundary.calls);
    }

    @Test
    void integralDecimalCrossesTheHostBoundaryAsBlueDouble() {
        RecordingBoundary boundary = new RecordingBoundary();
        BexAdmittedValue admitted = admission(boundary).admit(
                BexValues.scalar(new BigDecimal("1.0")),
                BexOutputKind.ROOT_RESULT);

        assertEquals(1, boundary.calls);
        Node supplied = boundary.inputs.get(0);
        assertEquals(
                new BigDecimal("1.0"),
                supplied.getRawValue());
        assertEquals(
                BlueCoreTypeRegistry.INSTANCE.blueId("Double"),
                supplied.getType().getBlueId());
        assertEquals(
                BlueCoreTypeRegistry.INSTANCE.blueId("Double"),
                admitted.node().getType().getBlueId());
        assertEquals(
                "double",
                BexValues.kind(admitted.value()));
    }

    @Test
    void admittedEmptyObjectsRetainTheirShapeWithoutOverridingHostFields() {
        RecordingBoundary boundary = new RecordingBoundary();
        BexOutputAdmission admission = admission(boundary);
        BexValue supplied = BexValues.fromSimple(map(
                "empty", Collections.emptyMap(),
                "status", "kept"));

        BexValue admitted = admission.admit(
                supplied,
                BexOutputKind.ROOT_RESULT).value();
        BexValue empty = admitted.get("empty");

        assertTrue(empty.isExact());
        assertTrue(empty.isObject());
        assertFalse(empty.isNull());
        assertEquals(Collections.emptyList(), empty.keys());
        assertEquals(Collections.emptyMap(), empty.toSimple());
        assertEquals("kept", admitted.get("status").asText());
        assertEquals(1, boundary.calls);
    }

    @Test
    void hostNormalizationWinsWhileMatchingExactDescendantsStayLocal() {
        Node exactContent = obj(
                "deep", "already-resolved");
        String exactChildBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        exactContent);
        FrozenNode exactChildFrozen =
                FrozenNode.fromResolvedNode(
                        exactContent);
        BexValue exactChild = BexValues.exact(
                exactChildFrozen,
                exactChildFrozen,
                exactChildBlueId);
        Node mismatchedSourceContent = obj(
                "source", "different-identity");
        FrozenNode mismatchedSourceFrozen =
                FrozenNode.fromResolvedNode(
                        mismatchedSourceContent);
        String mismatchedSourceBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        mismatchedSourceContent);
        BexValue mismatchedSourceExact =
                BexValues.exact(
                        mismatchedSourceFrozen,
                        mismatchedSourceFrozen,
                        mismatchedSourceBlueId);
        String hostMismatchBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        obj("host", "authoritative"));

        String authoredReferenceBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        obj("remote", "content"));
        BexValue supplied = BexValues.fromSimple(
                map(
                        "status", "before-host",
                        "removed", true,
                        "outer", map(
                                "exactChild", exactChild,
                                "mismatchedExact",
                                mismatchedSourceExact,
                                "authoredReference", map(
                                        "blueId",
                                        authoredReferenceBlueId))));
        Node hostNormalized = obj(
                "status", "after-host",
                "added", "by-host",
                "outer", obj(
                        "exactChild",
                        new Node().blueId(
                                exactChildBlueId),
                        "mismatchedExact",
                        new Node().blueId(
                                hostMismatchBlueId),
                        "authoredReference",
                        new Node().blueId(
                                authoredReferenceBlueId)));
        FrozenNode hostFrozen =
                FrozenNode.fromResolvedNode(
                        hostNormalized);
        String hostBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        hostNormalized);

        BexValue admitted = admission(ignored ->
                new BexEstablishedIdentity(
                        hostBlueId,
                        hostFrozen))
                .admit(
                        supplied,
                        BexOutputKind.ROOT_RESULT)
                .value();

        assertEquals(
                Arrays.asList(
                        "added", "outer", "status"),
                admitted.keys());
        assertEquals("after-host",
                admitted.get("status").asText());
        assertEquals("by-host",
                admitted.get("added").asText());
        assertTrue(admitted.get("removed")
                .isUndefined());

        BexValue outer = admitted.get("outer");
        assertTrue(outer.isExact());
        assertSame(
                exactChild,
                outer.get("exactChild"));
        assertEquals(
                "already-resolved",
                outer.get("exactChild")
                        .get("deep")
                        .asText());
        BexValue mismatchedExact =
                outer.get("mismatchedExact");
        assertNotSame(
                mismatchedSourceExact,
                mismatchedExact);
        assertEquals(
                hostMismatchBlueId,
                mismatchedExact.exactBlueId());

        BexValue authoredReference =
                outer.get("authoredReference");
        assertTrue(authoredReference.isExact());
        assertEquals(
                authoredReferenceBlueId,
                authoredReference.exactBlueId());
        assertThrows(
                BexException.class,
                () -> authoredReference.get("blueId"));
    }

    @Test
    void invokesBoundaryExactlyOnceForEveryDistinctTransientSemanticValue() {
        RecordingBoundary boundary = new RecordingBoundary();
        BexOutputAdmission admission = admission(boundary);
        List<BexValue> values = Arrays.asList(
                BexValues.scalar("text"),
                BexValues.scalar(BigInteger.valueOf(7L)),
                BexValues.scalar(new BigDecimal("7.5")),
                BexValues.scalar(true),
                BexValues.fromSimple(Arrays.asList("x", 1)),
                BexValues.fromSimple(map(
                        "outer", map("inner", 1),
                        "entries", Arrays.asList(true, "x"))));

        for (BexValue value : values) {
            assertTrue(admission.admit(
                    value, BexOutputKind.ROOT_RESULT).value().isExact());
        }

        assertEquals(values.size(), boundary.calls);
        assertEquals(values.size(), boundary.inputs.size());
        assertEquals(values.size(), admission.semanticIdentityMergeCount());
    }

    @Test
    void reusesAliasesButAdmitsEveryRecreatedTransientStructure() {
        RecordingBoundary boundary = new RecordingBoundary();
        BexOutputAdmission admission = admission(boundary);
        BexValue first = BexValues.fromSimple(map(
                "b", Arrays.asList(2, 3),
                "a", 1));
        BexValue sameStructureDifferentOrder = BexValues.fromSimple(map(
                "a", 1,
                "b", Arrays.asList(2, 3)));

        BexAdmittedValue initial = admission.admit(
                first, BexOutputKind.NODE_IDENTITY);
        assertSame(initial, admission.admit(
                first, BexOutputKind.ROOT_RESULT));
        BexAdmittedValue recreated = admission.admit(
                sameStructureDifferentOrder,
                BexOutputKind.EVENT);
        assertNotSame(initial, recreated);
        assertTrue(BexValues.equal(
                initial.value(),
                recreated.value()));
        assertEquals(
                initial.nodeBlueId(),
                recreated.nodeBlueId());

        assertEquals(2, boundary.calls);
        assertEquals(2L, admission.semanticIdentityMergeCount());
    }

    @Test
    void nullIsRejectedBeforeAnEmptyObjectIsAdmitted() {
        assertNullRejectedIndependentlyOfEmptyObject(true);
    }

    @Test
    void emptyObjectAdmissionDoesNotMakeNullAdmissible() {
        assertNullRejectedIndependentlyOfEmptyObject(false);
    }

    @Test
    void nodeIdentityAdmissionIsReusedByPatchEventOverlayAndRootOutput() {
        RecordingBoundary boundary = new RecordingBoundary();
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .semanticIdentityBoundary(boundary)
                .gasLimit(1_000_000L)
                .build();
        Node program = stepDo(list(
                op("$let", obj(
                        "name", "shared",
                        "expr", obj("nested", obj("count", 7)))),
                op("$let", obj(
                        "name", "identity",
                        "expr", op("$nodeBlueId",
                                op("$var", "shared")))),
                op("$appendChange", obj(
                        "op", "replace",
                        "path", "/state",
                        "val", op("$var", "shared"))),
                op("$appendEvent", op("$var", "shared")),
                op("$return", op("$var", "shared"))));

        BexExecutionResult result = BexEngine.builder().build()
                .compileAndExecute(
                        BexProgramSource.inline(frozen(program)),
                        context);
        BexPatchEntry patch =
                result.changeset().entries().get(0);
        BexAdmittedValue event =
                result.events().admittedEvents().get(0);

        assertEquals(1, boundary.calls);
        assertEquals(1L, result.metrics()
                .compiledExecutions());
        assertSame(result.output(), patch.admittedValue());
        assertSame(result.output(), event);
        assertSame(result.output().value(), patch.val());
        assertSame(result.output().value(),
                result.events().events().get(0));
        assertTrue(patch.val().isExact());
        assertTrue(result.events().events().get(0).isExact());
        assertEquals(result.output().nodeBlueId(),
                patch.val().exactBlueId());
        assertEquals(result.output().nodeBlueId(),
                result.events().events().get(0).exactBlueId());
        assertEquals(
                Collections.singletonMap(
                        "nested",
                        Collections.singletonMap(
                                "count",
                                BigInteger.valueOf(7L))),
                result.events().events().get(0).toSimple());
    }

    @Test
    void outputAdmissionIsReusedByALaterNodeIdentityRequest() {
        RecordingBoundary boundary = new RecordingBoundary();
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .semanticIdentityBoundary(boundary)
                .gasLimit(1_000_000L)
                .build();
        Node program = stepDo(list(
                op("$let", obj(
                        "name", "shared",
                        "expr", obj(
                                "nested",
                                obj("count", 7)))),
                op("$appendEvent",
                        op("$var", "shared")),
                op("$let", obj(
                        "name", "identity",
                        "expr", op("$nodeBlueId",
                                op("$var", "shared")))),
                op("$return",
                        op("$var", "shared"))));

        BexExecutionResult result =
                BexEngine.builder().build()
                        .compileAndExecute(
                                BexProgramSource.inline(
                                        frozen(program)),
                                context);
        BexAdmittedValue event =
                result.events()
                        .admittedEvents()
                        .get(0);

        assertEquals(1, boundary.calls);
        assertSame(result.output(), event);
        assertSame(
                result.output().value(),
                result.events().events().get(0));
        assertEquals(
                result.output().nodeBlueId(),
                event.nodeBlueId());
    }

    @Test
    void preservesHostFailureClassificationWithoutBexWrapping() {
        ExecutionEvidenceUnavailableException unavailable =
                new ExecutionEvidenceUnavailableException(
                        "missing exact evidence",
                        Collections.singleton("exact-id"));
        InvalidExecutionEvidenceException invalid =
                new InvalidExecutionEvidenceException("invalid evidence");
        ProcessorFailureException processor =
                new ProcessorFailureException(
                        ProcessorErrorCategory
                                .InvalidProcessingDocument,
                        "invalid output");
        PortableLimitExceededException portable =
                new PortableLimitExceededException(
                        "semanticNodes", 11L, 10L);
        GasLimitExceededException gas = gasFailure();

        assertBoundaryFailureIsSame(unavailable);
        assertBoundaryFailureIsSame(invalid);
        assertBoundaryFailureIsSame(processor);
        assertBoundaryFailureIsSame(portable);
        assertBoundaryFailureIsSame(gas);

        IllegalStateException unexpected =
                new IllegalStateException("adapter defect");
        BexException wrapped = assertThrows(
                BexException.class,
                () -> admission(node -> {
                    throw unexpected;
                }).admit(
                        BexValues.scalar("value"),
                        BexOutputKind.ROOT_RESULT));
        assertSame(unexpected, wrapped.getCause());
    }

    @Test
    void failedAdmissionMutatesNeitherPatchNorEventBuffers() {
        ExecutionEvidenceUnavailableException expected =
                new ExecutionEvidenceUnavailableException(
                        "semantic output evidence unavailable");
        BexOutputAdmission admission = admission(node -> {
            throw expected;
        });
        BexExecutionAccumulator accumulator =
                new BexExecutionAccumulator(
                        new BexResultOverlay(
                                defaultDocumentView(),
                                new BexMetricsRecorder()),
                        admission);

        ExecutionEvidenceUnavailableException patchFailure =
                assertThrows(
                        ExecutionEvidenceUnavailableException.class,
                        () -> accumulator.appendChange(
                                new BexPatchEntry(
                                        "replace",
                                        "/state",
                                        "/state",
                                        BexValues.scalar(
                                                "rejected"))));
        assertSame(expected, patchFailure);
        assertTrue(
                accumulator.changeset()
                        .entries()
                        .isEmpty());

        ExecutionEvidenceUnavailableException eventFailure =
                assertThrows(
                        ExecutionEvidenceUnavailableException.class,
                        () -> accumulator.appendEvent(
                                BexValues.scalar(
                                        "rejected-event")));
        assertSame(expected, eventFailure);
        assertTrue(
                accumulator.events()
                        .events()
                        .isEmpty());
        assertTrue(
                accumulator.events()
                        .admittedEvents()
                        .isEmpty());
    }

    @Test
    void exactCyclicMemberStaysOpaqueAndTransientCounterfeitsFailClosed() {
        String cyclicMember =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("cyclic-set"))
                        + "#0";
        FrozenNode reference = FrozenNode.fromResolvedNode(
                new Node().blueId(cyclicMember));
        BexValue exact = BexValues.exact(
                reference, reference, cyclicMember);
        RecordingBoundary boundary = new RecordingBoundary();
        BexAdmittedValue admitted = admission(boundary).admit(
                exact, BexOutputKind.ROOT_RESULT);

        assertEquals(0, boundary.calls);
        assertFalse(admitted.reconstructed());
        assertEquals(cyclicMember, admitted.nodeBlueId());
        assertEquals(cyclicMember, admitted.node().getBlueId());

        BexValue transientCounterfeit = BexValues.fromSimple(
                Collections.singletonMap(
                        "blueId", cyclicMember));
        BexException beforeBoundary = assertThrows(
                BexException.class,
                () -> admission(boundary).admit(
                        transientCounterfeit,
                        BexOutputKind.ROOT_RESULT));
        assertTrue(beforeBoundary.getMessage().contains(
                "cannot counterfeit"));
        assertEquals(0, boundary.calls);

        BexValue nestedCounterfeit = BexValues.fromSimple(
                Collections.singletonMap(
                        "nested",
                        Collections.singletonMap(
                                "blueId", cyclicMember)));
        BexException nestedFailure = assertThrows(
                BexException.class,
                () -> admission(boundary).admit(
                        nestedCounterfeit,
                        BexOutputKind.ROOT_RESULT));
        assertTrue(nestedFailure.getMessage().contains(
                "cannot counterfeit"));
        assertEquals(0, boundary.calls);

        BexValue transientWithExactCyclicChild =
                BexValues.fromSimple(
                        Collections.singletonMap(
                                "nested", exact));
        BexAdmittedValue admittedParent =
                admission(boundary).admit(
                        transientWithExactCyclicChild,
                        BexOutputKind.ROOT_RESULT);
        assertEquals(1, boundary.calls);
        assertSame(
                exact,
                admittedParent.value().get("nested"));
        assertEquals(
                cyclicMember,
                admittedParent.value()
                        .get("nested")
                        .exactBlueId());

        BexException afterBoundary = assertThrows(
                BexException.class,
                () -> admission(node ->
                        new BexEstablishedIdentity(
                                cyclicMember,
                                reference))
                        .admit(
                                BexValues.scalar("ordinary"),
                                BexOutputKind.ROOT_RESULT));
        assertTrue(afterBoundary.getMessage().contains(
                "cannot establish a cyclic-set"));
    }

    private static void assertBoundaryFailureIsSame(
            RuntimeException failure) {
        RuntimeException observed = assertThrows(
                failure.getClass(),
                () -> admission(node -> {
                    throw failure;
                }).admit(
                        BexValues.scalar("value"),
                        BexOutputKind.ROOT_RESULT));
        assertSame(failure, observed);
    }

    private static void assertNullRejectedIndependentlyOfEmptyObject(
            boolean nullFirst) {
        RecordingBoundary boundary = new RecordingBoundary();
        BexOutputAdmission admission = admission(boundary);
        BexValue suppliedNull = BexValues.nullValue();
        BexValue suppliedEmptyObject =
                BexValues.fromSimple(Collections.emptyMap());

        BexAdmittedValue admittedEmptyObject;
        if (nullFirst) {
            assertThrows(BexException.class, () -> admission.admit(
                    suppliedNull, BexOutputKind.ROOT_RESULT));
            admittedEmptyObject = admission.admit(
                    suppliedEmptyObject,
                    BexOutputKind.ROOT_RESULT);
        } else {
            admittedEmptyObject = admission.admit(
                    suppliedEmptyObject,
                    BexOutputKind.ROOT_RESULT);
            assertThrows(BexException.class, () -> admission.admit(
                    suppliedNull, BexOutputKind.ROOT_RESULT));
        }

        assertEquals(1, boundary.calls);
        assertEquals(1L, admission.semanticIdentityMergeCount());
        assertTrue(admittedEmptyObject.value().isExact());
        assertEquals("object", BexValues.kind(
                admittedEmptyObject.value()));
        assertEquals(
                Collections.emptyMap(),
                admittedEmptyObject.value().toSimple());
    }

    private static void assertAdmittedScalar(
            BexOutputAdmission admission,
            BexValue source,
            String expectedKind,
            boolean expectedTruthiness) {
        BexValue admitted = admission.admit(
                source,
                BexOutputKind.ROOT_RESULT)
                .value();

        assertEquals(
                expectedKind,
                BexValues.kind(admitted));
        assertTrue(BexValues.equal(
                admitted,
                source));
        assertEquals(
                expectedTruthiness,
                BexValues.truthy(admitted));
    }

    private static GasLimitExceededException gasFailure() {
        GasMeter meter = new GasMeter(
                blue.language.processor.GasSchedule.contracts10(),
                0L);
        GasMeter.ChildGasLedger ledger =
                meter.childLedger(
                        "identity-test",
                        Collections.singletonMap(
                                "identity", 1L));
        return assertThrows(
                GasLimitExceededException.class,
                () -> ledger.charge("identity", 1L));
    }

    private static BexOutputAdmission admission(
            BexSemanticIdentityBoundary boundary) {
        return new BexOutputAdmission(
                new BexGasMeter(
                        BexGasSchedule.defaults(),
                        1_000_000L),
                boundary,
                BexContractsFailureBoundary.INSTANCE);
    }

    private static Map<String, Object> map(
            Object... keysAndValues) {
        Map<String, Object> result =
                new LinkedHashMap<>();
        for (int index = 0;
             index < keysAndValues.length;
             index += 2) {
            result.put(
                    (String) keysAndValues[index],
                    keysAndValues[index + 1]);
        }
        return result;
    }

    private static final class RecordingBoundary
            implements BexSemanticIdentityBoundary {
        private final List<Node> inputs = new ArrayList<>();
        private int calls;

        @Override
        public BexEstablishedIdentity establishIdentity(
                Node node) {
            calls++;
            Node exact = node.clone();
            inputs.add(exact);
            return new BexEstablishedIdentity(
                    DirectBlueIdCalculator.calculateBlueId(exact),
                    FrozenNode.fromResolvedNode(exact));
        }
    }
}
