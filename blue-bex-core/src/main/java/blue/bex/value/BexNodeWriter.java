package blue.bex.value;

import blue.language.model.Node;

/**
 * Boundary writer from BEX values to mutable Blue nodes.
 */
public final class BexNodeWriter {
    private BexNodeWriter() {
    }

    public static Node toNode(BexValue value) {
        return BexBlueNodeWriter.toNode(value);
    }
}
