package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.BlueIds;
import blue.language.model.Nodes;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single strict transient-to-Blue conversion path.
 */
public final class BexBlueNodeWriter {
    private static final Set<String> SCHEMA_KEYS = schemaKeys();

    private BexBlueNodeWriter() {
    }

    /**
     * Converts a BEX value to valid Blue Language 1.0 content.
     *
     * <p>Exact children become pure references. Transient values are rebuilt in
     * canonical BEX key order and validated as direct Blue identity input.</p>
     */
    public static Node toNode(BexValue value) {
        return convert(value, ExactMode.REFERENCE);
    }

    /**
     * Builds a strict semantic view for operations such as Blue type
     * matching. Exact descendants remain inline when their verified content
     * is already available, so a transient aggregate does not discard local
     * evidence by replacing those descendants with provider-only references.
     */
    public static Node toSemanticNode(BexValue value) {
        return convert(value, ExactMode.SEMANTIC);
    }

    /**
     * Retains available canonical exact children for host Source admission.
     * Pure references stay references; resolved views are never substituted
     * for the identity-bearing content of an exact child.
     */
    public static Node toSourceNode(BexValue value) {
        return convert(value, ExactMode.CANONICAL);
    }

    private enum ExactMode { REFERENCE, CANONICAL, SEMANTIC }

    private static Node convert(BexValue value, ExactMode exactMode) {
        try {
            return toNode(value, Position.BOUNDARY_ROOT, exactMode);
        } catch (BexException ex) {
            if (ex.getMessage() != null
                    && ex.getMessage().startsWith(
                    "Blue output conversion failed:")) {
                throw ex;
            }
            throw new BexException(
                    "Blue output conversion failed: "
                            + ex.getMessage(),
                    ex);
        } catch (RuntimeException ex) {
            throw new BexException("Blue output conversion failed: " + ex.getMessage(), ex);
        }
    }

