package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.result.BexMetricsRecorder;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Shared grammar, validation primitives, and compiler state. */
abstract class BexCompilerSupport {
    static final String TEXT_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Text");
    static final String INTEGER_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Integer");
    static final String DOUBLE_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Double");
    static final String BOOLEAN_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Boolean");
    static final Set<String> RESERVED_BLUE_KEYS = reservedBlueKeys();

    final BexContainsCache containsCache = new BexContainsCache();
    final BexMetricsRecorder metrics;
    final BexIntrinsicCatalog intrinsics;
    final Set<String> requiredIntrinsicBlueIds = new LinkedHashSet<>();
    Map<String, FunctionSignature> functionSignatures = Collections.emptyMap();
    Map<String, BexValue> constants = Collections.emptyMap();
    String currentFunction = "$root";

    BexCompilerSupport(BexMetricsRecorder metrics, BexIntrinsicCatalog intrinsics) {
        this.metrics = metrics;
        this.intrinsics = intrinsics != null
                ? intrinsics
                : blueId -> false;
    }

    abstract CompiledExpression compileExpr(
            FrozenNode node, CompileScope scope, String pointer);

    FrozenNode prop(FrozenNode node, String key) {
        if (node == null) {
            return null;
        }
        if (node.getProperties() != null && node.getProperties().containsKey(key)) {
            return node.getProperties().get(key);
        }
        if ("name".equals(key) && node.getName() != null) {
            return scalarNode(node.getName());
        }
        if ("description".equals(key) && node.getDescription() != null) {
            return scalarNode(node.getDescription());
        }
        if ("type".equals(key) && node.getType() != null) {
            return node.getType();
        }
        if ("itemType".equals(key) && node.getItemType() != null) {
            return node.getItemType();
        }
        if ("keyType".equals(key) && node.getKeyType() != null) {
            return node.getKeyType();
        }
        if ("valueType".equals(key) && node.getValueType() != null) {
            return node.getValueType();
        }
        if ("value".equals(key) && node.getValue() != null) {
            return scalarNode(node.getValue());
        }
        if ("blueId".equals(key) && node.getReferenceBlueId() != null) {
            return scalarNode(node.getReferenceBlueId());
        }
        if ("blue".equals(key) && node.getBlue() != null) {
            return node.getBlue();
        }
        if ("contracts".equals(key) && node.getContracts() != null) {
            return node.getContracts();
        }
        return null;
    }

