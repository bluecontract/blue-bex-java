package blue.bex.compile;

import blue.language.snapshot.FrozenNode;

/**
 * Stable identity for compiled-program cache keys.
 */
public final class BexNodeIdentity {
    private BexNodeIdentity() {
    }

    public static String stable(FrozenNode node) {
        if (node == null) {
            return "none";
        }
        String blueId = node.blueId();
        if (blueId != null && !blueId.isEmpty()) {
            return "blueId:" + blueId;
        }
        String referenceBlueId = node.getReferenceBlueId();
        if (referenceBlueId != null && !referenceBlueId.isEmpty()) {
            return "referenceBlueId:" + referenceBlueId;
        }
        return "fingerprint:" + BexNodeFingerprint.compute(node);
    }
}
