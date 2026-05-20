package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;

final class CallExpr extends Expr {
    private final String function;
    private final String[] argNames;
    private final CompiledExpression[] argExpressions;
    private BexCompiledProgram.CompiledFunction cachedFunction;
    private int[] cachedTargetSlots;

    CallExpr(String function, String[] argNames, CompiledExpression[] argExpressions) {
        this.function = function;
        this.argNames = argNames;
        this.argExpressions = argExpressions;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexCompiledProgram.CompiledFunction compiled = cachedFunction;
        if (compiled == null) {
            compiled = frame.runtime().program().functions().get(function);
            if (compiled == null) throw new BexException("Unknown function: " + function);
            int[] slots = new int[argNames.length];
            for (int i = 0; i < argNames.length; i++) {
                slots[i] = compiled.argSlot(argNames[i]);
            }
            cachedTargetSlots = slots;
            cachedFunction = compiled;
        }
        BexValue[] values = new BexValue[argExpressions.length];
        for (int i = 0; i < argExpressions.length; i++) {
            values[i] = argExpressions[i].eval(frame);
        }
        return compiled.invokePrepared(frame.runtime(), frame, cachedTargetSlots, values);
    }
}
