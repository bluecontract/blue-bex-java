package blue.bex.value;

import blue.bex.result.BexMetrics;
import blue.language.snapshot.FrozenNode;

/**
 * Immutable projection of the single strict Blue output conversion path.
 */
public final class BexFrozenWriter {
    private final BexMetrics metrics;

    private BexFrozenWriter(BexMetrics metrics) {
        this.metrics = metrics;
    }

    public static FrozenNode toFrozen(BexValue value) {
        return toFrozen(value, null);
    }

    public static FrozenNode toFrozen(BexValue value, BexMetrics metrics) {
        return new BexFrozenWriter(metrics).toFrozenValue(value);
    }

    public FrozenNode toFrozenValue(BexValue value) {
        if (metrics != null) {
            metrics.incrementFrozenOutputConversions();
        }
        return toFrozenInternal(value);
    }

    private FrozenNode toFrozenInternal(BexValue value) {
        if (value instanceof FrozenNodeBexValue) {
            return ((FrozenNodeBexValue) value).canonicalNode();
        }
        if (metrics != null) {
            metrics.incrementFrozenWriterNodeFallbacks();
        }
        return FrozenNode.fromResolvedNode(
                BexBlueNodeWriter.toNode(value));
    }
}
