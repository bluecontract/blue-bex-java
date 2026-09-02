package blue.bex.type;

import blue.bex.value.BexValue;
import blue.language.model.Schema;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Closed shape and required-presence rules used by BEX type matching. */
public final class BexPatternValidator {
    private static final String TEXT_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Text");
    private static final String INTEGER_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Integer");
    private static final String DOUBLE_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Double");
    private static final String BOOLEAN_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Boolean");

    /**
     * Reports whether a static BEX pattern carries no matching constraint.
     *
     * <p>Blue Language now preserves an explicit empty object payload, so
     * {@link FrozenNode#isEmptyNode()} alone no longer recognizes the BEX
     * specification's empty-pattern wildcard. Name and description are
     * metadata; they do not turn an otherwise empty pattern into a
     * constraint.</p>
     *
     * @param pattern frozen static pattern, or {@code null}
     * @return {@code true} when the pattern imposes no value constraint
     */
    static boolean isUnconstrainedPattern(FrozenNode pattern) {
        return pattern == null
                || (pattern.getType() == null
                && pattern.getItemType() == null
                && pattern.getKeyType() == null
                && pattern.getValueType() == null
                && pattern.getValue() == null
                && pattern.getItems() == null
                && (pattern.getProperties() == null
                || pattern.getProperties().isEmpty())
                && pattern.getContracts() == null
                && pattern.getReferenceBlueId() == null
                && pattern.getSchema() == null
                && pattern.getMergePolicy() == null
                && pattern.getPreviousBlueId() == null
                && pattern.getPosition() == null
                && pattern.getBlue() == null);
    }

    public boolean requiresPresence(FrozenNode target) {
        Schema schema = target.getSchema();
        if (schema != null && Boolean.TRUE.equals(
                schema.getRequiredValue())) {
            return true;
        }
        return hasValueInNestedStructure(target);
    }

    public boolean keyMatchesType(
            String key,
            FrozenNode targetKeyType) {
        String identity = targetKeyType.getReferenceBlueId();
        if (identity == null && targetKeyType.getType() != null) {
            identity = targetKeyType.getType().getReferenceBlueId();
        }
        if (TEXT_TYPE_BLUE_ID.equals(identity)) {
            return true;
        }
        if (INTEGER_TYPE_BLUE_ID.equals(identity)) {
            try {
                new BigInteger(key);
                return true;
            } catch (NumberFormatException invalidInteger) {
                return false;
            }
        }
        if (DOUBLE_TYPE_BLUE_ID.equals(identity)) {
            try {
                return Double.isFinite(Double.parseDouble(key));
            } catch (NumberFormatException invalidDouble) {
                return false;
            }
        }
        if (BOOLEAN_TYPE_BLUE_ID.equals(identity)) {
            return "true".equalsIgnoreCase(key)
                    || "false".equalsIgnoreCase(key);
        }
        return false;
    }

