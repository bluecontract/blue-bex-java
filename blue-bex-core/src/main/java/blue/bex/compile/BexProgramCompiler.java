package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.value.BexValue;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValues;
import blue.bex.result.BexMetricsRecorder;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/** Program selection, functions, constants, and recursion validation. */
final class BexProgramCompiler extends BexStatementCompiler {
    BexProgramCompiler(BexMetricsRecorder metrics, BexIntrinsicCatalog intrinsics) {
        super(metrics, intrinsics);
    }

    final BexCompiledProgram compileProgram(
            BexCompilationInput source,
            String compilationEnvironmentIdentity) {
        requiredIntrinsicBlueIds.clear();
        FrozenNode step = source.programNode();
        FrozenNode definition = source.definitionNode().orElse(null);
        if (source.isExpression()) {
            if (definition != null) {
                throw new BexException("Expression BEX source cannot use a definition");
            }
            if (source.entry().isPresent()) {
                throw new BexException("Expression BEX source cannot use an entry");
            }
            constants = Collections.emptyMap();
            functionSignatures = Collections.emptyMap();
            currentFunction = "$root";
            CompileScope scope = new CompileScope();
            BexCompiledProgram.CompiledFunction root = new BexCompiledProgram.CompiledFunction("$root",
                    Collections.<BexCompiledProgram.ArgSpec>emptyList(),
                    Collections.<CompiledStatement>emptyList(),
                    compileExpr(step, scope, "/expr"),
                    scope.frameSize());
            return new BexCompiledProgram(root, Collections.emptyMap(), constants, scope.frameSize(),
                    BexNodeIdentity.safeBlueId(step), requiredIntrinsicBlueIds,
                    compilationEnvironmentIdentity);
        }
        requireProgramNode(step, "program");
        if (definition != null) {
            requireProgramNode(definition, "definition");
        }

        Map<String, BexValue> loadedConstants = new LinkedHashMap<>();
        loadConstants(loadedConstants, explicitProp(definition, "constants"));
        loadConstants(loadedConstants, explicitProp(step, "constants"));
        constants = Collections.unmodifiableMap(new LinkedHashMap<>(loadedConstants));

        Map<String, FrozenNode> functionNodes = new LinkedHashMap<>();
        loadFunctions(functionNodes, explicitProp(definition, "functions"));
        loadFunctions(functionNodes, explicitProp(step, "functions"));
        rejectRecursion(functionNodes);
        functionSignatures = compileFunctionSignatures(functionNodes);

        Map<String, BexCompiledProgram.CompiledFunction> compiledFunctions = new LinkedHashMap<>();
        for (String name : BexUnicodeOrder.sortedCopy(functionNodes.keySet())) {
            compiledFunctions.put(name, compileFunction(name, functionNodes.get(name), functionSignatures.get(name)));
        }

        boolean hasStepEntry = hasExplicitProperty(step, "entry");
        boolean hasStepExpr = hasExplicitProperty(step, "expr");
        boolean hasStepDo = hasExplicitProperty(step, "do");
        String entryName = source.entry().isPresent()
                ? requiredNonEmptyText(scalarNode(source.entry().get()), "entry")
                : hasStepEntry
                ? requiredNonEmptyText(explicitProp(step, "entry"), "entry")
                : null;
        BexCompiledProgram.CompiledFunction root;
        int rootFrameSize = 0;
        if (entryName != null) {
            BexCompiledProgram.CompiledFunction entry = compiledFunctions.get(entryName);
            if (entry == null) {
                throw new BexException("Unknown entry function: " + entryName);
            }
            if (!entry.args().isEmpty()) {
                throw new BexException("Entry function " + entryName + " declares arguments but entry invocation provides none");
            }
            root = new BexCompiledProgram.CompiledFunction("$root", Collections.<BexCompiledProgram.ArgSpec>emptyList(),
                    Collections.singletonList(sourceStatement("$root", "/entry", "$return",
                            new ReturnStatement(sourceExpr("$root", "/entry/$call", "$call",
                            new CallExpr(entryName, new int[0], new CompiledExpression[0]))))),
                    null, 0);
            validateUnselectedRoot(step, hasStepExpr, hasStepDo);
        } else if (hasStepExpr) {
            currentFunction = "$root";
            CompileScope scope = new CompileScope();
            CompiledExpression expression = compileExpr(explicitProp(step, "expr"), scope, "/expr");
            rootFrameSize = scope.frameSize();
            root = new BexCompiledProgram.CompiledFunction("$root", Collections.<BexCompiledProgram.ArgSpec>emptyList(),
                    Collections.<CompiledStatement>emptyList(), expression, rootFrameSize);
            if (hasStepDo) {
                validateRootStatements(explicitProp(step, "do"));
            }
        } else {
            CompileScope scope = new CompileScope();
            currentFunction = "$root";
            List<CompiledStatement> statements = hasStepDo
                    ? compileStatements(explicitProp(step, "do"), scope, "/do")
                    : Collections.<CompiledStatement>emptyList();
            rootFrameSize = scope.frameSize();
            root = new BexCompiledProgram.CompiledFunction("$root", Collections.<BexCompiledProgram.ArgSpec>emptyList(), statements, null, rootFrameSize);
        }

        return new BexCompiledProgram(root, compiledFunctions, constants, rootFrameSize,
                BexNodeIdentity.safeBlueId(step), requiredIntrinsicBlueIds,
                compilationEnvironmentIdentity);
    }

