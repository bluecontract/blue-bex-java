package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Boundary writer from BEX values to Blue nodes using Blue language keys.
 */
public final class BexBlueNodeWriter {
    private static final Set<String> SCHEMA_KEYS = schemaKeys();

    private BexBlueNodeWriter() {
    }

    public static Node toNode(BexValue value) {
        if (value.isUndefined()) {
            throw new BexException("Undefined cannot be emitted as a Blue value");
        }
        if (value.isNull()) {
            return new Node();
        }
        if (value instanceof NodeBexValue || value instanceof FrozenNodeBexValue) {
            return value.toNode();
        }
        if (value.isScalar()) {
            return value.toNode();
        }
        if (value.isList()) {
            return new Node().items(toNodeList(value));
        }
        if (!value.isObject()) {
            return value.toNode();
        }

        if (!value.get("properties").isUndefined()) {
            throw new BexException("\"properties\" is an internal Blue field and must not appear in BEX output");
        }
        validateBlueIdReferenceShape(value);

        Node node = new Node();
        LinkedHashMap<String, Node> properties = new LinkedHashMap<>();
        boolean hasValuePayload = false;
        boolean hasItemsPayload = false;
        boolean hasSchema = false;
        for (String key : value.keys()) {
            BexValue child = value.get(key);
            if (child == null || child.isUndefined()) {
                continue;
            }
            if ("name".equals(key)) {
                node.name(child.isNull() ? null : child.asText());
            } else if ("description".equals(key)) {
                node.description(child.isNull() ? null : child.asText());
            } else if ("type".equals(key)) {
                node.type(toNode(child));
            } else if ("itemType".equals(key)) {
                node.itemType(toNode(child));
            } else if ("keyType".equals(key)) {
                node.keyType(toNode(child));
            } else if ("valueType".equals(key)) {
                node.valueType(toNode(child));
            } else if ("mergePolicy".equals(key)) {
                node.mergePolicy(child.isNull() ? null : child.asText());
            } else if ("value".equals(key)) {
                hasValuePayload = true;
                node.value(scalarValue(child, "value"));
            } else if ("items".equals(key)) {
                hasItemsPayload = true;
                if (!child.isList()) {
                    throw new BexException("Blue items field must be a list");
                }
                node.items(toNodeList(child));
            } else if ("blueId".equals(key)) {
                node.blueId(child.asText());
            } else if ("blue".equals(key)) {
                node.blue(toNode(child));
            } else if ("schema".equals(key) || "constraints".equals(key)) {
                if (hasSchema) {
                    throw new BexException("A Blue node cannot contain both schema and constraints");
                }
                hasSchema = true;
                node.schema(toSchema(child));
            } else if ("$previous".equals(key) || "$pos".equals(key)) {
                throw new BexException("BEX output does not currently support Blue list-control field " + key);
            } else if ("properties".equals(key)) {
                throw new BexException("\"properties\" is an internal Blue field and must not appear in BEX output");
            } else {
                properties.put(key, toNode(child));
            }
        }
        int payloadKinds = 0;
        if (hasValuePayload) {
            payloadKinds++;
        }
        if (hasItemsPayload) {
            payloadKinds++;
        }
        if (!properties.isEmpty()) {
            payloadKinds++;
        }
        if (payloadKinds > 1) {
            throw new BexException("A Blue node may contain only one payload kind: value, items, or object fields");
        }
        if (!properties.isEmpty()) {
            node.properties(properties);
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
                || "schema".equals(key)
                || "constraints".equals(key)
                || "mergePolicy".equals(key)
                || "properties".equals(key)
                || "$previous".equals(key)
                || "$pos".equals(key);
    }

    private static void validateBlueIdReferenceShape(BexValue value) {
        if (value.get("blueId").isUndefined()) {
            return;
        }
        for (String key : value.keys()) {
            if (!"blueId".equals(key)) {
                throw new BexException("Blue blueId reference node cannot contain sibling field: " + key);
            }
        }
    }

    private static Object scalarValue(BexValue value, String field) {
        if (value.isNull()) {
            return null;
        }
        if (!value.isScalar()) {
            throw new BexException("Blue " + field + " field must be a scalar value");
        }
        return BexValues.rawScalar(value);
    }

    private static List<Node> toNodeList(BexValue value) {
        ArrayList<Node> items = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            items.add(toNode(value.get(String.valueOf(i))));
        }
        return items;
    }

