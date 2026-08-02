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
        String blueId = safeBlueId(node);
        if (blueId != null && !blueId.isEmpty()) {
            return "blueId:" + blueId;
        }
        String referenceBlueId = node.getReferenceBlueId();
        if (referenceBlueId != null && !referenceBlueId.isEmpty()) {
            return "referenceBlueId:" + referenceBlueId;
        }
        return "fingerprint:" + BexNodeFingerprint.compute(node);
    }

    public static String safeBlueId(FrozenNode node) {
        if (node == null) {
            return null;
        }
        try {
            return node.blueId();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
