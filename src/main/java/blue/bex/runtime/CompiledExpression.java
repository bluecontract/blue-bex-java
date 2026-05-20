package blue.bex.runtime;

import blue.bex.value.BexValue;

public interface CompiledExpression {
    BexValue eval(CompiledFrame frame);
}
