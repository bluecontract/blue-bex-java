package blue.bex.value;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Schema;
import blue.language.model.SchemaWireForm;
import blue.language.registry.BlueCoreTypeRegistry;

import java.util.Map;

/**
 * BEX-owned boundary from final Language model objects to transient BEX wire
 * values.
 *
 * <p>Language remains authoritative for reserved-field projection, list
 * controls, scalar canonicalization, and malformed-shape rejection. The
 * schema callback preserves the legacy BEX rule that explicitly core-typed
 * scalar constraints are imported as their scalar payload.</p>
 */
final class BexBlueValueImporter {
    private static final String TEXT_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Text");
    private static final String INTEGER_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Integer");
    private static final String DOUBLE_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Double");
    private static final String BOOLEAN_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Boolean");

    private BexBlueValueImporter() {
    }

    static Object node(Node node) {
        return NodeWireForm.get(node);
    }

    static Map<String, Object> schema(Schema schema) {
        return SchemaWireForm.get(schema, BexBlueValueImporter::schemaNode);
    }

    private static Object schemaNode(Node node) {
        if (isCoreTypedScalar(node)) {
            return node.getValue();
        }
        return NodeWireForm.get(node);
    }

    private static boolean isCoreTypedScalar(Node node) {
        if (node == null
                || node.getValue() == null
                || node.getName() != null
                || node.getDescription() != null
                || node.getItems() != null
                || node.getProperties() != null
                || node.getContracts() != null
                || node.getBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null) {
            return false;
        }
        if (node.getType() == null) {
            return true;
        }
        String typeBlueId = node.getType().getBlueId();
        return TEXT_TYPE_BLUE_ID.equals(typeBlueId)
                || INTEGER_TYPE_BLUE_ID.equals(typeBlueId)
                || DOUBLE_TYPE_BLUE_ID.equals(typeBlueId)
                || BOOLEAN_TYPE_BLUE_ID.equals(typeBlueId)
                || "Text".equals(typeBlueId)
                || "Integer".equals(typeBlueId)
                || "Double".equals(typeBlueId)
                || "Boolean".equals(typeBlueId);
    }
}
