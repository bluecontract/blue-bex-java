package blue.language.processor;

import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.bex.contracts.BexContractsExecutionContext;
import blue.bex.contracts.ProcessorExactBlueValueCapability;
import blue.bex.output.BexAdmittedValue;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexPatchEntry;
import blue.bex.test.TestBlue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepDo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-module acceptance for Contracts clauses C-BEX-NULL-01 through 04.
 *
 * <p>The Language/Contracts repository deliberately has no dependency on BEX.
 * This test therefore lives with the BEX-owned Contracts adapter and exercises
 * the actual public {@link DocumentProcessor} boundary rather than simulating
 * BEX inside the Contracts kernel.</p>
 */
class BexContractsNullBoundaryAcceptanceTest {

    private static final String EXACT_NULL_INTRINSIC =
            "C-BEX exact-null intrinsic";
    private static final String EXACT_NULL_REGISTRY =
            "c-bex-null-exact/1";

    @Test
    void cBexNull01AllTransientNullBoundariesLeaveContractsUnchanged() {
        for (FailureBoundary boundary : FailureBoundary.values()) {
            try (Scenario scenario = Scenario.failure(boundary)) {
                Node input = scenario.document();
                String inputBlueId =
                        DirectBlueIdCalculator.calculateBlueId(input);

                DocumentProcessingResult result =
                        scenario.processor.initializeDocument(input);

                assertEquals(1, scenario.handler.executions,
                        boundary.name());
                assertEquals(ProcessorStatus.RUNTIME_FATAL,
                        result.status(), boundary.name());
                assertFalse(result.commits(), boundary.name());
                assertNotNull(result.diagnostic(), boundary.name());
                assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                        result.diagnostic().category(), boundary.name());
                assertTrue(result.diagnostic().message()
                                .contains("root value is null"),
                        boundary.name() + ": "
                                + result.diagnostic().message());
                assertTrue(result.events().isEmpty(), boundary.name());

                Node unchanged = result.document();
                assertEquals(inputBlueId,
                        DirectBlueIdCalculator.calculateBlueId(unchanged),
                        boundary.name());
                assertEquals("before",
                        unchanged.getAsText("/state"), boundary.name());
                assertFalse(unchanged.getProperties()
                                .containsKey("output"),
                        boundary.name());
                assertNotNull(unchanged.getContracts(), boundary.name());
                assertFalse(unchanged.getContracts().getProperties()
                                .containsKey("checkpoint"),
                        boundary.name());
                assertFalse(unchanged.getContracts().getProperties()
                                .containsKey("initialized"),
                        boundary.name());
            }
        }
    }

    @Test
    void cBexNull02Through04PreserveExactStructuralDistinctionsInContracts() {
        try (Scenario scenario = Scenario.success()) {
            DocumentProcessingResult result =
                    scenario.processor.initializeDocument(
                            scenario.document());

            assertEquals(1, scenario.handler.executions);
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(ProcessorStatus.SUCCESS, result.status());
            assertTrue(result.events().isEmpty());

            Node document = result.document();
            Node nullRequest = document.getNode("/nullRequest");
            Node emptyRequest = document.getNode("/emptyRequest");
            assertExactEmptyObject(nullRequest);
            assertFalse(nullRequest.getProperties()
                    .containsKey("request"));
            assertNotNull(emptyRequest.getNode("/request"));
            assertExactEmptyObject(emptyRequest.getNode("/request"));
            assertNotEquals(
                    DirectBlueIdCalculator.calculateBlueId(nullRequest),
                    DirectBlueIdCalculator.calculateBlueId(emptyRequest));

            Node values = document.getNode("/values");
            assertNotNull(values);
            assertEquals(3, values.getItems().size());
            assertTrue(Nodes.isEmptyPlaceholder(
                    values.getItems().get(0)));
            assertExactEmptyObject(values.getItems().get(1));
            assertFalse(Nodes.isEmptyPlaceholder(
                    values.getItems().get(1)));
            assertNotNull(values.getItems().get(2).getItems());
            assertTrue(values.getItems().get(2).getItems().isEmpty());
            assertNotEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            values.getItems().get(0)),
                    DirectBlueIdCalculator.calculateBlueId(
                            values.getItems().get(1)));

            Node nested = document.getNode("/nested/x");
            assertExactEmptyObject(nested);
            for (Map.Entry<String, String> patch
                    : scenario.handler.patchBlueIds.entrySet()) {
                assertEquals(patch.getValue(),
                        DirectBlueIdCalculator.calculateBlueId(
                                document.getNode(patch.getKey())),
                        patch.getKey());
            }
        }
    }

    private static String diagnostic(DocumentProcessingResult result) {
        return result.diagnostic() != null
                ? result.diagnostic().category() + ": "
                + result.diagnostic().message()
                : "no diagnostic";
    }

    private static void assertExactEmptyObject(Node node) {
        assertNotNull(node);
        assertNotNull(node.getProperties());
        assertTrue(node.getProperties().isEmpty());
        assertFalse(node.isInlineValue());
    }

    private static Node emptyObject() {
        return new Node().properties(
                Collections.<String, Node>emptyMap());
    }

    private static Node emptyList() {
        return new Node().items(Collections.<Node>emptyList());
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private enum FailureBoundary {
        ROOT,
        PATCH,
        EVENT,
        EXACT_INTRINSIC,
        NODE_BLUE_ID
    }

    public static final class NullBoundaryHandler
            extends HandlerContract {
    }

    private static final class NullBoundaryHandlerProcessor
            implements HandlerProcessor<NullBoundaryHandler> {
        private final FailureBoundary failureBoundary;
        private int executions;
        private final Map<String, String> patchBlueIds =
                new LinkedHashMap<>();

        private NullBoundaryHandlerProcessor(
                FailureBoundary failureBoundary) {
            this.failureBoundary = failureBoundary;
        }

        @Override
        public Class<NullBoundaryHandler> contractType() {
            return NullBoundaryHandler.class;
        }

        @Override
        public void execute(
                NullBoundaryHandler contract,
                ProcessorExecutionContext context) {
            executions++;
            BexEngine.Builder builder = BexEngine.builder();
            if (failureBoundary == FailureBoundary.EXACT_INTRINSIC) {
                builder.intrinsic(
                        EXACT_NULL_INTRINSIC,
                        EXACT_NULL_REGISTRY,
                        Collections.singletonMap("work", 1L),
                        invocation -> {
                            invocation.exactField("payload");
                            return blue.bex.value.BexValues.scalar(
                                    "unreachable");
                        });
            }
            BexExecutionResult result = builder.build()
                    .compileAndExecute(
                            BexProgramSource.inline(frozen(program())),
                            BexContractsExecutionContext.builder(
                                    context,
                                    "bex:c-bex-null-acceptance")
                                    .build());
            for (BexPatchEntry patch : result.changeset().entries()) {
                applyExactPatch(context, patch);
            }
        }

        private Node program() {
            if (failureBoundary == null) {
                return stepDo(list(
                        add("/nullRequest", obj(
                                "request", op("$null", true))),
                        add("/emptyRequest", obj(
                                "request", emptyObject())),
                        add("/values", list(
                                op("$null", true),
                                emptyObject(),
                                emptyList())),
                        add("/nested", obj(
                                "x", obj(
                                        "y", op("$null", true)))),
                        op("$return", true)));
            }
            return stepDo(list(
                    op("$appendChange", obj(
                            "op", "replace",
                            "path", "/state",
                            "val", "buffered")),
                    op("$appendEvent", obj(
                            "kind", "buffered")),
                    failingExpression(failureBoundary)));
        }

        private static Node add(String path, Node value) {
            return op("$appendChange", obj(
                    "op", "add",
                    "path", path,
                    "val", value));
        }

        private void applyExactPatch(
                ProcessorExecutionContext context,
                BexPatchEntry patch) {
            BexAdmittedValue admitted = patch.admittedValue();
            ProcessorExactBlueValueCapability capability =
                    (ProcessorExactBlueValueCapability)
                            admitted.exactCapability();
            FrozenJsonPatch exact;
            if ("add".equals(patch.op())) {
                exact = FrozenJsonPatch.add(
                        patch.absolutePath(),
                        capability.exactValue());
            } else if ("replace".equals(patch.op())) {
                exact = FrozenJsonPatch.replace(
                        patch.absolutePath(),
                        capability.exactValue());
            } else {
                throw new AssertionError(
                        "Unexpected BEX patch op: " + patch.op());
            }
            context.applyFrozenPatch(exact);
            patchBlueIds.put(
                    patch.absolutePath(),
                    admitted.nodeBlueId());
        }

        private static Node failingExpression(
                FailureBoundary boundary) {
            switch (boundary) {
                case ROOT:
                    return op("$return", op("$null", true));
                case PATCH:
                    return op("$appendChange", obj(
                            "op", "replace",
                            "path", "/state",
                            "val", op("$null", true)));
                case EVENT:
                    return op("$appendEvent", op("$null", true));
                case EXACT_INTRINSIC:
                    return op("$return", op("$intrinsic", obj(
                            "type", obj(
                                    "blueId", EXACT_NULL_INTRINSIC),
                            "payload", op("$null", true))));
                case NODE_BLUE_ID:
                    return op("$return", op(
                            "$nodeBlueId", op("$null", true)));
                default:
                    throw new AssertionError(boundary);
            }
        }
    }

    private static final class Scenario implements AutoCloseable {
        private final TestBlue blue;
        private final NullBoundaryHandlerProcessor handler;
        private final DocumentProcessor processor;
        private final String handlerBlueId;

        private Scenario(FailureBoundary boundary) {
            handler = new NullBoundaryHandlerProcessor(boundary);
            Node handlerType = new Node()
                    .name("C-BEX null boundary Handler")
                    .type(reference(RuntimeBlueIds.HANDLER));
            handlerBlueId =
                    DirectBlueIdCalculator.calculateBlueId(handlerType);
            blue = new TestBlue(blueId -> handlerBlueId.equals(blueId)
                    ? Collections.singletonList(handlerType.clone())
                    : Collections.<Node>emptyList());
            processor = DocumentProcessor.Builder
                    .from(blue.getDocumentProcessor())
                    .registerContractProcessor(
                            handlerBlueId,
                            handlerType,
                            handler)
                    .build();
        }

        private static Scenario failure(FailureBoundary boundary) {
            return new Scenario(boundary);
        }

        private static Scenario success() {
            return new Scenario(null);
        }

        private Node document() {
            return new Node()
                    .properties("state", new Node().value("before"))
                    .contracts(new Node().properties(
                            "lifecycle",
                            new Node().type(reference(
                                    RuntimeBlueIds
                                            .LIFECYCLE_EVENT_CHANNEL)),
                            "runBex",
                            new Node()
                                    .type(reference(handlerBlueId))
                                    .properties(
                                            "channel",
                                            new Node().value("lifecycle"),
                                            "event",
                                            new Node().type(reference(
                                                    RuntimeBlueIds
                                                            .DOCUMENT_PROCESSING_INITIATED)))));
        }

        @Override
        public void close() {
            processor.close();
            blue.close();
        }
    }
}
