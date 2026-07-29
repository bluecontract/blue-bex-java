package blue.bex;

import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Closed-vocabulary evidence that every primitive rejects before its work and
 * leaves the rejected charge absent from the canonical prefix.
 */
class BexPrimitiveExhaustionEvidenceTest {

    @TestFactory
    Stream<DynamicTest> everyPrimitiveHasExactRejectedChargeEvidence() {
        BexGasSchedule schedule = BexGasSchedule.defaults();
        return Arrays.stream(BexGasCounter.values())
                .map(counter -> DynamicTest.dynamicTest(
                        counter.canonicalName(),
                        () -> assertRejectedPrimitive(schedule, counter)));
    }

    private static void assertRejectedPrimitive(
            BexGasSchedule schedule,
            BexGasCounter rejectedCounter) {
        long prefixBudget =
                schedule.weight(BexGasCounter.EXPRESSION_EVALUATED);
        BexGasMeter meter =
                new BexGasMeter(schedule, prefixBudget);
        meter.charge(
                BexGasCounter.EXPRESSION_EVALUATED,
                1L,
                "admitted-prefix");
        AtomicInteger workAfterCharge = new AtomicInteger();

        BexGasLimitExceededException failure = assertThrows(
                BexGasLimitExceededException.class,
                () -> {
                    meter.charge(
                            rejectedCounter,
                            1L,
                            "must-reject");
                    workAfterCharge.incrementAndGet();
                });

        assertEquals(BexGasCounter.NAMESPACE, failure.namespace());
        assertEquals(rejectedCounter, failure.counter());
        assertEquals(
                rejectedCounter.canonicalName(),
                failure.counterName());
        assertEquals(1L, failure.quantity());
        assertEquals(
                schedule.weight(rejectedCounter),
                failure.weight());
        assertEquals(prefixBudget, failure.admittedGas());
        assertEquals(prefixBudget, failure.effectiveBudget());
        assertEquals(0, workAfterCharge.get());
        assertEquals(prefixBudget, meter.totalGas());
        assertEquals(1, meter.trace().size());
        assertEquals(
                BexGasCounter.EXPRESSION_EVALUATED,
                meter.trace().get(0).counter());
    }
}
