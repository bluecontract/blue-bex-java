package blue.bex.type;

import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasMeter;
import blue.bex.value.BexBlueNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.runtime.BlueLanguage;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorFailureException;
import blue.language.snapshot.FrozenNode;
import blue.language.matching.FrozenTypeMatcher;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * BEX boundary adapter for Blue's node/type matcher.
 */
public final class BexBlueTypeMatcher {
    private static final int TEXT_BLOCK_CODE_POINTS = 64;
    private static final String TEXT_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Text");
    private static final String INTEGER_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Integer");
    private static final String DOUBLE_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Double");
    private static final String BOOLEAN_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Boolean");

    private final BlueLanguage blue;
    private final FrozenTypeMatcher matcher;

    public BexBlueTypeMatcher(BlueLanguage blue) {
        this.blue = blue != null
                ? blue : BlueLanguage.builder().build();
        this.matcher = FrozenTypeMatcher.withVerifiedReferenceMaterializer(
                reference -> FrozenNode.fromResolvedNode(
                        BexValues.referenceBacked(
                                BexValues.frozen(reference),
                                this.blue)
                                .toNode()));
    }

    /**
     * Matches a BEX value at the Blue Language boundary while recording the
     * canonical BEX comparison work.
     *
     * <p>The metering walk deliberately does not depend on
     * {@link FrozenTypeMatcher}'s caches. Every semantic occurrence that the
     * pattern compares is admitted before that recursive match is performed,
     * so warm and cold executions have the same BEX trace.</p>
     */
    public boolean matches(BexValue value,
                           FrozenNode pattern,
                           BexGasMeter gas,
                           BexSourcePath sourcePath) {
        if (value == null || value.isUndefined()) {
            return false;
        }
        if (pattern == null) {
            return true;
        }
        MatchGas matchGas = new MatchGas(gas, sourcePath);
        return matchesMetered(
                value,
                pattern,
                matchGas,
                CandidatePosition.ROOT);
    }

    private static RuntimeException classifiedBoundaryFailure(
            RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current
                    instanceof ExecutionEvidenceUnavailableException
                    || current
                    instanceof InvalidExecutionEvidenceException
                    || current
                    instanceof ProcessorFailureException
                    || current
                    instanceof PortableLimitExceededException
                    || current
                    instanceof GasLimitExceededException
                    || current
                    instanceof BexGasLimitExceededException) {
                return (RuntimeException) current;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        /*
         * An unexpected cursor/provider failure is still an execution
         * failure. It must never be converted into a semantic non-match.
         */
        return failure;
    }

    private boolean matchesMetered(FrozenNode candidate,
                                   FrozenNode pattern,
                                   MatchGas gas) {
        gas.comparisonNode();
        return matchesFrozenAfterAdmission(
                candidate, pattern, gas);
    }

    private boolean matchesFrozenAfterAdmission(
            FrozenNode candidate,
            FrozenNode pattern,
            MatchGas gas) {
        if (pattern.isEmptyNode()) {
            return true;
        }

        /*
         * A pure BlueId pattern is an identity/type check. It has no nested
         * semantic occurrences to compare.
         */
        if (pattern.isReferenceOnly()) {
            return matcher.matchesType(candidate, pattern);
        }

        if (!meterScalarComparison(candidate.getValue(),
                pattern.getValue(), gas)) {
            return false;
        }

        if (!meterItemType(candidate, pattern.getItemType(), gas)) {
            return false;
        }
        if (!meterKeyType(candidate, pattern.getKeyType(), gas)) {
            return false;
        }
        if (!meterValueType(candidate, pattern.getValueType(), gas)) {
            return false;
        }
        if (!meterItems(candidate, pattern.getItems(), gas)) {
            return false;
        }
        if (!meterProperties(candidate, pattern.getProperties(), gas)) {
            return false;
        }

        /*
         * Blue remains authoritative for declared-type, schema, subtype, and
         * provider semantics. The canonical recursive BEX work above has
         * already been admitted before this call can perform it.
         */
        return matcher.matchesType(candidate, pattern);
    }

