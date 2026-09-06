package blue.bex.output;

import blue.bex.BexException;
import blue.bex.api.BexFailureBoundary;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.contracts.BexContractsFailureBoundary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.bex.test.BexTestFixtures.*;

class BexUnclassifiedFailureBoundaryTest {
    @Test
    void arbitraryAndDecoratedHostFaultsAreNotDeterministic() {
        for (BexFailureBoundary boundary : new BexFailureBoundary[] {
                BexFailureBoundary.STANDALONE, BexContractsFailureBoundary.INSTANCE}) {
            for (RuntimeException failure : new RuntimeException[] {
                    new IllegalStateException("implementation defect"),
                    new IllegalArgumentException("host argument defect"),
                    new ArithmeticException("host arithmetic defect"),
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
    void realInterpreterPreservesUnknownBindingFailures() {
        for (RuntimeException expected : new RuntimeException[] {
                new IllegalArgumentException("host argument defect"),
                new ArithmeticException("host arithmetic defect"),
                new java.io.UncheckedIOException(new java.io.IOException("database offline")),
                new java.util.concurrent.CancellationException("cancelled")}) {
            BexExecutionContext context = BexExecutionContext.builder()
                    .document(defaultDocumentView())
                    .lazyBinding("hostValue", () -> { throw expected; })
                    .build();
            try (BexEngine engine = BexEngine.builder().build()) {
                RuntimeException actual = assertThrows(RuntimeException.class,
                        () -> engine.compileAndExecute(BexProgramSource.expression(
                                frozen(op("$binding", "hostValue"))), context));
                assertSame(expected, actual);
                assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED,
                        BexFailureBoundary.STANDALONE.classify(actual));
                assertEquals(BexFailureBoundary.Classification.UNCLASSIFIED,
                        BexContractsFailureBoundary.INSTANCE.classify(actual));
            }
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
