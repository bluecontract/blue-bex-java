package blue.bex.compile;

import blue.bex.api.BexProgramSource;

import java.util.Objects;

/**
 * Cache key for selected compiled BEX programs.
 */
public final class BexCompiledProgramKey {
    public static final String COMPILER_IDENTITY = "blue-bex-java/compiler/2.0";
    public static final String BEX_RUNTIME_REGISTRY_IDENTITY =
            "sha256:23d282ec1c0bb016263922b1b49c369fdd537efdcf23e005eceeb888d7763fe1";

    private final BexProgramSource.Kind kind;
    private final String programIdentity;
    private final String definitionIdentity;
    private final String entryName;
    private final String compileEnvironmentIdentity;

    public BexCompiledProgramKey(String programIdentity, String definitionIdentity, String entryName) {
        this(BexProgramSource.Kind.FULL_PROGRAM, programIdentity, definitionIdentity, entryName,
                COMPILER_IDENTITY);
    }

    public BexCompiledProgramKey(BexProgramSource.Kind kind, String programIdentity, String definitionIdentity, String entryName) {
        this(kind, programIdentity, definitionIdentity, entryName, COMPILER_IDENTITY);
    }

    public BexCompiledProgramKey(BexProgramSource.Kind kind,
                                 String programIdentity,
                                 String definitionIdentity,
                                 String entryName,
                                 String compileEnvironmentIdentity) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.programIdentity = Objects.requireNonNull(programIdentity, "programIdentity");
        this.definitionIdentity = definitionIdentity != null ? definitionIdentity : "none";
        this.entryName = entryName != null ? entryName : "";
        this.compileEnvironmentIdentity = Objects.requireNonNull(
                compileEnvironmentIdentity, "compileEnvironmentIdentity");
    }

    public static BexCompiledProgramKey from(BexProgramSource source) {
        return from(source, COMPILER_IDENTITY);
    }

    public static BexCompiledProgramKey from(BexProgramSource source,
                                             String compileEnvironmentIdentity) {
        return new BexCompiledProgramKey(source.kind(),
                BexNodeIdentity.stable(source.programNode()),
                source.definitionNode().map(BexNodeIdentity::stable).orElse("none"),
                source.entry().orElse(null),
                compileEnvironmentIdentity);
    }

    public BexProgramSource.Kind kind() { return kind; }
    public String programIdentity() { return programIdentity; }
    public String definitionIdentity() { return definitionIdentity; }
    public String entryName() { return entryName; }
    public String compileEnvironmentIdentity() { return compileEnvironmentIdentity; }

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
                && Objects.equals(entryName, that.entryName)
                && Objects.equals(compileEnvironmentIdentity, that.compileEnvironmentIdentity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, programIdentity, definitionIdentity, entryName,
                compileEnvironmentIdentity);
    }
}
