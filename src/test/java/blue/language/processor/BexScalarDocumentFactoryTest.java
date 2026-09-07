package blue.language.processor;

import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.bex.contracts.BexContractsExecutionContext;
import blue.bex.contracts.ProcessorExactBlueValueCapability;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexPatchEntry;
import blue.bex.test.TestBlue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Schema;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.closure.*;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual scalar function -> hosted exact output -> Contracts admission. */
class BexScalarDocumentFactoryTest {
    private enum TypeForm { INLINE, REFERENCE, DOCUMENT, IMPORTED }

    @Test
    void shouldCreateTypedContractBearingDocumentsFromScalarArguments() {
        // given
        for (String name : new String[]{"Order", "Observation"}) {
            for (TypeForm form : TypeForm.values()) {
                try (Scenario scenario = new Scenario(name, form, v("sku-1"))) {
                    Node input = scenario.document();
                    Object before = NodeWireForm.get(input);
                    // when
                    DocumentProcessingResult result = scenario.processor.initializeDocument(input);
                    // then
                    assertTrue(result.commits(), form + ": " + diagnostic(result));
                    assertEquals(before, NodeWireForm.get(input));
                    Node child = result.document().getNode("/children/first");
                    assertNotNull(child);
                    assertEquals("sku-1", child.getAsText("/label"));
                    assertEquals(7L, ((Number) child.getNode("/quantity").getValue()).longValue());
                    DocumentProcessingResult admission = scenario.processor.initializeDocument(child);
                    assertTrue(admission.commits(), diagnostic(admission));
                    assertNotNull(admission.document().getNode("/contracts/initialized"));
                    assertEquals("ready", admission.document().getAsText("/state"));
                    assertEquals(scenario.handler.childIdentity,
                            DirectBlueIdCalculator.calculateBlueId(child));
                }
            }
        }
    }

    @Test
    void shouldRejectWrongScalarKindAndMissingRequiredArgumentAtomically() {
        // given
        for (Node argument : new Node[]{v(42), op("$literal", obj()), op("$null", true), op("$document", "/missing")}) {
            try (Scenario scenario = new Scenario("Observation", TypeForm.DOCUMENT, argument)) {
                Node input = scenario.document();
                // when
                DocumentProcessingResult result = scenario.processor.initializeDocument(input);
                // then
                assertFalse(result.commits(), diagnostic(result));
                assertEquals(NodeWireForm.get(input), NodeWireForm.get(result.document()));
                assertTrue(result.events().isEmpty());
            }
        }
    }

