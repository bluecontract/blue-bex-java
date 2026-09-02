package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lazy-compiled BEX program.
 */
public final class BexCompiledProgram {
    private final CompiledFunction entry;
    private final Map<String, CompiledFunction> functions;
    private final Map<String, BexValue> constants;
    private final int rootFrameSize;
    private final String programBlueId;
    private final Set<String> requiredIntrinsicBlueIds;
    private final BexCompiledProgramKey compilationKey;

    BexCompiledProgram(CompiledFunction entry,
                       Map<String, CompiledFunction> functions,
                       Map<String, BexValue> constants,
                       int rootFrameSize,
                       String programBlueId,
                       Set<String> requiredIntrinsicBlueIds,
                       BexCompiledProgramKey compilationKey) {
        this.entry = entry;
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        this.constants = Collections.unmodifiableMap(new LinkedHashMap<>(constants));
        this.rootFrameSize = rootFrameSize;
        this.programBlueId = programBlueId;
        this.requiredIntrinsicBlueIds = Collections.unmodifiableSet(new LinkedHashSet<>(requiredIntrinsicBlueIds != null
                ? requiredIntrinsicBlueIds
                : Collections.<String>emptySet()));
        this.compilationKey = java.util.Objects.requireNonNull(
                compilationKey, "compilationKey");
    }

    BexValue execute(BexExecutionMachine machine) {
        return entry.invokeRoot(machine);
    }

    CompiledFunction entry() { return entry; }
    Map<String, CompiledFunction> functions() { return functions; }
    Map<String, BexValue> constants() { return constants; }
    int rootFrameSize() { return rootFrameSize; }
    public String programBlueId() { return programBlueId; }
    public Set<String> requiredIntrinsicBlueIds() { return requiredIntrinsicBlueIds; }
    boolean matchesCompilationKey(BexCompiledProgramKey expected) {
        return compilationKey.equals(java.util.Objects.requireNonNull(
                expected, "expected"));
    }
    /** Exact registry, gas, Language, and compiler identity used to compile. */
    public String compilationEnvironmentIdentity() {
        return compilationKey.compileEnvironmentIdentity();
    }

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
    static final class CompiledFunction {
        private final String name;
        private final List<ArgSpec> args;
        private final Map<String, ArgSpec> argByName;
        private final ArgSpec[] argBySlot;
        private final List<CompiledStatement> statements;
        private final CompiledExpression expression;
        private final int frameSize;

        CompiledFunction(String name,
                         List<ArgSpec> args,
                         List<CompiledStatement> statements,
                         CompiledExpression expression,
                         int frameSize) {
            this.name = name;
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
            LinkedHashMap<String, ArgSpec> argSpecs = new LinkedHashMap<>();
            this.argBySlot = new ArgSpec[frameSize];
            for (ArgSpec arg : this.args) {
                argSpecs.put(arg.name(), arg);
                if (arg.slot() >= 0 && arg.slot() < argBySlot.length) {
                    argBySlot[arg.slot()] = arg;
                }
            }
            this.argByName = Collections.unmodifiableMap(argSpecs);
            this.statements = Collections.unmodifiableList(
                    new ArrayList<>(statements));
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

        public BexValue invokeRoot(BexExecutionMachine machine) {
            return invokePrepared(
                    machine, null, new int[0], new BexValue[0]);
        }

        public BexValue invokePrepared(
                BexExecutionMachine machine,
                CompiledFrame parent,
                int[] slots,
                BexValue[] values) {
            machine.gas().charge(BexGasCounter.FUNCTION_CALLED);
            machine.metrics().incrementFunctionCalls();
            if (parent == null) {
                machine.metrics().incrementCompiledExecutions();
            }
            CompiledFrame frame = new CompiledFrame(
                    machine, frameSize, parent);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] >= 0) {
                    BexValue value = values[i];
                    ArgSpec arg = slots[i] < argBySlot.length ? argBySlot[slots[i]] : null;
                    if (arg != null && arg.typed()
                            && !machine.matchesType(
                            value,
                            arg.pattern(),
                            parent != null ? parent.sourcePath() : null)) {
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
                    return frame.returnValue() != null
                            ? frame.returnValue()
                            : machine.defaultResultValue();
                }
            }
            return machine.defaultResultValue();
        }
    }

    static final class ArgSpec {
        private final String name;
        private final int slot;
        private final FrozenNode pattern;
        private final boolean typed;
        private final String sourcePointer;

        ArgSpec(String name, int slot, FrozenNode pattern, String sourcePointer) {
            this.name = name;
            this.slot = slot;
            this.pattern = pattern;
            /*
             * A metadata-only declaration is an explicitly untyped BEX
             * argument. An exact {} is instead an empty Blue pattern: it is
             * checked so undefined is rejected, while every non-undefined
             * value matches it through BexPatternValidator.
             */
            this.typed = pattern != null
                    && hasExplicitTypeConstraint(pattern);
            this.sourcePointer = sourcePointer;
        }

        private static boolean hasExplicitTypeConstraint(
                FrozenNode pattern) {
            return pattern.getType() != null
                    || pattern.getItemType() != null
                    || pattern.getKeyType() != null
                    || pattern.getValueType() != null
                    || pattern.getValue() != null
                    || pattern.getItems() != null
                    || pattern.getProperties() != null
                    || pattern.getContracts() != null
                    || pattern.getReferenceBlueId() != null
                    || pattern.getSchema() != null
                    || pattern.getMergePolicy() != null
                    || pattern.getPreviousBlueId() != null
                    || pattern.getPosition() != null
                    || pattern.getBlue() != null;
        }

        public String name() { return name; }
        public int slot() { return slot; }
        public FrozenNode pattern() { return pattern; }
        public boolean typed() { return typed; }
        public String sourcePointer() { return sourcePointer; }
    }
}
