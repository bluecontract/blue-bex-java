package blue.bex;

import blue.bex.api.BexExecutionContext;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BexNodeCursorTest {
    @Test
    void nodeCursorTrustedImmutableReadsWithoutMaterialization() {
        Node event = obj("payload", obj("status", "before"));
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeCursorTrustedImmutable(event))
                .gasLimit(1_000_000)
                .build();

        BexExecutionResult result = runStep(stepExpr(op("$event", "/payload/status")), context);

        assertEquals("before", simple(result.value()));
        assertEquals(0, result.metrics().nodeMaterializations());
    }

    @Test
    void nodeSnapshotIsStableAfterOriginalNodeMutation() {
        Node event = obj("payload", obj("status", "before"));
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.nodeSnapshot(event))
                .gasLimit(1_000_000)
                .build();
        event.getProperties().put("payload", obj("status", "after"));

        assertEquals("before", simple(runStep(stepExpr(op("$event", "/payload/status")), context).value()));
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedNodeFactoryUsesSafeSnapshotSemantics() {
        Node event = obj("payload", obj("status", "before"));
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.node(event))
                .gasLimit(1_000_000)
                .build();
        event.getProperties().put("payload", obj("status", "after"));

        assertEquals("before", simple(runStep(stepExpr(op("$event", "/payload/status")), context).value()));
    }
}
