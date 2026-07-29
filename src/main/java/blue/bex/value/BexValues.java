package blue.bex.value;

import blue.bex.BexException;
import blue.language.Blue;
import blue.language.BlueOperationLimits;
import blue.language.BlueOperationOutcome;
import blue.language.BlueOperationResult;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.SchemaToMapListOrValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

/**
 * Value factories and shared value helpers.
 */
public final class BexValues {
    private BexValues() {
    }

    public static final BexValue UNDEFINED = new UndefinedBexValue();
    public static final BexValue NULL = new NullBexValue();

    public static BexValue undefined() {
        return UNDEFINED;
    }

    public static BexValue nullValue() {
        return NULL;
    }

    public static BexValue scalar(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof BexValue) {
            return (BexValue) value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return new ScalarBexValue(BigInteger.valueOf(((Number) value).longValue()));
        }
        if (value instanceof BigInteger || value instanceof BigDecimal || value instanceof String || value instanceof Boolean) {
            return new ScalarBexValue(value);
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new BexException("Non-finite numeric value is not supported");
            }
            return new ScalarBexValue(BigDecimal.valueOf(d));
        }
        throw new BexException("Unsupported scalar value: " + value.getClass().getName());
    }

    /**
     * Direct cursor over a Node with an explicit immutability contract.
     */
    public static BexValue nodeCursorTrustedImmutable(Node node) {
        return node != null ? new NodeBexValue(node) : UNDEFINED;
    }

    /**
     * Snapshot-safe Node value. This clones/freezes the Node at the boundary and
     * is therefore more expensive than a trusted immutable cursor.
     */
    public static BexValue nodeSnapshot(Node node) {
        return node != null ? frozen(FrozenNode.fromNode(node.clone())) : UNDEFINED;
    }

    /**
     * Wraps an immutable Blue node as an exact value. The identity remains
     * lazy inside {@link FrozenNode}; merely carrying the value does not hash
     * or materialize it.
     */
    public static BexValue frozen(FrozenNode node) {
        return node != null ? new FrozenNodeBexValue(node, node) : UNDEFINED;
    }

    /**
     * Creates a representation-blind exact value from canonical identity and
     * its resolved semantic view.
     */
    public static BexValue exact(FrozenNode canonicalNode, FrozenNode resolvedNode) {
        return exact(canonicalNode, resolvedNode, null);
    }

    /**
     * Creates an exact value with an identity already established by the host.
     * The retained identity is returned without independent re-hashing.
     */
    public static BexValue exact(FrozenNode canonicalNode,
                                 FrozenNode resolvedNode,
                                 String exactBlueId) {
        if (canonicalNode == null && resolvedNode == null) {
            return UNDEFINED;
        }
        FrozenNode canonical = canonicalNode != null ? canonicalNode : resolvedNode;
        FrozenNode semantic = resolvedNode != null ? resolvedNode : canonicalNode;
        String retainedBlueId = exactBlueId;
        if (retainedBlueId == null && canonical.isReferenceOnly()) {
            retainedBlueId = canonical.getReferenceBlueId();
        }
        /*
         * A finalized cyclic member has no independently hashable body.
         * Public callers cannot manufacture the Language host's complete-set
         * proof merely by pairing MASTER#index with an arbitrary resolved
         * node. Retain such values as exact opaque references; structural
         * access must pass through a cyclic-aware reference materializer.
         */
        if (retainedBlueId != null
                && retainedBlueId.indexOf('#') >= 0) {
            canonical = FrozenNode.fromNode(
                    new Node().blueId(retainedBlueId));
            semantic = canonical;
        }
        return new FrozenNodeBexValue(canonical, semantic, exactBlueId);
    }

    /**
     * Retains a boundary-established exact identity while preserving the
     * immutable run-local semantic cursor that produced it.
     *
     * <p>This avoids recursively reopening exact descendants when an admitted
     * aggregate is subsequently read through {@code $resultValue},
     * {@code $changeset}, {@code $events}, or a local variable.</p>
     */
    public static BexValue admittedExact(FrozenNode frozenValue,
                                         String exactBlueId,
                                         BexValue semanticValue) {
        return new AdmittedExactBexValue(
                frozenValue,
                exactBlueId,
                semanticValue);
    }

    /**
     * Adds demand-driven, verified reference materialization to an exact
     * frozen value.
     *
     * <p>{@link Blue#resolveToSnapshot(Object)} intentionally leaves untyped
     * pure references collapsed. BEX may carry those values by identity
     * without loading them, but semantic operations such as member access,
     * kind inspection, or key enumeration must establish their content.
     * {@link Blue#expandLimited(Node, BlueOperationLimits)} is the structured
     * Language boundary that both obtains and verifies that evidence.
     * Provider absence and temporary unavailability remain incomplete
     * execution evidence, while invalid evidence remains a deterministic
     * failure; neither is converted to BEX {@code undefined}.</p>
     *
     * @param value exact frozen value to make reference-backed
     * @param blue Language resolver used only when semantic content is demanded
     * @return a lazy reference-backed exact value, or {@code value} when it is
     *         not backed by a {@link FrozenNode}
     */
    public static BexValue referenceBacked(BexValue value, Blue blue) {
        if (value instanceof FrozenNodeBexValue && blue != null) {
            return ((FrozenNodeBexValue) value)
                    .withReferenceMaterializer(
                            blueId -> loadReference(blue, blueId));
        }
        return value;
    }

    private static ResolvedSnapshot loadReference(
            Blue blue, String blueId) {
        BlueOperationResult<Node> result = blue.expandLimited(
                new Node().blueId(blueId),
                BlueOperationLimits.demandedPath(""));
        if (result.outcome() == BlueOperationOutcome.INVALID) {
            throw new InvalidExecutionEvidenceException(
                    result.reason().orElse(
                            "Invalid exact reference evidence for " + blueId));
        }
        if (result.outcome() != BlueOperationOutcome.ESTABLISHED
                || !result.value().isPresent()) {
            java.util.Set<String> outstanding =
                    result.outstandingBlueIds();
            throw new ExecutionEvidenceUnavailableException(
                    result.reason().orElse(
                            "Exact reference evidence is unavailable for "
                                    + blueId),
                    outstanding.isEmpty()
                            ? Collections.singletonList(blueId)
                            : outstanding);
        }
        FrozenNode canonicalReference = FrozenNode.fromNode(
                new Node().blueId(blueId));
        FrozenNode verifiedDirectFragment = FrozenNode.fromResolvedNode(
                result.value().get());
        return new ResolvedSnapshot(
                canonicalReference, verifiedDirectFragment);
    }

    /**
     * Imports a frozen syntax tree as a transient runtime value. This is used
     * for executable literals; exact host/document values must use
     * {@link #frozen(FrozenNode)} or {@link #exact(FrozenNode, FrozenNode)}.
     */
    public static BexValue transientFrozen(FrozenNode node) {
        if (node == null) {
            return UNDEFINED;
        }
        return fromSimple(NodeToMapListOrValue.get(node.toNode()));
    }

    static BexValue schemaSnapshot(Schema schema) {
        if (schema == null) {
            return UNDEFINED;
        }
        // Schema is the value of a node's "schema" key, not another schema-bearing node.
        return fromSimple(SchemaToMapListOrValue.get(
                schema.clone(),
                BexValues::schemaNodeToSimple));
    }

    private static Object schemaNodeToSimple(Node node) {
        if (isCoreTypedScalar(node)) {
            return node.getValue();
        }
        return NodeToMapListOrValue.get(node);
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

    public static String frozenBlueId(BexValue value) {
        if (value == null || !value.isExact()) {
            return null;
        }
        try {
            return value.exactBlueId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static BexValue map(Map<String, BexValue> values) {
        return new MapBexValue(values);
    }

    public static BexValue list(List<BexValue> values) {
        return new ListBexValue(values);
    }

    public static BexValue overlay(BexValue base, String key, BexValue value) {
        return new OverlayMapBexValue(base, key, value);
    }

    public static BexValue pointerSet(BexValue base, List<String> segments, BexValue value, String op) {
        if (segments == null || segments.isEmpty()) {
            return "remove".equals(op)
                    ? UNDEFINED
                    : (value != null ? value : UNDEFINED);
        }
        return new PointerSetBexValue(base, segments, value, op);
    }

    /**
     * Applies a result-overlay pointer update.
     *
     * <p>This differs from the ordinary {@link #pointerSet} operation only for
     * terminal list removal: result overlays retain the list's positional
     * extent and expose an undefined slot, as required by BEX 2.0 §10.4.1.</p>
     */
    public static BexValue resultOverlayPointerSet(BexValue base,
                                                   List<String> segments,
                                                   BexValue value,
                                                   String op) {
        if (segments == null || segments.isEmpty()) {
            return "remove".equals(op)
                    ? UNDEFINED
                    : (value != null ? value : UNDEFINED);
        }
        return new PointerSetBexValue(base, segments, value, op, true);
    }

    public static BexValue fromSimple(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof Map) {
            Map<String, BexValue> out = new LinkedHashMap<>();
            for (Object entryObject : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
                out.put(String.valueOf(entry.getKey()), fromSimple(entry.getValue()));
            }
            return map(out);
        }
        if (value instanceof List) {
            List<BexValue> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(fromSimple(item));
            }
            return list(out);
        }
        return scalar(value);
    }

    public static boolean truthy(BexValue value) {
        return BexTruthiness.truthy(value);
    }

    public static boolean empty(BexValue value) {
        return !truthy(value);
    }

    public static boolean equal(BexValue left, BexValue right) {
        return BexEquality.equal(left, right);
    }

    public static String kind(BexValue value) {
        if (value == null || value.isUndefined()) {
            return "undefined";
        }
        if (value.isNull()) {
            return "null";
        }
        if (value.isList()) {
            return "list";
        }
        if (value.isObject()) {
            return "object";
        }
        if (!value.isScalar()) {
            return "undefined";
        }
        Object raw = rawScalar(value);
        if (raw instanceof Boolean) {
            return "boolean";
        }
        if (raw instanceof BigInteger
                || raw instanceof Integer
                || raw instanceof Long
                || raw instanceof Short
                || raw instanceof Byte) {
            return "integer";
        }
        if (raw instanceof BigDecimal
                || raw instanceof Float
                || raw instanceof Double) {
            return "double";
        }
        return "text";
    }

    static Object scalarSimple(Object value) {
        return value;
    }

    static Object rawScalar(BexValue value) {
        if (value instanceof ScalarBexValue) {
            return ((ScalarBexValue) value).raw();
        }
        if (value instanceof FrozenNodeBexValue) {
            return ((FrozenNodeBexValue) value).rawScalar();
        }
        if (value instanceof NodeBexValue) {
            return ((NodeBexValue) value).rawScalar();
        }
        if (value instanceof AdmittedExactBexValue) {
            return ((AdmittedExactBexValue) value).rawScalar();
        }
        return value.asText();
    }

    static BexValue atSegments(BexValue value, List<String> segments) {
        BexValue current = value;
        for (String segment : segments) {
            current = current.get(segment);
            if (current.isUndefined()) {
                return current;
            }
        }
        return current;
    }
}
