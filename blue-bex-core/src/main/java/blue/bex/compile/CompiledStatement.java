package blue.bex.compile;

/** Immutable statement node in a compiled BEX program. */
public interface CompiledStatement {
    Control exec(CompiledFrame frame);
}
