package blue.bex.type;

import blue.language.matching.FrozenTypeMatcher;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Recursive matching of already-frozen candidate evidence. */
final class BexFrozenTypeMatcher {
    private final FrozenTypeMatcher matcher;
    private final BexPatternValidator patternValidator;

    BexFrozenTypeMatcher(
            FrozenTypeMatcher matcher,
            BexPatternValidator patternValidator) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.patternValidator = Objects.requireNonNull(
                patternValidator, "patternValidator");
    }

    boolean matches(
            FrozenNode candidate,
            FrozenNode pattern,
            BexTypeMatchWorkRecorder work) {
        work.comparisonNode();
        return matchesAfterAdmission(candidate, pattern, work);
    }

    boolean matchesAfterAdmission(
            FrozenNode candidate,
            FrozenNode pattern,
            BexTypeMatchWorkRecorder work) {
        if (BexPatternValidator.isUnconstrainedPattern(pattern)) {
            return true;
        }
        if (pattern.isReferenceOnly()) {
            return matcher.matchesType(candidate, pattern);
        }
        if (!work.scalarMatches(candidate.getValue(), pattern.getValue())) {
            return false;
        }
        if (!matchesItemType(candidate, pattern.getItemType(), work)
                || !matchesKeyType(candidate, pattern.getKeyType(), work)
                || !matchesValueType(candidate, pattern.getValueType(), work)
                || !matchesItems(candidate, pattern.getItems(), work)
                || !matchesProperties(
                candidate, pattern.getProperties(), work)) {
            return false;
        }
        return matcher.matchesType(candidate, pattern);
    }

    private boolean matchesItemType(
            FrozenNode candidate,
            FrozenNode targetItemType,
            BexTypeMatchWorkRecorder work) {
        if (targetItemType == null || candidate.getItems() == null) {
            return true;
        }
        for (FrozenNode item : candidate.getItems()) {
            if (!matches(item, targetItemType, work)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesKeyType(
            FrozenNode candidate,
            FrozenNode targetKeyType,
            BexTypeMatchWorkRecorder work) {
        if (targetKeyType == null || candidate.getProperties() == null) {
            return true;
        }
        for (String key : candidate.getProperties().keySet()) {
            work.comparisonNode();
            if (!patternValidator.keyMatchesType(key, targetKeyType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesValueType(
            FrozenNode candidate,
            FrozenNode targetValueType,
            BexTypeMatchWorkRecorder work) {
        if (targetValueType == null || candidate.getProperties() == null) {
            return true;
        }
        for (String key : candidate.getProperties().keySet()) {
            if (!matches(
                    candidate.getProperties().get(key),
                    targetValueType,
                    work)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesItems(
            FrozenNode candidate,
            List<FrozenNode> targetItems,
            BexTypeMatchWorkRecorder work) {
        if (targetItems == null) {
            return true;
        }
        List<FrozenNode> candidateItems = candidate.getItems() != null
                ? candidate.getItems()
                : Collections.<FrozenNode>emptyList();
        for (int index = 0; index < targetItems.size(); index++) {
            FrozenNode targetItem = targetItems.get(index);
            if (index < candidateItems.size()) {
                if (!matches(candidateItems.get(index), targetItem, work)) {
                    return false;
                }
            } else if (patternValidator.requiresPresence(targetItem)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesProperties(
            FrozenNode candidate,
            Map<String, FrozenNode> targetProperties,
            BexTypeMatchWorkRecorder work) {
        if (targetProperties == null) {
            return true;
        }
        Map<String, FrozenNode> candidateProperties =
                candidate.getProperties() != null
                        ? candidate.getProperties()
                        : Collections.<String, FrozenNode>emptyMap();
        for (Map.Entry<String, FrozenNode> candidateEntry
                : candidateProperties.entrySet()) {
            FrozenNode targetProperty =
                    targetProperties.get(candidateEntry.getKey());
            if (targetProperty != null
                    && !matches(
                    candidateEntry.getValue(), targetProperty, work)) {
                return false;
            }
        }
        for (Map.Entry<String, FrozenNode> targetEntry
                : targetProperties.entrySet()) {
            if (!candidateProperties.containsKey(targetEntry.getKey())
                    && patternValidator.requiresPresence(
                    targetEntry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
