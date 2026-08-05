package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.contracts.BexContractsFailureBoundary;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.test.TestBlue;
import blue.bex.test.TestGasLedgerCapability;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CircularSetIdentityCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexExecutionEvidenceLedgerTest {
    @Test
    void transientProviderUnavailabilityCommitsNoChildLedger() {
        Node content = obj("a", 1);
        String blueId = calculateBlueId(content);
        NodeProvider unavailable =
                ignored -> Collections.<Node>emptyList();

        try (TestBlue blue = new TestBlue(unavailable)) {
            RecordingGasHost host = new RecordingGasHost();
            assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> executeKindRead(blue, blueId, host));

            assertEquals(1, host.openCount);
            assertEquals(0, host.mergeCount);
            assertEquals(0L, host.parent.totalGas());
        }
    }

    @Test
    void deterministicInvalidEvidenceCommitsAdmittedTraceOnce() {
        Node content = obj("a", 1);
        String blueId = calculateBlueId(content);
        NodeProvider invalid = ignored -> {
            throw new InvalidExecutionEvidenceException(
                    "deterministic invalid provider evidence");
        };

        try (TestBlue blue = new TestBlue(invalid)) {
            RecordingGasHost host = new RecordingGasHost();
            assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> executeKindRead(blue, blueId, host));

            assertEquals(1, host.openCount);
            assertEquals(1, host.mergeCount);
            assertTrue(host.parent.totalGas() > 0L);
        }
    }

    @Test
    void hostedCyclicStructuralReadWithMissingProofIsDeterministic() {
        Node placeholder = obj(
                "label", "cyclic-content",
                "next", new Node().blueId("this#0"))
                .name("hosted-cyclic-member");
        List<Node> placeholders =
                Collections.singletonList(placeholder);
        String memberBlueId =
                CircularSetIdentityCalculator
                        .calculateCircularSetBlueIds(
                                placeholders)
                        .get(0);
        Node resolvedMember = placeholder.clone();
        resolvedMember.getProperties().get("next")
                .blueId(memberBlueId);
        ProoflessCyclicProvider provider =
                new ProoflessCyclicProvider(
                        memberBlueId, resolvedMember);

        try (TestBlue blue = new TestBlue(provider)) {
            RecordingGasHost host = new RecordingGasHost();
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> executeKindRead(
                                    blue, memberBlueId, host));

            assertTrue(failure.getMessage().contains(
                    "cyclic-set proof"));
            assertEquals(1, provider.proofQueries);
            assertEquals(1, host.openCount);
            assertEquals(1, host.mergeCount);
            assertTrue(host.parent.totalGas() > 0L);
        }
    }

    @Test
    void cyclicProofUnavailabilityUsesHostedDiscardLifecycle() {
        Node placeholder = obj(
                "label", "cyclic-content",
                "next", new Node().blueId("this#0"))
                .name("hosted-unavailable-cyclic-member");
        List<Node> placeholders =
                Collections.singletonList(placeholder);
        String memberBlueId =
                CircularSetIdentityCalculator
                        .calculateCircularSetBlueIds(
                                placeholders)
                        .get(0);
        Node resolvedMember = placeholder.clone();
        resolvedMember.getProperties().get("next")
                .blueId(memberBlueId);
        UnavailableProofCyclicProvider provider =
                new UnavailableProofCyclicProvider(
                        memberBlueId,
                        resolvedMember);

        try (TestBlue blue = new TestBlue(provider)) {
            RecordingGasHost host = new RecordingGasHost();
            ExecutionEvidenceUnavailableException failure =
                    assertThrows(
                            ExecutionEvidenceUnavailableException.class,
                            () -> executeKindRead(
                                    blue, memberBlueId, host));

            assertEquals(
                    Collections.singletonList(memberBlueId),
                    failure.requiredExactBlueIds());
            assertEquals(
                    "hosted cyclic proof temporarily unavailable",
                    failure.getMessage());
            assertEquals(1, provider.proofQueries);
            assertEquals(1, host.openCount);
            assertEquals(0, host.mergeCount);
            assertEquals(1, host.unavailableCount);
            assertEquals(0L, host.parent.totalGas());
        }
    }

    private static void executeKindRead(
            TestBlue blue,
            String blueId,
            RecordingGasHost host) {
        ResolvedSnapshot document = blue.resolveToSnapshot(
                obj("x", new Node().blueId(blueId)));
        BexExecutionContext context = BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(
                        document.frozenCanonicalRoot(),
                        document.frozenResolvedRoot(),
                        "/"))
                .gasLedgerHost(host)
                .semanticIdentityBoundary(
                        BexSemanticIdentityBoundary.STANDALONE)
                .failureBoundary(BexContractsFailureBoundary.INSTANCE)
                .build();
        BexEngine.builder()
                .language(blue.runtime())
                .build()
                .compileAndExecute(
                        BexProgramSource.inline(FrozenNode.fromResolvedNode(
                                stepExpr(op(
                                        "$kind",
                                        op("$document", "/x"))))),
                        context);
    }

    private static String calculateBlueId(Node node) {
        try (TestBlue blue = new TestBlue()) {
            return blue.calculateBlueId(node);
        }
    }

    private static final class ProoflessCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node resolvedMember;
        private int proofQueries;

        private ProoflessCyclicProvider(
                String memberBlueId,
                Node resolvedMember) {
            this.memberBlueId = memberBlueId;
            this.resolvedMember = resolvedMember.clone();
        }

        @Override
        public List<Node> fetchByBlueId(
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
            return CyclicSetProofResult.notFound();
        }
    }

    private static final class UnavailableProofCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node resolvedMember;
        private int proofQueries;

        private UnavailableProofCyclicProvider(
                String memberBlueId,
                Node resolvedMember) {
            this.memberBlueId = memberBlueId;
            this.resolvedMember = resolvedMember.clone();
        }

        @Override
        public List<Node> fetchByBlueId(
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
                    ? CyclicSetProofResult.unavailable(
                            "hosted cyclic proof temporarily unavailable")
                    : CyclicSetProofResult.notFound();
        }
    }

    private static final class RecordingGasHost
            implements BexGasLedgerHost {
        private final GasMeter parent =
                new GasMeter(GasSchedule.contracts10(), 100_000L);
        private BexGasLedgerCapability child;
        private int openCount;
        private int mergeCount;
        private int unavailableCount;

        @Override
        public BexGasLedgerCapability open(
                String namespace,
                Map<String, Long> counterWeights) {
            openCount++;
            child = TestGasLedgerCapability.wrap(
                    parent.childLedger(namespace, counterWeights));
            return child;
        }

        @Override
        public void submit(BexGasLedgerCapability ledger) {
            mergeCount++;
            assertEquals(child, ledger);
            parent.merge(((TestGasLedgerCapability) ledger).delegate());
        }

        @Override
        public void failedDeterministically(
                BexGasLedgerCapability ledger) {
            submit(ledger);
        }

        @Override
        public void evidenceUnavailable(
                BexGasLedgerCapability ledger) {
            unavailableCount++;
            assertEquals(child, ledger);
        }
    }
}
