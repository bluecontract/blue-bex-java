package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexPatchEntry;
import blue.bex.result.BexMetrics;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCustomerPayNoteBexFunctionTest {
    private static final String BEX_FIXTURE = "/fixtures/customer-paynote-snapshot-bex-functions.yaml";
    private static final String EVENT_FIXTURE = "/fixtures/customer-paynote-snapshot-event.yaml";

    @Test
    void executesSnapshotResolvedFunctionFromFixtureWithAttachedEvent() throws IOException {
        Blue blue = new Blue();
        Node programDocument = blue.yamlToNode(readResource(BEX_FIXTURE));
        Node eventEnvelope = blue.yamlToNode(readResource(EVENT_FIXTURE));

        Node programNode = requiredNode(programDocument, "/contracts/processPackageCustomerPayNoteSnapshotResolved/steps/0");
        Node definitionNode = requiredNode(programDocument, "/contracts/packageFulfillmentBexDefinition");
        Node matchedEvent = requiredNode(eventEnvelope, "/message/request/0");

        assertEquals("processCustomerPayNoteSnapshotResolved", valueAt(programNode, "/entry"));
        assertEquals("snapshot:customer-paynote:customer-paynote-a", valueAt(matchedEvent, "/inResponseTo/requestId"));
        assertEquals("customer-paynote-a", valueAt(matchedEvent, "/targetSessionId"));

        BexExecutionResult result = BexEngine.builder().build().compileAndExecute(
                BexProgramSource.withDefinition(
                        FrozenNode.fromResolvedNode(programNode),
                        FrozenNode.fromResolvedNode(definitionNode),
                        "processCustomerPayNoteSnapshotResolved"),
                BexExecutionContext.builder()
                        .document(new FrozenBexDocumentView(FrozenNode.fromResolvedNode(programDocument)))
                        .event(BexValues.nodeCursorTrustedImmutable(matchedEvent))
                        .currentContract(BexValues.frozen(FrozenNode.fromResolvedNode(definitionNode)))
                        .gasLimit(100_000_000L)
                        .build());

        List<String> paths = patchPaths(result);
        assertTrue(paths.contains("/customerPayNoteRefsBySessionId/customer-paynote-a"), paths.toString());
        assertTrue(paths.contains("/orders/package-order-a/customerPayNote/sessionId"), paths.toString());
        assertTrue(paths.contains("/orders/package-order-a/customerPayNote/snapshotRequestId"), paths.toString());
        assertTrue(paths.contains("/orders/package-order-a/customerPayNote/subscriptionId"), paths.toString());
        assertTrue(paths.contains("/orders/package-order-a/customerPayNote/secured"), paths.toString());
        assertTrue(paths.contains("/orders/package-order-a/customerPayNote/securedAmount"), paths.toString());
        assertTrue(paths.contains("/orders/package-order-a/customerPayNote/attachedToPackageOrder"), paths.toString());

        BexPatchEntry refPatch = patchAt(result, "/customerPayNoteRefsBySessionId/customer-paynote-a");
        assertEquals("add", refPatch.op());
        assertEquals("customer-paynote-a", refPatch.val().get("sessionId").asText());
        assertEquals("package-order-a", refPatch.val().get("packageOrderSessionId").asText());
        assertEquals("zzimVxhnKLL5SwMkS9kmF8p5g7pyxPWBu664HxGbszB", refPatch.val().get("packageOrderDocumentId").asText());
        assertEquals("snapshot:customer-paynote:customer-paynote-a", refPatch.val().get("snapshotRequestId").asText());
        assertEquals("package-linked:customer-paynote-a", refPatch.val().get("subscriptionId").asText());

        assertEquals("customer-paynote-a", patchAt(result, "/orders/package-order-a/customerPayNote/sessionId").val().asText());
        assertEquals("snapshot:customer-paynote:customer-paynote-a",
                patchAt(result, "/orders/package-order-a/customerPayNote/snapshotRequestId").val().asText());
        assertEquals("package-linked:customer-paynote-a",
                patchAt(result, "/orders/package-order-a/customerPayNote/subscriptionId").val().asText());
        assertTrue(patchAt(result, "/orders/package-order-a/customerPayNote/secured").val().asBoolean());
        assertEquals("100000", patchAt(result, "/orders/package-order-a/customerPayNote/securedAmount").val().asText());
        assertTrue(patchAt(result, "/orders/package-order-a/customerPayNote/attachedToPackageOrder").val().asBoolean());

        BexValue attachEvent = findEvent(result, "attachPayNote");
        assertNotNull(attachEvent, "Expected attachPayNote event in " + result.events().events().size() + " computed events");
        assertEquals("MyOS/Call Operation Requested", attachEvent.get("type").asText());
        assertEquals("investorChannel", attachEvent.get("onBehalfOf").asText());
        assertEquals("package-order-a", attachEvent.get("targetSessionId").asText());
        assertEquals("customer-paynote-a", attachEvent.at("/request/payNoteSessionId").asText());
        assertEquals("customer_package_purchase",
                attachEvent.at("/request/initialSnapshot/context/paymentKind").asText());

        BexMetrics metrics = result.metrics();
        assertEquals(0, metrics.interpretedFallbacks());
        assertEquals(0, metrics.functionArgMapAllocations());
        assertEquals(0, metrics.nodeMaterializations());
        assertTrue(metrics.eventReads() > 0, "the function must read the attached BEX event");
        assertTrue(metrics.frozenDocumentReads() > 0, "the function must read the frozen document view");
        assertTrue(metrics.resultValueReads() > 0, "the function should use result overlay reads");
        assertTrue(metrics.functionCalls() > 1, "the entry should call helper functions from the fixture definition");
        assertTrue(result.gasUsed() > 0);

        assertEquals(result.changeset().entries().size(), result.value().get("changeset").size());
        assertEquals(result.events().events().size(), result.value().get("events").size());
    }

    private static String readResource(String resource) throws IOException {
        InputStream input = ExternalCustomerPayNoteBexFunctionTest.class.getResourceAsStream(resource);
        assertNotNull(input, "Missing test resource: " + resource);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static Node requiredNode(Node root, String pointer) {
        Node node = nodeAt(root, pointer);
        assertNotNull(node, "Missing node at " + pointer);
        return node;
    }

    private static Node nodeAt(Node root, String pointer) {
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null) {
                return null;
            }
            if (current.getProperties() != null) {
                current = current.getProperties().get(segment);
            } else if (current.getItems() != null) {
                current = item(current, segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Node item(Node node, String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 && index < node.getItems().size() ? node.getItems().get(index) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String valueAt(Node root, String pointer) {
        Node node = requiredNode(root, pointer);
        assertNotNull(node.getValue(), "Expected scalar value at " + pointer);
        return String.valueOf(node.getValue());
    }

    private static List<String> patchPaths(BexExecutionResult result) {
        List<String> paths = new ArrayList<>();
        for (BexPatchEntry entry : result.changeset().entries()) {
            paths.add(entry.absolutePath());
        }
        return paths;
    }

    private static BexPatchEntry patchAt(BexExecutionResult result, String path) {
        for (BexPatchEntry entry : result.changeset().entries()) {
            if (path.equals(entry.absolutePath())) {
                return entry;
            }
        }
        throw new AssertionError("Missing patch at " + path + "; got " + patchPaths(result));
    }

    private static BexValue findEvent(BexExecutionResult result, String operation) {
        for (BexValue event : result.events().events()) {
            if (operation.equals(event.get("operation").asText())) {
                return event;
            }
        }
        return null;
    }
}
