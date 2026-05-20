package blue.bex.compile;

import blue.bex.api.BexProgramSource;

import java.util.Objects;

/**
 * Cache key for selected compiled BEX programs.
 */
public final class BexCompiledProgramKey {
    private final String programIdentity;
    private final String definitionIdentity;
    private final String entryName;

    public BexCompiledProgramKey(String programIdentity, String definitionIdentity, String entryName) {
        this.programIdentity = Objects.requireNonNull(programIdentity, "programIdentity");
        this.definitionIdentity = definitionIdentity != null ? definitionIdentity : "none";
        this.entryName = entryName != null ? entryName : "";
    }

    public static BexCompiledProgramKey from(BexProgramSource source) {
        return new BexCompiledProgramKey(BexNodeIdentity.stable(source.programNode()),
                source.definitionNode().map(BexNodeIdentity::stable).orElse("none"),
                source.entry().orElse(null));
    }

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
        return Objects.equals(programIdentity, that.programIdentity)
                && Objects.equals(definitionIdentity, that.definitionIdentity)
                && Objects.equals(entryName, that.entryName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(programIdentity, definitionIdentity, entryName);
    }
}
