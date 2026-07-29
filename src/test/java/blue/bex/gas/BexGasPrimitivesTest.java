package blue.bex.gas;

import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.language.processor.GasMeter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexGasPrimitivesTest {

    @Test
    void exposesExactManifestVocabularyAndWeightsInOrder() {
        String[] names = {
                "expressionEvaluated",
                "statementExecuted",
                "functionCalled",
                "intrinsicCalled",
                "documentRead",
                "eventRead",
                "processingEventRead",
                "currentContractRead",
                "stepsRead",
                "bindingRead",
                "variableRead",
                "constantRead",
                "resultValueRead",
                "pointerSegmentRead",
                "pointerSegmentWritten",
                "objectMemberRead",
                "listItemRead",
                "collectionItemVisited",
                "collectionItemProduced",
                "textBlockExamined",
                "textBlockConstructed",
                "integerLimbOperation",
                "comparisonNodeVisited",
                "sortComparison",
                "patchAppended",
                "eventAppended",
                "transientObjectMemberProduced",
                "transientListItemProduced",
                "blueOutputBoundary",
                "nodeIdentityRequested"
        };
        long[] weights = {
                1, 1, 2, 5, 2, 1, 1, 1, 1, 1,
                1, 1, 2, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 5, 5, 1, 1, 5, 5
        };

        assertEquals(30, BexGasCounter.values().length);
        assertEquals(30, BexGasCounter.defaultWeights().size());
        for (int index = 0; index < names.length; index++) {
            BexGasCounter counter = BexGasCounter.values()[index];
            assertEquals(names[index], counter.canonicalName());
            assertEquals(weights[index], counter.defaultWeight());
            assertEquals(counter, BexGasCounter.fromName(names[index]));
        }
        assertEquals("blue-bex/gas/2.0", BexGasCounter.SCHEDULE_ID);
        assertEquals(
                "sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d",
                BexGasCounter.MANIFEST_IDENTITY);
    }

    @Test
    void scheduleContainsAllCanonicalCounters() {
        BexGasSchedule schedule = BexGasSchedule.builder()
                .expressionEvaluated(7L)
                .pointerSegmentWritten(3L)
                .eventAppended(9L)
                .build();

        assertEquals(30, schedule.weights().size());
        assertEquals(30, schedule.counterWeights().size());
        assertEquals(7L, schedule.expressionEvaluated);
        assertEquals(3L, schedule.pointerSegmentWritten);
        assertEquals(9L, schedule.eventAppended);
        assertEquals(9L, schedule.weight("eventAppended"));
        assertThrows(UnsupportedOperationException.class,
                () -> schedule.counterWeights().put("other", 1L));
    }

    @Test
    void portableScheduleRejectsZeroOrNegativeWeights() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BexGasSchedule.builder()
                        .expressionEvaluated(0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> BexGasSchedule.builder()
                        .expressionEvaluated(-1L));
        assertTrue(BexGasSchedule.defaults()
                .counterWeights()
                .values()
                .stream()
                .allMatch(weight -> weight.longValue() > 0L));
    }

    @Test
    void customScheduleIdentityIsRetainedByItsLedger() {
        BexGasSchedule schedule = BexGasSchedule.builder()
                .expressionEvaluated(7L)
                .build();
        BexGasMeter meter = new BexGasMeter(schedule, 100L);
        meter.charge(BexGasCounter.EXPRESSION_EVALUATED);

        BexGasLedger ledger = meter.ledger();
        assertEquals(schedule.scheduleId(), ledger.scheduleId());
        assertEquals(
                schedule.manifestIdentity(),
                ledger.manifestIdentity());
        assertNotEquals(
                BexGasCounter.MANIFEST_IDENTITY,
                ledger.manifestIdentity());
    }

    @Test
    void rejectsBeforeWorkAndLeavesRejectedChargeOutOfTrace() {
        BexGasMeter meter = new BexGasMeter(BexGasSchedule.defaults(), 6L);
        meter.charge(
                BexGasCounter.EXPRESSION_EVALUATED,
                1L,
                "$.expr",
                "$literal",
                "evaluate");

        BexGasLimitExceededException exhausted = assertThrows(
                BexGasLimitExceededException.class,
                () -> meter.charge(BexGasCounter.PATCH_APPENDED, 2L));

        assertEquals(BexGasCounter.PATCH_APPENDED, exhausted.counter());
        assertEquals(1L, exhausted.admittedGas());
        assertEquals(6L, exhausted.effectiveBudget());
        assertEquals(1L, meter.totalGas());
        assertEquals(1, meter.trace().size());
        assertEquals("$.expr", meter.trace().get(0).sourcePath());
        assertEquals("$literal", meter.trace().get(0).operator());
        assertEquals("evaluate", meter.trace().get(0).reason());
    }

    @Test
    void localLimitCanOnlyReduceParentBudget() {
        BexGasMeter meter =
                new BexGasMeter(BexGasSchedule.defaults(), 100L, 4L);

        assertEquals(4L, meter.effectiveBudget());
        meter.charge(BexGasCounter.DOCUMENT_READ, 2L);
        assertEquals(4L, meter.totalGas());
        assertEquals(0L, meter.remainingGas());
        assertThrows(BexGasLimitExceededException.class,
                () -> meter.charge(BexGasCounter.EXPRESSION_EVALUATED));
        assertEquals(1, meter.trace().size());
    }

    @Test
    void ledgerIsAnImmutableValidatedSnapshotAndResultDerivesTotalFromIt() {
        BexGasMeter meter =
                new BexGasMeter(BexGasSchedule.defaults(), 100L);
        meter.charge(BexGasCounter.FUNCTION_CALLED, 1L);
        meter.charge(BexGasCounter.EVENT_APPENDED, 2L);

        BexGasLedger ledger = meter.ledger();
        assertEquals(12L, ledger.totalGas());
        assertEquals(2L, ledger.quantity(BexGasCounter.EVENT_APPENDED));
        assertThrows(UnsupportedOperationException.class,
                () -> ledger.trace().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new BexGasLedger(Arrays.asList(
                        new BexGasCharge(
                                1L,
                                BexGasCounter.EVENT_READ,
                                1L,
                                1L,
                                null,
                                null,
                                "bad-sequence"))));

        BexExecutionResult result =
                new BexExecutionResult(null, null, null, ledger, new BexMetrics());
        assertEquals(ledger, result.gasLedger());
        assertEquals(ledger.trace(), result.gasTrace());
        assertEquals(12L, result.gasUsed());
    }

    @Test
    void hostChildLedgerIsChargedLiveAndSubmittedExactlyOnce() {
        blue.language.processor.GasSchedule hostSchedule =
                blue.language.processor.GasSchedule.contracts10();
        GasMeter host = new GasMeter(hostSchedule, 100L);
        BexGasSchedule schedule = BexGasSchedule.defaults();
        GasMeter.ChildGasLedger child =
                host.childLedger(BexGasCounter.NAMESPACE,
                        schedule.counterWeights());
        BexGasMeter meter = new BexGasMeter(schedule, child, 20L);

        meter.charge(BexGasCounter.INTRINSIC_CALLED, 2L, "intrinsic");
        assertTrue(meter.hasHostLedger());
        assertFalse(meter.hostLedgerSubmitted());
        assertEquals(10L, child.totalGas());
        assertEquals(0L, host.totalGas());

        meter.submitHostLedger(host::merge);

        assertTrue(meter.hostLedgerSubmitted());
        assertEquals(10L, host.totalGas());
        assertEquals(1, host.trace().size());
        assertEquals("bex", host.trace().get(0).namespace());
        assertEquals("intrinsicCalled", host.trace().get(0).counter());
        assertThrows(IllegalStateException.class,
                () -> meter.submitHostLedger(host::merge));
        assertThrows(IllegalStateException.class,
                () -> meter.charge(BexGasCounter.EVENT_READ));
    }

    @Test
    void registryBoundNamedCountersCannotSelectArbitraryWeights() {
        String namespace = "intrinsic:sha256-test";
        String counter = "hashBlock";
        String qualified =
                BexGasMeter.qualifiedCounterName(namespace, counter);
        Map<String, Long> registered = new LinkedHashMap<>();
        registered.put(qualified, 3L);
        BexGasMeter meter = new BexGasMeter(
                BexGasSchedule.defaults(),
                20L,
                BexGasMeter.NO_LOCAL_LIMIT,
                registered);

        meter.chargeNamed(
                namespace,
                counter,
                2L,
                3L,
                "$.expr",
                "$intrinsic",
                "registry-child-work");

        assertEquals(6L, meter.totalGas());
        assertEquals(1, meter.trace().size());
        assertEquals(namespace, meter.trace().get(0).namespace());
        assertEquals(counter, meter.trace().get(0).counterName());
        assertNull(meter.trace().get(0).portableCounter());
        assertEquals(2L, meter.ledger().quantity(namespace, counter));
        assertEquals(3L, meter.childLedgerWeights().get(qualified));
        assertThrows(IllegalArgumentException.class,
                () -> meter.chargeNamed(
                        namespace,
                        counter,
                        1L,
                        4L,
                        null,
                        null,
                        "mismatched-weight"));
        assertThrows(IllegalArgumentException.class,
                () -> meter.chargeNamed(
                        namespace,
                        "unregistered",
                        1L));
        assertEquals(6L, meter.totalGas());
    }

    @Test
    void chargeAndLedgerRejectInvalidArithmeticAndMutation() {
        assertThrows(IllegalArgumentException.class,
                () -> new BexGasCharge(
                        0L,
                        BexGasCounter.EVENT_READ,
                        2L,
                        1L,
                        3L,
                        null,
                        null,
                        "invalid-gas"));
        assertThrows(IllegalArgumentException.class,
                () -> new BexGasMeter(BexGasSchedule.defaults(), 1L)
                        .charge(BexGasCounter.EVENT_READ, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> BexGasSchedule.builder()
                        .weight(BexGasCounter.EVENT_READ, -1L));
        List<BexGasCharge> external = Collections.singletonList(
                new BexGasCharge(
                        0L,
                        BexGasCounter.EVENT_READ,
                        1L,
                        1L,
                        null,
                        null,
                        "read"));
        BexGasLedger ledger = new BexGasLedger(external);
        assertEquals(1L, ledger.totalGas());
    }

    @Test
    void everyPhysicalLedgerReceivesItsFinalCallbackEvenWhenOneThrows() {
        BexGasSchedule schedule = BexGasSchedule.defaults();
        GasMeter parent = new GasMeter(
                blue.language.processor.GasSchedule.contracts10(),
                100L);
        GasMeter.ChildGasLedger bex = parent.childLedger(
                "bex-run",
                schedule.counterWeights());
        GasMeter.ChildGasLedger intrinsic = parent.childLedger(
                "bex-run/intrinsic-test",
                Collections.singletonMap("work", 1L));
        Map<String, GasMeter.ChildGasLedger> ledgers =
                new LinkedHashMap<>();
        ledgers.put(BexGasCounter.NAMESPACE, bex);
        ledgers.put("intrinsic-test", intrinsic);
        Map<String, Long> registered = Collections.singletonMap(
                BexGasMeter.qualifiedCounterName(
                        "intrinsic-test", "work"),
                1L);
        BexGasMeter meter = new BexGasMeter(
                schedule,
                ledgers,
                BexGasMeter.NO_LOCAL_LIMIT,
                registered);
        Map<GasMeter.ChildGasLedger, Boolean> callbacks =
                new IdentityHashMap<>();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> meter.failHostLedger(ledger -> {
                    callbacks.put(ledger, Boolean.TRUE);
                    if (ledger == bex) {
                        throw new IllegalStateException(
                                "first callback failed");
                    }
                }));

        assertEquals("first callback failed", failure.getMessage());
        assertEquals(2, callbacks.size());
        assertTrue(callbacks.containsKey(bex));
        assertTrue(callbacks.containsKey(intrinsic));
        assertTrue(meter.hostLedgerFinalized());
        assertThrows(
                IllegalStateException.class,
                () -> meter.failHostLedger(ignored -> {
                }));
    }
}