    private static Node toNode(BexValue value,
                               Position position,
                               ExactMode exactMode) {
        if (value == null || value.isUndefined()) {
            throw new BexException("Undefined cannot be emitted as a Blue value");
        }
        if (value.isExact()) {
            if (exactMode == ExactMode.SEMANTIC) {
                return value.toNode();
            }
            if (exactMode == ExactMode.CANONICAL) {
                return BexExactSourceWriter.toSource(value);
            }
            String blueId = BlueIds.requireBlueIdOrCyclicMember(
                    value.exactBlueId(), "BEX exact output blueId");
            return new Node().blueId(blueId);
        }
        if (value.isNull()) {
            if (position == Position.LIST_ITEM) {
                return Nodes.emptyPlaceholder();
            }
            throw new BexException(
                    "Null cannot be emitted as a Blue boundary root");
        }
        if (value.isScalar()) {
            return scalarNode(BexValues.rawScalar(value));
        }
        if (value.isList()) {
            return new Node().items(toNodeList(value, exactMode));
        }
        if (!value.isObject()) {
            throw new BexException("Unsupported BEX output value kind");
        }
        if (isEmptyPlaceholder(value)) {
            if (position != Position.LIST_ITEM) {
                throw new BexException("\"$empty\" is valid only as a Blue list item");
            }
            return Nodes.emptyPlaceholder();
        }

        rejectForbiddenFields(value);
        validateBlueIdReferenceShape(value);

        Node node = new Node();
        LinkedHashMap<String, Node> properties = new LinkedHashMap<>();
        boolean hasValuePayload = false;
        boolean hasItemsPayload = false;
        for (String key : value.keys()) {
            BexValue child = value.get(key);
            if (isOmittedObjectMember(child)) {
                continue;
            }
            if ("name".equals(key)) {
                node.name(requiredText(child, "name"));
            } else if ("description".equals(key)) {
                node.description(requiredText(child, "description"));
            } else if ("type".equals(key)) {
                node.type(toNode(child, Position.OBJECT_MEMBER, exactMode));
            } else if ("itemType".equals(key)) {
                node.itemType(toNode(child, Position.OBJECT_MEMBER, exactMode));
            } else if ("keyType".equals(key)) {
                node.keyType(toNode(child, Position.OBJECT_MEMBER, exactMode));
            } else if ("valueType".equals(key)) {
                node.valueType(toNode(child, Position.OBJECT_MEMBER, exactMode));
            } else if ("mergePolicy".equals(key)) {
                node.mergePolicy(requiredText(child, "mergePolicy"));
            } else if ("value".equals(key)) {
                hasValuePayload = true;
                node.value(scalarValue(child, "value"));
            } else if ("items".equals(key)) {
                hasItemsPayload = true;
                if (!child.isList()) {
                    throw new BexException("Blue items field must be a list");
                }
                node.items(toNodeList(child, exactMode));
            } else if ("blueId".equals(key)) {
                String blueId = BlueIds.requireBlueIdOrCyclicMember(
                        requiredText(child, "blueId"),
                        "BEX output blueId");
                if (blueId.indexOf('#') >= 0) {
                    throw new BexException(
                            "Transient BEX output cannot counterfeit an exact "
                                    + "cyclic-set member reference: "
                                    + blueId);
                }
                node.blueId(blueId);
            } else if ("contracts".equals(key)) {
                node.contracts(toObjectNode(
                        child, "contracts", exactMode));
            } else if ("schema".equals(key)) {
                node.schema(toSchema(child, exactMode));
            } else {
                properties.put(key, toNode(
                        child, Position.OBJECT_MEMBER, exactMode));
            }
        }

        int payloadKinds = (hasValuePayload ? 1 : 0)
                + (hasItemsPayload ? 1 : 0)
                + (!properties.isEmpty() ? 1 : 0);
        if (payloadKinds > 1) {
            throw new BexException(
                    "A Blue node may contain only one payload kind: value, items, or object fields");
        }
        if (!properties.isEmpty()) {
            node.properties(properties);
        } else if (Nodes.isEmptyNode(node)) {
            /*
             * A transient BEX object that has no retained members is still
             * exact object content.  Keep an explicit empty object payload so
             * downstream Language presence, schema, mapping, and identity
             * paths cannot confuse it with a temporary fieldless builder.
             */
            node.properties(Collections.<String, Node>emptyMap());
        }
        return node;
    }

