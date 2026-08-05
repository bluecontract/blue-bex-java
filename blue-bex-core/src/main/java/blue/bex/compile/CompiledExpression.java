package blue.bex.compile;

import blue.bex.value.BexValue;

/** Immutable expression node in a compiled BEX program. */
public interface CompiledExpression {
    BexValue eval(CompiledFrame frame);
}
