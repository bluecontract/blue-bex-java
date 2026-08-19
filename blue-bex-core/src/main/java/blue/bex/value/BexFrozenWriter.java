package blue.bex.value;

import blue.language.snapshot.FrozenNode;

/**
 * Immutable projection of the single strict Blue output conversion path.
 */
public final class BexFrozenWriter {
    private final BexValueMetrics metrics;

    private BexFrozenWriter(BexValueMetrics metrics) {
        this.metrics = metrics;
    }

    public static FrozenNode toFrozen(BexValue value) {
        return toFrozen(value, null);
    }

    public static FrozenNode toFrozen(
            BexValue value,
            BexValueMetrics metrics) {
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
        if (value instanceof AdmittedExactBexValue) {
            /*
             * The semantic-output host has already authenticated this exact
             * frozen representation. Re-materializing the supplied diagnostic
             * cursor can generalize nominal types or schema fields and thereby
             * change the identity the host established.
             */
            return ((AdmittedExactBexValue) value).establishedFrozenValue();
        }
        if (metrics != null) {
            metrics.incrementFrozenWriterNodeFallbacks();
        }
        return FrozenNode.fromResolvedNode(
                BexBlueNodeWriter.toNode(value));
    }
}