    /**
     * Walks a candidate cursor lazily. The current occurrence is admitted
     * before any semantic cursor access, and a descendant is not converted or
     * materialized until its own recursive admission succeeds.
     */
    private boolean matchesMetered(
            BexValue candidate,
            FrozenNode pattern,
            MatchGas gas,
            CandidatePosition position) {
        gas.comparisonNode();
        return matchesAfterAdmission(
                candidate, pattern, gas, position);
    }

    private boolean matchesAfterAdmission(
            BexValue candidate,
            FrozenNode pattern,
            MatchGas gas,
            CandidatePosition position) {
        if (pattern.isEmptyNode()) {
            return true;
        }
        if (candidate == null
                || candidate.isUndefined()) {
            return !requiresPresence(pattern);
        }

        /*
         * Reference-only matching has no nested BEX occurrences. Blue may
         * therefore materialize the admitted current occurrence directly.
         */
        if (pattern.isReferenceOnly()) {
            if (candidate.isExact()
                    && pattern.getReferenceBlueId().equals(
                    candidate.exactBlueId())) {
                return true;
            }
            /*
             * A transient candidate must still be a valid local Blue shape
             * before its identity can be compared. Invalid local content is
             * a semantic non-match; provider and cursor failures raised while
             * inspecting the admitted occurrence continue to propagate.
             */
            if (!candidate.isExact()) {
                CandidateView referenceView;
                try {
                    referenceView = CandidateView.from(
                            candidate, position);
                } catch (RuntimeException viewFailure) {
                    throw classifiedBoundaryFailure(
                            viewFailure);
                }
                if (!referenceView.valid) {
                    return false;
                }
            }
            return matchesAuthoritatively(
                    candidate, pattern, position);
        }

        CandidateView view;
        try {
            view = CandidateView.from(
                    candidate, position);
        } catch (RuntimeException viewFailure) {
            throw classifiedBoundaryFailure(viewFailure);
        }
        if (!view.valid) {
            return false;
        }
        if (view.materializeCurrent) {
            FrozenNode materialized = freezeCandidate(
                    candidate, position);
            return materialized != null
                    && matchesFrozenAfterAdmission(
                    materialized, pattern, gas);
        }

        if (!meterScalarComparison(
                view.scalar, pattern.getValue(), gas)) {
            return false;
        }
        if (!meterItemType(
                view, pattern.getItemType(), gas)) {
            return false;
        }
        if (!meterKeyType(
                view, pattern.getKeyType(), gas)) {
            return false;
        }
        if (!meterValueType(
                view, pattern.getValueType(), gas)) {
            return false;
        }
        if (!meterItems(
                view, pattern.getItems(), gas)) {
            return false;
        }
        if (!meterProperties(
                view, pattern.getProperties(), gas)) {
            return false;
        }
        return matchesAuthoritatively(
                candidate, pattern, position);
    }

    private boolean matchesAuthoritatively(
            BexValue candidate,
            FrozenNode pattern,
            CandidatePosition position) {
        FrozenNode frozen = freezeCandidate(
                candidate, position);
        return frozen != null
                && matcher.matchesType(frozen, pattern);
    }

    private FrozenNode freezeCandidate(
            BexValue candidate,
            CandidatePosition position) {
        try {
            if (candidate.isExact()) {
                return FrozenNode.fromResolvedNode(
                        candidate.toNode());
            }
            if (position == CandidatePosition.ROOT) {
                return FrozenNode.fromResolvedNode(
                        BexBlueNodeWriter.toSemanticNode(
                                candidate));
            }
            if (position == CandidatePosition.LIST_ITEM) {
                BexValue wrapper = BexValues.list(
                        Collections.singletonList(candidate));
                FrozenNode frozenWrapper =
                        FrozenNode.fromResolvedNode(
                                BexBlueNodeWriter
                                        .toSemanticNode(wrapper));
                return frozenWrapper.getItems().get(0);
            }
            Map<String, BexValue> member =
                    Collections.singletonMap(
                            "_bexCandidate", candidate);
            FrozenNode frozenWrapper =
                    FrozenNode.fromResolvedNode(
                            BexBlueNodeWriter.toSemanticNode(
                                    BexValues.map(member)));
            return frozenWrapper.getProperties().get(
                    "_bexCandidate");
        } catch (RuntimeException conversionFailure) {
            throw classifiedBoundaryFailure(
                    conversionFailure);
        }
    }

