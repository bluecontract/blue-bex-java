package blue.bex.output;

import blue.bex.BexException;
import blue.bex.api.BexFailureBoundary;
import blue.bex.contracts.BexContractsFailureBoundary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BexUnclassifiedFailureBoundaryTest {
    @Test
    void arbitraryAndDecoratedHostFaultsAreNotDeterministic() {
        for (BexFailureBoundary boundary : new BexFailureBoundary[] {
                BexFailureBoundary.STANDALONE, BexContractsFailureBoundary.INSTANCE}) {
            for (RuntimeException failure : new RuntimeException[] {
                    new IllegalStateException("implementation defect"),
                    new java.io.UncheckedIOException(new java.io.IOException("database offline")),
                    new java.util.concurrent.CancellationException("cancelled")}) {
                assertSame(failure, boundary.preserveOrWrap("output", failure));
                assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED, boundary.classify(failure));
                assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED,
                        boundary.classify(new BexException("diagnostic decoration", failure)));
            }
            assertEquals(BexFailureBoundary.Classification.DETERMINISTIC,
                    boundary.classify(new BexException("invalid authored operator")));
        }
    }

    @Test
    void diagnosticCauseCyclesCannotHangClassificationOrTranslation() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED, BexFailureBoundary.STANDALONE.classify(first));
        assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED, BexContractsFailureBoundary.INSTANCE.classify(first));
        assertSame(first, BexContractsFailureBoundary.INSTANCE.translate(first));
        assertFalse(BexFailurePolicy.STANDALONE.evidenceUnavailable(first));
    }
}
