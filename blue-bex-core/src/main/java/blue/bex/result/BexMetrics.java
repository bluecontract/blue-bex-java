package blue.bex.result;

/**
 * Immutable compatibility view of invocation metrics.
 *
 * @deprecated use {@link BexMetricsSnapshot}; mutable recording is an internal
 * implementation concern.
 */
@Deprecated
public final class BexMetrics {
    private final BexMetricsSnapshot snapshot;

    public BexMetrics() {
        this(new BexMetricsRecorder().snapshot());
    }

    BexMetrics(BexMetricsSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public BexMetrics copy() { return new BexMetrics(snapshot); }
    public BexMetricsSnapshot snapshot() { return snapshot; }

    /** Creates an independent compatibility recorder from a snapshot. */
    public static BexMetrics fromSnapshot(BexMetricsSnapshot snapshot) {
        return new BexMetrics(snapshot);
    }

    public long compiledExecutions() { return snapshot.compiledExecutions(); }
    public long compileCacheHits() { return snapshot.compileCacheHits(); }
    public long compileCacheMisses() { return snapshot.compileCacheMisses(); }
    public long interpretedFallbacks() { return snapshot.interpretedFallbacks(); }
    public long expressionEvaluations() { return snapshot.expressionEvaluations(); }
    public long statementExecutions() { return snapshot.statementExecutions(); }
    public long functionCalls() { return snapshot.functionCalls(); }
    public long loopIterations() { return snapshot.loopIterations(); }
    public long frozenDocumentReads() { return snapshot.frozenDocumentReads(); }
    public long resolvedDocumentReads() { return snapshot.resolvedDocumentReads(); }
    public long eventReads() { return snapshot.eventReads(); }
    public long stepsReads() { return snapshot.stepsReads(); }
    public long currentContractReads() { return snapshot.currentContractReads(); }
    public long nodeMaterializations() { return snapshot.nodeMaterializations(); }
    public long simpleMaterializations() { return snapshot.simpleMaterializations(); }
    public long frozenOutputConversions() { return snapshot.frozenOutputConversions(); }
    public long nodeOutputConversions() { return snapshot.nodeOutputConversions(); }
    public long containsBexScans() { return snapshot.containsBexScans(); }
    public long containsBexCacheHits() { return snapshot.containsBexCacheHits(); }
    public long containsBexCacheMisses() { return snapshot.containsBexCacheMisses(); }
    public long resultValueReads() { return snapshot.resultValueReads(); }
    public long resultOverlayExactHits() { return snapshot.resultOverlayExactHits(); }
    public long resultOverlayAncestorHits() { return snapshot.resultOverlayAncestorHits(); }
    public long resultOverlayDocumentFallbacks() { return snapshot.resultOverlayDocumentFallbacks(); }
    public long pointerParses() { return snapshot.pointerParses(); }
    public long pointerCacheHits() { return snapshot.pointerCacheHits(); }
    public long pointerCacheMisses() { return snapshot.pointerCacheMisses(); }
    public long functionArgMapAllocations() { return snapshot.functionArgMapAllocations(); }
    public long frozenWriterNodeFallbacks() { return snapshot.frozenWriterNodeFallbacks(); }
    public long compileNanos() { return snapshot.compileNanos(); }
    public long executeNanos() { return snapshot.executeNanos(); }
}
