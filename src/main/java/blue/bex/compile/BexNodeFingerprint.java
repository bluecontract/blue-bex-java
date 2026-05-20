package blue.bex.compile;

import blue.language.snapshot.FrozenNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Content fingerprint fallback for FrozenNode values without stable Blue IDs.
 */
final class BexNodeFingerprint {
    private BexNodeFingerprint() {
    }

    static String compute(FrozenNode node) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateNode(digest, node);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void updateNode(MessageDigest digest, FrozenNode node) {
        if (node == null) {
            update(digest, "null;");
            return;
        }
        updateField(digest, "name", node.getName());
        updateField(digest, "description", node.getDescription());
        updateField(digest, "referenceBlueId", node.getReferenceBlueId());
        updateField(digest, "blueId", node.blueId());
        if (node.getValue() != null) {
            update(digest, "value:");
            update(digest, node.getValue().getClass().getName());
            update(digest, ":");
            update(digest, String.valueOf(node.getValue()));
            update(digest, ";");
        }
        if (node.getType() != null) {
            update(digest, "type{");
            updateNode(digest, node.getType());
            update(digest, "}");
        }
        if (node.getItems() != null) {
            update(digest, "items[");
            for (FrozenNode item : node.getItems()) {
                updateNode(digest, item);
                update(digest, ",");
            }
            update(digest, "]");
        }
        if (node.getProperties() != null) {
            update(digest, "properties{");
            List<String> keys = new ArrayList<>(node.getProperties().keySet());
            Collections.sort(keys);
            for (String key : keys) {
                updateField(digest, "key", key);
                updateNode(digest, node.getProperties().get(key));
            }
            update(digest, "}");
        }
    }

    private static void updateField(MessageDigest digest, String name, String value) {
        if (value != null) {
            update(digest, name);
            update(digest, ":");
            update(digest, value);
            update(digest, ";");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xf, 16));
            out.append(Character.forDigit(value & 0xf, 16));
        }
        return out.toString();
    }
}
