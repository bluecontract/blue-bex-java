package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.CircularBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepDo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexExactReferenceDocumentTest {
    @Test
    void semanticAccessMaterializesVerifiedReferenceButExactIdentityDoesNot() {
        Node content = obj("a", 1, "values", list(1, 2));
        String blueId = calculateBlueId(content);
        AtomicInteger demands = new AtomicInteger();
        NodeProvider provider = requestedBlueId -> {
            demands.incrementAndGet();
            return blueId.equals(requestedBlueId)
                    ? Collections.singletonList(content.clone())
                    : Collections.<Node>emptyList();
        };

        try (Blue blue = new Blue(provider)) {
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(
                    obj("x", new Node().blueId(blueId)));
            assertTrue(snapshot.frozenResolvedRoot()
                    .property("x")
                    .isReferenceOnly());

            BexValue root = BexValues.referenceBacked(
                    BexValues.exact(
                            snapshot.frozenCanonicalRoot(),
                            snapshot.frozenResolvedRoot()),
                    blue);
            BexValue referenced = root.get("x");

            assertEquals(blueId, referenced.exactBlueId());
            assertEquals(0, demands.get());

            assertEquals("object", BexValues.kind(referenced));
            assertEquals(1, demands.get());
            assertTrue(referenced.get("blueId").isUndefined());
            assertEquals(BigInteger.ONE,
                    referenced.get("a").asInteger());
            assertEquals(java.util.Arrays.asList("a", "values"),
                    referenced.keys());
            assertEquals(1, demands.get());
        }
    }

    @Test
    void unavailableReferenceEvidencePropagatesInsteadOfBecomingUndefined() {
        Node unavailableContent = obj("a", 1);
        String unavailableBlueId = calculateBlueId(unavailableContent);
        AtomicInteger demands = new AtomicInteger();
        NodeProvider provider = requestedBlueId -> {
            demands.incrementAndGet();
            return Collections.<Node>emptyList();
        };

        try (Blue blue = new Blue(provider)) {
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(
                    obj("x", new Node().blueId(unavailableBlueId)));
            BexValue root = BexValues.referenceBacked(
                    BexValues.exact(
                            snapshot.frozenCanonicalRoot(),
                            snapshot.frozenResolvedRoot()),
                    blue);

            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> root.at("/x/a"));
            assertTrue(messageChain(failure).contains(unavailableBlueId));
            assertEquals(1, demands.get());
        }
    }

    @Test
    void resultOverlayCanPatchThroughAProviderBackedExactReference() {
        Node content = obj("a", 1, "untouched", 2);
        String blueId = calculateBlueId(content);
        AtomicInteger demands = new AtomicInteger();
        NodeProvider provider = requestedBlueId -> {
            demands.incrementAndGet();
            return blueId.equals(requestedBlueId)
                    ? Collections.singletonList(content.clone())
                    : Collections.<Node>emptyList();
        };

        try (Blue blue = new Blue(provider)) {
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(
                    obj("x", new Node().blueId(blueId)));
            BexExecutionContext context = BexExecutionContext.builder()
                    .document(new FrozenBexDocumentView(
                            snapshot.frozenCanonicalRoot(),
                            snapshot.frozenResolvedRoot(),
                            "/"))
                    .gasLimit(100_000)
                    .build();
            Node program = stepDo(list(
                    op("$appendChange", obj(
                            "op", "replace",
                            "path", "/x/a",
                            "val", 3)),
                    op("$return", op("$resultValue", "/x"))));

            BexValue result = BexEngine.builder()
                    .blue(blue)
                    .build()
                    .compileAndExecute(
                            BexProgramSource.inline(
                                    FrozenNode.fromResolvedNode(program)),
                            context)
                    .value();

            assertEquals(BigInteger.valueOf(3),
                    result.get("a").asInteger());
            assertEquals(BigInteger.valueOf(2),
                    result.get("untouched").asInteger());
            /*
             * Overlay evaluation and final output admission each use a
             * structured provider operation. Provider calls are diagnostics;
             * no long-lived Blue cache may suppress the second attempt.
             */
            assertEquals(2, demands.get());
        }
    }

    @Test
    void collapsedReferenceWithoutAResolverIsIncompleteNotAbsent() {
        Node content = obj("a", 1);
        String blueId = calculateBlueId(content);
        ResolvedSnapshot snapshot;
        try (Blue blue = new Blue()) {
            snapshot = blue.resolveToSnapshot(
                    obj("x", new Node().blueId(blueId)));
        }

        BexValue referenced = BexValues.exact(
                snapshot.frozenCanonicalRoot(),
                snapshot.frozenResolvedRoot()).get("x");

        assertTrue(BexValues.equal(referenced, referenced));
        BexException failure = assertThrows(
                BexException.class,
                referenced::isObject);
        assertTrue(failure.getMessage().contains(blueId));
        assertThrows(BexException.class,
                () -> referenced.get("blueId"));
    }

    @Test
    void cyclicMemberStructuralReadRequiresAndAcceptsCompleteSetProof() {
        Node member = obj(
                "label", "verified-cycle",
                "next", new Node().blueId("this#0"))
                .name("cyclic-member");
        java.util.List<Node> placeholders =
                Collections.singletonList(member);
        String memberBlueId =
                CircularBlueIdCalculator
                        .calculateCircularSetBlueIds(placeholders)
                        .get(0);
        Node resolvedMember = member.clone();
        resolvedMember.getProperties().get("next")
                .blueId(memberBlueId);
        VerifiedCyclicProvider provider =
                new VerifiedCyclicProvider(
                        memberBlueId,
                        resolvedMember,
                        placeholders);
        assertTrue(memberBlueId.contains("#"));

        try (Blue blue = new Blue(provider)) {
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(
                    obj("x", new Node().blueId(memberBlueId)));
            BexValue root = BexValues.referenceBacked(
                    BexValues.exact(
                            snapshot.frozenCanonicalRoot(),
                            snapshot.frozenResolvedRoot()),
                    blue);

            assertEquals(memberBlueId,
                    root.get("x").exactBlueId());
            assertEquals("verified-cycle",
                    root.at("/x/label").asText());
            assertEquals(memberBlueId,
                    root.at("/x/next").exactBlueId());
        }

        NodeProvider proofless = requested -> memberBlueId.equals(requested)
                ? Collections.singletonList(resolvedMember.clone())
                : Collections.<Node>emptyList();
        try (Blue blue = new Blue(proofless)) {
            BexValue prooflessMember = BexValues.referenceBacked(
                    BexValues.frozen(FrozenNode.fromNode(
                            new Node().blueId(memberBlueId))),
                    blue);
            assertEquals(memberBlueId,
                    prooflessMember.exactBlueId());
            assertThrows(
                    InvalidExecutionEvidenceException.class,
                    prooflessMember::isObject);
        }

        BexValue forgedResolvedMember = BexValues.exact(
                FrozenNode.fromNode(
                        new Node().blueId(memberBlueId)),
                FrozenNode.fromResolvedNode(
                        resolvedMember),
                memberBlueId);
        assertEquals(memberBlueId,
                forgedResolvedMember.exactBlueId());
        BexException forgedFailure = assertThrows(
                BexException.class,
                forgedResolvedMember::isObject);
        assertTrue(forgedFailure.getMessage().contains(
                memberBlueId));
    }

    @Test
    void nestedCyclicResolvedBodyRemainsOpaqueUntilCompleteProof() {
        CyclicFixture fixture = new CyclicFixture();
        Node ordinaryBody = obj(
                "label", "ordinary-exact-child");
        String ordinaryBlueId =
                calculateBlueId(ordinaryBody);
        FrozenNode canonicalParent =
                FrozenNode.fromNode(obj(
                        "cyclic",
                        new Node().blueId(
                                fixture.memberBlueId),
                        "ordinary",
                        new Node().blueId(
                                ordinaryBlueId)));
        FrozenNode resolvedParent =
                FrozenNode.fromResolvedNode(obj(
                        "cyclic",
                        fixture.resolvedMember,
                        "ordinary",
                        ordinaryBody));

        BexValue unverifiedParent = BexValues.exact(
                canonicalParent, resolvedParent);
        BexValue unverifiedCyclic =
                unverifiedParent.get("cyclic");
        assertEquals(
                fixture.memberBlueId,
                unverifiedCyclic.exactBlueId());
        BexException unverifiedFailure = assertThrows(
                BexException.class,
                unverifiedCyclic::isObject);
        assertTrue(unverifiedFailure.getMessage().contains(
                fixture.memberBlueId));

        BexValue ordinary =
                unverifiedParent.get("ordinary");
        assertEquals(
                ordinaryBlueId,
                ordinary.exactBlueId());
        assertTrue(ordinary.isObject());
        assertEquals(
                Collections.singletonList("label"),
                ordinary.keys());

        VerifiedCyclicProvider provider =
                new VerifiedCyclicProvider(
                        fixture.memberBlueId,
                        fixture.resolvedMember,
                        Collections.singletonList(
                                fixture.placeholder));
        try (Blue blue = new Blue(provider)) {
            BexValue verifiedParent =
                    BexValues.referenceBacked(
                            BexValues.exact(
                                    canonicalParent,
                                    resolvedParent),
                            blue);
            BexValue verifiedCyclic =
                    verifiedParent.get("cyclic");

            assertEquals(
                    fixture.memberBlueId,
                    verifiedCyclic.exactBlueId());
            assertEquals(
                    "verified-cycle",
                    verifiedCyclic.get("label")
                            .asText());
            assertEquals(1, provider.proofQueries);
        }
    }

    @Test
    void cyclicMemberContentFetchUnavailabilityStopsBeforeProofQuery() {
        CyclicFixture fixture = new CyclicFixture();
        CyclicEvidenceProvider provider =
                new CyclicEvidenceProvider(
                        NodeProviderResult.unavailable(
                                "cyclic member content temporarily unavailable"),
                        null);

        try (Blue blue = new Blue(provider)) {
            BexValue member = exactReference(
                    blue, fixture.memberBlueId);

            assertEquals(fixture.memberBlueId,
                    member.exactBlueId());
            ExecutionEvidenceUnavailableException failure =
                    assertThrows(
                            ExecutionEvidenceUnavailableException.class,
                            member::isObject);

            assertEquals(
                    Collections.singletonList(
                            fixture.memberBlueId),
                    failure.requiredExactBlueIds());
            assertEquals(
                    "cyclic member content temporarily unavailable",
                    failure.getMessage());
            assertEquals(0, provider.proofQueries);
        }
    }

    @Test
    void nullCyclicProofAfterFoundContentIsInvalidNotUnavailable() {
        CyclicFixture fixture = new CyclicFixture();
        CyclicEvidenceProvider provider =
                new CyclicEvidenceProvider(
                        NodeProviderResult.found(
                                Collections.singletonList(
                                        fixture.resolvedMember)),
                        null);

        try (Blue blue = new Blue(provider)) {
            BexValue member = exactReference(
                    blue, fixture.memberBlueId);

            assertEquals(fixture.memberBlueId,
                    member.exactBlueId());
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            member::isObject);

            assertTrue(failure.getMessage().contains(
                    "typed proof result"));
            assertEquals(1, provider.proofQueries);
        }
    }

    @Test
    void cyclicProofUnavailabilityAfterFoundContentRemainsTransient() {
        CyclicFixture fixture = new CyclicFixture();
        CyclicEvidenceProvider provider =
                new CyclicEvidenceProvider(
                        NodeProviderResult.found(
                                Collections.singletonList(
                                        fixture.resolvedMember)),
                        CyclicSetProofResult.unavailable(
                                "cyclic proof store temporarily unavailable"));

        try (Blue blue = new Blue(provider)) {
            BexValue member = exactReference(
                    blue, fixture.memberBlueId);

            assertEquals(fixture.memberBlueId,
                    member.exactBlueId());
            ExecutionEvidenceUnavailableException failure =
                    assertThrows(
                            ExecutionEvidenceUnavailableException.class,
                            member::isObject);

            assertEquals(
                    Collections.singletonList(
                            fixture.memberBlueId),
                    failure.requiredExactBlueIds());
            assertEquals(
                    "cyclic proof store temporarily unavailable",
                    failure.getMessage());
            assertEquals(1, provider.proofQueries);
        }
    }

    @Test
    void malformedCyclicProofIsDeterministicInvalidEvidence() {
        CyclicFixture fixture = new CyclicFixture();
        Node wrongMember = obj(
                "label", "wrong-cycle",
                "next", new Node().blueId("this#0"))
                .name("wrong-cyclic-member");
        CyclicSetProof wrongProof =
                CyclicSetProof.fromDeclaredPlaceholderSet(
                        Collections.singletonList(wrongMember));
        CyclicEvidenceProvider provider =
                new CyclicEvidenceProvider(
                        NodeProviderResult.found(
                                Collections.singletonList(
                                        fixture.resolvedMember)),
                        CyclicSetProofResult.found(
                                wrongProof));

        try (Blue blue = new Blue(provider)) {
            BexValue member = exactReference(
                    blue, fixture.memberBlueId);

            assertEquals(fixture.memberBlueId,
                    member.exactBlueId());
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            member::isObject);

            assertTrue(failure.getMessage().contains(
                    fixture.memberBlueId));
            assertEquals(1, provider.proofQueries);
        }
    }

    private static final class VerifiedCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node resolvedMember;
        private final CyclicSetProof proof;
        private int proofQueries;

        private VerifiedCyclicProvider(
                String memberBlueId,
                Node resolvedMember,
                java.util.List<Node> placeholders) {
            this.memberBlueId = memberBlueId;
            this.resolvedMember = resolvedMember.clone();
            this.proof = CyclicSetProof
                    .fromDeclaredPlaceholderSet(placeholders);
        }

        @Override
        public java.util.List<Node> fetchByBlueId(
                String requestedBlueId) {
            return memberBlueId.equals(requestedBlueId)
                    ? Collections.singletonList(
                    resolvedMember.clone())
                    : Collections.<Node>emptyList();
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(
                String requestedBlueId) {
            proofQueries++;
            return memberBlueId.equals(requestedBlueId)
                    ? CyclicSetProofResult.found(proof)
                    : CyclicSetProofResult.notFound();
        }
    }

    private static final class CyclicFixture {
        private final Node placeholder;
        private final String memberBlueId;
        private final Node resolvedMember;

        private CyclicFixture() {
            placeholder = obj(
                    "label", "verified-cycle",
                    "next", new Node().blueId("this#0"))
                    .name("cyclic-member");
            java.util.List<Node> placeholders =
                    Collections.singletonList(placeholder);
            memberBlueId = CircularBlueIdCalculator
                    .calculateCircularSetBlueIds(placeholders)
                    .get(0);
            resolvedMember = placeholder.clone();
            resolvedMember.getProperties().get("next")
                    .blueId(memberBlueId);
        }
    }

    private static final class CyclicEvidenceProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final NodeProviderResult result;
        private final CyclicSetProofResult proofResult;
        private int proofQueries;

        private CyclicEvidenceProvider(
                NodeProviderResult result,
                CyclicSetProofResult proofResult) {
            this.result = result;
            this.proofResult = proofResult;
        }

        @Override
        public java.util.List<Node> fetchByBlueId(
                String requestedBlueId) {
            NodeProviderResult current =
                    fetchResultByBlueId(requestedBlueId);
            return current.outcome() == NodeProviderOutcome.FOUND
                    ? current.nodes()
                    : Collections.<Node>emptyList();
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(
                String requestedBlueId) {
            return result;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(
                String requestedBlueId) {
            proofQueries++;
            return proofResult;
        }
    }

    private static String calculateBlueId(Node node) {
        try (Blue blue = new Blue()) {
            return blue.calculateBlueId(node);
        }
    }

    private static BexValue exactReference(
            Blue blue, String blueId) {
        return BexValues.referenceBacked(
                BexValues.frozen(FrozenNode.fromNode(
                        new Node().blueId(blueId))),
                blue);
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }
}
