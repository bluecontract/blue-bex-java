package blue.bex;

import blue.bex.api.BexExecutionContext;
import blue.bex.api.FrozenBexDocumentView;
import org.junit.jupiter.api.Test;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BexContextReadTest {
    @Test
    void relativeDocumentPointerUsesDocumentViewScope() {
        FrozenBexDocumentView document = new FrozenBexDocumentView(frozen(defaultDocument()), frozen(defaultDocument()), "/nested");
        BexExecutionContext context = BexExecutionContext.builder()
                .document(document)
                .currentScopePath("/ignored")
                .gasLimit(1_000_000)
                .build();

        assertEquals("/nested", context.currentScopePath());
        assertEquals("node", simple(runStep(stepExpr(op("$document", "name")), context).value()));
    }
}
