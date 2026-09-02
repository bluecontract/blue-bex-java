package blue.bex.type;

import blue.bex.BexExecutionEvidenceUnavailableException;
import blue.bex.BexInvalidExecutionEvidenceException;
import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.value.BexBlueNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import blue.language.matching.FrozenTypeMatcher;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import blue.bex.type.BexPatternValidator.CandidatePosition;
import blue.bex.type.BexPatternValidator.CandidateView;

/**
 * BEX boundary adapter for Blue's node/type matcher.
 */
public final class BexTypeMatcher {
    private final BlueLanguage blue;
    private final FrozenTypeMatcher matcher;
    private final BexPatternValidator patternValidator =
            new BexPatternValidator();
    private final BexFrozenTypeMatcher frozenMatcher;

    public BexTypeMatcher(BlueLanguage blue) {
        this.blue = blue != null
                ? blue : BlueLanguage.builder().build();
        this.matcher = FrozenTypeMatcher.withVerifiedReferenceMaterializer(
                reference -> this.blue.processing()
                        .runtimeAccess()
                        .materializeTypeReferenceForMatching(reference));
        this.frozenMatcher = new BexFrozenTypeMatcher(
                matcher, patternValidator);
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
        BexTypeMatchWorkRecorder matchGas =
                new BexTypeMatchWorkRecorder(gas, sourcePath);
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
                    instanceof BexExecutionEvidenceUnavailableException
                    || current
                    instanceof BexInvalidExecutionEvidenceException
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

    /**
     * Walks a candidate cursor lazily. The current occurrence is admitted
     * before any semantic cursor access, and a descendant is not converted or
     * materialized until its own recursive admission succeeds.
     */
    private boolean matchesMetered(
            BexValue candidate,
            FrozenNode pattern,
            BexTypeMatchWorkRecorder gas,
            CandidatePosition position) {
        gas.comparisonNode();
        return matchesAfterAdmission(
                candidate, pattern, gas, position);
    }

    private boolean matchesAfterAdmission(
            BexValue candidate,
            FrozenNode pattern,
            BexTypeMatchWorkRecorder gas,
            CandidatePosition position) {
        if (pattern.isEmptyNode()) {
            return true;
        }
        if (candidate == null
                || candidate.isUndefined()) {
            return !patternValidator.requiresPresence(pattern);
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
                    && frozenMatcher.matchesAfterAdmission(
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

    private boolean meterItemType(
            CandidateView candidate,
            FrozenNode targetItemType,
            BexTypeMatchWorkRecorder gas) {
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

    private boolean meterKeyType(
            CandidateView candidate,
            FrozenNode targetKeyType,
            BexTypeMatchWorkRecorder gas) {
        if (targetKeyType == null) {
            return true;
        }
        for (String key : candidate.propertyKeys) {
            gas.comparisonNode();
            if (!patternValidator.keyMatchesType(key, targetKeyType)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterValueType(
            CandidateView candidate,
            FrozenNode targetValueType,
            BexTypeMatchWorkRecorder gas) {
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

    private boolean meterItems(
            CandidateView candidate,
            List<FrozenNode> targetItems,
            BexTypeMatchWorkRecorder gas) {
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
            } else if (patternValidator.requiresPresence(targetItem)) {
                return false;
            }
        }
        return true;
    }

    private boolean meterProperties(
            CandidateView candidate,
            Map<String, FrozenNode> targetProperties,
            BexTypeMatchWorkRecorder gas) {
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
                    && patternValidator.requiresPresence(
                    targetEntry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean meterScalarComparison(
            Object candidate,
            Object target,
            BexTypeMatchWorkRecorder gas) {
        return gas.scalarMatches(candidate, target);
    }
}
