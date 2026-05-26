package blue.bex.api;

import blue.language.snapshot.FrozenNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Selected BEX program source.
 *
 * <p>Hosts should create a source only after they have selected the BEX program
 * to execute. Inline sources compile the program node directly. Definition
 * sources combine a selected program node with a shared definition/library node
 * and entry function.</p>
 */
public final class BexProgramSource {
    public enum Kind {
        FULL_PROGRAM,
        EXPRESSION
    }

    private final Kind kind;
    private final FrozenNode programNode;
    private final FrozenNode definitionNode;
    private final String entry;

    private BexProgramSource(Kind kind, FrozenNode programNode, FrozenNode definitionNode, String entry) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.programNode = Objects.requireNonNull(programNode, "programNode");
        this.definitionNode = definitionNode;
        this.entry = entry;
    }

    public static BexProgramSource inline(FrozenNode programNode) {
        return new BexProgramSource(Kind.FULL_PROGRAM, programNode, null, null);
    }

    public static BexProgramSource expression(FrozenNode expressionNode) {
        return new BexProgramSource(Kind.EXPRESSION, expressionNode, null, null);
    }

    public static BexProgramSource withDefinition(FrozenNode programNode, FrozenNode definitionNode, String entry) {
        return new BexProgramSource(Kind.FULL_PROGRAM, programNode, definitionNode, entry);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isExpression() {
        return kind == Kind.EXPRESSION;
    }

    public FrozenNode programNode() {
        return programNode;
    }

    public Optional<FrozenNode> definitionNode() {
        return Optional.ofNullable(definitionNode);
    }

    public Optional<String> entry() {
        return Optional.ofNullable(entry);
    }
}
