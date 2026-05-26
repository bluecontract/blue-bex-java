package blue.bex.compile;

import blue.bex.api.BexProgramSource;

import java.util.Objects;

/**
 * Cache key for selected compiled BEX programs.
 */
public final class BexCompiledProgramKey {
    private final BexProgramSource.Kind kind;
    private final String programIdentity;
    private final String definitionIdentity;
    private final String entryName;

    public BexCompiledProgramKey(String programIdentity, String definitionIdentity, String entryName) {
        this(BexProgramSource.Kind.FULL_PROGRAM, programIdentity, definitionIdentity, entryName);
    }

    public BexCompiledProgramKey(BexProgramSource.Kind kind, String programIdentity, String definitionIdentity, String entryName) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.programIdentity = Objects.requireNonNull(programIdentity, "programIdentity");
        this.definitionIdentity = definitionIdentity != null ? definitionIdentity : "none";
        this.entryName = entryName != null ? entryName : "";
    }

    public static BexCompiledProgramKey from(BexProgramSource source) {
        return new BexCompiledProgramKey(source.kind(),
                BexNodeIdentity.stable(source.programNode()),
                source.definitionNode().map(BexNodeIdentity::stable).orElse("none"),
                source.entry().orElse(null));
    }

    public BexProgramSource.Kind kind() { return kind; }
    public String programIdentity() { return programIdentity; }
    public String definitionIdentity() { return definitionIdentity; }
    public String entryName() { return entryName; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BexCompiledProgramKey)) {
            return false;
        }
        BexCompiledProgramKey that = (BexCompiledProgramKey) other;
        return kind == that.kind
                && Objects.equals(programIdentity, that.programIdentity)
                && Objects.equals(definitionIdentity, that.definitionIdentity)
                && Objects.equals(entryName, that.entryName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, programIdentity, definitionIdentity, entryName);
    }
}
