package blue.bex.output;

import blue.bex.value.BexValue;
import blue.language.model.Node;

import java.util.Objects;

/**
 * One successfully admitted exact Blue output.
 */
public final class BexAdmittedValue {
    private final BexValue value;
    private final BexValue semanticValue;
    private final Node node;
    private final String nodeBlueId;
    private final boolean reconstructed;

    BexAdmittedValue(BexValue value,
                     BexValue semanticValue,
                     Node node,
                     String nodeBlueId,
                     boolean reconstructed) {
        this.value = Objects.requireNonNull(value, "value");
        this.semanticValue = Objects.requireNonNull(
                semanticValue, "semanticValue");
        this.node = Objects.requireNonNull(node, "node");
        this.nodeBlueId = Objects.requireNonNull(nodeBlueId, "nodeBlueId");
        this.reconstructed = reconstructed;
    }

    /**
     * Representation-blind exact semantic value used by later BEX operations.
     */
    public BexValue value() {
        return value;
    }

    /**
     * The exact semantic value returned by the identity boundary.
     *
     * <p>For reconstructed output this is the same exact value as
     * {@link #value()}; it is retained as a named semantic lane for API
     * compatibility. Existing exact input remains unchanged.</p>
     */
    public BexValue semanticValue() {
        return semanticValue;
    }

    /**
     * Blue boundary representation. Existing exact values are pure references.
     */
    public Node node() {
        return node.clone();
    }

    public String nodeBlueId() {
        return nodeBlueId;
    }

    public boolean reconstructed() {
        return reconstructed;
    }
}