    private boolean meterItemType(FrozenNode candidate,
                                  FrozenNode targetItemType,
                                  MatchGas gas) {
        if (targetItemType == null) {
            return true;
        }
        List<FrozenNode> items = candidate.getItems();
        if (items == null) {
            return true;
        }
        for (FrozenNode item : items) {
            if (!matchesMetered(item, targetItemType, gas)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterItemType(
            CandidateView candidate,
            FrozenNode targetItemType,
            MatchGas gas) {
        if (targetItemType == null
                || candidate.items == null) {
            return true;
        }
        for (int index = 0;
             index < candidate.items.size();
             index++) {
            gas.comparisonNode();
            BexValue item = candidate.items.get(
                    String.valueOf(index));
            if (!matchesAfterAdmission(
                    item,
                    targetItemType,
                    gas,
                    CandidatePosition.LIST_ITEM)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterKeyType(FrozenNode candidate,
                                 FrozenNode targetKeyType,
                                 MatchGas gas) {
        if (targetKeyType == null || candidate.getProperties() == null) {
            return true;
        }
        for (String key
                : candidate.getProperties().keySet()) {
            gas.comparisonNode();
            if (!keyMatchesType(key, targetKeyType)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterKeyType(
            CandidateView candidate,
            FrozenNode targetKeyType,
            MatchGas gas) {
        if (targetKeyType == null) {
            return true;
        }
        for (String key : candidate.propertyKeys) {
            gas.comparisonNode();
            if (!keyMatchesType(key, targetKeyType)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterValueType(FrozenNode candidate,
                                   FrozenNode targetValueType,
                                   MatchGas gas) {
        if (targetValueType == null || candidate.getProperties() == null) {
            return true;
        }
        for (String key
                : candidate.getProperties().keySet()) {
            if (!matchesMetered(
                    candidate.getProperties().get(key),
                    targetValueType,
                    gas)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterValueType(
            CandidateView candidate,
            FrozenNode targetValueType,
            MatchGas gas) {
        if (targetValueType == null) {
            return true;
        }
        for (String key : candidate.propertyKeys) {
            gas.comparisonNode();
            BexValue property =
                    candidate.source.get(key);
            if (!matchesAfterAdmission(
                    property,
                    targetValueType,
                    gas,
                    CandidatePosition.OBJECT_MEMBER)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterItems(FrozenNode candidate,
                               List<FrozenNode> targetItems,
                               MatchGas gas) {
        if (targetItems == null) {
            return true;
        }
        List<FrozenNode> candidateItems = candidate.getItems() != null
                ? candidate.getItems()
                : Collections.<FrozenNode>emptyList();
        for (int index = 0; index < targetItems.size(); index++) {
            FrozenNode targetItem = targetItems.get(index);
            if (index < candidateItems.size()) {
                if (!matchesMetered(
                        candidateItems.get(index), targetItem, gas)) {
                    return false;
                }
            } else if (requiresPresence(targetItem)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterItems(
            CandidateView candidate,
            List<FrozenNode> targetItems,
            MatchGas gas) {
        if (targetItems == null) {
            return true;
        }
        int candidateSize = candidate.items != null
                ? candidate.items.size()
                : 0;
        for (int index = 0;
             index < targetItems.size();
             index++) {
            FrozenNode targetItem =
                    targetItems.get(index);
            if (index < candidateSize) {
                gas.comparisonNode();
                BexValue item = candidate.items.get(
                        String.valueOf(index));
                if (!matchesAfterAdmission(
                        item,
                        targetItem,
                        gas,
                        CandidatePosition.LIST_ITEM)) {
                    return false;
                }
            } else if (requiresPresence(targetItem)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterProperties(
            FrozenNode candidate,
            Map<String, FrozenNode> targetProperties,
            MatchGas gas) {
        if (targetProperties == null) {
            return true;
        }
        Map<String, FrozenNode> candidateProperties =
                candidate.getProperties() != null
                        ? candidate.getProperties()
                        : Collections.<String, FrozenNode>emptyMap();
        for (Map.Entry<String, FrozenNode> candidateEntry
                : candidateProperties.entrySet()) {
            String key = candidateEntry.getKey();
            FrozenNode targetProperty =
                    targetProperties.get(key);
            if (targetProperty != null) {
                if (!matchesMetered(
                        candidateEntry.getValue(),
                        targetProperty,
                        gas)) {
                    return false;
                }
            }
        }
        for (Map.Entry<String, FrozenNode> targetEntry
                : targetProperties.entrySet()) {
            if (!candidateProperties.containsKey(
                    targetEntry.getKey())
                    && requiresPresence(
                    targetEntry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean meterProperties(
            CandidateView candidate,
            Map<String, FrozenNode> targetProperties,
            MatchGas gas) {
        if (targetProperties == null) {
            return true;
        }
        for (String key : candidate.propertyKeys) {
            FrozenNode targetProperty =
                    targetProperties.get(key);
            if (targetProperty != null) {
                gas.comparisonNode();
                BexValue candidateProperty =
                        candidate.source.get(key);
                if (!matchesAfterAdmission(
                        candidateProperty,
                        targetProperty,
                        gas,
                        CandidatePosition.OBJECT_MEMBER)) {
                    return false;
                }
            }
        }
        for (Map.Entry<String, FrozenNode> targetEntry
                : targetProperties.entrySet()) {
            if (!candidate.hasProperty(
                    targetEntry.getKey())
                    && requiresPresence(
                    targetEntry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean meterScalarComparison(Object candidate,
                                          Object target,
                                          MatchGas gas) {
        if (target == null) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof Number && target instanceof Number) {
            gas.integerLimbs(
                    integerLimbs(unscaled(candidate))
                            + integerLimbs(unscaled(target))
                            + (candidate instanceof BigDecimal
                            || target instanceof BigDecimal ? 1L : 0L));
            return number(candidate).compareTo(number(target)) == 0;
        }
        if (candidate instanceof String && target instanceof String) {
            return gas.compareText(
                    (String) candidate,
                    (String) target) == 0;
        }
        return candidate.equals(target);
    }

    private static boolean isOrdinaryProperty(
            String key) {
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

    private static boolean isForbiddenField(
            String key) {
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

    private enum CandidatePosition {
        ROOT,
        OBJECT_MEMBER,
        LIST_ITEM
    }

    /**
     * Current-node-only view of the transient Blue conversion contract.
     * Descendant values remain as cursors and are not converted here.
     */
    private static final class CandidateView {
        private final BexValue source;
        private final Object scalar;
        private final BexValue items;
        private final List<String> propertyKeys;
        private final boolean materializeCurrent;
        private final boolean valid;

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
            this.materializeCurrent =
                    materializeCurrent;
            this.valid = valid;
        }

        private static CandidateView from(
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
            ArrayList<String> properties =
                    new ArrayList<>();
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
                    if (child == null
                            || child.isUndefined()
                            || child.isNull()
                            || !child.isScalar()) {
                        return invalid(source);
                    }
                } else if ("items".equals(key)) {
                    BexValue child = source.get(key);
                    hasItems = true;
                    if (child == null
                            || child.isUndefined()
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
            /*
             * Explicit scalar types normalize their raw value. Materializing
             * this already-admitted current occurrence is the faithful,
             * bounded way to obtain that scalar without touching any payload
             * descendants (a scalar has none).
             */
            if (hasValue) {
                return materialized(source);
            }
            return new CandidateView(
                    source,
                    null,
                    items,
                    Collections.unmodifiableList(
                            properties),
                    false,
                    true);
        }

        private boolean hasProperty(String key) {
            return isOrdinaryProperty(key)
                    && propertyKeys.contains(key);
        }

        private static CandidateView empty(
                BexValue source) {
            return new CandidateView(
                    source,
                    null,
                    null,
                    Collections.<String>emptyList(),
                    false,
                    true);
        }

        private static CandidateView materialized(
                BexValue source) {
            return new CandidateView(
                    source,
                    null,
                    null,
                    Collections.<String>emptyList(),
                    true,
                    true);
        }

        private static CandidateView invalid(
                BexValue source) {
            return new CandidateView(
                    source,
                    null,
                    null,
                    Collections.<String>emptyList(),
                    false,
                    false);
        }

        private static boolean isEmptyPlaceholder(
                BexValue source,
                List<String> keys) {
            if (keys.size() != 1
                    || !"$empty".equals(keys.get(0))) {
                return false;
            }
            BexValue marker = source.get("$empty");
            return marker != null
                    && marker.isScalar()
                    && Boolean.TRUE.equals(
                            marker.toSimple());
        }
    }

    private boolean requiresPresence(FrozenNode target) {
        Schema schema = target.getSchema();
        if (schema != null && Boolean.TRUE.equals(
                schema.getRequiredValue())) {
            return true;
        }
        return hasValueInNestedStructure(target);
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

    private boolean keyMatchesType(String key, FrozenNode targetKeyType) {
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

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        return new BigDecimal(value.toString());
    }

    private static BigInteger unscaled(Object value) {
        return number(value).unscaledValue();
    }

    private static long integerLimbs(BigInteger value) {
        int bits = value.abs().bitLength();
        return Math.max(1L, (bits + 31L) / 32L);
    }

    private static final class MatchGas {
        private final BexGasMeter gas;
        private final BexSourcePath sourcePath;

        private MatchGas(BexGasMeter gas, BexSourcePath sourcePath) {
            this.gas = java.util.Objects.requireNonNull(gas, "gas");
            this.sourcePath = sourcePath;
        }

        private void comparisonNode() {
            charge(BexGasCounter.COMPARISON_NODE_VISITED, 1L);
        }

        private void textBlocks(long quantity) {
            charge(BexGasCounter.TEXT_BLOCK_EXAMINED, quantity);
        }

        private void integerLimbs(long quantity) {
            charge(BexGasCounter.INTEGER_LIMB_OPERATION, quantity);
        }

        /**
         * Canonical comparison with each pair of 64-code-point blocks
         * admitted before either block is inspected.
         */
        private int compareText(
                String left,
                String right) {
            if (left == right) {
                return 0;
            }
            if (left == null) {
                return -1;
            }
            if (right == null) {
                return 1;
            }
            int leftOffset = 0;
            int rightOffset = 0;
            while (leftOffset < left.length()
                    && rightOffset < right.length()) {
                textBlocks(2L);
                int inBlock = 0;
                while (inBlock
                        < TEXT_BLOCK_CODE_POINTS
                        && leftOffset < left.length()
                        && rightOffset < right.length()) {
                    int leftCodePoint =
                            left.codePointAt(leftOffset);
                    int rightCodePoint =
                            right.codePointAt(rightOffset);
                    leftOffset += Character.charCount(
                            leftCodePoint);
                    rightOffset += Character.charCount(
                            rightCodePoint);
                    if (leftCodePoint
                            != rightCodePoint) {
                        return Integer.compare(
                                leftCodePoint,
                                rightCodePoint);
                    }
                    inBlock++;
                }
            }
            return Integer.compare(
                    left.length() - leftOffset,
                    right.length() - rightOffset);
        }

        private void charge(BexGasCounter counter, long quantity) {
            if (quantity <= 0L) {
                return;
            }
            gas.charge(
                    counter,
                    quantity,
                    sourcePath,
                    sourcePath != null ? sourcePath.operator() : null,
                    counter.canonicalName());
        }
    }
}