    BexCompiledProgram.CompiledFunction compileFunction(String name, FrozenNode functionNode, FunctionSignature signature) {
        String previousFunction = currentFunction;
        currentFunction = name;
        try {
            CompileScope scope = functionScope(name, signature);
            String basePointer = "/functions/" + escape(name);
            boolean hasExpression = hasExplicitProperty(functionNode, "expr");
            boolean hasStatements = hasExplicitProperty(functionNode, "do");
            CompiledExpression expression = hasExpression
                    ? compileExpr(explicitProp(functionNode, "expr"), scope, basePointer + "/expr")
                    : null;
            List<CompiledStatement> statements = !hasExpression && hasStatements
                    ? compileStatements(explicitProp(functionNode, "do"), scope, basePointer + "/do")
                    : Collections.<CompiledStatement>emptyList();
            if (hasExpression && hasStatements) {
                CompileScope validationScope = functionScope(name, signature);
                compileStatements(explicitProp(functionNode, "do"), validationScope, basePointer + "/do");
            }
            return new BexCompiledProgram.CompiledFunction(name, signature.args(),
                    statements, expression, scope.frameSize());
        } finally {
            currentFunction = previousFunction;
        }
    }

    CompileScope functionScope(String name, FunctionSignature signature) {
        CompileScope scope = new CompileScope();
        for (BexCompiledProgram.ArgSpec arg : signature.args()) {
            int slot = scope.declareOrGetSlot(arg.name());
            if (slot != arg.slot()) {
                throw new BexException("Internal function arg slot mismatch for " + name + "." + arg.name());
            }
        }
        return scope;
    }

    void validateUnselectedRoot(FrozenNode step, boolean hasExpression, boolean hasStatements) {
        if (hasExpression) {
            currentFunction = "$root";
            compileExpr(explicitProp(step, "expr"), new CompileScope(), "/expr");
        }
        if (hasStatements) {
            validateRootStatements(explicitProp(step, "do"));
        }
    }

    void validateRootStatements(FrozenNode statements) {
        currentFunction = "$root";
        compileStatements(statements, new CompileScope(), "/do");
    }

    Map<String, FunctionSignature> compileFunctionSignatures(Map<String, FrozenNode> functionNodes) {
        Map<String, FunctionSignature> signatures = new LinkedHashMap<>();
        for (String name : BexUnicodeOrder.sortedCopy(functionNodes.keySet())) {
            signatures.put(name, compileFunctionSignature(name, functionNodes.get(name)));
        }
        return signatures;
    }

