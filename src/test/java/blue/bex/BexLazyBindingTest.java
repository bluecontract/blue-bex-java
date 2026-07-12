package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasSchedule;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.runtime.BexRuntime;
import blue.bex.value.BexFrozenWriter;
import blue.bex.value.BexNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.n;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.runStep;
import static blue.bex.test.BexTestFixtures.simple;
import static blue.bex.test.BexTestFixtures.stepDo;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexLazyBindingTest {
    private static final long GENEROUS_GAS_LIMIT = 1_000_000L;

    @Test
    void unusedLazyBindingDoesNotInvokeSupplier() {
        AtomicInteger calls = new AtomicInteger();
        BexExecutionContext context = contextBuilder()
                .lazyBinding("expensive", () -> {
                    calls.incrementAndGet();
                    return BexValues.scalar("unused");
                })
                .build();

        BexExecutionResult result = runStep(stepExpr(n("result")), context);

        assertEquals("result", simple(result.value()));
        assertEquals(0, calls.get());
    }

    @Test
    void firstReadInvokesSupplierExactlyOnceAndReusesConcreteValue() {
        AtomicInteger calls = new AtomicInteger();
        BexValue supplied = BexValues.scalar("value");
        BexExecutionContext context = contextBuilder()
                .lazyBinding("value", () -> {
                    calls.incrementAndGet();
                    return supplied;
                })
                .build();

        assertSame(supplied, context.binding("value"));
        assertSame(supplied, context.binding("value"));
        assertEquals(1, calls.get());
    }

    @Test
    void builderCreatesIndependentLazySlotsForEachContext() {
        AtomicInteger calls = new AtomicInteger();
        BexExecutionContext.Builder builder = contextBuilder()
                .lazyBinding("value", () -> {
                    calls.incrementAndGet();
                    return BexValues.scalar(calls.get());
                });

        BexExecutionContext first = builder.build();
        BexExecutionContext second = builder.build();

        assertEquals(BigInteger.ONE, simple(first.binding("value")));
        assertEquals(BigInteger.valueOf(2), simple(second.binding("value")));
        assertEquals(2, calls.get());
    }

    @Test
    void nullAndUndefinedSupplierResultsBecomeMemoizedUndefined() {
        AtomicInteger nullCalls = new AtomicInteger();
        AtomicInteger undefinedCalls = new AtomicInteger();
        BexExecutionContext context = contextBuilder()
                .lazyBinding("nullResult", () -> {
                    nullCalls.incrementAndGet();
                    return null;
                })
                .lazyBinding("undefinedResult", () -> {
                    undefinedCalls.incrementAndGet();
                    return BexValues.undefined();
                })
                .build();

        assertSame(BexValues.undefined(), context.binding("nullResult"));
        assertSame(BexValues.undefined(), context.binding("nullResult"));
        assertSame(BexValues.undefined(), context.binding("undefinedResult"));
        assertSame(BexValues.undefined(), context.binding("undefinedResult"));
        assertEquals(1, nullCalls.get());
        assertEquals(1, undefinedCalls.get());
    }

    @Test
    void supplierFailureIsMemoizedWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("snapshot failed");
        BexExecutionContext context = contextBuilder()
                .lazyBinding("broken", () -> {
                    calls.incrementAndGet();
                    throw expected;
                })
                .build();

        assertSame(expected, assertThrows(IllegalStateException.class, () -> context.binding("broken")));
        assertSame(expected, assertThrows(IllegalStateException.class, () -> context.binding("broken")));
        assertEquals(1, calls.get());
    }

    @Test
    void recursiveSelfReadFailsDeterministicallyAndIsMemoized() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<BexExecutionContext> contextReference = new AtomicReference<>();
        BexExecutionContext context = contextBuilder()
                .lazyBinding("recursive", () -> {
                    calls.incrementAndGet();
                    return contextReference.get().binding("recursive");
                })
                .build();
        contextReference.set(context);

        IllegalStateException first = assertThrows(IllegalStateException.class, () -> context.binding("recursive"));
        IllegalStateException second = assertThrows(IllegalStateException.class, () -> context.binding("recursive"));

        assertEquals("Recursive lazy binding resolution", first.getMessage());
        assertSame(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void concurrentReadersInvokeSupplierOnceAndReceiveTheSameValue() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        CountDownLatch readersReady = new CountDownLatch(2);
        BexValue supplied = BexValues.scalar("value");
        BexExecutionContext context = contextBuilder()
                .lazyBinding("value", () -> {
                    calls.incrementAndGet();
                    supplierStarted.countDown();
                    await(releaseSupplier);
                    return supplied;
                })
                .build();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<BexValue> read = () -> {
                readersReady.countDown();
                return context.binding("value");
            };
            Future<BexValue> first = executor.submit(read);
            assertTrue(supplierStarted.await(5, TimeUnit.SECONDS));
            Future<BexValue> second = executor.submit(read);
            assertTrue(readersReady.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());

            releaseSupplier.countDown();

            assertSame(supplied, first.get(5, TimeUnit.SECONDS));
            assertSame(supplied, second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            releaseSupplier.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentReadersReceiveOneMemoizedFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        IllegalStateException expected = new IllegalStateException("failed once");
        BexExecutionContext context = contextBuilder()
                .lazyBinding("broken", () -> {
                    calls.incrementAndGet();
                    supplierStarted.countDown();
                    await(releaseSupplier);
                    throw expected;
                })
                .build();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BexValue> first = executor.submit(() -> context.binding("broken"));
            assertTrue(supplierStarted.await(5, TimeUnit.SECONDS));
            Future<BexValue> second = executor.submit(() -> context.binding("broken"));

            releaseSupplier.countDown();

            assertSame(expected, failureFrom(first));
            assertSame(expected, failureFrom(second));
            assertEquals(1, calls.get());
        } finally {
            releaseSupplier.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedConcurrentReaderWaitsForResolutionAndRestoresItsInterrupt() throws Exception {
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        CountDownLatch waitingReaderStarted = new CountDownLatch(1);
        AtomicReference<BexValue> waitingReaderValue = new AtomicReference<>();
        AtomicReference<Throwable> waitingReaderFailure = new AtomicReference<>();
        AtomicReference<Boolean> waitingReaderInterrupted = new AtomicReference<>();
        BexValue supplied = BexValues.scalar("value");
        BexExecutionContext context = contextBuilder()
                .lazyBinding("value", () -> {
                    supplierStarted.countDown();
                    await(releaseSupplier);
                    return supplied;
                })
                .build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BexValue> owner = executor.submit(() -> context.binding("value"));
            assertTrue(supplierStarted.await(5, TimeUnit.SECONDS));
            Thread waitingReader = new Thread(() -> {
                waitingReaderStarted.countDown();
                try {
                    waitingReaderValue.set(context.binding("value"));
                } catch (Throwable ex) {
                    waitingReaderFailure.set(ex);
                } finally {
                    waitingReaderInterrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            waitingReader.start();
            assertTrue(waitingReaderStarted.await(5, TimeUnit.SECONDS));
            waitingReader.interrupt();

            releaseSupplier.countDown();

            assertSame(supplied, owner.get(5, TimeUnit.SECONDS));
            waitingReader.join(5_000L);
            assertFalse(waitingReader.isAlive());
            assertSame(supplied, waitingReaderValue.get());
            assertNull(waitingReaderFailure.get());
            assertTrue(waitingReaderInterrupted.get());
        } finally {
            releaseSupplier.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void eagerAndLazyBindingsUseLastDeclarationWinsWithoutChangingOrder() {
        AtomicInteger lazyCalls = new AtomicInteger();
        BexExecutionContext eagerWins = contextBuilder()
                .lazyBinding("value", () -> {
                    lazyCalls.incrementAndGet();
                    return BexValues.scalar("lazy");
                })
                .binding("value", BexValues.scalar("eager"))
                .build();

        assertEquals("eager", simple(eagerWins.binding("value")));
        assertEquals(0, lazyCalls.get());

        BexExecutionContext lazyWins = contextBuilder()
                .binding("first", BexValues.scalar(1))
                .binding("value", BexValues.scalar("eager"))
                .binding("last", BexValues.scalar(3))
                .lazyBinding("value", () -> BexValues.scalar("lazy"))
                .build();

        assertEquals("lazy", simple(lazyWins.binding("value")));
        assertEquals(Arrays.asList("first", "value", "last"), Arrays.asList(lazyWins.bindings().keySet().toArray(new String[0])));
    }

    @Test
    void lazyBindingRejectsStandardBindingsAndInvalidArguments() {
        BexExecutionContext.Builder builder = contextBuilder();

        assertThrows(IllegalArgumentException.class, () -> builder.lazyBinding("event", () -> BexValues.scalar("event")));
        assertThrows(IllegalArgumentException.class, () -> builder.lazyBinding("currentContract", () -> BexValues.scalar("contract")));
        assertThrows(IllegalArgumentException.class, () -> builder.lazyBinding("steps", () -> BexValues.scalar("steps")));
        assertThrows(IllegalArgumentException.class, () -> builder.lazyBinding(" ", () -> BexValues.scalar("value")));
        assertThrows(NullPointerException.class, () -> builder.lazyBinding("value", null));

        BexExecutionContext eagerStandardBindings = builder
                .event(BexValues.scalar("event"))
                .currentContract(BexValues.scalar("contract"))
                .build();
        assertEquals("event", simple(eagerStandardBindings.event()));
        assertEquals("contract", simple(eagerStandardBindings.currentContract()));
    }

    @Test
    void unknownOrUnrelatedBindingsDoNotResolveLazySlots() {
        AtomicInteger leftCalls = new AtomicInteger();
        AtomicInteger rightCalls = new AtomicInteger();
        BexExecutionContext context = contextBuilder()
                .lazyBinding("left", () -> {
                    leftCalls.incrementAndGet();
                    return BexValues.scalar("left");
                })
                .lazyBinding("right", () -> {
                    rightCalls.incrementAndGet();
                    return BexValues.scalar("right");
                })
                .build();

        assertTrue(context.binding("missing").isUndefined());
        assertEquals("right", simple(context.binding("right")));
        assertEquals(0, leftCalls.get());
        assertEquals(1, rightCalls.get());
    }

    @Test
    void unselectedConditionalBranchDoesNotInvokeSupplier() {
        AtomicInteger calls = new AtomicInteger();
        BexExecutionContext context = contextBuilder()
                .lazyBinding("expensive", () -> {
                    calls.incrementAndGet();
                    return BexValues.scalar("unexpected");
                })
                .build();
        Node program = stepDo(list(
                op("$if", obj(
                        "cond", false,
                        "then", list(op("$return", op("$binding", "expensive"))),
                        "else", list(op("$return", "fallback"))))));

        BexExecutionResult result = runStep(program, context);

        assertEquals("fallback", simple(result.value()));
        assertEquals(0, calls.get());
    }

    @Test
    void bindingsMaterializesConcreteValuesInInsertionOrderAndReturnsUnmodifiableMap() {
        List<String> resolutionOrder = new java.util.ArrayList<>();
        BexValue second = BexValues.scalar("second");
        BexValue fourth = BexValues.scalar("fourth");
        BexExecutionContext context = contextBuilder()
                .binding("first", BexValues.scalar("first"))
                .lazyBinding("second", () -> {
                    resolutionOrder.add("second");
                    return second;
                })
                .binding("third", BexValues.scalar("third"))
                .lazyBinding("fourth", () -> {
                    resolutionOrder.add("fourth");
                    return fourth;
                })
                .build();

        Map<String, BexValue> bindings = context.bindings();

        assertEquals(Arrays.asList("first", "second", "third", "fourth"), Arrays.asList(bindings.keySet().toArray(new String[0])));
        assertEquals(Arrays.asList("second", "fourth"), resolutionOrder);
        assertSame(second, bindings.get("second"));
        assertSame(fourth, bindings.get("fourth"));
        assertSame(bindings, context.bindings());
        assertThrows(UnsupportedOperationException.class, () -> bindings.put("other", BexValues.scalar("other")));
    }

    @Test
    void bindingsStopsAtAndMemoizesSupplierFailure() {
        AtomicInteger beforeCalls = new AtomicInteger();
        AtomicInteger failureCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("broken snapshot");
        BexExecutionContext context = contextBuilder()
                .lazyBinding("before", () -> {
                    beforeCalls.incrementAndGet();
                    return BexValues.scalar("before");
                })
                .lazyBinding("failure", () -> {
                    failureCalls.incrementAndGet();
                    throw expected;
                })
                .lazyBinding("after", () -> {
                    afterCalls.incrementAndGet();
                    return BexValues.scalar("after");
                })
                .build();

        assertSame(expected, assertThrows(IllegalStateException.class, context::bindings));
        assertEquals(1, beforeCalls.get());
        assertEquals(1, failureCalls.get());
        assertEquals(0, afterCalls.get());
        assertSame(expected, assertThrows(IllegalStateException.class, () -> context.binding("failure")));
        assertEquals(1, failureCalls.get());
        assertEquals("after", simple(context.binding("after")));
        assertEquals(1, afterCalls.get());
    }

    @Test
    void lazyValuesRemainConcreteForKindsNavigationOverlaysAndWriters() {
        BexValue falseValue = BexValues.scalar(false);
        BexValue integerValue = BexValues.scalar(BigInteger.valueOf(7));
        BexValue doubleValue = BexValues.scalar(new BigDecimal("7.0"));
        BexValue textValue = BexValues.scalar("text");
        BexValue nullValue = BexValues.nullValue();
        BexValue listValue = BexValues.list(Arrays.asList(BexValues.scalar("first"), BexValues.scalar("second")));
        BexValue mapValue = BexValues.map(values("name", BexValues.scalar("Ada")));
        FrozenNode frozenNode = frozen(obj("id", "frozen"));
        BexValue frozenValue = BexValues.frozen(frozenNode);
        Node node = obj("id", "node");
        BexValue nodeValue = BexValues.nodeCursorTrustedImmutable(node);
        BexExecutionContext context = contextBuilder()
                .lazyBinding("false", () -> falseValue)
                .lazyBinding("integer", () -> integerValue)
                .lazyBinding("double", () -> doubleValue)
                .lazyBinding("text", () -> textValue)
                .lazyBinding("null", () -> nullValue)
                .lazyBinding("list", () -> listValue)
                .lazyBinding("map", () -> mapValue)
                .lazyBinding("frozen", () -> frozenValue)
                .lazyBinding("node", () -> nodeValue)
                .build();

        assertSame(falseValue, context.binding("false"));
        assertFalse(BexValues.truthy(context.binding("false")));
        assertEquals("boolean", BexValues.kind(context.binding("false")));
        assertTrue(BexValues.equal(context.binding("integer"), context.binding("double")));
        assertEquals("text", BexValues.kind(context.binding("text")));
        assertTrue(context.binding("null").isNull());
        assertEquals("second", simple(context.binding("list").get("1")));
        assertEquals(Arrays.asList("first", "second"), simple(context.binding("list")));
        assertEquals("Ada", simple(context.binding("map").get("name")));
        assertEquals(m("name", "Ada", "active", true), simple(BexValues.overlay(context.binding("map"), "active", BexValues.scalar(true))));
        assertEquals("frozen", simple(context.binding("frozen").get("id")));
        assertEquals(BexValues.frozenBlueId(frozenValue), BexValues.frozenBlueId(context.binding("frozen")));
        assertSame(frozenNode, BexFrozenWriter.toFrozen(context.binding("frozen")));
        assertEquals(m("id", "node"), simple(context.binding("node")));
        assertEquals("node", BexNodeWriter.toNode(context.binding("node")).getProperties().get("id").getValue());
    }

    @Test
    void lazyBindingPathReadMatchesEagerBehaviorAndGas() {
        BexValue value = BexValues.map(values(
                "nested", BexValues.map(values("answer", BexValues.scalar(42))),
                "items", BexValues.list(Arrays.asList(BexValues.scalar(1), BexValues.scalar(2)))));
        Node expression = obj(
                "answer", op("$binding", "subject/nested/answer"),
                "item", op("$binding", "subject/items/1"));

        assertEagerAndLazyExecution(stepExpr(expression), value, GENEROUS_GAS_LIMIT);
    }

    @Test
    void lazyBindingSupportsOutputAndUpdateInsertionWithExactGasEquivalence() {
        BexValue value = BexValues.map(values("kind", BexValues.scalar("Built"), "count", BexValues.scalar(2)));
        Node program = stepDo(list(
                op("$appendChange", obj("op", "replace", "path", "/state", "val", op("$binding", "subject"))),
                op("$appendEvent", op("$binding", "subject")),
                op("$return", op("$binding", "subject"))));

        assertEagerAndLazyExecution(program, value, GENEROUS_GAS_LIMIT);
    }

    @Test
    void eagerAndLazyProgramsExhaustAtTheSameGasBoundary() {
        BexValue value = BexValues.map(values("answer", BexValues.scalar(42)));
        Node program = stepExpr(obj(
                "answer", op("$binding", "subject/answer"),
                "kind", op("$kind", op("$binding", "subject"))));
        BexExecutionResult complete = runStep(program, eagerContext(value, GENEROUS_GAS_LIMIT));
        long exhaustedLimit = complete.gasUsed() - 1;
        AtomicInteger lazyCalls = new AtomicInteger();

        BexException eagerFailure = assertThrows(BexException.class,
                () -> runStep(program, eagerContext(value, exhaustedLimit)));
        BexException lazyFailure = assertThrows(BexException.class,
                () -> runStep(program, lazyContext(value, exhaustedLimit, lazyCalls)));

        assertEquals(eagerFailure.getMessage(), lazyFailure.getMessage());
    }

    @Test
    void eagerAndLazyProgramsReportTheSameSemanticError() {
        BexValue value = BexValues.scalar("not an integer");
        Node program = stepExpr(op("$integer", op("$binding", "subject")));
        AtomicInteger lazyCalls = new AtomicInteger();

        BexException eagerFailure = assertThrows(BexException.class,
                () -> runStep(program, eagerContext(value, GENEROUS_GAS_LIMIT)));
        BexException lazyFailure = assertThrows(BexException.class,
                () -> runStep(program, lazyContext(value, GENEROUS_GAS_LIMIT, lazyCalls)));

        assertEquals(eagerFailure.getMessage(), lazyFailure.getMessage());
        assertEquals(1, lazyCalls.get());
    }

    @Test
    void unusedLazyBindingAddsNoGas() {
        AtomicInteger calls = new AtomicInteger();
        Node program = stepExpr(n("result"));
        BexExecutionResult withoutBinding = runStep(program, contextBuilder().build());
        BexExecutionResult withUnusedLazyBinding = runStep(program, contextBuilder()
                .lazyBinding("unused", () -> {
                    calls.incrementAndGet();
                    return BexValues.scalar("unused");
                })
                .build());

        assertEquals(withoutBinding.gasUsed(), withUnusedLazyBinding.gasUsed());
        assertEquals(0, calls.get());
    }

    @Test
    void supplierFailureAddsNoGasBeyondTheExistingBindingRead() {
        BexGasSchedule schedule = BexGasSchedule.defaults();
        BexExecutionContext context = contextBuilder()
                .lazyBinding("broken", () -> {
                    throw new IllegalStateException("snapshot failed");
                })
                .build();
        BexCompiledProgram program = BexEngine.builder().build()
                .compile(BexProgramSource.inline(frozen(stepExpr(op("$binding", "broken")))));
        BexRuntime runtime = new BexRuntime(program, context, new Blue(), schedule,
                new BexMetrics(), new BexPointerCache());

        assertThrows(IllegalStateException.class, () -> runtime.readBinding("broken", Collections.<String>emptyList()));
        assertEquals(schedule.varRead, runtime.gas().used());
    }

    private static BexExecutionContext.Builder contextBuilder() {
        return BexExecutionContext.builder()
                .document(defaultDocumentView())
                .gasLimit(GENEROUS_GAS_LIMIT);
    }

    private static BexExecutionContext eagerContext(BexValue value, long gasLimit) {
        return contextBuilder()
                .binding("subject", value)
                .gasLimit(gasLimit)
                .build();
    }

    private static BexExecutionContext lazyContext(BexValue value, long gasLimit, AtomicInteger calls) {
        return contextBuilder()
                .lazyBinding("subject", () -> {
                    calls.incrementAndGet();
                    return value;
                })
                .gasLimit(gasLimit)
                .build();
    }

    private static void assertEagerAndLazyExecution(Node program, BexValue value, long gasLimit) {
        AtomicInteger lazyCalls = new AtomicInteger();
        BexExecutionResult eager = runStep(program, eagerContext(value, gasLimit));
        BexExecutionResult lazy = runStep(program, lazyContext(value, gasLimit, lazyCalls));

        assertEquals(simple(eager.value()), simple(lazy.value()));
        assertEquals(simple(eager.changeset().asValue()), simple(lazy.changeset().asValue()));
        assertEquals(simple(eager.events().asValue()), simple(lazy.events().asValue()));
        assertEquals(eager.gasUsed(), lazy.gasUsed());
        assertEquals(1, lazyCalls.get());
    }

    private static Map<String, BexValue> values(Object... entries) {
        Map<String, BexValue> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], (BexValue) entries[i + 1]);
        }
        return values;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent lazy binding test");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent lazy binding test", ex);
        }
    }

    private static Throwable failureFrom(Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected lazy binding read to fail");
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
    }
}