    FrozenNode explicitProp(FrozenNode node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    boolean hasExplicitProperty(FrozenNode node, String key) {
        return node != null && node.getProperties() != null && node.getProperties().containsKey(key);
    }

    boolean hasAuthoredField(FrozenNode node, String key) {
        return authoredFieldNames(node).contains(key);
    }

    Set<String> authoredFieldNames(FrozenNode node) {
        Set<String> fields = new LinkedHashSet<>();
        if (node == null) {
            return fields;
        }
        if (node.getProperties() != null) {
            fields.addAll(node.getProperties().keySet());
        }
        if (node.getName() != null) fields.add("name");
        if (node.getDescription() != null) fields.add("description");
        if (node.getType() != null) fields.add("type");
        if (node.getItemType() != null) fields.add("itemType");
        if (node.getKeyType() != null) fields.add("keyType");
        if (node.getValueType() != null) fields.add("valueType");
        if (node.getValue() != null) fields.add("value");
        if (node.getItems() != null) fields.add("items");
        if (node.getReferenceBlueId() != null) fields.add("blueId");
        if (node.getBlue() != null) fields.add("blue");
        if (node.getSchema() != null) fields.add("schema");
        if (node.getMergePolicy() != null) fields.add("mergePolicy");
        if (node.getContracts() != null) fields.add("contracts");
        if (node.getPreviousBlueId() != null) fields.add("$previous");
        if (node.getPosition() != null) fields.add("$pos");
        return fields;
    }

    FrozenNode required(FrozenNode node, String label) {
        if (node == null) {
            throw new BexException("Missing required field: " + label);
        }
        return node;
    }

    String requiredText(FrozenNode node, String label) {
        String value = text(node);
        if (value == null) {
            throw new BexException("Missing required text field: " + label);
        }
        return value;
    }

    String requiredNonEmptyText(FrozenNode node, String label) {
        String value = requiredText(node, label);
        if (value.isEmpty()) {
            throw new BexException("Required text field is empty: " + label);
        }
        return value;
    }

    String text(FrozenNode node) {
        return node != null && node.getValue() instanceof String
                ? (String) node.getValue()
                : null;
    }

    boolean isExpressionOperatorShape(FrozenNode node) {
        if (node == null
                || node.getProperties() == null
                || node.getProperties().size() != 1
                || authoredFieldNames(node).size() != 1) {
            return false;
        }
        String key = node.getProperties().keySet().iterator().next();
        return key.startsWith("$");
    }

    boolean isOperator(FrozenNode node, String op) {
        return isExpressionOperatorShape(node) && node.getProperties().containsKey(op);
    }

    FrozenNode onlyValue(FrozenNode node) {
        return node.getProperties().values().iterator().next();
    }

    void addMetadataFields(Map<String, CompiledExpression> fields, FrozenNode node, CompileScope scope, String pointer) {
        if (node.getName() != null) {
            fields.put("name", new TransientLiteralExpr(node.getName()));
        }
        if (node.getDescription() != null) {
            fields.put("description", new TransientLiteralExpr(node.getDescription()));
        }
        if (node.getType() != null) {
            fields.put("type", compileExpr(node.getType(), scope, pointer + "/type"));
        }
        if (node.getItemType() != null) {
            fields.put("itemType", compileExpr(node.getItemType(), scope, pointer + "/itemType"));
        }
        if (node.getKeyType() != null) {
            fields.put("keyType", compileExpr(node.getKeyType(), scope, pointer + "/keyType"));
        }
        if (node.getValueType() != null) {
            fields.put("valueType", compileExpr(node.getValueType(), scope, pointer + "/valueType"));
        }
        if (node.getValue() != null) {
            fields.put("value", new TransientLiteralExpr(node.getValue()));
        }
        if (node.getReferenceBlueId() != null) {
            fields.put("blueId", new TransientLiteralExpr(node.getReferenceBlueId()));
        }
        if (node.getBlue() != null) {
            fields.put("blue", compileExpr(node.getBlue(), scope, pointer + "/blue"));
        }
        if (node.getContracts() != null) {
            fields.put("contracts", compileExpr(node.getContracts(), scope, pointer + "/contracts"));
        }
        if (node.getSchema() != null) {
            fields.put("schema", new LiteralExpr(BexValues.nodeSnapshot(new blue.language.model.Node().schema(node.getSchema()))));
        }
        if (node.getMergePolicy() != null) {
            fields.put("mergePolicy", new TransientLiteralExpr(node.getMergePolicy()));
        }
    }

    boolean hasLanguageFields(FrozenNode node) {
        return node.getName() != null
                || node.getDescription() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getReferenceBlueId() != null
                || node.getBlue() != null
                || node.getContracts() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null;
    }

    FrozenNode scalarNode(Object value) {
        return FrozenNode.fromResolvedNode(new blue.language.model.Node().value(value));
    }

    CompiledExpression sourceExpr(String functionName, String pointer, String operator, CompiledExpression expression) {
        return new SourceExpr(BexSourcePath.of(functionName, pointer, operator), expression);
    }

    CompiledStatement sourceStatement(String functionName, String pointer, String operator, CompiledStatement statement) {
        return new SourceStatement(BexSourcePath.of(functionName, pointer, operator), statement);
    }

    void validateDistinctForEachBindings(String itemName, String keyName, String indexName) {
        if (keyName != null && keyName.equals(itemName)) {
            throw new BexException("$forEach.key must use a different binding name than $forEach.item");
        }
        if (indexName != null && indexName.equals(itemName)) {
            throw new BexException("$forEach.index must use a different binding name than $forEach.item");
        }
        if (keyName != null && indexName != null && keyName.equals(indexName)) {
            throw new BexException("$forEach.key must use a different binding name than $forEach.index");
        }
    }

    String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    void validatePlainObjectContainer(FrozenNode node, String label) {
        if (node == null) {
            return;
        }
        if (node.getValue() != null || node.getItems() != null || node.getReferenceBlueId() != null) {
            throw new BexException(label + " must be a plain object with non-reserved field names");
        }
        if (node.getPreviousBlueId() != null || node.getPosition() != null) {
            throw new BexException(label + " contains a Blue list-control key; use non-reserved names");
        }
        if (node.getContracts() != null) {
            throw new BexException(label + " contains reserved Blue key: contracts");
        }
        if (node.getName() != null
                || node.getDescription() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getBlue() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null) {
            throw new BexException(label + " contains a Blue language key; use non-reserved names");
        }
        if (node.getProperties() != null) {
            for (String key : node.getProperties().keySet()) {
                if (RESERVED_BLUE_KEYS.contains(key)) {
                    throw new BexException(label + " contains reserved Blue key: " + key);
                }
            }
        }
    }

    void rejectBexAnywhereInStaticPattern(FrozenNode pattern, String pointer) {
        if (pattern != null && containsCache.containsBex(pattern, metrics)) {
            throw new BexException("BEX expressions inside static Blue patterns are not supported at " + pointer);
        }
        rejectBexInStaticBlueDefinitionFields(pattern, pointer);
    }

    void rejectBexInStaticBlueDefinitionFields(FrozenNode node, String pointer) {
        if (node == null) {
            return;
        }
        rejectBexInStaticField(node.getType(), pointer + "/type", "type");
        rejectBexInStaticField(node.getItemType(), pointer + "/itemType", "itemType");
        rejectBexInStaticField(node.getKeyType(), pointer + "/keyType", "keyType");
        rejectBexInStaticField(node.getValueType(), pointer + "/valueType", "valueType");
        rejectBexInStaticField(node.getBlue(), pointer + "/blue", "blue");
        // Contracts can carry a future program under $literal. Only the
        // specification's static type/blue/schema positions inside them are
        // forbidden expression positions (BEX 2.0 sections 2.5 and 2.6).
        rejectBexInStaticBlueDefinitionFields(node.getContracts(), pointer + "/contracts");
        if (node.getSchema() != null) {
            rejectBexInSchema(node.getSchema(), pointer + "/schema");
        }
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                rejectBexInStaticBlueDefinitionFields(node.getItems().get(i), pointer + "/" + i);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
                rejectBexInStaticBlueDefinitionFields(entry.getValue(), pointer + "/" + escape(entry.getKey()));
            }
        }
    }

    void rejectBexInStaticField(FrozenNode field, String pointer, String fieldName) {
        if (field != null && containsCache.containsBex(field, metrics)) {
            throw new BexException("BEX expressions inside Blue " + fieldName
                    + " fields are not supported at " + pointer);
        }
        rejectBexInStaticBlueDefinitionFields(field, pointer);
    }

    void rejectBexInSchema(Schema schema, String pointer) {
        rejectSchemaNode(schema.getRequired(), pointer);
        rejectSchemaNode(schema.getMinLength(), pointer);
        rejectSchemaNode(schema.getMaxLength(), pointer);
        rejectSchemaNode(schema.getMinimum(), pointer);
        rejectSchemaNode(schema.getMaximum(), pointer);
        rejectSchemaNode(schema.getExclusiveMinimum(), pointer);
        rejectSchemaNode(schema.getExclusiveMaximum(), pointer);
        rejectSchemaNode(schema.getMultipleOf(), pointer);
        rejectSchemaNode(schema.getMinItems(), pointer);
        rejectSchemaNode(schema.getMaxItems(), pointer);
        rejectSchemaNode(schema.getUniqueItems(), pointer);
        rejectSchemaNode(schema.getMinFields(), pointer);
        rejectSchemaNode(schema.getMaxFields(), pointer);
        if (schema.getEnum() != null) {
            for (Node node : schema.getEnum()) {
                rejectSchemaNode(node, pointer);
            }
        }
    }

    void rejectSchemaNode(Node node, String pointer) {
        if (node == null) {
            return;
        }
        FrozenNode frozen = FrozenNode.fromResolvedNode(node);
        if (containsCache.containsBex(frozen, metrics)) {
            throw new BexException("BEX expressions inside schema are not supported at " + pointer);
        }
        rejectBexInStaticBlueDefinitionFields(frozen, pointer);
    }

    static Set<String> reservedBlueKeys() {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys,
                "name",
                "description",
                "type",
                "itemType",
                "keyType",
                "valueType",
                "value",
                "items",
                "blueId",
                "blue",
                "schema",
                "constraints",
                "mergePolicy",
                "properties",
                "contracts",
                "$previous",
                "$pos",
                "$replace",
                "$empty");
        return Collections.unmodifiableSet(keys);
    }

    static final class FunctionSignature {
        private final List<BexCompiledProgram.ArgSpec> args;
        private final Map<String, BexCompiledProgram.ArgSpec> argsByName;

        FunctionSignature(List<BexCompiledProgram.ArgSpec> args) {
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
            Map<String, BexCompiledProgram.ArgSpec> byName = new LinkedHashMap<>();
            for (BexCompiledProgram.ArgSpec arg : this.args) {
                byName.put(arg.name(), arg);
            }
            this.argsByName = Collections.unmodifiableMap(byName);
        }

        List<BexCompiledProgram.ArgSpec> args() {
            return args;
        }

        BexCompiledProgram.ArgSpec arg(String name) {
            return argsByName.get(name);
        }
    }
}
