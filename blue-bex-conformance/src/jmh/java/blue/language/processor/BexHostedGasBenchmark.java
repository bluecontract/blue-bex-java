package blue.language.processor;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.benchmark.BexBenchmarkSupport;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.contracts.BexContractsFailureBoundary;
import blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost;
import blue.bex.gas.BexGasCharge;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.result.BexExecutionResult;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static blue.bex.benchmark.BexBenchmarkSupport.expected;
import static blue.bex.benchmark.BexBenchmarkSupport.frozen;
import static blue.bex.benchmark.BexBenchmarkSupport.integerList;
import static blue.bex.benchmark.BexBenchmarkSupport.list;
import static blue.bex.benchmark.BexBenchmarkSupport.obj;
import static blue.bex.benchmark.BexBenchmarkSupport.op;
import static blue.bex.benchmark.BexBenchmarkSupport.verify;

/** Contracts RuntimeWorkSession child-ledger benchmark with exact trace checks. */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class BexHostedGasBenchmark {
    @Benchmark
    public BexExecutionResult contractsHostedGas(
            HostedState state) {
        return state.executeAndVerify();
    }

    @State(Scope.Thread)
    public static class HostedState {
        private static final long HOST_BUDGET =
                100_000L;

        private BexEngine engine;
        private BexCompiledProgram program;
        private FrozenNode document;
        private BexBenchmarkSupport.ExpectedOutcome expected;

        @Setup(Level.Trial)
        public void setup() {
            engine = BexEngine.builder().build();
            document = FrozenNode.fromResolvedNode(new Node());
            BexProgramSource source = BexProgramSource.expression(
                    frozen(op("$map", obj(
                            "in", integerList(128),
                            "item", "item",
                            "expr", op("$add", list(
                                    op("$var", "item"),
                                    1))))));
            program = engine.compile(source);
            expected = expected(executeHosted());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            engine.close();
        }

        private BexExecutionResult executeAndVerify() {
            return verify(executeHosted(), expected);
        }

        private BexExecutionResult executeHosted() {
            GasMeter parent = new GasMeter(
                    GasSchedule.contracts10(),
                    HOST_BUDGET);
            RuntimeWorkSession session = new RuntimeWorkSession(
                    parent,
                    RuntimeWorkSession.Mode.PROCESSING);
            ProcessorExecutionContextBexGasLedgerHost host =
                    new ProcessorExecutionContextBexGasLedgerHost(
                            session,
                            "benchmarkHosted");
            BexExecutionContext context = BexExecutionContext.builder()
                    .document(new FrozenBexDocumentView(document))
                    .gasLedgerHost(host)
                    .semanticIdentityBoundary(
                            BexSemanticIdentityBoundary.STANDALONE)
                    .failureBoundary(
                            BexContractsFailureBoundary.INSTANCE)
                    .gasLimit(HOST_BUDGET)
                    .build();

            BexExecutionResult result = engine.execute(
                    program, context);
            assertHostedTrace(
                    result.gasTrace(),
                    session.stagedTrace());
            session.complete();
            assertHostedTrace(
                    result.gasTrace(),
                    parent.trace());
            if (parent.totalGas() != result.gasUsed()) {
                throw new IllegalStateException(
                        "hosted parent gas differs from BEX child total");
            }
            return result;
        }

        private static void assertHostedTrace(
                List<BexGasCharge> bex,
                List<GasTraceEntry> hosted) {
            if (bex.size() != hosted.size()) {
                throw new IllegalStateException(
                        "hosted trace size differs from BEX trace");
            }
            for (int index = 0; index < bex.size(); index++) {
                BexGasCharge child = bex.get(index);
                GasTraceEntry parent = hosted.get(index);
                if (child.sequence() != parent.sequence()
                        || !child.counterName().equals(
                                parent.counter())
                        || child.quantity() != parent.quantity()
                        || child.weight() != parent.weight()
                        || child.gas() != parent.subtotal()) {
                    throw new IllegalStateException(
                            "hosted trace differs at entry " + index);
                }
            }
        }
    }
}
