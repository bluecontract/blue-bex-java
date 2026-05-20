package blue.bex.compile;

import blue.bex.runtime.BexRuntime;
import blue.bex.runtime.CompiledFrame;
import blue.bex.runtime.CompiledStatement;
import blue.bex.runtime.Control;
import blue.bex.runtime.CompiledExpression;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lazy-compiled BEX program.
 */
public final class BexCompiledProgram {
    private final CompiledFunction entry;
    private final Map<String, CompiledFunction> functions;
    private final Map<String, BexValue> constants;
    private final int rootFrameSize;
    private final String programBlueId;

    public BexCompiledProgram(CompiledFunction entry,
                              Map<String, CompiledFunction> functions,
                              Map<String, BexValue> constants,
                              int rootFrameSize,
                              String programBlueId) {
        this.entry = entry;
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        this.constants = Collections.unmodifiableMap(new LinkedHashMap<>(constants));
        this.rootFrameSize = rootFrameSize;
        this.programBlueId = programBlueId;
    }

    public BexValue execute(BexRuntime runtime) {
        return entry.invokeRoot(runtime);
    }

    public CompiledFunction entry() { return entry; }
    public Map<String, CompiledFunction> functions() { return functions; }
    public Map<String, BexValue> constants() { return constants; }
    public int rootFrameSize() { return rootFrameSize; }
    public String programBlueId() { return programBlueId; }

    public BexValue constant(String name) {
        BexValue value = constants.get(name);
        return value != null ? value : BexValues.undefined();
    }

    /**
     * Compiled function or root body.
     */
    public static final class CompiledFunction {
        private final String name;
        private final List<String> args;
        private final Map<String, Integer> argSlotByName;
        private final List<CompiledStatement> statements;
        private final CompiledExpression expression;
        private final int frameSize;

        public CompiledFunction(String name,
                                List<String> args,
                                List<CompiledStatement> statements,
                                CompiledExpression expression,
                                int frameSize) {
            this.name = name;
            this.args = args;
            LinkedHashMap<String, Integer> argSlots = new LinkedHashMap<>();
            for (int i = 0; i < args.size(); i++) {
                argSlots.put(args.get(i), i);
            }
            this.argSlotByName = Collections.unmodifiableMap(argSlots);
            this.statements = statements;
            this.expression = expression;
            this.frameSize = frameSize;
        }

        public String name() { return name; }
        public List<String> args() { return args; }
        public int frameSize() { return frameSize; }
        public int argSlot(String name) {
            Integer slot = argSlotByName.get(name);
            return slot != null ? slot : -1;
        }

        public BexValue invokeRoot(BexRuntime runtime) {
            return invokePrepared(runtime, null, new int[0], new BexValue[0]);
        }

        public BexValue invokePrepared(BexRuntime runtime, CompiledFrame parent, int[] slots, BexValue[] values) {
            runtime.metrics().incrementFunctionCalls();
            runtime.gas().charge(runtime.gas().schedule().functionCall);
            CompiledFrame frame = new CompiledFrame(runtime, frameSize, parent);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] >= 0) {
                    BexValue value = values[i];
                    frame.set(slots[i], value != null ? value : BexValues.undefined());
                }
            }
            if (expression != null) {
                return expression.eval(frame);
            }
            for (CompiledStatement statement : statements) {
                if (statement.exec(frame) == Control.RETURN) {
                    return frame.returnValue() != null ? frame.returnValue() : runtime.defaultResultValue();
                }
            }
            return runtime.defaultResultValue();
        }
    }
}
