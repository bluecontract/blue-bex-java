package blue.bex.result;

/**
 * Mutable execution metrics collected during compile and execution.
 *
 * <p>{@link BexExecutionResult#metrics()} returns a copy, so callers can inspect
 * counters without mutating the stored result. Metrics distinguish hot-path
 * reads, cache behavior, materialization, and output boundary conversions.</p>
 */
public final class BexMetrics {
    private long compiledExecutions;
    private long compileCacheHits;
    private long compileCacheMisses;
    private long interpretedFallbacks;
    private long expressionEvaluations;
    private long statementExecutions;
    private long functionCalls;
    private long loopIterations;
    private long frozenDocumentReads;
    private long resolvedDocumentReads;
    private long eventReads;
    private long stepsReads;
    private long currentContractReads;
    private long nodeMaterializations;
    private long simpleMaterializations;
    private long frozenOutputConversions;
    private long nodeOutputConversions;
    private long containsBexScans;
    private long containsBexCacheHits;
    private long containsBexCacheMisses;
    private long resultValueReads;
    private long resultOverlayExactHits;
    private long resultOverlayAncestorHits;
    private long resultOverlayDocumentFallbacks;
    private long pointerParses;
    private long pointerCacheHits;
    private long pointerCacheMisses;
    private long functionArgMapAllocations;
    private long sizeEstimateCalls;
    private long sizeEstimateCacheHits;
    private long sizeEstimateCacheMisses;
    private long frozenWriterNodeFallbacks;
    private long frozenWriterChildNodeRoundTrips;
    private long compileNanos;
    private long executeNanos;

    public BexMetrics copy() {
        BexMetrics copy = new BexMetrics();
        copy.compiledExecutions = compiledExecutions;
        copy.compileCacheHits = compileCacheHits;
        copy.compileCacheMisses = compileCacheMisses;
        copy.interpretedFallbacks = interpretedFallbacks;
        copy.expressionEvaluations = expressionEvaluations;
        copy.statementExecutions = statementExecutions;
        copy.functionCalls = functionCalls;
        copy.loopIterations = loopIterations;
        copy.frozenDocumentReads = frozenDocumentReads;
        copy.resolvedDocumentReads = resolvedDocumentReads;
        copy.eventReads = eventReads;
        copy.stepsReads = stepsReads;
        copy.currentContractReads = currentContractReads;
        copy.nodeMaterializations = nodeMaterializations;
        copy.simpleMaterializations = simpleMaterializations;
        copy.frozenOutputConversions = frozenOutputConversions;
        copy.nodeOutputConversions = nodeOutputConversions;
        copy.containsBexScans = containsBexScans;
        copy.containsBexCacheHits = containsBexCacheHits;
        copy.containsBexCacheMisses = containsBexCacheMisses;
        copy.resultValueReads = resultValueReads;
        copy.resultOverlayExactHits = resultOverlayExactHits;
        copy.resultOverlayAncestorHits = resultOverlayAncestorHits;
        copy.resultOverlayDocumentFallbacks = resultOverlayDocumentFallbacks;
        copy.pointerParses = pointerParses;
        copy.pointerCacheHits = pointerCacheHits;
        copy.pointerCacheMisses = pointerCacheMisses;
        copy.functionArgMapAllocations = functionArgMapAllocations;
        copy.sizeEstimateCalls = sizeEstimateCalls;
        copy.sizeEstimateCacheHits = sizeEstimateCacheHits;
        copy.sizeEstimateCacheMisses = sizeEstimateCacheMisses;
        copy.frozenWriterNodeFallbacks = frozenWriterNodeFallbacks;
        copy.frozenWriterChildNodeRoundTrips = frozenWriterChildNodeRoundTrips;
        copy.compileNanos = compileNanos;
        copy.executeNanos = executeNanos;
        return copy;
    }