    public static boolean hasLanguageField(BexValue value) {
        if (value == null || !value.isObject()) {
            return false;
        }
        for (String key : value.keys()) {
            if (isLanguageField(key)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLanguageField(String key) {
        return "name".equals(key)
                || "description".equals(key)
                || "type".equals(key)
                || "itemType".equals(key)
                || "keyType".equals(key)
                || "valueType".equals(key)
                || "value".equals(key)
                || "items".equals(key)
                || "blueId".equals(key)
                || "blue".equals(key)
                || "contracts".equals(key)
                || "schema".equals(key)
                || "constraints".equals(key)
                || "mergePolicy".equals(key)
                || "properties".equals(key)
                || "$previous".equals(key)
                || "$pos".equals(key)
                || "$replace".equals(key)
                || "$empty".equals(key);
    }

    private static void rejectForbiddenFields(BexValue value) {
        if (!isOmittedObjectMember(value.get("properties"))) {
            throw new BexException(
                    "\"properties\" is an internal Blue field and must not appear in BEX output");
        }
        if (!isOmittedObjectMember(value.get("constraints"))) {
            throw new BexException(
                    "Blue constraints field is invalid in Blue Language 1.0; use schema");
        }
        if (!isOmittedObjectMember(value.get("allowMultiple"))) {
            throw new BexException(
                    "Blue allowMultiple field is invalid in Blue Language 1.0");
        }
        if (!isOmittedObjectMember(value.get("options"))) {
            throw new BexException(
                    "Blue options field is invalid in Blue Language 1.0");
        }
        if (!isOmittedObjectMember(value.get("blue"))) {
            throw new BexException(
                    "Computed BEX output must not contain the Blue preprocessing field \"blue\"");
        }
        for (String control : Arrays.asList("$previous", "$pos", "$replace")) {
            if (!isOmittedObjectMember(value.get(control))) {
                throw new BexException(
                        "Computed BEX output must not contain Blue list-control field " + control);
            }
        }
        if (!isOmittedObjectMember(value.get("$empty"))) {
            throw new BexException(
                    "\"$empty\" list placeholder must have exact shape { \"$empty\": true }");
        }
    }

    private static boolean isEmptyPlaceholder(BexValue value) {
        if (!value.isObject()) {
            return false;
        }
        BexValue marker = value.get("$empty");
        if (isOmittedObjectMember(marker)
                || !marker.isScalar()
                || !marker.asBoolean()
                || !Boolean.TRUE.equals(BexValues.rawScalar(marker))) {
            return false;
        }
        int retainedMembers = 0;
        for (String key : value.keys()) {
            if (!isOmittedObjectMember(value.get(key))) {
                retainedMembers++;
            }
        }
        return retainedMembers == 1;
    }

    private static void validateBlueIdReferenceShape(BexValue value) {
        if (isOmittedObjectMember(value.get("blueId"))) {
            return;
        }
        for (String key : value.keys()) {
            if (!"blueId".equals(key)
                    && !isOmittedObjectMember(value.get(key))) {
                throw new BexException(
                        "Blue blueId reference node cannot contain sibling field: " + key);
            }
        }
    }

    private static Node scalarNode(Object value) {
        if (value instanceof String) {
            return Nodes.textNode((String) value);
        }
        if (value instanceof BigInteger) {
            return Nodes.integerNode((BigInteger) value);
        }
        if (value instanceof BigDecimal) {
            return Nodes.doubleNode((BigDecimal) value);
        }
        if (value instanceof Boolean) {
            return Nodes.booleanNode((Boolean) value);
        }
        throw new BexException("Unsupported BEX scalar output: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static Object scalarValue(BexValue value, String field) {
        if (value.isNull() || !value.isScalar()) {
            throw new BexException("Blue " + field + " field must be a non-null scalar value");
        }
        Object raw = BexValues.rawScalar(value);
        return raw;
    }

    private static String requiredText(BexValue value, String field) {
        if (value.isNull() || !value.isScalar()) {
            throw new BexException("Blue " + field + " field must be Text");
        }
        Object raw = BexValues.rawScalar(value);
        if (!(raw instanceof String)) {
            throw new BexException("Blue " + field + " field must be Text");
        }
        return (String) raw;
    }

    private static Node toObjectNode(BexValue value,
                                     String field,
                                     ExactMode exactMode) {
        if (!value.isObject()) {
            throw new BexException("Blue " + field + " field must be an object");
        }
        return toNode(value, Position.OBJECT_MEMBER, exactMode);
    }

    private static List<Node> toNodeList(BexValue value,
                                         ExactMode exactMode) {
        ArrayList<Node> items = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            BexValue item = value.get(String.valueOf(i));
            if (item == null || item.isUndefined()) {
                throw new BexException("Undefined cannot appear in a Blue list");
            }
            items.add(toNode(item, Position.LIST_ITEM, exactMode));
        }
        return items;
    }

    private static Schema toSchema(BexValue value,
                                   ExactMode exactMode) {
        if (value.isNull() || value.isUndefined()) {
            return null;
        }
        if (value.isExact()) {
            Node exactReference = toNode(
                value, Position.OBJECT_MEMBER, ExactMode.REFERENCE);
            Schema schema = new Schema();
            schema.blueId(exactReference.getBlueId());
            return schema;
        }
        if (!value.isObject()) {
            throw new BexException("Blue schema field must be an object");
        }
        validateSchemaKeys(value);
        BexValue schemaBlueId = value.get("blueId");
        if (!isOmittedObjectMember(schemaBlueId)) {
            int retainedMembers = 0;
            for (String key : value.keys()) {
                if (!isOmittedObjectMember(value.get(key))) {
                    retainedMembers++;
                }
            }
            if (retainedMembers != 1) {
                throw new BexException("Blue schema blueId reference must be pure");
            }
            Schema schema = new Schema();
            schema.blueId(BlueIds.requireBlueIdOrCyclicMember(
                    requiredText(schemaBlueId, "schema.blueId"),
                    "BEX output schema.blueId"));
            return schema;
        }
        Schema schema = new Schema();
        setSchemaNode(schema, value, "required", exactMode);
        setSchemaNode(schema, value, "minLength", exactMode);
        setSchemaNode(schema, value, "maxLength", exactMode);
        setSchemaNode(schema, value, "minimum", exactMode);
        setSchemaNode(schema, value, "maximum", exactMode);
        setSchemaNode(schema, value, "exclusiveMinimum", exactMode);
        setSchemaNode(schema, value, "exclusiveMaximum", exactMode);
        setSchemaNode(schema, value, "multipleOf", exactMode);
        setSchemaNode(schema, value, "minItems", exactMode);
        setSchemaNode(schema, value, "maxItems", exactMode);
        setSchemaNode(schema, value, "uniqueItems", exactMode);
        setSchemaNode(schema, value, "minFields", exactMode);
        setSchemaNode(schema, value, "maxFields", exactMode);
        setSchemaList(schema, value, "enum", exactMode);
        return schema;
    }

    private static void setSchemaNode(Schema schema,
                                      BexValue source,
                                      String key,
                                      ExactMode exactMode) {
        BexValue value = source.get(key);
        if (isOmittedObjectMember(value)) {
            return;
        }
        Node node = toNode(value, Position.OBJECT_MEMBER, exactMode);
        if ("required".equals(key)) schema.required(node);
        else if ("minLength".equals(key)) schema.minLength(node);
        else if ("maxLength".equals(key)) schema.maxLength(node);
        else if ("minimum".equals(key)) schema.minimum(node);
        else if ("maximum".equals(key)) schema.maximum(node);
        else if ("exclusiveMinimum".equals(key)) schema.exclusiveMinimum(node);
        else if ("exclusiveMaximum".equals(key)) schema.exclusiveMaximum(node);
        else if ("multipleOf".equals(key)) schema.multipleOf(node);
        else if ("minItems".equals(key)) schema.minItems(node);
        else if ("maxItems".equals(key)) schema.maxItems(node);
        else if ("uniqueItems".equals(key)) schema.uniqueItems(node);
        else if ("minFields".equals(key)) schema.minFields(node);
        else if ("maxFields".equals(key)) schema.maxFields(node);
    }

    private static void setSchemaList(Schema schema,
                                      BexValue source,
                                      String key,
                                      ExactMode exactMode) {
        BexValue value = source.get(key);
        if (isOmittedObjectMember(value)) {
            return;
        }
        if (!value.isList()) {
            throw new BexException("Blue schema " + key + " field must be a list");
        }
        if ("enum".equals(key)) {
            schema.enumValues(toNodeList(value, exactMode));
        }
    }

    private static void validateSchemaKeys(BexValue value) {
        for (String key : value.keys()) {
            if (isOmittedObjectMember(value.get(key))) {
                continue;
            }
            if (!SCHEMA_KEYS.contains(key) && !"blueId".equals(key)) {
                throw new BexException("Unsupported Blue schema field: " + key);
            }
        }
    }

    private static Set<String> schemaKeys() {
        return new LinkedHashSet<>(Arrays.asList(
                "required",
                "minLength",
                "maxLength",
                "minimum",
                "maximum",
                "exclusiveMinimum",
                "exclusiveMaximum",
                "multipleOf",
                "minItems",
                "maxItems",
                "uniqueItems",
                "minFields",
                "maxFields",
                "enum"));
    }

    private static boolean isOmittedObjectMember(BexValue value) {
        return value == null
                || value.isUndefined()
                || (!value.isExact() && value.isNull());
    }

    private enum Position {
        BOUNDARY_ROOT,
        OBJECT_MEMBER,
        LIST_ITEM
    }
}
