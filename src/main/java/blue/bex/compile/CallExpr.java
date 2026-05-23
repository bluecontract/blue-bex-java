package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;

final class CallExpr extends Expr {
    private final String function;
    private final int[] targetSlots;
    private final CompiledExpression[] argExpressions;

    CallExpr(String function, int[] targetSlots, CompiledExpression[] argExpressions) {
        this.function = function;
        this.targetSlots = targetSlots;
        this.argExpressions = argExpressions;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexCompiledProgram.CompiledFunction compiled = frame.runtime().program().functions().get(function);
        if (compiled == null) {
            throw new BexException("Unknown function: " + function);
        }
        BexValue[] values = new BexValue[argExpressions.length];
        for (int i = 0; i < argExpressions.length; i++) {
            values[i] = argExpressions[i].eval(frame);
        }
        return compiled.invokePrepared(frame.runtime(), frame, targetSlots, values);
    }
}
