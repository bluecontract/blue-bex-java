package blue.bex.type;

import blue.bex.value.BexNodeWriter;
import blue.bex.value.BexValue;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

/**
 * BEX boundary adapter for Blue's node/type matcher.
 */
public final class BexBlueTypeMatcher {
    private final Blue blue;

    public BexBlueTypeMatcher(Blue blue) {
        this.blue = blue != null ? blue : new Blue();
    }

    public boolean matches(BexValue value, FrozenNode pattern) {
        if (value == null || value.isUndefined()) {
            return false;
        }
        if (pattern == null || pattern.isEmptyNode()) {
            return true;
        }
        try {
            Node valueNode = BexNodeWriter.toNode(value);
            Node patternNode = pattern.toNode();
            return blue.nodeMatchesType(valueNode, patternNode);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