    public void incrementCompiledExecutions() { compiledExecutions++; }
    public void incrementCompileCacheHits() { compileCacheHits++; }
    public void incrementCompileCacheMisses() { compileCacheMisses++; }
    public void incrementInterpretedFallbacks() { interpretedFallbacks++; }
    public void incrementExpressionEvaluations() { expressionEvaluations++; }
    public void incrementStatementExecutions() { statementExecutions++; }
    public void incrementFunctionCalls() { functionCalls++; }
    public void incrementLoopIterations() { loopIterations++; }
    public void incrementFrozenDocumentReads() { frozenDocumentReads++; }
    public void incrementResolvedDocumentReads() { resolvedDocumentReads++; }
    public void incrementEventReads() { eventReads++; }
    public void incrementStepsReads() { stepsReads++; }
    public void incrementCurrentContractReads() { currentContractReads++; }
    public void incrementNodeMaterializations() { nodeMaterializations++; }
    public void incrementSimpleMaterializations() { simpleMaterializations++; }
    public void incrementFrozenOutputConversions() { frozenOutputConversions++; }
    public void incrementNodeOutputConversions() { nodeOutputConversions++; }
    public void incrementContainsBexScans() { containsBexScans++; }
    public void incrementContainsBexCacheHits() { containsBexCacheHits++; }
    public void incrementContainsBexCacheMisses() { containsBexCacheMisses++; }
    public void incrementResultValueReads() { resultValueReads++; }
    public void incrementResultOverlayExactHits() { resultOverlayExactHits++; }
    public void incrementResultOverlayAncestorHits() { resultOverlayAncestorHits++; }
    public void incrementResultOverlayDocumentFallbacks() { resultOverlayDocumentFallbacks++; }
    public void incrementPointerParses() { pointerParses++; }
    public void incrementPointerCacheHits() { pointerCacheHits++; }
    public void incrementPointerCacheMisses() { pointerCacheMisses++; }
    public void incrementFunctionArgMapAllocations() { functionArgMapAllocations++; }
    public void incrementSizeEstimateCalls() { sizeEstimateCalls++; }
    public void incrementSizeEstimateCacheHits() { sizeEstimateCacheHits++; }
    public void incrementSizeEstimateCacheMisses() { sizeEstimateCacheMisses++; }
    public void incrementFrozenWriterNodeFallbacks() { frozenWriterNodeFallbacks++; }
    public void incrementFrozenWriterChildNodeRoundTrips() { frozenWriterChildNodeRoundTrips++; }
    public void addCompileNanos(long nanos) { compileNanos += Math.max(0L, nanos); }
    public void addExecuteNanos(long nanos) { executeNanos += Math.max(0L, nanos); }

    public long compiledExecutions() { return compiledExecutions; }
    public long compileCacheHits() { return compileCacheHits; }
    public long compileCacheMisses() { return compileCacheMisses; }
    public long interpretedFallbacks() { return interpretedFallbacks; }
    public long expressionEvaluations() { return expressionEvaluations; }
    public long statementExecutions() { return statementExecutions; }
    public long functionCalls() { return functionCalls; }
    public long loopIterations() { return loopIterations; }
    public long frozenDocumentReads() { return frozenDocumentReads; }
    public long resolvedDocumentReads() { return resolvedDocumentReads; }
    public long eventReads() { return eventReads; }
    public long stepsReads() { return stepsReads; }
    public long currentContractReads() { return currentContractReads; }
    public long nodeMaterializations() { return nodeMaterializations; }
    public long simpleMaterializations() { return simpleMaterializations; }
    public long frozenOutputConversions() { return frozenOutputConversions; }
    public long nodeOutputConversions() { return nodeOutputConversions; }
    public long containsBexScans() { return containsBexScans; }
    public long containsBexCacheHits() { return containsBexCacheHits; }
    public long containsBexCacheMisses() { return containsBexCacheMisses; }
    public long resultValueReads() { return resultValueReads; }
    public long resultOverlayExactHits() { return resultOverlayExactHits; }
    public long resultOverlayAncestorHits() { return resultOverlayAncestorHits; }
    public long resultOverlayDocumentFallbacks() { return resultOverlayDocumentFallbacks; }
    public long pointerParses() { return pointerParses; }
    public long pointerCacheHits() { return pointerCacheHits; }
    public long pointerCacheMisses() { return pointerCacheMisses; }
    public long functionArgMapAllocations() { return functionArgMapAllocations; }
    public long sizeEstimateCalls() { return sizeEstimateCalls; }
    public long sizeEstimateCacheHits() { return sizeEstimateCacheHits; }
    public long sizeEstimateCacheMisses() { return sizeEstimateCacheMisses; }
    public long frozenWriterNodeFallbacks() { return frozenWriterNodeFallbacks; }
    public long frozenWriterChildNodeRoundTrips() { return frozenWriterChildNodeRoundTrips; }
    public long compileNanos() { return compileNanos; }
    public long executeNanos() { return executeNanos; }
}
