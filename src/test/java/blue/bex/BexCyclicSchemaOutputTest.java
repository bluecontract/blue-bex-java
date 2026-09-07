package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexFailureBoundary;
import blue.bex.api.BexProgramSource;
import blue.bex.contracts.BexContractsFailureBoundary;
import blue.bex.output.BexAdmittedValue;
import blue.bex.result.BexExecutionResult;
import blue.bex.test.TestBlue;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.Node;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexCyclicSchemaOutputTest {
    private static final String MEMBER = DirectBlueIdCalculator.calculateBlueId(v("not-a-cyclic-set")) + "#0";

    @ParameterizedTest(name = "transient {0} through {1}")
    @CsvSource({"nested, identity", "nested, event", "nested, patch",
            "type, identity", "type, event", "type, patch",
            "schema, identity", "schema, event", "schema, patch"})
    void transientCyclicReferencesFailDeterministicallyAtEveryExport(String field, String export) {
        Node value = dynamicField(field, dynamicField("blueId", MEMBER));
        BexException failure = assertThrows(BexException.class,
                () -> export(value, export, defaultContext()));
        assertTrue(failure.getMessage().contains("cannot counterfeit"), failure::getMessage);
        assertEquals(BexFailureBoundary.Classification.DETERMINISTIC,
                BexFailureBoundary.STANDALONE.classify(failure));
        assertEquals(BexFailureBoundary.Classification.DETERMINISTIC,
                BexContractsFailureBoundary.INSTANCE.classify(failure));
    }

    @ParameterizedTest(name = "carried exact {0}")
    @ValueSource(strings = {"nested", "type", "schema"})
    void exactCarriedChildrenRetainIdentityAcrossExports(String field) {
        BexValue ordinary = BexValues.frozen(frozen(obj("minimum", 0)));
        CyclicSchemaProvider provider = new CyclicSchemaProvider();
        try (TestBlue blue = new TestBlue(provider);
             BexEngine engine = BexEngine.builder().language(blue.runtime()).build()) {
            for (BexValue exact : Arrays.asList(ordinary, provider.reference())) {
                BexExecutionContext context = BexExecutionContext.builder().document(defaultDocumentView())
                        .binding("exact", exact).build();
                Node value = dynamicField(field, op("$binding", "exact"));
                String identity = export(engine, value, "identity", context).value().asText();
                BexExecutionResult event = export(engine, value, "event", context);
                BexExecutionResult patch = export(engine, value, "patch", context);
                assertEquals(1, event.events().admittedEvents().size());
                assertEquals(1, patch.changeset().entries().size());
                BexAdmittedValue emitted = event.events().admittedEvents().get(0);
                BexAdmittedValue changed = patch.changeset().entries().get(0).admittedValue();
                assertEquals(identity, emitted.nodeBlueId());
                assertEquals(identity, changed.nodeBlueId());
                assertEquals(exact.exactBlueId(), childReference(emitted.node(), field));
                assertEquals(exact.exactBlueId(), childReference(changed.node(), field));
            }
            if ("schema".equals(field)) {
                assertTrue(provider.contentReads > 0, "Schema kind inspection requires its exact content");
                assertTrue(provider.proofQueries > 0, "A cyclic schema body must have a verified complete-set proof");
            }
        }
    }

    @ParameterizedTest(name = "missing exact schema through {0}")
    @ValueSource(strings = {"identity", "event", "patch"})
    void unavailableExactCyclicSchemaRemainsMissingEvidence(String export) {
        CyclicSchemaProvider provider = new CyclicSchemaProvider();
        provider.available = false;
        try (TestBlue blue = new TestBlue(provider);
             BexEngine engine = BexEngine.builder().language(blue.runtime()).build()) {
            BexExecutionContext context = BexExecutionContext.builder().document(defaultDocumentView())
                    .binding("exact", provider.reference()).build();
            Node value = dynamicField("schema", op("$binding", "exact"));
            BexException failure = assertThrows(BexException.class, () -> export(engine, value, export, context));
            assertEquals(BexFailureBoundary.Classification.EVIDENCE_UNAVAILABLE,
                    BexFailureBoundary.STANDALONE.classify(failure));
            assertEquals(BexFailureBoundary.Classification.EVIDENCE_UNAVAILABLE,
                    BexContractsFailureBoundary.INSTANCE.classify(failure));
            assertFalse(failure.getMessage().contains("cannot counterfeit"));
            assertTrue(provider.contentReads > 0);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertInstanceOf(BexExecutionEvidenceUnavailableException.class, cause);
            assertEquals(Collections.singletonList(provider.memberBlueId),
                    ((BexExecutionEvidenceUnavailableException) cause).requiredExactBlueIds());
        }
    }

    @ParameterizedTest(name = "ordinary reference {0}")
    @ValueSource(strings = {"nested", "type", "schema"})
    void ordinaryTransientReferencesRemainValidWithoutProviderAcquisition(String field) {
        String blueId = DirectBlueIdCalculator.calculateBlueId(obj("minimum", 0));
        Node value = dynamicField(field, dynamicField("blueId", blueId));
        String identity = export(value, "identity", defaultContext()).value().asText();
        BexAdmittedValue emitted = export(value, "event", defaultContext()).events().admittedEvents().get(0);
        BexAdmittedValue changed = export(value, "patch", defaultContext()).changeset().entries().get(0).admittedValue();
        assertEquals(identity, emitted.nodeBlueId());
        assertEquals(identity, changed.nodeBlueId());
        assertEquals(blueId, childReference(emitted.node(), field));
        assertEquals(blueId, childReference(changed.node(), field));
    }

    private static BexExecutionResult export(Node value, String export, BexExecutionContext context) {
        return runStep(exportStep(value, export), context);
    }

    private static BexExecutionResult export(BexEngine engine, Node value, String export, BexExecutionContext context) {
        return engine.compileAndExecute(BexProgramSource.inline(frozen(exportStep(value, export))), context);
    }

    private static Node exportStep(Node value, String export) {
        if ("identity".equals(export)) return stepExpr(op("$nodeBlueId", value));
        if ("event".equals(export)) return stepDo(list(op("$appendEvent", value)));
        return stepDo(list(op("$appendChange", obj("op", "replace", "path", "/status", "val", value))));
    }

    private static Node dynamicField(String field, Object value) {
        return op("$objectSet", obj("object", op("$emptyObject", true), "key", field, "val", value));
    }

    private static String childReference(Node node, String field) {
        if ("schema".equals(field)) return node.getSchema().getBlueId();
        if ("type".equals(field)) return node.getType().getBlueId();
        return node.getProperties().get(field).getBlueId();
    }

    /** A real cyclic schema value: its enum contains the schema object itself. */
    private static final class CyclicSchemaProvider implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node resolvedMember;
        private final CyclicSetProof proof;
        private boolean available = true;
        private int contentReads, proofQueries;

        private CyclicSchemaProvider() {
            Node placeholder = obj("enum", list(new Node().blueId("this#0")));
            List<Node> placeholders = Collections.singletonList(placeholder);
            memberBlueId = CircularSetIdentityCalculator.calculateCircularSetBlueIds(placeholders).get(0);
            proof = CyclicSetProof.fromDeclaredPlaceholderSet(placeholders);
            resolvedMember = placeholder.clone();
            resolvedMember.getProperties().get("enum").getItems().get(0).blueId(memberBlueId);
        }

        private BexValue reference() {
            FrozenNode reference = frozen(new Node().blueId(memberBlueId));
            return BexValues.exact(reference, reference, memberBlueId);
        }

        @Override public List<Node> fetchByBlueId(String requested) {
            return available && memberBlueId.equals(requested) ? Collections.singletonList(resolvedMember.clone()) : Collections.emptyList();
        }

        @Override public NodeProviderResult fetchResultByBlueId(String requested) {
            if (memberBlueId.equals(requested)) {
                contentReads++;
                if (!available) return NodeProviderResult.unavailable("Cyclic schema evidence temporarily unavailable");
            }
            return NodeProvider.super.fetchResultByBlueId(requested);
        }

        @Override public CyclicSetProofResult cyclicSetProofFor(String requested) {
            proofQueries++;
            return memberBlueId.equals(requested) ? CyclicSetProofResult.found(proof) : CyclicSetProofResult.notFound();
        }
    }
}
