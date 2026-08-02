package blue.bex.benchmark;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static blue.bex.benchmark.BexBenchmarkSupport.context;
import static blue.bex.benchmark.BexBenchmarkSupport.expected;
import static blue.bex.benchmark.BexBenchmarkSupport.frozen;
import static blue.bex.benchmark.BexBenchmarkSupport.integerList;
import static blue.bex.benchmark.BexBenchmarkSupport.list;
import static blue.bex.benchmark.BexBenchmarkSupport.obj;
import static blue.bex.benchmark.BexBenchmarkSupport.op;
import static blue.bex.benchmark.BexBenchmarkSupport.verify;

/**
 * BEX compile/runtime JMH matrix. Every measured invocation checks the admitted
 * result BlueId and the complete immutable named-gas ledger before returning.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class BexCoreBenchmark {
    @Benchmark
    public BexExecutionResult coldCompileAndExecute(
            BasicState state) {
        BexExecutionResult result = BexEngine.builder()
                .cache(new LruBexCompiledProgramCache())
                .build()
                .compileAndExecute(
                        state.coldSource,
                        context());
        return verify(result, state.coldExpected);
    }

    @Benchmark
    public BexExecutionResult compileCacheHit(
            BasicState state) {
        BexExecutionResult result = state.cacheEngine
                .compileAndExecute(
                        state.cacheSource,
                        context());
        return verify(result, state.cacheExpected);
    }

    @Benchmark
    public BexExecutionResult smallExecution(
            BasicState state) {
        return verify(
                state.engine.execute(
                        state.smallProgram,
                        context()),
                state.smallExpected);
    }

    @Benchmark
    public BexExecutionResult functionExecution(
            BasicState state) {
        return verify(
                state.engine.execute(
                        state.functionProgram,
                        context()),
                state.functionExpected);
    }

    @Benchmark
    public BexExecutionResult standaloneGas(
            BasicState state) {
        return verify(
                state.engine.execute(
                        state.standaloneGasProgram,
                        context()),
                state.standaloneGasExpected);
    }

    @Benchmark
    public BexExecutionResult collectionMap(
            CollectionState state) {
        return verify(
                state.engine.execute(
                        state.program,
                        context()),
                state.expected);
    }

    @Benchmark
    public BexExecutionResult pointerDepth(
            PointerState state) {
        return verify(
                state.engine.execute(
                        state.program,
                        context()),
                state.expected);
    }

    @Benchmark
    public BexExecutionResult textWork(
            TextNumericState state) {
        return verify(
                state.engine.execute(
                        state.textProgram,
                        context()),
                state.textExpected);
    }

    @Benchmark
    public BexExecutionResult integerAndDecimalWork(
            TextNumericState state) {
        return verify(
                state.engine.execute(
                        state.numericProgram,
                        context()),
                state.numericExpected);
    }

    @Benchmark
    public BexExecutionResult exactOutput(
            OutputState state) {
        return verify(
                state.engine.execute(
                        state.exactOutputProgram,
                        context("subject", state.exactValue)),
                state.exactOutputExpected);
    }

    @Benchmark
    public BexExecutionResult transientOutput(
            OutputState state) {
        return verify(
                state.engine.execute(
                        state.transientOutputProgram,
                        context()),
                state.transientOutputExpected);
    }

    @Benchmark
    public BexExecutionResult nodeBlueIdExact(
            OutputState state) {
        return verify(
                state.engine.execute(
                        state.exactIdentityProgram,
                        context("subject", state.exactValue)),
                state.exactIdentityExpected);
    }

    @Benchmark
    public BexExecutionResult nodeBlueIdTransient(
            OutputState state) {
        return verify(
                state.engine.execute(
                        state.transientIdentityProgram,
                        context()),
                state.transientIdentityExpected);
    }

    @Benchmark
    public BexExecutionResult intrinsicInvocation(
            IntrinsicState state) {
        return verify(
                state.engine.execute(
                        state.program,
                        context()),
                state.expected);
    }

    @State(Scope.Thread)
    public static class BasicState {
        private BexEngine engine;
        private BexProgramSource coldSource;
        private BexBenchmarkSupport.ExpectedOutcome coldExpected;
        private BexEngine cacheEngine;
        private BexProgramSource cacheSource;
        private BexBenchmarkSupport.ExpectedOutcome cacheExpected;
        private BexCompiledProgram smallProgram;
        private BexBenchmarkSupport.ExpectedOutcome smallExpected;
        private BexCompiledProgram functionProgram;
        private BexBenchmarkSupport.ExpectedOutcome functionExpected;
        private BexCompiledProgram standaloneGasProgram;
        private BexBenchmarkSupport.ExpectedOutcome standaloneGasExpected;

        @Setup(Level.Trial)
        public void setup() {
            engine = BexEngine.builder().build();

            BexProgramSource smallSource = BexProgramSource.expression(
                    frozen(op("$add", list(40, 2))));
            smallProgram = engine.compile(smallSource);
            smallExpected = expected(
                    engine.execute(smallProgram, context()));

            Node function = obj(
                    "args", obj("left", obj(), "right", obj()),
                    "expr", op("$add", list(
                            op("$var", "left"),
                            op("$var", "right"))));
            BexProgramSource functionSource = BexProgramSource.inline(
                    frozen(obj(
                            "functions", obj("sum", function),
                            "expr", op("$call", obj(
                                    "function", "sum",
                                    "args", obj(
                                            "left", 19,
                                            "right", 23))))));
            functionProgram = engine.compile(functionSource);
            functionExpected = expected(
                    engine.execute(functionProgram, context()));

            coldSource = BexProgramSource.inline(
                    frozen(obj(
                            "constants", obj("limit", 41),
                            "expr", obj(
                                    "answer", op("$add", list(
                                            op("$const", "limit"),
                                            1)),
                                    "label", op("$concat", list(
                                            "cold-", "compile"))))));
            coldExpected = expected(BexEngine.builder().build()
                    .compileAndExecute(coldSource, context()));

            cacheSource = BexProgramSource.expression(
                    frozen(op("$pointerGet", obj(
                            "object", obj(
                                    "nested", obj("answer", 42)),
                            "path", "/nested/answer"))));
            cacheEngine = BexEngine.builder()
                    .cache(new LruBexCompiledProgramCache())
                    .build();
            cacheEngine.compile(cacheSource);
            cacheExpected = expected(cacheEngine.compileAndExecute(
                    cacheSource, context()));

            BexProgramSource gasSource = BexProgramSource.expression(
                    frozen(op("$map", obj(
                            "in", integerList(128),
                            "item", "item",
                            "expr", op("$add", list(
                                    op("$var", "item"),
                                    1))))));
            standaloneGasProgram = engine.compile(gasSource);
            standaloneGasExpected = expected(
                    engine.execute(
                            standaloneGasProgram,
                            context()));
        }
    }

    @State(Scope.Thread)
    public static class CollectionState {
        @Param({"10", "100", "1000", "10000"})
        public int size;

        private BexEngine engine;
        private BexCompiledProgram program;
        private BexBenchmarkSupport.ExpectedOutcome expected;

        @Setup(Level.Trial)
        public void setup() {
            engine = BexEngine.builder().build();
            BexProgramSource source = BexProgramSource.expression(
                    frozen(op("$map", obj(
                            "in", integerList(size),
                            "item", "item",
                            "index", "index",
                            "expr", op("$add", list(
                                    op("$var", "item"),
                                    op("$var", "index")))))));
            program = engine.compile(source);
            expected = expected(engine.execute(program, context()));
        }
    }

    @State(Scope.Thread)
    public static class PointerState {
        @Param({"1", "8", "32", "128"})
        public int depth;

        private BexEngine engine;
        private BexCompiledProgram program;
        private BexBenchmarkSupport.ExpectedOutcome expected;

        @Setup(Level.Trial)
        public void setup() {
            Node nested = new Node().value("leaf");
            StringBuilder pointer = new StringBuilder();
            for (int index = depth - 1; index >= 0; index--) {
                String key = "level-" + index;
                nested = obj(key, nested);
            }
            for (int index = 0; index < depth; index++) {
                pointer.append('/').append("level-").append(index);
            }
            engine = BexEngine.builder().build();
            BexProgramSource source = BexProgramSource.expression(
                    frozen(op("$pointerGet", obj(
                            "object", nested,
                            "path", pointer.toString()))));
            program = engine.compile(source);
            expected = expected(engine.execute(program, context()));
        }
    }

    @State(Scope.Thread)
    public static class TextNumericState {
        private BexEngine engine;
        private BexCompiledProgram textProgram;
        private BexBenchmarkSupport.ExpectedOutcome textExpected;
        private BexCompiledProgram numericProgram;
        private BexBenchmarkSupport.ExpectedOutcome numericExpected;

        @Setup(Level.Trial)
        public void setup() {
            engine = BexEngine.builder().build();
            String text = repeat("Blue-\uD83D\uDE80-", 512);
            BexProgramSource textSource = BexProgramSource.expression(
                    frozen(op("$concat", list(
                            text,
                            "|",
                            text,
                            "|tail"))));
            textProgram = engine.compile(textSource);
            textExpected = expected(
                    engine.execute(textProgram, context()));

            BigInteger left = BigInteger.ONE.shiftLeft(4096)
                    .add(BigInteger.valueOf(17L));
            BigInteger right = BigInteger.ONE.shiftLeft(2048)
                    .add(BigInteger.valueOf(31L));
            BexProgramSource numericSource = BexProgramSource.expression(
                    frozen(obj(
                            "integer", op("$multiply", list(
                                    left, right)),
                            "decimalCompare", op("$gt", list(
                                    new BigDecimal(
                                            "98765432109876543210.875"),
                                    new BigDecimal(
                                            "12345678901234567890.125"))),
                            "decimalEqual", op("$eq", list(
                                    new BigDecimal(
                                            "12345678901234567890.125"),
                                    new BigDecimal(
                                            "12345678901234567890.125"))),
                            "decimalKind", op("$kind",
                                    new BigDecimal("1.2500")),
                            "decimalToInteger", op("$integer",
                                    new BigDecimal(
                                            "12345678901234567890.000")))));
            numericProgram = engine.compile(numericSource);
            numericExpected = expected(
                    engine.execute(numericProgram, context()));
        }

        private static String repeat(String value, int count) {
            StringBuilder repeated = new StringBuilder(
                    value.length() * count);
            for (int index = 0; index < count; index++) {
                repeated.append(value);
            }
            return repeated.toString();
        }
    }

    @State(Scope.Thread)
    public static class OutputState {
        private BexEngine engine;
        private BexValue exactValue;
        private BexCompiledProgram exactOutputProgram;
        private BexBenchmarkSupport.ExpectedOutcome exactOutputExpected;
        private BexCompiledProgram transientOutputProgram;
        private BexBenchmarkSupport.ExpectedOutcome transientOutputExpected;
        private BexCompiledProgram exactIdentityProgram;
        private BexBenchmarkSupport.ExpectedOutcome exactIdentityExpected;
        private BexCompiledProgram transientIdentityProgram;
        private BexBenchmarkSupport.ExpectedOutcome transientIdentityExpected;

        @Setup(Level.Trial)
        public void setup() {
            engine = BexEngine.builder().build();
            FrozenNode exactNode = frozen(obj(
                    "status", "exact",
                    "values", list(1, 2, 3)));
            exactValue = BexValues.frozen(exactNode);

            exactOutputProgram = engine.compile(
                    BexProgramSource.expression(
                            frozen(op("$binding", "subject"))));
            exactOutputExpected = expected(engine.execute(
                    exactOutputProgram,
                    context("subject", exactValue)));

            Node transientValue = obj(
                    "status", op("$concat", list(
                            "trans", "ient")),
                    "values", op("$listConcat", list(
                            list(1, 2),
                            list(3, 4))));
            transientOutputProgram = engine.compile(
                    BexProgramSource.expression(
                            frozen(transientValue)));
            transientOutputExpected = expected(engine.execute(
                    transientOutputProgram, context()));

            exactIdentityProgram = engine.compile(
                    BexProgramSource.expression(
                            frozen(op("$nodeBlueId",
                                    op("$binding", "subject")))));
            exactIdentityExpected = expected(engine.execute(
                    exactIdentityProgram,
                    context("subject", exactValue)));

            transientIdentityProgram = engine.compile(
                    BexProgramSource.expression(
                            frozen(op("$nodeBlueId", obj(
                                    "status", op("$concat", list(
                                            "trans", "ient")),
                                    "values", list(1, 2, 3, 4))))));
            transientIdentityExpected = expected(engine.execute(
                    transientIdentityProgram, context()));
        }
    }

    @State(Scope.Thread)
    public static class IntrinsicState {
        private static final String BLUE_ID =
                "BexBenchmarkIntrinsic";
        private static final String REGISTRY_IDENTITY =
                "blue-bex-benchmark-intrinsics/1.0";

        private BexEngine engine;
        private BexCompiledProgram program;
        private BexBenchmarkSupport.ExpectedOutcome expected;

        @Setup(Level.Trial)
        public void setup() {
            engine = BexEngine.builder()
                    .intrinsic(
                            BLUE_ID,
                            REGISTRY_IDENTITY,
                            Collections.singletonMap(
                                    "work", 7L),
                            invocation -> {
                                invocation.charge(
                                        "work", 3L,
                                        "benchmark-intrinsic-work");
                                return invocation.field("payload");
                            })
                    .build();
            BexProgramSource source = BexProgramSource.expression(
                    frozen(op("$intrinsic", obj(
                            "type", obj("blueId", BLUE_ID),
                            "payload", obj(
                                    "answer", 42,
                                    "label", "intrinsic")))));
            program = engine.compile(source);
            expected = expected(engine.execute(program, context()));
        }
    }
}
