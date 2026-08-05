package blue.bex.result;

import blue.bex.value.BexValueMetrics;

/**
 * Invocation-owned mutable metrics state used only by BEX implementation
 * code. Public API consumers receive {@link BexMetricsSnapshot} instances.
 *
 * <p>This type is public solely because the implementation spans cohesive Java
 * packages; it is classified as internal implementation and is not a supported
 * extension point.</p>
 */
public final class BexMetricsRecorder implements BexValueMetrics {
    static final int COMPILED_EXECUTIONS = 0;
    static final int COMPILE_CACHE_HITS = 1;
    static final int COMPILE_CACHE_MISSES = 2;
    static final int INTERPRETED_FALLBACKS = 3;
    static final int EXPRESSION_EVALUATIONS = 4;
    static final int STATEMENT_EXECUTIONS = 5;
    static final int FUNCTION_CALLS = 6;
    static final int LOOP_ITERATIONS = 7;
    static final int FROZEN_DOCUMENT_READS = 8;
    static final int RESOLVED_DOCUMENT_READS = 9;
    static final int EVENT_READS = 10;
    static final int STEPS_READS = 11;
    static final int CURRENT_CONTRACT_READS = 12;
    static final int NODE_MATERIALIZATIONS = 13;
    static final int SIMPLE_MATERIALIZATIONS = 14;
    static final int FROZEN_OUTPUT_CONVERSIONS = 15;
    static final int NODE_OUTPUT_CONVERSIONS = 16;
    static final int CONTAINS_BEX_SCANS = 17;
    static final int CONTAINS_BEX_CACHE_HITS = 18;
    static final int CONTAINS_BEX_CACHE_MISSES = 19;
    static final int RESULT_VALUE_READS = 20;
    static final int RESULT_OVERLAY_EXACT_HITS = 21;
    static final int RESULT_OVERLAY_ANCESTOR_HITS = 22;
    static final int RESULT_OVERLAY_DOCUMENT_FALLBACKS = 23;
    static final int POINTER_PARSES = 24;
    static final int POINTER_CACHE_HITS = 25;
    static final int POINTER_CACHE_MISSES = 26;
    static final int FUNCTION_ARG_MAP_ALLOCATIONS = 27;
    static final int FROZEN_WRITER_NODE_FALLBACKS = 28;
    static final int COMPILE_NANOS = 29;
    static final int EXECUTE_NANOS = 30;
    private static final int SIZE = 31;

    private final long[] values;

    public BexMetricsRecorder() {
        this.values = new long[SIZE];
    }

    BexMetricsRecorder(BexMetricsSnapshot snapshot) {
        this.values = snapshot.copyValues();
    }

    void increment(int counter) {
        values[counter]++;
    }

    void addNonNegative(int counter, long amount) {
        values[counter] += Math.max(0L, amount);
    }

    long value(int counter) {
        return values[counter];
    }

    public BexMetricsSnapshot snapshot() {
        return new BexMetricsSnapshot(values);
    }

