package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.gas.BexGasLedger;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetricsSnapshot;
import blue.bex.value.BexValues;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared-engine concurrency proof for cache, context, result, metric and gas isolation. */
class BexConcurrentEngineIsolationTest {
    private static final int TASKS = 48;

    @Test
    void oneColdEngineIsolatesConcurrentProgramsAndContexts()
            throws Exception {
        ConcurrentLinkedQueue<BexMetricsSnapshot> deliveredMetrics =
                new ConcurrentLinkedQueue<BexMetricsSnapshot>();
        BexEngine shared = BexEngine.builder()
                .cache(new LruBexCompiledProgramCache())
                .metrics(deliveredMetrics::add)
                .build();
        BexProgramSource richSource = richSource();
        BexProgramSource textSource = textSource();

        BexExecutionResult richReference = BexEngine.builder().build()
                .compileAndExecute(richSource, context(0, new AtomicInteger()));
        BexExecutionResult textReference = BexEngine.builder().build()
                .compileAndExecute(textSource, context(0, new AtomicInteger()));
        BexGasLedger richGas = richReference.gasLedger();
        BexGasLedger textGas = textReference.gasLedger();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Observation>> futures =
                new ArrayList<Future<Observation>>(TASKS);
        try {
            for (int index = 0; index < TASKS; index++) {
                final int taskIndex = index;
                final boolean rich = (index & 1) == 0;
                futures.add(executor.submit(() -> {
                    AtomicInteger lazyCalls = new AtomicInteger();
                    start.await();
                    BexExecutionResult result = shared.compileAndExecute(
                            rich ? richSource : textSource,
                            context(taskIndex, lazyCalls));
                    return new Observation(
                            taskIndex,
                            rich,
                            lazyCalls.get(),
                            result);
                }));
            }
            start.countDown();

            for (Future<Observation> future : futures) {
                Observation observed = future.get(30L, TimeUnit.SECONDS);
                if (observed.rich) {
                    assertEquals(1, observed.lazyCalls,
                            "lazy binding must be memoized per context");
                    assertEquals(expectedRich(observed.index),
                            observed.result.value().toSimple());
                    assertEquals(richGas, observed.result.gasLedger());
                    assertEquals(
                            richReference.metrics().expressionEvaluations(),
                            observed.result.metrics().expressionEvaluations());
                } else {
                    assertEquals(0, observed.lazyCalls,
                            "unread lazy binding leaked across contexts");
                    assertEquals("task-" + observed.index + "!",
                            observed.result.value().toSimple());
                    assertEquals(textGas, observed.result.gasLedger());
                    assertEquals(
                            textReference.metrics().expressionEvaluations(),
                            observed.result.metrics().expressionEvaluations());
                }
                assertTrue(observed.result.changeset().entries().isEmpty());
                assertTrue(observed.result.events().events().isEmpty());
                assertFalse(observed.result.output().nodeBlueId()
                        .trim().isEmpty());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    10L, TimeUnit.SECONDS));
        }

        assertEquals(TASKS, deliveredMetrics.size());
        assertFalse(deliveredMetrics.contains(null));
    }

    private static BexProgramSource richSource() {
        return BexProgramSource.expression(frozen(obj(
                "task", op("$binding", "task"),
                "lazyFirst", op("$binding", "lazy"),
                "lazySecond", op("$binding", "lazy"),
                "sum", op("$add", list(
                        op("$binding", "number"),
                        1)),
                "pointer", op("$pointerGet", obj(
                        "object", op("$binding", "payload"),
                        "path", "/nested/value")))));
    }

    private static BexProgramSource textSource() {
        return BexProgramSource.expression(frozen(op(
                "$concat",
                list(op("$binding", "task"), "!"))));
    }

    private static BexExecutionContext context(
            int index,
            AtomicInteger lazyCalls) {
        return BexExecutionContext.builder()
                .document(defaultDocumentView())
                .binding("task", BexValues.scalar("task-" + index))
                .binding("number", BexValues.scalar(index))
                .binding("payload", BexValues.fromSimple(m(
                        "nested", m("value", "payload-" + index))))
                .lazyBinding("lazy", () -> {
                    lazyCalls.incrementAndGet();
                    return BexValues.scalar("lazy-" + index);
                })
                .gasLimit(1_000_000L)
                .build();
    }

    private static Map<String, Object> expectedRich(int index) {
        return m(
                "lazyFirst", "lazy-" + index,
                "lazySecond", "lazy-" + index,
                "pointer", "payload-" + index,
                "sum", BigInteger.valueOf(index + 1L),
                "task", "task-" + index);
    }

    private static final class Observation {
        private final int index;
        private final boolean rich;
        private final int lazyCalls;
        private final BexExecutionResult result;

        private Observation(
                int index,
                boolean rich,
                int lazyCalls,
                BexExecutionResult result) {
            this.index = index;
            this.rich = rich;
            this.lazyCalls = lazyCalls;
            this.result = result;
        }
    }
}
