package blue.bex.output;

/**
 * The semantic reason a value crosses the Blue output boundary.
 */
public enum BexOutputKind {
    ROOT_RESULT("root-result"),
    PATCH_VALUE("patch-value"),
    EVENT("event"),
    INTRINSIC_INPUT("intrinsic-input"),
    NODE_IDENTITY("node-identity");

    private final String reason;

    BexOutputKind(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