    @Test
    void shouldAdmitTwoFactoryChildrenAndRollbackAllOnTheSecondChildFailure() {
        // given
        Set<String> distinctCreationReceipts = new HashSet<>();
        for (int command = 0; command < 2; command++) {
            for (boolean fail : new boolean[]{false, true}) {
                try (Scenario scenario = new Scenario("Order", TypeForm.DOCUMENT, v("sku-1"))) {
                    scenario.handler.failSecond = fail;
                    Node root = scenario.document();
                    root.getContracts().properties("embedded", new Node().type(new Node().blueId(
                            RuntimeBlueIds.PROCESS_EMBEDDED)).properties("collectionPaths", list("/children")));
                    DocumentId rootId = new DocumentId("agreement-" + command);
                    ManagedDocumentSnapshot member = new ManagedDocumentSnapshot(rootId,
                            DirectBlueIdCalculator.calculateBlueId(root), root, false, false, true, 0L, 1L);
                    AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L,
                            Collections.singletonList(member), Collections.emptyList(),
                            Collections.singletonList(ClosureEvidenceFactory.acyclicComponent(member)),
                            Collections.singletonList(rootId));
                    String hash = "sha256:" + String.join("", Collections.nCopies(64, "a"));
                    ClosureEnvironment environment = ClosureEvidenceFactory.environment(scenario.processor,
                            hash, hash, "r2-lineage", "r2-binding", "r2-provider", "r2-order", "r2-limits",
                            GasSchedule.contracts10().portableLimits());
                    ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(snapshot,
                            ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                                    "r2-factory-create-" + command, null, null, "r2-admission"), null,
                            ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "r2-gas"), environment);
                    ClosureProcessResult result = null;
                    // when: resolve exact lineage demands; every attempt replays the full prefix.
                    for (int attemptIndex = 0; attemptIndex < 3; attemptIndex++) {
                        scenario.childInitializations.clear();
                        ClosureAttemptResult attempt;
                        try (BlueClosureContracts contracts = new BlueClosureContracts(scenario.processor)) {
                            attempt = contracts.admitClosureWithLifecycleQueue(input);
                        }
                        if (attempt.isComplete()) { result = attempt.processResult(); break; }
                        assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, attempt.kind());
                        assertNull(attempt.processResult());
                        List<ManagedDocumentBirth> births = new ArrayList<>();
                        for (ClosureResourceDemand resource : attempt.resourceDemands()) {
                            ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) resource;
                            births.add(new ManagedDocumentBirth(demand, new DocumentId(demand.sourceDocumentId().value() + demand.sourcePath()),
                                    demand.suppliedExactValue().get()));
                        }
                        input = ClosureEvidenceFactory.withProspectiveBirths(input, births);
                    }
                    // then
                    assertNotNull(result);
                    assertEquals(!fail, result.commits(), result.diagnostic() == null ? "" : result.diagnostic().message());
                    assertEquals(2, scenario.childInitializations.size());
                    if (fail) {
                        assertTrue(result.publicEvents().isEmpty());
                        assertTrue(result.managedTransitionReceipts().isEmpty());
                        assertNull(result.commitCompanion());
                        for (ResultingDocument value : result.resultingDocuments()) {
                            assertEquals(value.beforeBlueId(), value.afterBlueId());
                            assertFalse(value.initialized());
                        }
                    } else {
                        assertEquals(3, result.resultingDocuments().size());
                        assertTrue(result.resultingDocuments().stream().allMatch(ResultingDocument::initialized));
                        assertEquals(2, result.occurrenceBindings().size());
                        assertNotEquals(result.occurrenceBindings().get(0).occurrenceIdentity(),
                                result.occurrenceBindings().get(1).occurrenceIdentity());
                        assertEquals(1, result.publicEvents().size());
                        assertEquals(3, result.managedTransitionReceipts().size());
                        ClosureProcessResult replay;
                        try (BlueClosureContracts contracts = new BlueClosureContracts(scenario.processor)) {
                            replay = contracts.admitClosureWithLifecycleQueue(input).processResult();
                        }
                        assertTrue(replay.commits());
                        assertEquals(result.outputClosureIdentity(), replay.outputClosureIdentity());
                        for (int index = 0; index < 3; index++) {
                            String identity = result.managedTransitionReceipts().get(index).transitionReceiptIdentity();
                            assertEquals(identity, replay.managedTransitionReceipts().get(index).transitionReceiptIdentity());
                            assertTrue(distinctCreationReceipts.add(identity),
                                    "A distinct command with fresh lineages must have distinct receipts");
                        }
                    }
                }
            }
        }
        assertEquals(6, distinctCreationReceipts.size());
    }

    @Test
    void shouldNotRepeatCreationWhenAnInitializedCommandIsReplayed() {
        // given
        try (Scenario scenario = new Scenario("Observation", TypeForm.DOCUMENT, v("sensor"))) {
            DocumentProcessingResult first = scenario.processor.initializeDocument(scenario.document());
            assertTrue(first.commits(), diagnostic(first));
            int executions = scenario.handler.executions;
            // when
            assertThrows(IllegalStateException.class, () -> scenario.processor.initializeDocument(first.document()));
            // then
            assertEquals(executions, scenario.handler.executions);
            assertEquals(2, first.document().getNode("/children").getProperties().size());
        }
    }

    private static String diagnostic(DocumentProcessingResult result) {
        return result.diagnostic() == null ? result.status().toString()
                : result.diagnostic().message();
    }

    public static final class FactoryHandler extends HandlerContract { }

    private static final class FactoryProcessor implements HandlerProcessor<FactoryHandler> {
        final Node definition;
        final TypeForm form;
        final Node argument;
        String childIdentity;
        String importedType;
        boolean failSecond;
        int executions;
        FactoryProcessor(Node definition, TypeForm form, Node argument) {
            this.definition = definition;
            this.form = form;
            this.argument = argument;
        }
        public Class<FactoryHandler> contractType() { return FactoryHandler.class; }
        public void execute(FactoryHandler contract, ProcessorExecutionContext context) {
            executions++;
            Node type = form == TypeForm.INLINE ? op("$literal", definition)
                    : form == TypeForm.IMPORTED ? obj("blueId", importedType)
                    : form == TypeForm.REFERENCE
                    ? obj("blueId", DirectBlueIdCalculator.calculateBlueId(definition))
                    : op("$document", "/definition");
            Node factory = obj("args", obj("label", obj(), "quantity", obj()),
                    "do", list(op("$return", obj("type", type,
                            "label", op("$var", "label"),
                            "quantity", op("$var", "quantity")))));
            List<Node> operations = new ArrayList<>();
            operations.add(op("$appendEvent", obj("kind", "tentative")));
            for (String key : new String[]{"first", "second"}) {
                operations.add(op("$appendChange", obj("op", "add", "path", "/children/" + key,
                        "val", op("$call", obj("function", "create", "args", obj(
                                "label", failSecond && key.equals("second") ? v("reject") : argument,
                                "quantity", 7))))));
            }
            operations.add(op("$return", true));
            Node program = obj("type", "Blue/BEX Program", "functions", obj("create", factory),
                    "do", new Node().items(operations));
            BexExecutionResult result = BexEngine.builder().build().compileAndExecute(
                    BexProgramSource.inline(frozen(program)),
                    BexContractsExecutionContext.builder(context, "bex:r2-factory").build());
            for (BexPatchEntry patch : result.changeset().entries()) {
                ProcessorExactBlueValueCapability capability = (ProcessorExactBlueValueCapability)
                        patch.admittedValue().exactCapability();
                childIdentity = patch.admittedValue().nodeBlueId();
                context.applyFrozenPatch(FrozenJsonPatch.add(patch.absolutePath(), capability.exactValue()));
            }
            for (blue.bex.output.BexAdmittedValue event : result.events().admittedEvents()) {
                context.emitEvent(((ProcessorExactBlueValueCapability) event.exactCapability()).exactValue());
            }
        }
    }

    public static final class ChildHandler extends HandlerContract { }

    private static final class Scenario implements AutoCloseable {
        final TestBlue blue;
        final DocumentProcessor processor;
        final BlueContracts contracts;
        final FactoryProcessor handler;
        final String handlerId;
        final Node definition;
        final List<String> childInitializations = new ArrayList<>();
        Scenario(String name, TypeForm form, Node argument) {
            Node handlerType = new Node().name("R2 scalar factory handler")
                    .type(new Node().blueId(RuntimeBlueIds.HANDLER));
            handlerId = DirectBlueIdCalculator.calculateBlueId(handlerType);
            Node childHandlerType = new Node().name("R2 child initialization handler")
                    .type(new Node().blueId(RuntimeBlueIds.HANDLER));
            String childHandlerId = DirectBlueIdCalculator.calculateBlueId(childHandlerType);
            definition = new Node().name(name)
                    .properties("label", new Node().type(new Node().blueId(BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                            .schema(new Schema().required(new Node().value(true))))
                    .properties("quantity", new Node().type(new Node().blueId(BlueLanguageConstants.INTEGER_TYPE_BLUE_ID))
                            .schema(new Schema().required(new Node().value(true))))
                    .properties("state", new Node().type(new Node().blueId(BlueLanguageConstants.TEXT_TYPE_BLUE_ID)))
                    .contracts(new Node().properties("lifecycle", new Node().type(
                            new Node().blueId(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                            .properties("initialize", new Node().type(new Node().blueId(childHandlerId))
                                    .properties("channel", new Node().value("lifecycle"))
                                    .properties("event", new Node().type(new Node().blueId(
                                            RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED)))));
            Map<String, Node> nodes = new LinkedHashMap<>();
            nodes.put(handlerId, handlerType);
            nodes.put(childHandlerId, childHandlerType);
            nodes.put(DirectBlueIdCalculator.calculateBlueId(definition), definition);
            blue = new TestBlue(id -> nodes.containsKey(id)
                    ? Collections.singletonList(nodes.get(id).clone()) : Collections.emptyList());
            handler = new FactoryProcessor(definition, form, argument);
            handler.importedType = blue.yamlToNode("blue:\n  imports:\n    Child:\n      blueId: "
                    + DirectBlueIdCalculator.calculateBlueId(definition) + "\ntype: Child\n")
                    .getType().getBlueId();
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create().registerDefaults()
                    .register(handlerId, handlerType, handler)
                    .register(childHandlerId, childHandlerType, new HandlerProcessor<ChildHandler>() {
                        public Class<ChildHandler> contractType() { return ChildHandler.class; }
                        public void execute(ChildHandler contract, ProcessorExecutionContext context) {
                            String label = context.documentAt("/label").getValue().toString();
                            childInitializations.add(label);
                            if (label.equals("reject")) throw new IllegalStateException("Rejected child admission");
                            context.applyPatch(JsonPatch.add("/state", new Node().value("ready")));
                            context.emitEvent(new Node().properties("kind", new Node().value("child-ready")));
                        }
                    }).build();
            contracts = BlueContracts.builder(blue.runtime().processing()).runtimeRegistry(registry).build();
            processor = DocumentProcessor.builder().runtimeAccess(contracts.runtimeAccess())
                    .runtimeRegistry(registry).runtimeRegistryIdentity(registry.generationIdentity()).build();
        }
        Node document() {
            return new Node().properties("definition", new Node().blueId(DirectBlueIdCalculator.calculateBlueId(definition)))
                    .properties("children", new Node().properties(Collections.emptyMap()))
                    .contracts(new Node().properties("lifecycle", new Node().type(new Node().blueId(
                            RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                            .properties("create", new Node().type(new Node().blueId(handlerId))
                                    .properties("channel", new Node().value("lifecycle"))
                                    .properties("event", new Node().type(new Node().blueId(
                                            RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED)))));
        }
        public void close() { processor.close(); contracts.close(); blue.close(); }
    }
}
