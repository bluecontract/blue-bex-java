package blue.bex.value;

/** Closed semantic shape/kind model, independent of exactness provenance. */
public enum BexValueKind {
    UNDEFINED("undefined"),
    NULL("null"),
    BOOLEAN("boolean"),
    INTEGER("integer"),
    DECIMAL("double"),
    TEXT("text"),
    OBJECT("object"),
    LIST("list");

    private final String operatorName;

    BexValueKind(String operatorName) {
        this.operatorName = operatorName;
    }

    /** Existing BEX {@code $kind} spelling; decimal remains {@code double}. */
    public String operatorName() {
        return operatorName;
    }
}
