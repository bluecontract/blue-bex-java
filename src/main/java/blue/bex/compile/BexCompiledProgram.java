package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.runtime.BexRuntime;
import blue.bex.runtime.CompiledFrame;
import blue.bex.runtime.CompiledStatement;
import blue.bex.runtime.Control;
import blue.bex.runtime.CompiledExpression;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
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
        if (value == null) {
            throw new BexException("Unknown constant: " + name);
        }
        return value;
    }

    /**
     * Compiled function or root body.
     */
    public static final class CompiledFunction {
        private final String name;
        private final List<ArgSpec> args;
        private final Map<String, ArgSpec> argByName;
        private final ArgSpec[] argBySlot;
        private final List<CompiledStatement> statements;
        private final CompiledExpression expression;
        private final int frameSize;

        public CompiledFunction(String name,
                                List<ArgSpec> args,
                                List<CompiledStatement> statements,
                                CompiledExpression expression,
                                int frameSize) {
            this.name = name;
            this.args = Collections.unmodifiableList(args);
            LinkedHashMap<String, ArgSpec> argSpecs = new LinkedHashMap<>();
            this.argBySlot = new ArgSpec[frameSize];
            for (ArgSpec arg : args) {
                argSpecs.put(arg.name(), arg);
                if (arg.slot() >= 0 && arg.slot() < argBySlot.length) {
                    argBySlot[arg.slot()] = arg;
                }
            }
            this.argByName = Collections.unmodifiableMap(argSpecs);
            this.statements = statements;
            this.expression = expression;
            this.frameSize = frameSize;
        }

        public String name() { return name; }
        public Collection<ArgSpec> args() { return args; }
        public int frameSize() { return frameSize; }
        public boolean hasArg(String name) { return argByName.containsKey(name); }
        public ArgSpec arg(String name) { return argByName.get(name); }
        public int argSlot(String name) {
            ArgSpec arg = argByName.get(name);
            return arg != null ? arg.slot() : -1;
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
                    ArgSpec arg = slots[i] < argBySlot.length ? argBySlot[slots[i]] : null;
                    if (arg != null && arg.typed()
                            && !runtime.typeMatcher().matches(value, arg.pattern())) {
                        throw new BexException("Function " + name
                                + " argument " + arg.name()
                                + " does not match declared Blue pattern at "
                                + arg.sourcePointer());
                    }
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

    public static final class ArgSpec {
        private final String name;
        private final int slot;
        private final FrozenNode pattern;
        private final boolean typed;
        private final String sourcePointer;

        public ArgSpec(String name, int slot, FrozenNode pattern, String sourcePointer) {
            this.name = name;
            this.slot = slot;
            this.pattern = pattern;
            this.typed = pattern != null && !pattern.isEmptyNode();
            this.sourcePointer = sourcePointer;
        }

        public String name() { return name; }
        public int slot() { return slot; }
        public FrozenNode pattern() { return pattern; }
        public boolean typed() { return typed; }
        public String sourcePointer() { return sourcePointer; }
    }
}
