package blue.bex;

import blue.bex.result.BexExecutionResult;

import static blue.bex.test.BexTestFixtures.*;

/**
 * Compile-only local benchmark corpus. The release benchmark gate compiles
 * this class under Java 8 but deliberately does not execute timing.
 */
class BexLocalBenchmarkTest {
    void largeStaticLiteralAndDocumentReads() {
        long startCompileAndExecute = System.nanoTime();
        BexExecutionResult result = runStep(stepExpr(obj(
                "status", op("$document", "/status"),
                "event", op("$event", "/kind"),
                "large", largeObject(1000)
        )), defaultContext());
        long elapsedMs = (System.nanoTime() - startCompileAndExecute) / 1_000_000L;

        System.out.println("execute ms=" + elapsedMs);
        System.out.println("frozen reads=" + result.metrics().frozenDocumentReads());
        System.out.println("event reads=" + result.metrics().eventReads());
        System.out.println("contains cache hits=" + result.metrics().containsBexCacheHits());
        System.out.println("output conversions=" + result.metrics().frozenOutputConversions());
    }
}