    private boolean hasValueInNestedStructure(FrozenNode node) {
        if (node.isReferenceOnly() || node.getValue() != null) {
            return true;
        }
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                if (hasValueInNestedStructure(item)) {
                    return true;
                }
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode property : node.getProperties().values()) {
                if (hasValueInNestedStructure(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    enum CandidatePosition {
        ROOT,
        OBJECT_MEMBER,
        LIST_ITEM
    }

    /** Current-node-only view; descendants remain lazy BEX cursors. */
    static final class CandidateView {
        final BexValue source;
        final Object scalar;
        final BexValue items;
        final List<String> propertyKeys;
        final boolean materializeCurrent;
        final boolean valid;

        private CandidateView(
                BexValue source,
                Object scalar,
                BexValue items,
                List<String> propertyKeys,
                boolean materializeCurrent,
                boolean valid) {
            this.source = source;
            this.scalar = scalar;
            this.items = items;
            this.propertyKeys = propertyKeys;
            this.materializeCurrent = materializeCurrent;
            this.valid = valid;
        }

        static CandidateView from(
                BexValue source,
                CandidatePosition position) {
            if (source == null || source.isUndefined()) {
                return invalid(source);
            }
            if (source.isNull()) {
                return empty(source);
            }
            if (source.isScalar()) {
                return new CandidateView(
                        source,
                        source.toSimple(),
                        null,
                        Collections.<String>emptyList(),
                        false,
                        true);
            }
            if (source.isList()) {
                return new CandidateView(
                        source,
                        null,
                        source,
                        Collections.<String>emptyList(),
                        false,
                        true);
            }
            if (!source.isObject()) {
                return invalid(source);
            }

            List<String> keys = source.keys();
            if (isEmptyPlaceholder(source, keys)) {
                return position == CandidatePosition.LIST_ITEM
                        ? empty(source)
                        : invalid(source);
            }

            boolean hasBlueId = false;
            boolean hasValue = false;
            boolean hasItems = false;
            int retainedFields = 0;
            BexValue items = null;
            ArrayList<String> properties = new ArrayList<>();
            for (String key : keys) {
                retainedFields++;
                if (isForbiddenField(key)) {
                    return invalid(source);
                }
                if ("blueId".equals(key)) {
                    hasBlueId = true;
                } else if ("value".equals(key)) {
                    BexValue child = source.get(key);
                    hasValue = true;
                    if (child == null || child.isUndefined()
                            || child.isNull() || !child.isScalar()) {
                        return invalid(source);
                    }
                } else if ("items".equals(key)) {
                    BexValue child = source.get(key);
                    hasItems = true;
                    if (child == null || child.isUndefined()
                            || !child.isList()) {
                        return invalid(source);
                    }
                    items = child;
                } else if (isOrdinaryProperty(key)) {
                    properties.add(key);
                }
            }

            if (hasBlueId) {
                return retainedFields == 1
                        ? materialized(source)
                        : invalid(source);
            }
            int payloadKinds = (hasValue ? 1 : 0)
                    + (hasItems ? 1 : 0)
                    + (!properties.isEmpty() ? 1 : 0);
            if (payloadKinds > 1) {
                return invalid(source);
            }
            if (hasValue) {
                return materialized(source);
            }
            return new CandidateView(
                    source,
                    null,
                    items,
                    Collections.unmodifiableList(properties),
                    false,
                    true);
        }

        boolean hasProperty(String key) {
            return isOrdinaryProperty(key)
                    && propertyKeys.contains(key);
        }

        private static CandidateView empty(BexValue source) {
            return new CandidateView(
                    source, null, null,
                    Collections.<String>emptyList(), false, true);
        }

        private static CandidateView materialized(BexValue source) {
            return new CandidateView(
                    source, null, null,
                    Collections.<String>emptyList(), true, true);
        }

        private static CandidateView invalid(BexValue source) {
            return new CandidateView(
                    source, null, null,
                    Collections.<String>emptyList(), false, false);
        }

        private static boolean isEmptyPlaceholder(
                BexValue source,
                List<String> keys) {
            if (keys.size() != 1 || !"$empty".equals(keys.get(0))) {
                return false;
            }
            BexValue marker = source.get("$empty");
            return marker != null && marker.isScalar()
                    && Boolean.TRUE.equals(marker.toSimple());
        }
    }

    private static boolean isOrdinaryProperty(String key) {
        return !"name".equals(key)
                && !"description".equals(key)
                && !"type".equals(key)
                && !"itemType".equals(key)
                && !"keyType".equals(key)
                && !"valueType".equals(key)
                && !"mergePolicy".equals(key)
                && !"value".equals(key)
                && !"items".equals(key)
                && !"blueId".equals(key)
                && !"contracts".equals(key)
                && !"schema".equals(key)
                && !isForbiddenField(key);
    }

    private static boolean isForbiddenField(String key) {
        return "properties".equals(key)
                || "constraints".equals(key)
                || "allowMultiple".equals(key)
                || "options".equals(key)
                || "blue".equals(key)
                || "$previous".equals(key)
                || "$pos".equals(key)
                || "$replace".equals(key)
                || "$empty".equals(key);
    }
}
