package blue.bex;

import java.util.Objects;

/**
 * Source location of a compiled BEX operator.
 */
public final class BexSourcePath {
    private final String functionName;
    private final String pointer;
    private final String operator;

    public BexSourcePath(String functionName, String pointer, String operator) {
        this.functionName = functionName != null ? functionName : "$root";
        this.pointer = pointer != null && !pointer.isEmpty() ? pointer : "/";
        this.operator = operator;
    }

    public static BexSourcePath of(String functionName, String pointer, String operator) {
        return new BexSourcePath(functionName, pointer, operator);
    }

    public String functionName() {
        return functionName;
    }

    public String pointer() {
        return pointer;
    }

    public String operator() {
        return operator;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("function ").append(functionName).append(' ').append(pointer);
        if (operator != null && !operator.isEmpty()) {
            out.append(' ').append(operator);
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BexSourcePath)) {
            return false;
        }
        BexSourcePath that = (BexSourcePath) other;
        return Objects.equals(functionName, that.functionName)
                && Objects.equals(pointer, that.pointer)
                && Objects.equals(operator, that.operator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(functionName, pointer, operator);
    }
}
