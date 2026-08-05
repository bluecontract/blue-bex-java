package blue.bex.conformance;

import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.gas.BexGasCharge;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetricsSnapshot;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static blue.bex.test.BexTestFixtures.defaultContext;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.runExpr;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic property and differential checks supplementing the exact
 * published fixtures.
 */
class BexConformancePropertyTest {
    @Test
    void exactAndMaterializedRepresentationsAreDifferentiallyEquivalent() {
        ConformancePackage.Fixture fixture = fixture("bex-r-01");
        BexEngineFixtureAdapter adapter = new BexEngineFixtureAdapter();
        Object expectedResult = null;
        List<Map<String, Object>> expectedTrace = null;

        for (Object value : ConformancePackage.list(
                fixture.expected().get("variants"),
                fixture.path + ".expected.variants")) {
            Map<String, Object> variant =
                    ConformancePackage.map(value, "representation variant");
            String name = String.valueOf(variant.get("name"));
            BexFixtureRun run = adapter.execute(
                    fixture,
                    fixture.program(),
                    fixture.context(),
                    variant,
                    "property-representation-" + name);
            assertNull(run.failure, run.name + " failed");
            if (expectedTrace == null) {
                expectedResult = run.result;
                expectedTrace = run.gasTrace;
            } else {
                assertEquals(expectedResult, run.result,
                        name + " semantic result");
                assertEquals(expectedTrace, run.gasTrace,
                        name + " canonical gas trace");
            }
        }
    }

    @Test
    void fixtureAdapterActuallyExecutesDeclaredBatchingPreparation() {
        ConformancePackage.Fixture fixture = fixture("bex-r-08");
        BexEngineFixtureAdapter adapter = new BexEngineFixtureAdapter();
        BexFixtureRun coldUnbatched = null;
        BexFixtureRun warmBatched = null;

        for (Object value : ConformancePackage.list(
                fixture.expected().get("variants"),
                fixture.path + ".expected.variants")) {
            Map<String, Object> variant =
                    ConformancePackage.map(value, "provider variant");
            BexFixtureRun run = adapter.execute(
                    fixture,
                    fixture.program(),
                    fixture.context(),
                    variant,
                    "property-provider-" + variant.get("name"));
            assertNull(run.failure, run.name + " failed");
            if ("batched".equals(variant.get("batching"))) {
                warmBatched = run;
            } else {
                coldUnbatched = run;
            }
        }

        assertTrue(coldUnbatched != null);
        assertTrue(warmBatched != null);
        assertEquals("unbatched", coldUnbatched.providerBatching);
        assertEquals(0, coldUnbatched.providerWarmupNodeLoads);
        assertEquals(0, coldUnbatched.providerWarmupBatchLoads);
        assertTrue(coldUnbatched.providerRuntimeNodeLoads > 0);
        assertEquals(0, coldUnbatched.providerRuntimeBatchLoads);
        assertEquals(0, coldUnbatched.providerRuntimeCacheHits);

        assertEquals("batched", warmBatched.providerBatching);
        assertEquals(0, warmBatched.providerWarmupNodeLoads);
        assertEquals(1, warmBatched.providerWarmupBatchLoads);
        assertEquals(0, warmBatched.providerRuntimeNodeLoads);
        assertEquals(0, warmBatched.providerRuntimeBatchLoads);
        assertTrue(warmBatched.providerRuntimeCacheHits > 0);
        assertEquals(coldUnbatched.result, warmBatched.result);
        assertEquals(coldUnbatched.gasTrace, warmBatched.gasTrace);
    }

    @Test
    void compileCacheHitAndMissHaveIdenticalResultAndGas() {
        List<BexMetricsSnapshot> observed = new ArrayList<BexMetricsSnapshot>();
        BexEngine engine = BexEngine.builder()
                .cache(new LruBexCompiledProgramCache())
                .metrics(observed::add)
                .build();
        BexProgramSource source = BexProgramSource.inline(
                FrozenNode.fromResolvedNode(
                        stepExpr(op("$add", list(1, 2, 3)))));

        BexCompiledProgram coldProgram = engine.compile(source);
        BexExecutionResult cold =
                engine.execute(coldProgram, defaultContext());
        BexCompiledProgram warmProgram = engine.compile(source);
        BexExecutionResult warm =
                engine.execute(warmProgram, defaultContext());

        assertEquals(cold.value().toSimple(), warm.value().toSimple());
        assertEquals(traceSignature(cold), traceSignature(warm));
        assertEquals(cold.gasUsed(), warm.gasUsed());
        assertEquals(1L, observed.get(0).compileCacheMisses());
        assertEquals(1L, observed.get(2).compileCacheHits());
    }

