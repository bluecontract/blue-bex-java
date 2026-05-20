package blue.bex;

import blue.bex.result.BexExecutionResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static blue.bex.test.BexTestFixtures.*;

@Disabled("local benchmark; prints counters and avoids CI timing thresholds")
class BexLocalBenchmarkTest {
    @Test
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
