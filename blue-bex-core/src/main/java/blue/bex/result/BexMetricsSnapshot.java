package blue.bex.result;

/** Immutable metrics captured at a compile or execution boundary. */
public final class BexMetricsSnapshot {
    private final long[] values;

    BexMetricsSnapshot(long[] values) {
        this.values = values.clone();
    }

    long[] copyValues() {
        return values.clone();
    }

    private long value(int counter) {
        return values[counter];
    }

    public long compiledExecutions() { return value(BexMetricsRecorder.COMPILED_EXECUTIONS); }
    public long compileCacheHits() { return value(BexMetricsRecorder.COMPILE_CACHE_HITS); }
    public long compileCacheMisses() { return value(BexMetricsRecorder.COMPILE_CACHE_MISSES); }
    public long interpretedFallbacks() { return value(BexMetricsRecorder.INTERPRETED_FALLBACKS); }
    public long expressionEvaluations() { return value(BexMetricsRecorder.EXPRESSION_EVALUATIONS); }
    public long statementExecutions() { return value(BexMetricsRecorder.STATEMENT_EXECUTIONS); }
    public long functionCalls() { return value(BexMetricsRecorder.FUNCTION_CALLS); }
    public long loopIterations() { return value(BexMetricsRecorder.LOOP_ITERATIONS); }
    public long frozenDocumentReads() { return value(BexMetricsRecorder.FROZEN_DOCUMENT_READS); }
    public long resolvedDocumentReads() { return value(BexMetricsRecorder.RESOLVED_DOCUMENT_READS); }
    public long eventReads() { return value(BexMetricsRecorder.EVENT_READS); }
    public long stepsReads() { return value(BexMetricsRecorder.STEPS_READS); }
    public long currentContractReads() { return value(BexMetricsRecorder.CURRENT_CONTRACT_READS); }
    public long nodeMaterializations() { return value(BexMetricsRecorder.NODE_MATERIALIZATIONS); }
    public long simpleMaterializations() { return value(BexMetricsRecorder.SIMPLE_MATERIALIZATIONS); }
    public long frozenOutputConversions() { return value(BexMetricsRecorder.FROZEN_OUTPUT_CONVERSIONS); }
    public long nodeOutputConversions() { return value(BexMetricsRecorder.NODE_OUTPUT_CONVERSIONS); }
    public long containsBexScans() { return value(BexMetricsRecorder.CONTAINS_BEX_SCANS); }
    public long containsBexCacheHits() { return value(BexMetricsRecorder.CONTAINS_BEX_CACHE_HITS); }
    public long containsBexCacheMisses() { return value(BexMetricsRecorder.CONTAINS_BEX_CACHE_MISSES); }
    public long resultValueReads() { return value(BexMetricsRecorder.RESULT_VALUE_READS); }
    public long resultOverlayExactHits() { return value(BexMetricsRecorder.RESULT_OVERLAY_EXACT_HITS); }
    public long resultOverlayAncestorHits() { return value(BexMetricsRecorder.RESULT_OVERLAY_ANCESTOR_HITS); }
    public long resultOverlayDocumentFallbacks() { return value(BexMetricsRecorder.RESULT_OVERLAY_DOCUMENT_FALLBACKS); }
    public long pointerParses() { return value(BexMetricsRecorder.POINTER_PARSES); }
    public long pointerCacheHits() { return value(BexMetricsRecorder.POINTER_CACHE_HITS); }
    public long pointerCacheMisses() { return value(BexMetricsRecorder.POINTER_CACHE_MISSES); }
    public long functionArgMapAllocations() { return value(BexMetricsRecorder.FUNCTION_ARG_MAP_ALLOCATIONS); }
    public long frozenWriterNodeFallbacks() { return value(BexMetricsRecorder.FROZEN_WRITER_NODE_FALLBACKS); }
    public long compileNanos() { return value(BexMetricsRecorder.COMPILE_NANOS); }
    public long executeNanos() { return value(BexMetricsRecorder.EXECUTE_NANOS); }
}
