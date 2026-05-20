package blue.bex.runtime;

public interface CompiledStatement {
    Control exec(CompiledFrame frame);
}