    @Test
    void randomFiniteProgramsRemainDeterministicWithinLimits() {
        Random random = new Random(0xBEE20L);
        for (int example = 0; example < 64; example++) {
            int width = 1 + random.nextInt(8);
            Object[] operands = new Object[width];
            BigInteger expected = BigInteger.ZERO;
            for (int index = 0; index < width; index++) {
                long value = random.nextInt(2_000_001) - 1_000_000L;
                operands[index] = value;
                expected = expected.add(BigInteger.valueOf(value));
            }
            BexExecutionResult first =
                    runExpr(op("$add", list(operands)));
            BexExecutionResult second =
                    runExpr(op("$add", list(operands)));
            assertEquals(expected, first.value().toSimple(),
                    "generated example " + example);
            assertEquals(first.value().toSimple(), second.value().toSimple(),
                    "result example " + example);
            assertEquals(traceSignature(first), traceSignature(second),
                    "gas example " + example);
        }
    }

    @Test
    void operatorShortCircuitDoesNotEvaluateFailingOperand() {
        BexExecutionResult result = runExpr(op("$or", list(
                true,
                op("$integer", "not-an-integer"))));

        assertEquals(true, result.value().toSimple());
    }

    @Test
    void pointerSegmentsRoundTripWithoutEscapingLoss() {
        List<String> alphabet = Arrays.asList(
                "",
                "plain",
                "a/b",
                "a~b",
                "~1",
                "emoji-\uD83D\uDE80",
                "line\nbreak");
        Random random = new Random(0xBEE21L);
        for (int example = 0; example < 128; example++) {
            List<String> segments = new ArrayList<String>();
            int size = random.nextInt(12);
            for (int index = 0; index < size; index++) {
                segments.add(alphabet.get(
                        random.nextInt(alphabet.size())));
            }
            String pointer = JsonPointer.toPointer(segments);
            assertEquals(segments, JsonPointer.split(pointer),
                    "pointer " + pointer);
        }
    }

    @Test
    void numericIdentityDistinguishesIntegerFromDecimal() {
        BexExecutionResult result = runExpr(obj(
                "integer", op("$nodeBlueId", 1),
                "decimal", op("$nodeBlueId",
                        new Node().value(new BigDecimal("1.0")))));
        Object simple = result.value().toSimple();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> identities =
                (java.util.Map<String, Object>) simple;

        assertNotEquals(
                identities.get("integer"),
                identities.get("decimal"));
    }

    @Test
    void canonicalMergeSortIsStableForNumericallyEqualValues() {
        ConformancePackage.Fixture fixture = fixture("bex-g-09");
        Map<String, Object> program = m(
                "expr", m(
                        "$intrinsic", m(
                                "type", m(
                                        "blueId",
                                        BexEngineFixtureAdapter
                                                .SORT_FIXTURE_INTRINSIC),
                                "values", Arrays.<Object>asList(
                                        new BigDecimal("2.0"),
                                        BigInteger.ONE,
                                        BigInteger.valueOf(2L),
                                        new BigDecimal("1.0")))));
        BexEngineFixtureAdapter adapter = new BexEngineFixtureAdapter();
        BexFixtureRun first = adapter.execute(
                fixture,
                program,
                fixture.context(),
                Collections.<String, Object>emptyMap(),
                "property-sort-first");
        BexFixtureRun second = adapter.execute(
                fixture,
                program,
                fixture.context(),
                Collections.<String, Object>emptyMap(),
                "property-sort-second");

        assertNull(first.failure, "first stable sort failed");
        assertNull(second.failure, "second stable sort failed");
        assertEquals(
                Arrays.<Object>asList(
                        BigInteger.ONE,
                        new BigDecimal("1.0"),
                        new BigDecimal("2.0"),
                        BigInteger.valueOf(2L)),
                first.result);
        assertEquals(first.result, second.result);
        assertEquals(first.gasTrace, second.gasTrace);
        assertTrue(first.gasQuantity("sortComparison") > 0L);
    }

    @Test
    void admittedOutputRoundTripsItsExactBlueIdentity() {
        BexExecutionResult result = runExpr(obj(
                "a", 1,
                "b", list(true, "x")));
        Node admitted = result.output().node();

        assertTrue(result.output().reconstructed());
        assertEquals(
                result.output().nodeBlueId(),
                FrozenNode.fromNode(admitted).blueId());
    }

    private static ConformancePackage.Fixture fixture(String id) {
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            if (id.equals(fixture.id())) {
                return fixture;
            }
        }
        throw new IllegalArgumentException("Unknown fixture " + id);
    }

    private static List<String> traceSignature(
            BexExecutionResult result) {
        List<String> signature = new ArrayList<String>();
        for (BexGasCharge charge : result.gasTrace()) {
            signature.add(
                    charge.sequence()
                            + "|" + charge.namespace()
                            + "|" + charge.counterName()
                            + "|" + charge.quantity()
                            + "|" + charge.weight()
                            + "|" + charge.gas()
                            + "|" + charge.sourcePath()
                            + "|" + charge.operator()
                            + "|" + charge.reason());
        }
        return signature;
    }
}
