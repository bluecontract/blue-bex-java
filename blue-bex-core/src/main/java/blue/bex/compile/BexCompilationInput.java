package blue.bex.compile;

import blue.language.snapshot.FrozenNode;

import java.util.Optional;

/**
 * Immutable compiler view of a selected BEX program.
 *
 * <p>Implementations must keep every returned value stable for their entire
 * lifetime. The compiler deliberately owns this narrow boundary so that its
 * implementation does not depend on the public host API package.</p>
 */
public interface BexCompilationInput {
    /** Source shape used by cache identity and root compilation. */
    enum Kind {
        FULL_PROGRAM,
        EXPRESSION
    }

    boolean isExpression();

    FrozenNode programNode();

    Optional<FrozenNode> definitionNode();

    Optional<String> entry();
}