    private static Schema toSchema(BexValue value) {
        if (value.isNull() || value.isUndefined()) {
            return null;
        }
        Node node = toNode(value);
        if (node.getSchema() != null && node.getValue() == null
                && node.getItems() == null && node.getProperties() == null) {
            return node.getSchema();
        }
        if (!value.isObject()) {
            throw new BexException("Blue schema field must be an object");
        }
        validateSchemaKeys(value);
        Schema schema = new Schema();
        setSchemaNode(schema, value, "required");
        setSchemaNode(schema, value, "allowMultiple");
        setSchemaNode(schema, value, "minLength");
        setSchemaNode(schema, value, "maxLength");
        setSchemaNode(schema, value, "minimum");
        setSchemaNode(schema, value, "maximum");
        setSchemaNode(schema, value, "exclusiveMinimum");
        setSchemaNode(schema, value, "exclusiveMaximum");
        setSchemaNode(schema, value, "multipleOf");
        setSchemaNode(schema, value, "minItems");
        setSchemaNode(schema, value, "maxItems");
        setSchemaNode(schema, value, "uniqueItems");
        setSchemaNode(schema, value, "minFields");
        setSchemaNode(schema, value, "maxFields");
        setSchemaList(schema, value, "enum");
        setSchemaList(schema, value, "options");
        return schema;
    }

    private static void setSchemaNode(Schema schema, BexValue source, String key) {
        BexValue value = source.get(key);
        if (value.isUndefined()) {
            return;
        }
        Node node = toNode(value);
        if ("required".equals(key)) {
            schema.required(node);
        } else if ("allowMultiple".equals(key)) {
            schema.allowMultiple(node);
        } else if ("minLength".equals(key)) {
            schema.minLength(node);
        } else if ("maxLength".equals(key)) {
            schema.maxLength(node);
        } else if ("minimum".equals(key)) {
            schema.minimum(node);
        } else if ("maximum".equals(key)) {
            schema.maximum(node);
        } else if ("exclusiveMinimum".equals(key)) {
            schema.exclusiveMinimum(node);
        } else if ("exclusiveMaximum".equals(key)) {
            schema.exclusiveMaximum(node);
        } else if ("multipleOf".equals(key)) {
            schema.multipleOf(node);
        } else if ("minItems".equals(key)) {
            schema.minItems(node);
        } else if ("maxItems".equals(key)) {
            schema.maxItems(node);
        } else if ("uniqueItems".equals(key)) {
            schema.uniqueItems(node);
        } else if ("minFields".equals(key)) {
            schema.minFields(node);
        } else if ("maxFields".equals(key)) {
            schema.maxFields(node);
        }
    }

    private static void setSchemaList(Schema schema, BexValue source, String key) {
        BexValue value = source.get(key);
        if (value.isUndefined()) {
            return;
        }
        if (!value.isList()) {
            throw new BexException("Blue schema " + key + " field must be a list");
        }
        List<Node> nodes = toNodeList(value);
        if ("enum".equals(key)) {
            schema.enumValues(nodes);
        } else if ("options".equals(key)) {
            schema.options(nodes);
        }
    }

    private static void validateSchemaKeys(BexValue value) {
        for (String key : value.keys()) {
            if (!SCHEMA_KEYS.contains(key)) {
                throw new BexException("Unsupported Blue schema field: " + key);
            }
        }
    }

    private static Set<String> schemaKeys() {
        return new LinkedHashSet<>(Arrays.asList(
                "required",
                "allowMultiple",
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
                "enum",
                "options"));
    }
}
