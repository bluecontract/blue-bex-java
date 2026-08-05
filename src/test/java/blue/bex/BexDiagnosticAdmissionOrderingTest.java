package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasCharge;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasSchedule;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetricsRecorder;
import blue.bex.runtime.BexRuntime;
import blue.bex.test.TestBlue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToLongFunction;

import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepDo;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BexDiagnosticAdmissionOrderingTest {

    @Test
    void rejectedRootAndNestedFunctionChargesDoNotMutateFunctionMetrics() {
        RejectedExecution rejectedRoot = reject(
                stepExpr(new Node().value(true)),
                1L);
        assertEquals(BexGasCounter.FUNCTION_CALLED,
                rejectedRoot.failure.counter());
        assertEquals(0L, rejectedRoot.metrics.compiledExecutions());
        assertEquals(0L, rejectedRoot.metrics.functionCalls());

        Node nestedCallProgram = obj(
                "type", "Blue/BEX Program",
                "functions", obj(
                        "helper", obj("expr", true)),
                "expr", op("$call", obj("function", "helper")));
        RejectedExecution rejectedNested = reject(
                nestedCallProgram,
                3L);
        assertEquals(BexGasCounter.FUNCTION_CALLED,
                rejectedNested.failure.counter());
        assertEquals(1L, rejectedNested.metrics.compiledExecutions());
        assertEquals(1L, rejectedNested.metrics.functionCalls());
        assertEquals(1L, rejectedNested.metrics.expressionEvaluations());
    }

    @Test
    void rejectedExpressionAndStatementChargesDoNotMutateTheirMetrics() {
        RejectedExecution rejectedExpression = reject(
                stepExpr(new Node().value(true)),
                2L);
        assertEquals(BexGasCounter.EXPRESSION_EVALUATED,
                rejectedExpression.failure.counter());
        assertEquals(1L, rejectedExpression.metrics.compiledExecutions());
        assertEquals(1L, rejectedExpression.metrics.functionCalls());
        assertEquals(0L,
                rejectedExpression.metrics.expressionEvaluations());

        RejectedExecution rejectedStatement = reject(
                stepDo(list(op("$return", true))),
                2L);
        assertEquals(BexGasCounter.STATEMENT_EXECUTED,
                rejectedStatement.failure.counter());
        assertEquals(1L, rejectedStatement.metrics.compiledExecutions());
        assertEquals(1L, rejectedStatement.metrics.functionCalls());
        assertEquals(0L,
                rejectedStatement.metrics.statementExecutions());
    }

    @Test
    void rejectedReadChargesDoNotMutateReadMetrics() {
        assertRejectedReadMetric(
                op("$document", "/"),
                BexGasCounter.DOCUMENT_READ,
                BexMetricsRecorder::frozenDocumentReads);
        assertRejectedReadMetric(
                op("$document", obj("path", "/", "view", "resolved")),
                BexGasCounter.DOCUMENT_READ,
                BexMetricsRecorder::resolvedDocumentReads);
        assertRejectedReadMetric(
                op("$event", "/"),
                BexGasCounter.EVENT_READ,
                BexMetricsRecorder::eventReads);
        assertRejectedReadMetric(
                op("$currentContract", "/"),
                BexGasCounter.CURRENT_CONTRACT_READ,
                BexMetricsRecorder::currentContractReads);
        assertRejectedReadMetric(
                op("$steps", obj("step", "Build", "path", "/")),
                BexGasCounter.STEPS_READ,
                BexMetricsRecorder::stepsReads);
        assertRejectedReadMetric(
                op("$resultValue", "/"),
                BexGasCounter.RESULT_VALUE_READ,
                BexMetricsRecorder::resultValueReads);
    }

    @Test
    void rejectedIterationReadDoesNotAddAnUnfinishedLoopMetric() {
        Node programNode = stepDo(list(
                op("$forEach", obj(
                        "in", list(1, 2, 3),
                        "item", "item",
                        "do", list())),
                op("$return", true)));

        try (TestBlue blue = new TestBlue()) {
            BexEngine engine = BexEngine.builder()
                    .language(blue.runtime())
                    .build();
            BexCompiledProgram program = engine.compile(
                    BexProgramSource.inline(frozen(programNode)));
            BexExecutionResult unlimited = engine.execute(
                    program, context(1_000_000L));
            List<BexGasCharge> trace = unlimited.gasTrace();
            int rejectedIndex = nthCharge(
                    trace,
                    BexGasCounter.LIST_ITEM_READ,
                    "$forEach",
                    1);
            long prefixGas = gas(trace.subList(0, rejectedIndex));
            long admittedIterations = countCharges(
                    trace.subList(0, rejectedIndex),
                    BexGasCounter.LIST_ITEM_READ,
                    "$forEach");

            BexMetricsRecorder metrics = new BexMetricsRecorder();
            BexRuntime runtime = new BexRuntime(
                    program,
                    context(prefixGas),
                    blue.runtime(),
                    BexGasSchedule.defaults(),
                    metrics,
                    new BexPointerCache(),
                    BexIntrinsicRegistry.empty());

            BexException wrapped = assertThrows(
                    BexException.class,
                    runtime::execute);
            BexGasLimitExceededException failure = findCause(
                    wrapped,
                    BexGasLimitExceededException.class);
            assertNotNull(failure);

            assertEquals(BexGasCounter.LIST_ITEM_READ,
                    failure.counter());
            assertEquals(admittedIterations,
                    metrics.loopIterations());
        }
    }

    private static void assertRejectedReadMetric(
            Node expression,
            BexGasCounter expectedCounter,
            ToLongFunction<BexMetricsRecorder> metric) {
        RejectedExecution rejected = reject(
                stepExpr(expression),
                3L);
        assertEquals(expectedCounter, rejected.failure.counter());
        assertEquals(0L, metric.applyAsLong(rejected.metrics));
    }

    private static RejectedExecution reject(
            Node programNode,
            long gasLimit) {
        try (TestBlue blue = new TestBlue()) {
            BexEngine engine = BexEngine.builder()
                    .language(blue.runtime())
                    .build();
            BexCompiledProgram program = engine.compile(
                    BexProgramSource.inline(frozen(programNode)));
            BexMetricsRecorder metrics = new BexMetricsRecorder();
            BexRuntime runtime = new BexRuntime(
                    program,
                    context(gasLimit),
                    blue.runtime(),
                    BexGasSchedule.defaults(),
                    metrics,
                    new BexPointerCache(),
                    BexIntrinsicRegistry.empty());
            BexException wrapped = assertThrows(
                    BexException.class,
                    runtime::execute);
            BexGasLimitExceededException failure = findCause(
                    wrapped,
                    BexGasLimitExceededException.class);
            assertNotNull(failure);
            return new RejectedExecution(failure, metrics);
        }
    }

    private static <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static BexExecutionContext context(long gasLimit) {
        return BexExecutionContext.builder()
                .document(defaultDocumentView())
                .gasLimit(gasLimit)
                .build();
    }

    private static int nthCharge(
            List<BexGasCharge> trace,
            BexGasCounter counter,
            String operator,
            int occurrence) {
        int seen = 0;
        for (int index = 0; index < trace.size(); index++) {
            BexGasCharge charge = trace.get(index);
            if (charge.counter() == counter
                    && operator.equals(charge.operator())) {
                if (seen == occurrence) {
                    return index;
                }
                seen++;
            }
        }
        throw new AssertionError(
                "Missing charge " + counter + " occurrence " + occurrence);
    }

    private static long countCharges(
            List<BexGasCharge> trace,
            BexGasCounter counter,
            String operator) {
        long count = 0L;
        for (BexGasCharge charge : trace) {
            if (charge.counter() == counter
                    && operator.equals(charge.operator())) {
                count++;
            }
        }
        return count;
    }

    private static long gas(List<BexGasCharge> trace) {
        long total = 0L;
        for (BexGasCharge charge : trace) {
            total += charge.gas();
        }
        return total;
    }

    private static final class RejectedExecution {
        private final BexGasLimitExceededException failure;
        private final BexMetricsRecorder metrics;

        private RejectedExecution(
                BexGasLimitExceededException failure,
                BexMetricsRecorder metrics) {
            this.failure = failure;
            this.metrics = metrics;
        }
    }
}
