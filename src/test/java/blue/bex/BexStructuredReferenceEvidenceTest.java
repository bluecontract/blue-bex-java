package blue.bex;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static blue.bex.test.BexTestFixtures.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexStructuredReferenceEvidenceTest {

    @Test
    void providerNotFoundIsIncompleteExecutionEvidenceNotSemanticAbsence() {
        Node content = obj("value", "known-shape");
        String blueId = calculateBlueId(content);
        MutableProvider provider = new MutableProvider(
                NodeProviderResult.notFound());

        try (Blue blue = new Blue(provider)) {
            ExecutionEvidenceUnavailableException failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> exactReference(blue, blueId).isObject());

            assertEquals(
                    Collections.singletonList(blueId),
                    failure.requiredExactBlueIds());
            assertTrue(failure.getMessage().contains(blueId));
            assertEquals(
                    NodeProviderOutcome.NOT_FOUND,
                    provider.current().outcome());
        }
    }

    @Test
    void providerUnavailableRetainsItsDiagnosticAndRequiredIdentity() {
        Node content = obj("value", "known-shape");
        String blueId = calculateBlueId(content);
        MutableProvider provider = new MutableProvider(
                NodeProviderResult.unavailable("feeder is offline"));

        try (Blue blue = new Blue(provider)) {
            ExecutionEvidenceUnavailableException failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> exactReference(blue, blueId).keys());

            assertEquals(
                    Collections.singletonList(blueId),
                    failure.requiredExactBlueIds());
            assertEquals("feeder is offline", failure.getMessage());
        }
    }

    @Test
    void invalidProviderEvidenceIsASeparateDeterministicFailure() {
        Node content = obj("value", "known-shape");
        String blueId = calculateBlueId(content);
        MutableProvider provider = new MutableProvider(
                NodeProviderResult.invalidEvidence(
                        "signature does not match"));

        try (Blue blue = new Blue(provider)) {
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> exactReference(blue, blueId).get("value"));

            assertEquals(
                    "signature does not match",
                    failure.getMessage());
        }
    }

    @Test
    void foundContentWithMismatchedIdentityIsInvalidEvidence() {
        Node expected = obj("value", "expected");
        String requestedBlueId = calculateBlueId(expected);
        MutableProvider provider = new MutableProvider(
                NodeProviderResult.found(
                        Collections.singletonList(
                                obj("value", "different"))));

        try (Blue blue = new Blue(provider)) {
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> exactReference(
                            blue, requestedBlueId).isObject());

            assertTrue(failure.getMessage().contains(
                    requestedBlueId));
            assertEquals(
                    NodeProviderOutcome.FOUND,
                    provider.current().outcome());
        }
    }

    @Test
    void arbitraryProviderBugIsNeverReclassifiedAsTransientUnavailability() {
        Node content = obj("value", "known-shape");
        String blueId = calculateBlueId(content);
        IllegalStateException providerBug =
                new IllegalStateException("provider implementation bug");
        NodeProvider provider = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String ignored) {
                throw providerBug;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String ignored) {
                throw providerBug;
            }
        };

        try (Blue blue = new Blue(provider)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> exactReference(blue, blueId).isObject());

            assertSame(providerBug, failure);
        }
    }

    @Test
    void priorValidMaterializationDoesNotHideAChangedProviderOutcome() {
        Node content = obj("value", "first-attempt");
        String blueId = calculateBlueId(content);
        MutableProvider provider = new MutableProvider(
                NodeProviderResult.found(
                        Collections.singletonList(content)));

        try (Blue blue = new Blue(provider)) {
            assertTrue(exactReference(blue, blueId).isObject());
            provider.set(NodeProviderResult.unavailable(
                    "second attempt cannot acquire evidence"));

            ExecutionEvidenceUnavailableException failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> exactReference(blue, blueId).isObject());

            assertEquals(
                    "second attempt cannot acquire evidence",
                    failure.getMessage());
            assertTrue(provider.fetches() >= 2);
        }
    }

    private static BexValue exactReference(
            Blue blue, String blueId) {
        return BexValues.referenceBacked(
                BexValues.frozen(FrozenNode.fromNode(
                        new Node().blueId(blueId))),
                blue);
    }

    private static String calculateBlueId(Node node) {
        try (Blue blue = new Blue()) {
            return blue.calculateBlueId(node);
        }
    }

    private static final class MutableProvider
            implements NodeProvider {
        private final AtomicReference<NodeProviderResult> result;
        private int fetches;

        private MutableProvider(NodeProviderResult initial) {
            result = new AtomicReference<>(initial);
        }

        private void set(NodeProviderResult next) {
            result.set(next);
        }

        private NodeProviderResult current() {
            return result.get();
        }

        private int fetches() {
            return fetches;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult current = fetchResultByBlueId(blueId);
            return current.outcome() == NodeProviderOutcome.FOUND
                    ? current.nodes()
                    : Collections.<Node>emptyList();
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(
                String blueId) {
            fetches++;
            return result.get();
        }
    }
}