    public void incrementCompiledExecutions() { increment(COMPILED_EXECUTIONS); }
    public void incrementCompileCacheHits() { increment(COMPILE_CACHE_HITS); }
    public void incrementCompileCacheMisses() { increment(COMPILE_CACHE_MISSES); }
    public void incrementInterpretedFallbacks() { increment(INTERPRETED_FALLBACKS); }
    public void incrementExpressionEvaluations() { increment(EXPRESSION_EVALUATIONS); }
    public void incrementStatementExecutions() { increment(STATEMENT_EXECUTIONS); }
    public void incrementFunctionCalls() { increment(FUNCTION_CALLS); }
    public void incrementLoopIterations() { increment(LOOP_ITERATIONS); }
    public void incrementFrozenDocumentReads() { increment(FROZEN_DOCUMENT_READS); }
    public void incrementResolvedDocumentReads() { increment(RESOLVED_DOCUMENT_READS); }
    public void incrementEventReads() { increment(EVENT_READS); }
    public void incrementStepsReads() { increment(STEPS_READS); }
    public void incrementCurrentContractReads() { increment(CURRENT_CONTRACT_READS); }
    public void incrementNodeMaterializations() { increment(NODE_MATERIALIZATIONS); }
    public void incrementSimpleMaterializations() { increment(SIMPLE_MATERIALIZATIONS); }
    @Override
    public void incrementFrozenOutputConversions() { increment(FROZEN_OUTPUT_CONVERSIONS); }
    public void incrementNodeOutputConversions() { increment(NODE_OUTPUT_CONVERSIONS); }
    public void incrementContainsBexScans() { increment(CONTAINS_BEX_SCANS); }
    public void incrementContainsBexCacheHits() { increment(CONTAINS_BEX_CACHE_HITS); }
    public void incrementContainsBexCacheMisses() { increment(CONTAINS_BEX_CACHE_MISSES); }
    public void incrementResultValueReads() { increment(RESULT_VALUE_READS); }
    public void incrementResultOverlayExactHits() { increment(RESULT_OVERLAY_EXACT_HITS); }
    public void incrementResultOverlayAncestorHits() { increment(RESULT_OVERLAY_ANCESTOR_HITS); }
    public void incrementResultOverlayDocumentFallbacks() { increment(RESULT_OVERLAY_DOCUMENT_FALLBACKS); }
    public void incrementPointerParses() { increment(POINTER_PARSES); }
    public void incrementPointerCacheHits() { increment(POINTER_CACHE_HITS); }
    public void incrementPointerCacheMisses() { increment(POINTER_CACHE_MISSES); }
    public void incrementFunctionArgMapAllocations() { increment(FUNCTION_ARG_MAP_ALLOCATIONS); }
    @Override
    public void incrementFrozenWriterNodeFallbacks() { increment(FROZEN_WRITER_NODE_FALLBACKS); }
    public void addCompileNanos(long nanos) { addNonNegative(COMPILE_NANOS, nanos); }
    public void addExecuteNanos(long nanos) { addNonNegative(EXECUTE_NANOS, nanos); }

    public long compiledExecutions() { return value(COMPILED_EXECUTIONS); }
    public long compileCacheHits() { return value(COMPILE_CACHE_HITS); }
    public long compileCacheMisses() { return value(COMPILE_CACHE_MISSES); }
    public long interpretedFallbacks() { return value(INTERPRETED_FALLBACKS); }
    public long expressionEvaluations() { return value(EXPRESSION_EVALUATIONS); }
    public long statementExecutions() { return value(STATEMENT_EXECUTIONS); }
    public long functionCalls() { return value(FUNCTION_CALLS); }
    public long loopIterations() { return value(LOOP_ITERATIONS); }
    public long frozenDocumentReads() { return value(FROZEN_DOCUMENT_READS); }
    public long resolvedDocumentReads() { return value(RESOLVED_DOCUMENT_READS); }
    public long eventReads() { return value(EVENT_READS); }
    public long stepsReads() { return value(STEPS_READS); }
    public long currentContractReads() { return value(CURRENT_CONTRACT_READS); }
    public long nodeMaterializations() { return value(NODE_MATERIALIZATIONS); }
    public long simpleMaterializations() { return value(SIMPLE_MATERIALIZATIONS); }
    public long frozenOutputConversions() { return value(FROZEN_OUTPUT_CONVERSIONS); }
    public long nodeOutputConversions() { return value(NODE_OUTPUT_CONVERSIONS); }
    public long containsBexScans() { return value(CONTAINS_BEX_SCANS); }
    public long containsBexCacheHits() { return value(CONTAINS_BEX_CACHE_HITS); }
    public long containsBexCacheMisses() { return value(CONTAINS_BEX_CACHE_MISSES); }
    public long resultValueReads() { return value(RESULT_VALUE_READS); }
    public long resultOverlayExactHits() { return value(RESULT_OVERLAY_EXACT_HITS); }
    public long resultOverlayAncestorHits() { return value(RESULT_OVERLAY_ANCESTOR_HITS); }
    public long resultOverlayDocumentFallbacks() { return value(RESULT_OVERLAY_DOCUMENT_FALLBACKS); }
    public long pointerParses() { return value(POINTER_PARSES); }
    public long pointerCacheHits() { return value(POINTER_CACHE_HITS); }
    public long pointerCacheMisses() { return value(POINTER_CACHE_MISSES); }
    public long functionArgMapAllocations() { return value(FUNCTION_ARG_MAP_ALLOCATIONS); }
    public long frozenWriterNodeFallbacks() { return value(FROZEN_WRITER_NODE_FALLBACKS); }
    public long compileNanos() { return value(COMPILE_NANOS); }
    public long executeNanos() { return value(EXECUTE_NANOS); }
}
