package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.CircularBlueIdCalculator;
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

        try (Blue blue = new Blue(unavailable)) {
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

        try (Blue blue = new Blue(invalid)) {
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
                CircularBlueIdCalculator
                        .calculateCircularSetBlueIds(
                                placeholders)
                        .get(0);
        Node resolvedMember = placeholder.clone();
        resolvedMember.getProperties().get("next")
                .blueId(memberBlueId);
        ProoflessCyclicProvider provider =
                new ProoflessCyclicProvider(
                        memberBlueId, resolvedMember);

        try (Blue blue = new Blue(provider)) {
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

    private static void executeKindRead(
            Blue blue,
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
                .build();
        BexEngine.builder()
                .blue(blue)
                .build()
                .compileAndExecute(
                        BexProgramSource.inline(FrozenNode.fromResolvedNode(
                                stepExpr(op(
                                        "$kind",
                                        op("$document", "/x"))))),
                        context);
    }

    private static String calculateBlueId(Node node) {
        try (Blue blue = new Blue()) {
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
        public CyclicSetProof cyclicSetProofFor(
                String requestedBlueId) {
            proofQueries++;
            return null;
        }
    }

    private static final class RecordingGasHost
            implements BexGasLedgerHost {
        private final GasMeter parent =
                new GasMeter(GasSchedule.contracts10(), 100_000L);
        private GasMeter.ChildGasLedger child;
        private int openCount;
        private int mergeCount;

        @Override
        public GasMeter.ChildGasLedger open(
                String namespace,
                Map<String, Long> counterWeights) {
            openCount++;
            child = parent.childLedger(namespace, counterWeights);
            return child;
        }

        @Override
        public void submit(GasMeter.ChildGasLedger ledger) {
            mergeCount++;
            assertEquals(child, ledger);
            parent.merge(ledger);
        }

        @Override
        public void failedDeterministically(
                GasMeter.ChildGasLedger ledger) {
            submit(ledger);
        }

        @Override
        public void evidenceUnavailable(
                GasMeter.ChildGasLedger ledger) {
            assertEquals(child, ledger);
        }
    }
}
