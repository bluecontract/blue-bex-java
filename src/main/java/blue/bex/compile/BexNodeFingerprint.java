package blue.bex.compile;

import blue.language.snapshot.FrozenNode;
import blue.language.model.Node;
import blue.language.model.Schema;

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
        if (node.isInlineValue()) {
            updateField(digest, "inlineValue", "true");
        }
        updateField(digest, "mergePolicy", node.getMergePolicy());
        updateField(digest, "previousBlueId", node.getPreviousBlueId());
        updateField(digest, "position", node.getPosition());
        if (node.getValue() != null) {
            update(digest, "value:");
            update(digest, node.getValue().getClass().getName());
            update(digest, ":");
            update(digest, String.valueOf(node.getValue()));
            update(digest, ";");
        }
        if (node.getType() != null) {
            updateNodeField(digest, "type", node.getType());
        }
        if (node.getItemType() != null) {
            updateNodeField(digest, "itemType", node.getItemType());
        }
        if (node.getKeyType() != null) {
            updateNodeField(digest, "keyType", node.getKeyType());
        }
        if (node.getValueType() != null) {
            updateNodeField(digest, "valueType", node.getValueType());
        }
        if (node.getBlue() != null) {
            updateNodeField(digest, "blue", node.getBlue());
        }
        if (node.getSchema() != null) {
            updateSchema(digest, node.getSchema());
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

    private static void updateNodeField(MessageDigest digest, String name, FrozenNode value) {
        update(digest, name);
        update(digest, "{");
        updateNode(digest, value);
        update(digest, "}");
    }

    private static void updateSchema(MessageDigest digest, Schema schema) {
        update(digest, "schema{");
        updateSchemaNodeField(digest, "required", schema.getRequired());
        updateSchemaNodeField(digest, "allowMultiple", schema.getAllowMultiple());
        updateSchemaNodeField(digest, "minLength", schema.getMinLength());
        updateSchemaNodeField(digest, "maxLength", schema.getMaxLength());
        updateSchemaNodeField(digest, "minimum", schema.getMinimum());
        updateSchemaNodeField(digest, "maximum", schema.getMaximum());
        updateSchemaNodeField(digest, "exclusiveMinimum", schema.getExclusiveMinimum());
        updateSchemaNodeField(digest, "exclusiveMaximum", schema.getExclusiveMaximum());
        updateSchemaNodeField(digest, "multipleOf", schema.getMultipleOf());
        updateSchemaNodeField(digest, "minItems", schema.getMinItems());
        updateSchemaNodeField(digest, "maxItems", schema.getMaxItems());
        updateSchemaNodeField(digest, "uniqueItems", schema.getUniqueItems());
        updateSchemaNodeField(digest, "minFields", schema.getMinFields());
        updateSchemaNodeField(digest, "maxFields", schema.getMaxFields());
        updateSchemaNodeListField(digest, "enum", schema.getEnum());
        updateSchemaNodeListField(digest, "options", schema.getOptions());
        update(digest, "}");
    }

    private static void updateSchemaNodeField(MessageDigest digest, String name, Node value) {
        if (value == null) {
            return;
        }
        update(digest, name);
        update(digest, "{");
        updateNode(digest, FrozenNode.fromResolvedNode(value));
        update(digest, "}");
    }

    private static void updateSchemaNodeListField(MessageDigest digest, String name, List<Node> values) {
        if (values == null) {
            return;
        }
        update(digest, name);
        update(digest, "[");
        for (Node value : values) {
            updateNode(digest, FrozenNode.fromResolvedNode(value));
            update(digest, ",");
        }
        update(digest, "]");
    }

    private static void updateField(MessageDigest digest, String name, String value) {
        if (value != null) {
            update(digest, name);
            update(digest, ":");
            update(digest, value);
            update(digest, ";");
        }
    }

    private static void updateField(MessageDigest digest, String name, Object value) {
        if (value != null) {
            updateField(digest, name, String.valueOf(value));
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