    FunctionSignature compileFunctionSignature(String name, FrozenNode functionNode) {
        requireObjectBody(functionNode, "Function " + name, "args", "expr", "do");
        List<String> names = new ArrayList<>();
        FrozenNode argsNode = prop(functionNode, "args");
        if (argsNode != null) {
            validatePlainObjectContainer(argsNode, "Function " + name + " args");
            if (argsNode.getProperties() == null) {
                if (!argsNode.isEmptyNode()) {
                    throw new BexException("Function " + name + " args must be an object");
                }
            } else {
                names.addAll(argsNode.getProperties().keySet());
                Collections.sort(names, BexUnicodeOrder.CODE_POINT_COMPARATOR);
            }
        }
        List<BexCompiledProgram.ArgSpec> args = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String arg = names.get(i);
            FrozenNode pattern = argsNode.getProperties().get(arg);
            String sourcePointer = "/functions/" + escape(name) + "/args/" + escape(arg);
            rejectBexAnywhereInStaticPattern(pattern, sourcePointer);
            args.add(new BexCompiledProgram.ArgSpec(arg, i,
                    pattern,
                    sourcePointer));
        }
        return new FunctionSignature(Collections.unmodifiableList(args));
    }

    void loadConstants(Map<String, BexValue> constants, FrozenNode node) {
        validatePlainObjectContainer(node, "constants");
        if (node == null || node.getProperties() == null) {
            return;
        }
        for (String name : BexUnicodeOrder.sortedCopy(node.getProperties().keySet())) {
            FrozenNode value = node.getProperties().get(name);
            rejectBexInStaticBlueDefinitionFields(value, "/constants/" + escape(name));
            constants.put(name, BexValues.frozen(value));
        }
    }

    void loadFunctions(Map<String, FrozenNode> functions, FrozenNode node) {
        validatePlainObjectContainer(node, "functions");
        if (node == null || node.getProperties() == null) {
            return;
        }
        for (String name : BexUnicodeOrder.sortedCopy(node.getProperties().keySet())) {
            functions.put(name, node.getProperties().get(name));
        }
    }

    void rejectRecursion(Map<String, FrozenNode> functions) {
        Map<String, List<CallSite>> calls = new LinkedHashMap<>();
        for (String name : BexUnicodeOrder.sortedCopy(functions.keySet())) {
            List<CallSite> targets = new ArrayList<>();
            FrozenNode function = functions.get(name);
            String base = "/functions/" + escape(name);
            if (hasExplicitProperty(function, "expr")) {
                collectCalls(explicitProp(function, "expr"), name, base + "/expr", targets);
            }
            if (hasExplicitProperty(function, "do")) {
                collectCalls(explicitProp(function, "do"), name, base + "/do", targets);
            }
            calls.put(name, targets);
        }
        Map<String, VisitState> states = new LinkedHashMap<>();
        for (String name : calls.keySet()) {
            states.put(name, VisitState.UNVISITED);
        }
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (String name : calls.keySet()) {
            if (states.get(name) == VisitState.UNVISITED) {
                detectCycle(name, calls, states, stack);
            }
        }
    }

    void detectCycle(String current,
                             Map<String, List<CallSite>> calls,
                             Map<String, VisitState> states,
                             ArrayDeque<String> stack) {
        states.put(current, VisitState.VISITING);
        stack.addLast(current);
        List<CallSite> edges = new ArrayList<>(calls.get(current));
        Collections.sort(edges, (left, right) -> {
            int byTarget = BexUnicodeOrder.compareCodePoints(left.target, right.target);
            return byTarget != 0
                    ? byTarget
                    : BexUnicodeOrder.compareCodePoints(left.sourcePath.pointer(), right.sourcePath.pointer());
        });
        for (CallSite edge : edges) {
            if (!calls.containsKey(edge.target)) {
                continue;
            }
            VisitState state = states.get(edge.target);
            if (state == VisitState.VISITING) {
                throw BexException.at(edge.sourcePath,
                        "Compile error reason=recursive-call-graph: recursive BEX function cycle "
                                + cycleText(stack, edge.target));
            }
            if (state == VisitState.UNVISITED) {
                detectCycle(edge.target, calls, states, stack);
            }
        }
        stack.removeLast();
        states.put(current, VisitState.VISITED);
    }

    String cycleText(ArrayDeque<String> stack, String target) {
        List<String> cycle = new ArrayList<>();
        boolean append = false;
        for (String name : stack) {
            if (name.equals(target)) {
                append = true;
            }
            if (append) {
                cycle.add(name);
            }
        }
        cycle.add(target);
        return String.join(" -> ", cycle);
    }

    void collectCalls(FrozenNode node, String functionName, String pointer, List<CallSite> calls) {
        if (node == null) {
            return;
        }
        if (isOperator(node, "$literal")) {
            return;
        }
        if (isOperator(node, "$is")) {
            collectCalls(prop(onlyValue(node), "node"), functionName,
                    pointer + "/$is/node", calls);
            return;
        }
        if (isOperator(node, "$fail")) {
            FrozenNode body = onlyValue(node);
            boolean messageWrapper = body != null
                    && body.getProperties() != null
                    && hasExplicitProperty(body, "message");
            collectCalls(messageWrapper ? explicitProp(body, "message") : body,
                    functionName,
                    messageWrapper ? pointer + "/$fail/message" : pointer + "/$fail",
                    calls);
            return;
        }
        if (isOperator(node, "$call")) {
            FrozenNode body = onlyValue(node);
            String function = text(prop(body, "function"));
            if (function != null) {
                calls.add(new CallSite(function,
                        BexSourcePath.of(functionName, pointer + "/$call", "$call")));
            }
        }
        if (node.getProperties() != null) {
            for (String key : BexUnicodeOrder.sortedCopy(node.getProperties().keySet())) {
                collectCalls(node.getProperties().get(key), functionName,
                        pointer + "/" + escape(key), calls);
            }
        }
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                collectCalls(node.getItems().get(i), functionName,
                        pointer + "/" + i, calls);
            }
        }
    }


    enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }

    static final class CallSite {
        private final String target;
        private final BexSourcePath sourcePath;

        private CallSite(String target, BexSourcePath sourcePath) {
            this.target = target;
            this.sourcePath = sourcePath;
        }
    }
}
