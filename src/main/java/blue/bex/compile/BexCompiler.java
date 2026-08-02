package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.runtime.CompileScope;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledStatement;
import blue.bex.value.BexValue;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValues;
import blue.bex.result.BexMetrics;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler from frozen BEX Blue data to specialized runtime objects.
 */
public final class BexCompiler {
    private static final String TEXT_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Text");
    private static final String INTEGER_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Integer");
    private static final String DOUBLE_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Double");
    private static final String BOOLEAN_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Boolean");
    private static final Set<String> RESERVED_BLUE_KEYS = reservedBlueKeys();

    private final BexContainsCache containsCache = new BexContainsCache();
    private final BexMetrics metrics;
    private final BexIntrinsicRegistry intrinsics;
    private final Set<String> requiredIntrinsicBlueIds = new LinkedHashSet<>();
    private Map<String, FunctionSignature> functionSignatures = Collections.emptyMap();
    private Map<String, BexValue> constants = Collections.emptyMap();
    private String currentFunction = "$root";

    public BexCompiler(BexMetrics metrics) {
        this(metrics, BexIntrinsicRegistry.empty());
    }

    public BexCompiler(BexMetrics metrics, BexIntrinsicRegistry intrinsics) {
        this.metrics = metrics;
        this.intrinsics = intrinsics != null ? intrinsics : BexIntrinsicRegistry.empty();
    }

    public BexCompiledProgram compile(blue.bex.api.BexProgramSource source) {
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
                    BexNodeIdentity.safeBlueId(step), requiredIntrinsicBlueIds);
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
                BexNodeIdentity.safeBlueId(step), requiredIntrinsicBlueIds);
    }

    private BexCompiledProgram.CompiledFunction compileFunction(String name, FrozenNode functionNode, FunctionSignature signature) {
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

    private CompileScope functionScope(String name, FunctionSignature signature) {
        CompileScope scope = new CompileScope();
        for (BexCompiledProgram.ArgSpec arg : signature.args()) {
            int slot = scope.declareOrGetSlot(arg.name());
            if (slot != arg.slot()) {
                throw new BexException("Internal function arg slot mismatch for " + name + "." + arg.name());
            }
        }
        return scope;
    }

    private void validateUnselectedRoot(FrozenNode step, boolean hasExpression, boolean hasStatements) {
        if (hasExpression) {
            currentFunction = "$root";
            compileExpr(explicitProp(step, "expr"), new CompileScope(), "/expr");
        }
        if (hasStatements) {
            validateRootStatements(explicitProp(step, "do"));
        }
    }

    private void validateRootStatements(FrozenNode statements) {
        currentFunction = "$root";
        compileStatements(statements, new CompileScope(), "/do");
    }

    private Map<String, FunctionSignature> compileFunctionSignatures(Map<String, FrozenNode> functionNodes) {
        Map<String, FunctionSignature> signatures = new LinkedHashMap<>();
        for (String name : BexUnicodeOrder.sortedCopy(functionNodes.keySet())) {
            signatures.put(name, compileFunctionSignature(name, functionNodes.get(name)));
        }
        return signatures;
    }

    private FunctionSignature compileFunctionSignature(String name, FrozenNode functionNode) {
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

    private void loadConstants(Map<String, BexValue> constants, FrozenNode node) {
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

    private void loadFunctions(Map<String, FrozenNode> functions, FrozenNode node) {
        validatePlainObjectContainer(node, "functions");
        if (node == null || node.getProperties() == null) {
            return;
        }
        for (String name : BexUnicodeOrder.sortedCopy(node.getProperties().keySet())) {
            functions.put(name, node.getProperties().get(name));
        }
    }

    private void rejectRecursion(Map<String, FrozenNode> functions) {
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

    private void detectCycle(String current,
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

    private String cycleText(ArrayDeque<String> stack, String target) {
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

    private void collectCalls(FrozenNode node, String functionName, String pointer, List<CallSite> calls) {
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

    private List<CompiledStatement> compileStatements(FrozenNode node, CompileScope scope, String pointer) {
        if (node == null) {
            return Collections.emptyList();
        }
        if (node.getItems() == null) {
            throw BexException.at(BexSourcePath.of(currentFunction, pointer, null),
                    "Compile error: statement body must be a list");
        }
        List<CompiledStatement> statements = new ArrayList<>();
        for (int i = 0; i < node.getItems().size(); i++) {
            FrozenNode item = node.getItems().get(i);
            statements.add(compileStatement(item, scope, pointer + "/" + i));
        }
        return statements;
    }

    private CompiledStatement compileStatement(FrozenNode statement, CompileScope scope, String pointer) {
        if (isEmptyStatement(statement, pointer)) {
            throw BexException.at(BexSourcePath.of(currentFunction, pointer, null),
                    "Compile error: null or empty statement item");
        }
        if (statement.getProperties() == null) {
            throw BexException.at(BexSourcePath.of(currentFunction, pointer, null),
                    "Compile error: statement must be an operator object");
        }
        int count = 0;
        String op = null;
        FrozenNode body = null;
        for (Map.Entry<String, FrozenNode> entry : statement.getProperties().entrySet()) {
            if (entry.getKey().startsWith("$")) {
                count++;
                op = entry.getKey();
                body = entry.getValue();
            }
        }
        if (count != 1
                || statement.getProperties().size() != 1
                || authoredFieldNames(statement).size() != 1) {
            throw BexException.at(BexSourcePath.of(currentFunction, pointer, null),
                    "Compile error: statement must have exactly one $ operator");
        }
        String bodyPointer = pointer + "/" + escape(op);
        BexSourcePath sourcePath = BexSourcePath.of(currentFunction, bodyPointer, op);
        try {
            if ("$empty".equals(op)) {
                throw new BexException("Compile error: $empty placeholder is not a BEX statement");
            }
            validateStatementBody(op, body);
            CompiledStatement compiled;
            if ("$let".equals(op)) {
            if (prop(body, "vars") != null) {
                compiled = compileMultiLet(body, scope, bodyPointer);
            } else {
                String name = requiredText(prop(body, "name"), "$let.name");
                int slot = scope.declareOrGetSlot(name);
                compiled = new LetStatement(slot, compileExpr(required(prop(body, "expr"), "$let.expr"), scope, bodyPointer + "/expr"));
            }
        } else if ("$set".equals(op)) {
            String name = requiredText(prop(body, "name"), "$set.name");
            int slot = scope.resolveSlot(name);
            compiled = new SetStatement(slot, compileExpr(required(prop(body, "expr"), "$set.expr"), scope, bodyPointer + "/expr"));
        } else if ("$if".equals(op)) {
            compiled = new IfStatement(compileExpr(required(prop(body, "cond"), "$if.cond"), scope, bodyPointer + "/cond"),
                    compileStatements(prop(body, "then"), scope, bodyPointer + "/then"),
                    compileStatements(prop(body, "else"), scope, bodyPointer + "/else"));
        } else if ("$forEach".equals(op)) {
            String itemName = requiredText(prop(body, "item"), "$forEach.item");
            String keyName = prop(body, "key") != null ? requiredText(prop(body, "key"), "$forEach.key") : null;
            String indexName = prop(body, "index") != null ? requiredText(prop(body, "index"), "$forEach.index") : null;
            validateDistinctForEachBindings(itemName, keyName, indexName);
            CompiledExpression input = compileExpr(required(prop(body, "in"), "$forEach.in"),
                    scope, bodyPointer + "/in");
            int slot = scope.declareOrGetSlot(itemName);
            int keySlot = keyName != null ? scope.declareOrGetSlot(keyName) : -1;
            int indexSlot = indexName != null ? scope.declareOrGetSlot(indexName) : -1;
            compiled = new ForEachStatement(input,
                    slot, keySlot, indexSlot, compileStatements(prop(body, "do"), scope, bodyPointer + "/do"));
        } else if ("$appendChange".equals(op)) {
            compiled = new AppendChangeStatement(textOrExpr(required(prop(body, "op"), "$appendChange.op"), scope, null, bodyPointer + "/op"),
                    pointerOperand(required(prop(body, "path"), "$appendChange.path"), scope, bodyPointer + "/path"),
                    prop(body, "val") != null ? compileExpr(prop(body, "val"), scope, bodyPointer + "/val") : null);
        } else if ("$appendChanges".equals(op)) {
            compiled = new AppendChangesStatement(compileExpr(body, scope, bodyPointer));
        } else if ("$appendEvent".equals(op)) {
            compiled = new AppendEventStatement(compileExpr(body, scope, bodyPointer));
        } else if ("$appendEvents".equals(op)) {
            compiled = new AppendEventsStatement(compileExpr(body, scope, bodyPointer));
        } else if ("$call".equals(op)) {
            compiled = new CallStatement(compileCall(body, scope, bodyPointer));
        } else if ("$return".equals(op)) {
            if (body == null || body.isEmptyNode() || (body.getProperties() != null && body.getProperties().isEmpty())) {
                compiled = new ReturnStatement(null);
            } else {
                compiled = new ReturnStatement(compileExpr(body, scope, bodyPointer));
            }
        } else if ("$returnIf".equals(op)) {
            if (hasExplicitProperty(body, "value")) {
                throw new BexException("$returnIf uses expr for its return payload; value is not supported");
            }
            compiled = new ReturnIfStatement(
                    compileExpr(required(prop(body, "cond"), "$returnIf.cond"), scope, bodyPointer + "/cond"),
                    hasExplicitProperty(body, "expr")
                            ? compileExpr(explicitProp(body, "expr"), scope, bodyPointer + "/expr")
                            : null);
        } else if ("$fail".equals(op)) {
            compiled = new FailStatement(failMessageExpr(body, scope, bodyPointer));
        } else if ("$failIf".equals(op)) {
            compiled = new FailIfStatement(
                    compileExpr(required(prop(body, "cond"), "$failIf.cond"), scope, bodyPointer + "/cond"),
                    compileExpr(required(prop(body, "message"), "$failIf.message"), scope, bodyPointer + "/message"));
        } else {
            throw new BexException("Unknown statement operator: " + op);
        }
            return new SourceStatement(sourcePath, compiled);
        } catch (BexException ex) {
            throw ex.withSourcePath(sourcePath);
        }
    }

    private CompiledStatement compileMultiLet(FrozenNode body, CompileScope scope, String pointer) {
        FrozenNode varsNode = required(prop(body, "vars"), "$let.vars");
        validatePlainObjectContainer(varsNode, "$let.vars");
        if (varsNode.getProperties() == null) {
            if (varsNode.isEmptyNode()) {
                return new MultiLetStatement(new int[0], new CompiledExpression[0], false);
            }
            throw new BexException("$let.vars must be an object");
        }
        List<String> names = new ArrayList<>(varsNode.getProperties().keySet());
        boolean sequential = prop(body, "order") != null;
        if (sequential) {
            names = orderedLetNames(prop(body, "order"), varsNode, pointer + "/order");
        } else {
            Collections.sort(names, BexUnicodeOrder.CODE_POINT_COMPARATOR);
        }

        int[] slots = new int[names.size()];
        List<CompiledExpression> expressions = new ArrayList<>();
        if (sequential) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                expressions.add(compileExpr(varsNode.getProperties().get(name), scope,
                        pointer + "/vars/" + escape(name)));
                slots[i] = scope.declareOrGetSlot(name);
            }
        } else {
            for (int i = 0; i < names.size(); i++) {
                slots[i] = scope.declareOrGetSlot(names.get(i));
            }
            for (String name : names) {
                expressions.add(compileExpr(varsNode.getProperties().get(name), scope,
                        pointer + "/vars/" + escape(name)));
            }
        }
        return new MultiLetStatement(slots, expressions.toArray(new CompiledExpression[0]), sequential);
    }

    private List<String> orderedLetNames(FrozenNode orderNode, FrozenNode varsNode, String pointer) {
        if (orderNode == null || orderNode.getItems() == null) {
            throw new BexException("$let.order must be a list");
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < orderNode.getItems().size(); i++) {
            String name = requiredText(orderNode.getItems().get(i), "$let.order item");
            if (!seen.add(name)) {
                throw new BexException("$let.order contains duplicate variable: " + name);
            }
            if (varsNode.getProperties() == null || !varsNode.getProperties().containsKey(name)) {
                throw new BexException("$let.order references unknown variable: " + name);
            }
            names.add(name);
        }
        if (varsNode.getProperties() != null && seen.size() != varsNode.getProperties().size()) {
            for (String name : varsNode.getProperties().keySet()) {
                if (!seen.contains(name)) {
                    throw new BexException("$let.order missing variable: " + name);
                }
            }
        }
        return names;
    }

    private boolean isEmptyStatement(FrozenNode statement, String pointer) {
        return statement == null || statement.isEmptyNode();
    }

    private CompiledExpression compileExpr(FrozenNode node, CompileScope scope, String pointer) {
        if (node == null) {
            return sourceExpr(currentFunction, pointer, null, new LiteralExpr(BexValues.nullValue()));
        }
        rejectBexInStaticBlueDefinitionFields(node, pointer);
        if (isExpressionOperatorShape(node)) {
            String op = node.getProperties().keySet().iterator().next();
            FrozenNode body = node.getProperties().values().iterator().next();
            BexSourcePath sourcePath = BexSourcePath.of(currentFunction, pointer + "/" + escape(op), op);
            try {
                return new SourceExpr(sourcePath, compileOperator(op, body, scope, pointer + "/" + escape(op)));
            } catch (BexException ex) {
                throw ex.withSourcePath(sourcePath);
            }
        }
        if (node.isEmptyNode()) {
            return sourceExpr(currentFunction, pointer, null, new LiteralExpr(BexValues.nullValue()));
        }
        if (isScalarNode(node)) {
            return sourceExpr(currentFunction, pointer, null,
                    new TransientLiteralExpr(node.getValue()));
        }
        if (node.getItems() != null && !hasLanguageFields(node)) {
            List<CompiledExpression> items = new ArrayList<>();
            for (int i = 0; i < node.getItems().size(); i++) {
                items.add(compileExpr(node.getItems().get(i), scope, pointer + "/" + i));
            }
            return sourceExpr(currentFunction, pointer, null, new ListExpr(items));
        }
        if (node.getProperties() != null || hasLanguageFields(node)) {
            Map<String, CompiledExpression> fields = new LinkedHashMap<>();
            addMetadataFields(fields, node, scope, pointer);
            if (node.getItems() != null) {
                List<CompiledExpression> items = new ArrayList<>();
                for (int i = 0; i < node.getItems().size(); i++) {
                    items.add(compileExpr(node.getItems().get(i), scope, pointer + "/" + i));
                }
                fields.put("items", new ListExpr(items));
            }
            if (node.getProperties() != null) {
                for (String key : BexUnicodeOrder.sortedCopy(node.getProperties().keySet())) {
                    fields.put(key, compileExpr(node.getProperties().get(key), scope,
                            pointer + "/" + escape(key)));
                }
            }
            return sourceExpr(currentFunction, pointer, null, new ObjectExpr(fields));
        }
        return sourceExpr(currentFunction, pointer, null,
                new TransientLiteralExpr(node.getValue()));
    }

    private CompiledExpression compileOperator(String op, FrozenNode body, CompileScope scope, String pointer) {
        validateExpressionBody(op, body);
        if ("$literal".equals(op)) return new LiteralExpr(BexValues.frozen(body));
        if ("$null".equals(op)) return new LiteralExpr(BexValues.nullValue());
        if ("$emptyObject".equals(op)) return new LiteralExpr(BexValues.map(Collections.<String, BexValue>emptyMap()));
        if ("$emptyList".equals(op)) return new LiteralExpr(BexValues.list(Collections.<BexValue>emptyList()));
        if ("$document".equals(op)) return documentExpr(body, scope, pointer);
        if ("$binding".equals(op)) return bindingExpr(body, scope, pointer);
        if ("$event".equals(op)) return contextPointerExpr(body, scope, ContextKind.EVENT, pointer);
        if ("$processingEvent".equals(op)) return contextPointerExpr(body, scope, ContextKind.PROCESSING_EVENT, pointer);
        if ("$steps".equals(op)) return stepsExpr(body, scope, pointer);
        if ("$currentContract".equals(op)) return contextPointerExpr(body, scope, ContextKind.CURRENT_CONTRACT, pointer);
        if ("$var".equals(op)) return varExpr(body, scope, pointer);
        if ("$const".equals(op)) {
            String name = constName(body);
            if (!constants.containsKey(name)) {
                throw new BexException("Unknown constant: " + name);
            }
            return new ConstExpr(name, pathOperandOrNull(body, scope, pointer));
        }
        if ("$get".equals(op)) return new GetExpr(compileExpr(required(prop(body, "object"), "$get.object"), scope, pointer + "/object"), textOrExpr(required(prop(body, "key"), "$get.key"), scope, null, pointer + "/key"));
        if ("$changeset".equals(op)) return new ChangesetExpr();
        if ("$events".equals(op)) return new EventsExpr();
        if ("$resultValue".equals(op)) return new ResultValueExpr(pointerOperand(body, scope, pointer));
        if ("$unwrap".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.UNWRAP);
        if ("$is".equals(op)) return isExpr(body, scope, pointer);
        if ("$text".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.TEXT);
        if ("$integer".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.INTEGER);
        if ("$number".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.NUMBER);
        if ("$boolean".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.BOOLEAN);
        if ("$object".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.OBJECT);
        if ("$list".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.LIST);
        if ("$concat".equals(op)) return new VariadicExpr(compileExprList(body, scope, pointer), VariadicOp.CONCAT);
        if ("$pointerJoin".equals(op)) return new PointerJoinExpr(compileExprList(body, scope, pointer));
        if ("$join".equals(op)) return new JoinExpr(compileExpr(required(prop(body, "list"), "$join.list"), scope, pointer + "/list"), compileExpr(required(prop(body, "separator"), "$join.separator"), scope, pointer + "/separator"));
        if ("$split".equals(op)) return new SplitExpr(compileExpr(required(prop(body, "text"), "$split.text"), scope, pointer + "/text"), compileExpr(required(prop(body, "separator"), "$split.separator"), scope, pointer + "/separator"), prop(body, "limit") != null ? compileExpr(prop(body, "limit"), scope, pointer + "/limit") : null);
        if ("$startsWith".equals(op)) return new BinaryTextExpr(compileExprList(body, scope, pointer), BinaryTextOp.STARTS_WITH);
        if ("$sliceAfter".equals(op)) return new BinaryTextExpr(compileExprList(body, scope, pointer), BinaryTextOp.SLICE_AFTER);
        if ("$eq".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.EQ);
        if ("$ne".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.NE);
        if ("$gt".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.GT);
        if ("$gte".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.GTE);
        if ("$lt".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.LT);
        if ("$lte".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.LTE);
        if ("$and".equals(op)) return new LogicalExpr(compileExprList(body, scope, pointer), true);
        if ("$or".equals(op)) return new LogicalExpr(compileExprList(body, scope, pointer), false);
        if ("$not".equals(op)) return new NotExpr(compileExpr(body, scope, pointer));
        if ("$truthy".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.TRUTHY);
        if ("$empty".equals(op) || "$isEmpty".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.EMPTY);
        if ("$exists".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.EXISTS);
        if ("$kind".equals(op)) return new KindExpr(compileExpr(body, scope, pointer));
        if ("$isKind".equals(op)) return new IsKindExpr(compileExpr(required(prop(body, "val"), "$isKind.val"), scope, pointer + "/val"), kindSet(required(prop(body, "kind"), "$isKind.kind")));
        if ("$nodeBlueId".equals(op)) return new NodeBlueIdExpr(compileExpr(body, scope, pointer));
        if ("$coalesce".equals(op)) return new CoalesceExpr(compileExprList(body, scope, pointer));
        if ("$default".equals(op)) return new CoalesceExpr(compileExprList(body, scope, pointer));
        if ("$add".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.ADD);
        if ("$subtract".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.SUBTRACT);
        if ("$multiply".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.MULTIPLY);
        if ("$divide".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.DIVIDE);
        if ("$keys".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.KEYS);
        if ("$entries".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.ENTRIES);
        if ("$size".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.SIZE);
        if ("$listGet".equals(op)) return new ListGetExpr(compileExpr(required(prop(body, "list"), "$listGet.list"), scope, pointer + "/list"), compileExpr(required(prop(body, "index"), "$listGet.index"), scope, pointer + "/index"), prop(body, "default") != null ? compileExpr(prop(body, "default"), scope, pointer + "/default") : null);
        if ("$listConcat".equals(op)) return new VariadicExpr(compileExprList(body, scope, pointer), VariadicOp.LIST_CONCAT);
        if ("$merge".equals(op)) return new VariadicExpr(compileExprList(body, scope, pointer), VariadicOp.MERGE);
        if ("$objectSet".equals(op)) return new ObjectSetExpr(compileExpr(required(prop(body, "object"), "$objectSet.object"), scope, pointer + "/object"), textOrExpr(required(prop(body, "key"), "$objectSet.key"), scope, null, pointer + "/key"), compileExpr(required(prop(body, "val"), "$objectSet.val"), scope, pointer + "/val"));
        if ("$pointerGet".equals(op)) return new PointerGetExpr(compileExpr(required(prop(body, "object"), "$pointerGet.object"), scope, pointer + "/object"), valuePointerOperand(required(prop(body, "path"), "$pointerGet.path"), scope, pointer + "/path"), prop(body, "default") != null ? compileExpr(prop(body, "default"), scope, pointer + "/default") : null);
        if ("$pointerSet".equals(op)) return new PointerSetExpr(compileExpr(required(prop(body, "object"), "$pointerSet.object"), scope, pointer + "/object"), textOrExpr(prop(body, "op"), scope, "set", pointer + "/op"), valuePointerOperand(required(prop(body, "path"), "$pointerSet.path"), scope, pointer + "/path"), prop(body, "val") != null ? compileExpr(prop(body, "val"), scope, pointer + "/val") : null);
        if ("$map".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.MAP, "expr");
        if ("$filter".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FILTER, "where");
        if ("$flatMap".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FLAT_MAP, "expr");
        if ("$reduce".equals(op)) return reduceExpr(body, scope, pointer);
        if ("$some".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.SOME, "where");
        if ("$find".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FIND, "where");
        if ("$findEntry".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FIND_ENTRY, "where");
        if ("$includes".equals(op)) return new IncludesExpr(compileExpr(required(prop(body, "list"), "$includes.list"), scope, pointer + "/list"),
                compileExpr(required(prop(body, "val"), "$includes.val"), scope, pointer + "/val"));
        if ("$hasKey".equals(op)) return new HasKeyExpr(compileExpr(required(prop(body, "object"), "$hasKey.object"), scope, pointer + "/object"),
                textOrExpr(required(prop(body, "key"), "$hasKey.key"), scope, null, pointer + "/key"));
        if ("$objectFromEntries".equals(op)) return new ObjectFromEntriesExpr(compileExpr(body, scope, pointer));
        if ("$intrinsic".equals(op)) return intrinsicExpr(body, scope, pointer);
        if ("$fail".equals(op)) return new FailExpr(failMessageExpr(body, scope, pointer));
        if ("$choose".equals(op)) return new ChooseExpr(compileExpr(required(prop(body, "cond"), "$choose.cond"), scope, pointer + "/cond"), compileExpr(required(prop(body, "then"), "$choose.then"), scope, pointer + "/then"), prop(body, "else") != null ? compileExpr(prop(body, "else"), scope, pointer + "/else") : new LiteralExpr(BexValues.undefined()));
        if ("$call".equals(op)) return compileCall(body, scope, pointer);
        throw new BexException("Unknown expression operator: " + op);
    }

    private CompiledExpression failMessageExpr(FrozenNode body,
                                               CompileScope scope,
                                               String pointer) {
        boolean messageWrapper = body != null
                && body.getProperties() != null
                && hasExplicitProperty(body, "message");
        return compileExpr(messageWrapper ? explicitProp(body, "message") : body,
                scope,
                messageWrapper ? pointer + "/message" : pointer);
    }

    private void validateExpressionBody(String op, FrozenNode body) {
        if ("$concat".equals(op)
                || "$pointerJoin".equals(op)
                || "$listConcat".equals(op)
                || "$merge".equals(op)
                || "$and".equals(op)
                || "$or".equals(op)
                || "$coalesce".equals(op)
                || "$default".equals(op)) {
            requireListBody(body, op);
            return;
        }
        if ("$eq".equals(op)
                || "$ne".equals(op)
                || "$gt".equals(op)
                || "$gte".equals(op)
                || "$lt".equals(op)
                || "$lte".equals(op)
                || "$startsWith".equals(op)
                || "$sliceAfter".equals(op)) {
            requireListArity(body, op, 2);
            return;
        }
        if ("$add".equals(op)
                || "$subtract".equals(op)
                || "$multiply".equals(op)
                || "$divide".equals(op)) {
            requireListBody(body, op);
            if (body.getItems().isEmpty()) {
                throw new BexException(op + " requires at least one operand");
            }
            return;
        }
        if ("$get".equals(op)) {
            requireObjectBody(body, op, "object", "key");
        } else if ("$is".equals(op)) {
            requireObjectBody(body, op, "node", "pattern");
        } else if ("$isKind".equals(op)) {
            requireObjectBody(body, op, "val", "kind");
        } else if ("$join".equals(op)) {
            requireObjectBody(body, op, "list", "separator");
        } else if ("$split".equals(op)) {
            requireObjectBody(body, op, "text", "separator", "limit");
        } else if ("$listGet".equals(op)) {
            requireObjectBody(body, op, "list", "index", "default");
        } else if ("$objectSet".equals(op)) {
            requireObjectBody(body, op, "object", "key", "val");
        } else if ("$pointerGet".equals(op)) {
            requireObjectBody(body, op, "object", "path", "default");
        } else if ("$pointerSet".equals(op)) {
            requireObjectBody(body, op, "object", "path", "op", "val");
        } else if ("$map".equals(op) || "$flatMap".equals(op)) {
            requireObjectBody(body, op, "in", "item", "key", "index", "expr");
        } else if ("$filter".equals(op)
                || "$some".equals(op)
                || "$find".equals(op)
                || "$findEntry".equals(op)) {
            requireObjectBody(body, op, "in", "item", "key", "index", "where");
        } else if ("$reduce".equals(op)) {
            requireObjectBody(body, op, "in", "acc", "init", "item", "key", "index", "expr");
        } else if ("$includes".equals(op)) {
            requireObjectBody(body, op, "list", "val");
        } else if ("$hasKey".equals(op)) {
            requireObjectBody(body, op, "object", "key");
        } else if ("$choose".equals(op)) {
            requireObjectBody(body, op, "cond", "then", "else");
        } else if ("$call".equals(op)) {
            requireObjectBody(body, op, "function", "args");
        } else if ("$intrinsic".equals(op)) {
            requireObjectNode(body, op);
        } else if ("$binding".equals(op)) {
            if (!isScalarBody(body)) {
                requireObjectBody(body, op, "name", "path");
            }
        } else if ("$steps".equals(op)) {
            if (!isScalarBody(body)) {
                requireObjectBody(body, op, "step", "path");
            }
        } else if ("$var".equals(op) || "$const".equals(op)) {
            if (!isScalarBody(body)) {
                requireObjectBody(body, op, "name", "path");
            }
        } else if ("$document".equals(op)
                && body != null
                && hasAuthoredField(body, "path")
                && !isExpressionOperatorShape(body)) {
            requireObjectBody(body, op, "path", "view");
        }
    }

    private void validateStatementBody(String op, FrozenNode body) {
        if ("$let".equals(op)) {
            requireObjectBody(body, op, "name", "expr", "vars", "order");
            boolean multi = hasAuthoredField(body, "vars");
            if (multi && (hasAuthoredField(body, "name") || hasAuthoredField(body, "expr"))) {
                throw new BexException("$let must use either name/expr or vars/order form");
            }
            if (!multi && (!hasAuthoredField(body, "name") || !hasAuthoredField(body, "expr"))) {
                throw new BexException("$let single-binding form requires name and expr");
            }
        } else if ("$set".equals(op)) {
            requireObjectBody(body, op, "name", "expr");
        } else if ("$if".equals(op)) {
            requireObjectBody(body, op, "cond", "then", "else");
        } else if ("$forEach".equals(op)) {
            requireObjectBody(body, op, "in", "item", "key", "index", "do");
            if (!hasAuthoredField(body, "do")) {
                throw new BexException("$forEach.do is required");
            }
        } else if ("$appendChange".equals(op)) {
            requireObjectBody(body, op, "op", "path", "val");
        } else if ("$call".equals(op)) {
            requireObjectBody(body, op, "function", "args");
        } else if ("$returnIf".equals(op)) {
            requireObjectBody(body, op, "cond", "expr");
        } else if ("$failIf".equals(op)) {
            requireObjectBody(body, op, "cond", "message");
        }
    }

    private void requireListBody(FrozenNode body, String op) {
        if (body == null || body.getItems() == null || hasNonListPayload(body)) {
            throw new BexException(op + " expects a list body");
        }
    }

    private void requireListArity(FrozenNode body, String op, int expected) {
        requireListBody(body, op);
        if (body.getItems().size() != expected) {
            throw new BexException(op + " expects exactly " + expected + " operands");
        }
    }

    private void requireObjectBody(FrozenNode body, String op, String... allowedFields) {
        requireObjectNode(body, op);
        Set<String> allowed = new LinkedHashSet<>();
        Collections.addAll(allowed, allowedFields);
        for (String field : authoredFieldNames(body)) {
            if (!allowed.contains(field)) {
                throw new BexException(op + " has unknown body field: " + field);
            }
        }
    }

    private void requireObjectNode(FrozenNode body, String op) {
        if (body == null
                || body.getItems() != null
                || body.getValue() != null
                || body.getReferenceBlueId() != null
                || body.getPreviousBlueId() != null
                || body.getPosition() != null) {
            throw new BexException(op + " expects an object body");
        }
    }

    private void requireProgramNode(FrozenNode node, String label) {
        if (node == null
                || node.getValue() != null
                || node.getItems() != null
                || node.getReferenceBlueId() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null) {
            throw new BexException("BEX " + label + " must be an object node");
        }
    }

    private boolean hasNonListPayload(FrozenNode node) {
        return node.getValue() != null
                || node.getProperties() != null
                || hasLanguageFields(node)
                || node.getPreviousBlueId() != null
                || node.getPosition() != null;
    }

    private boolean isScalarBody(FrozenNode body) {
        return isScalarNode(body);
    }

    /**
     * Blue preprocessing adds an exact core-type reference to an authored
     * scalar. That inferred metadata is part of the scalar representation, not
     * a BEX object literal field. Keep the exception deliberately narrow:
     * computed or additional Blue metadata must still compile as an object.
     */
    private boolean isScalarNode(FrozenNode node) {
        if (node == null
                || node.getValue() == null
                || node.getItems() != null
                || node.getProperties() != null
                || node.getName() != null
                || node.getDescription() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getReferenceBlueId() != null
                || node.getBlue() != null
                || node.getContracts() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null) {
            return false;
        }
        FrozenNode type = node.getType();
        if (type == null) {
            return true;
        }
        String expectedTypeBlueId = scalarTypeBlueId(node.getValue());
        return expectedTypeBlueId != null
                && type.isReferenceOnly()
                && expectedTypeBlueId.equals(type.getReferenceBlueId());
    }

    private String scalarTypeBlueId(Object value) {
        if (value instanceof String) {
            return TEXT_TYPE_BLUE_ID;
        }
        if (value instanceof Boolean) {
            return BOOLEAN_TYPE_BLUE_ID;
        }
        if (value instanceof java.math.BigDecimal
                || value instanceof Float
                || value instanceof Double) {
            return DOUBLE_TYPE_BLUE_ID;
        }
        if (value instanceof java.math.BigInteger
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return INTEGER_TYPE_BLUE_ID;
        }
        return null;
    }

    private CompiledExpression intrinsicExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body == null || body.getValue() != null || body.getItems() != null) {
            throw new BexException("$intrinsic expects an object body");
        }
        FrozenNode typeNode = required(prop(body, "type"), "$intrinsic.type");
        rejectBexAnywhereInStaticPattern(typeNode, pointer + "/type");
        String blueId = intrinsicTypeBlueId(typeNode);
        BexValue typeValue = BexValues.frozen(typeNode);
        if (blueId == null || blueId.isEmpty()) {
            throw new BexException("$intrinsic.type must resolve to a BlueId");
        }
        if (!intrinsics.supports(blueId)) {
            throw new BexException("Unsupported intrinsic BlueId: " + blueId);
        }
        requiredIntrinsicBlueIds.add(blueId);

        Map<String, CompiledExpression> fields = new LinkedHashMap<>();
        if (body.getProperties() != null) {
            for (String fieldName : BexUnicodeOrder.sortedCopy(body.getProperties().keySet())) {
                if ("type".equals(fieldName)) {
                    continue;
                }
                fields.put(fieldName, compileExpr(body.getProperties().get(fieldName), scope,
                        pointer + "/" + escape(fieldName)));
            }
        }
        return new IntrinsicExpr(blueId, typeValue, fields);
    }

    private String intrinsicTypeBlueId(FrozenNode typeNode) {
        if (hasExplicitProperty(typeNode, "blueId")) {
            return text(explicitProp(typeNode, "blueId"));
        }
        if (typeNode.getReferenceBlueId() != null && !typeNode.getReferenceBlueId().isEmpty()) {
            return typeNode.getReferenceBlueId();
        }
        String blueId = BexNodeIdentity.safeBlueId(typeNode);
        if (blueId != null && !blueId.isEmpty()) {
            return blueId;
        }
        return text(typeNode);
    }

    private CompiledExpression varExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body != null && body.getProperties() != null) {
            String name = requiredText(prop(body, "name"), "$var.name");
            return new VarExpr(scope.resolveSlot(name), pathOperandOrNull(body, scope, pointer));
        }
        return new VarExpr(scope.resolveSlot(requiredText(body, "$var")));
    }

    private String constName(FrozenNode body) {
        if (body != null && body.getProperties() != null) {
            return requiredText(prop(body, "name"), "$const.name");
        }
        return requiredText(body, "$const");
    }

    private PointerOperand pathOperandOrNull(FrozenNode body, CompileScope scope, String pointer) {
        if (body == null || body.getProperties() == null || prop(body, "path") == null) {
            return null;
        }
        return valuePointerOperand(prop(body, "path"), scope, pointer + "/path");
    }

    private Set<String> kindSet(FrozenNode node) {
        Set<String> kinds = new LinkedHashSet<>();
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                kinds.add(validateKind(requiredText(item, "$isKind.kind item")));
            }
        } else {
            kinds.add(validateKind(requiredText(node, "$isKind.kind")));
        }
        return Collections.unmodifiableSet(kinds);
    }

    private String validateKind(String kind) {
        if ("undefined".equals(kind)
                || "null".equals(kind)
                || "text".equals(kind)
                || "integer".equals(kind)
                || "double".equals(kind)
                || "boolean".equals(kind)
                || "object".equals(kind)
                || "list".equals(kind)) {
            return kind;
        }
        throw new BexException("Unknown BEX kind: " + kind);
    }

    private CompiledExpression collectionExpr(FrozenNode body, CompileScope scope, String pointer,
                                              CollectionOp op, String bodyField) {
        String operator = collectionOperatorName(op);
        CompiledExpression input = compileExpr(required(prop(body, "in"), operator + ".in"), scope, pointer + "/in");
        String itemName = requiredText(prop(body, "item"), operator + ".item");
        String keyName = prop(body, "key") != null ? requiredText(prop(body, "key"), operator + ".key") : null;
        String indexName = prop(body, "index") != null ? requiredText(prop(body, "index"), operator + ".index") : null;
        validateDistinctCollectionBindings(operator, itemName, keyName, indexName);
        CompileScope.Visibility visibility = scope.captureVisibility();
        try {
            int itemSlot = scope.declareOrGetSlot(itemName);
            int keySlot = keyName != null ? scope.declareOrGetSlot(keyName) : -1;
            int indexSlot = indexName != null ? scope.declareOrGetSlot(indexName) : -1;
            CompiledExpression expr = compileExpr(required(prop(body, bodyField), operator + "." + bodyField),
                    scope, pointer + "/" + bodyField);
            return new CollectionQueryExpr(input, itemSlot, keySlot, indexSlot, expr, op);
        } finally {
            scope.restoreVisibility(visibility);
        }
    }

    private CompiledExpression reduceExpr(FrozenNode body, CompileScope scope, String pointer) {
        CompiledExpression input = compileExpr(required(prop(body, "in"), "$reduce.in"), scope, pointer + "/in");
        String accName = requiredText(prop(body, "acc"), "$reduce.acc");
        String itemName = requiredText(prop(body, "item"), "$reduce.item");
        String keyName = prop(body, "key") != null ? requiredText(prop(body, "key"), "$reduce.key") : null;
        String indexName = prop(body, "index") != null ? requiredText(prop(body, "index"), "$reduce.index") : null;
        validateDistinctCollectionBindings("$reduce", itemName, keyName, indexName);
        if (accName.equals(itemName) || accName.equals(keyName) || accName.equals(indexName)) {
            throw new BexException("$reduce.acc must use a different binding name");
        }
        CompiledExpression init = compileExpr(required(prop(body, "init"), "$reduce.init"), scope, pointer + "/init");
        CompileScope.Visibility visibility = scope.captureVisibility();
        try {
            int accSlot = scope.declareOrGetSlot(accName);
            int itemSlot = scope.declareOrGetSlot(itemName);
            int keySlot = keyName != null ? scope.declareOrGetSlot(keyName) : -1;
            int indexSlot = indexName != null ? scope.declareOrGetSlot(indexName) : -1;
            CompiledExpression expr = compileExpr(required(prop(body, "expr"), "$reduce.expr"), scope, pointer + "/expr");
            return new ReduceExpr(input, accSlot, init, itemSlot, keySlot, indexSlot, expr);
        } finally {
            scope.restoreVisibility(visibility);
        }
    }

    private String collectionOperatorName(CollectionOp op) {
        switch (op) {
            case MAP:
                return "$map";
            case FILTER:
                return "$filter";
            case FLAT_MAP:
                return "$flatMap";
            case SOME:
                return "$some";
            case FIND:
                return "$find";
            case FIND_ENTRY:
                return "$findEntry";
            default:
                return "collection operator";
        }
    }

    private void validateDistinctCollectionBindings(String operator, String itemName, String keyName, String indexName) {
        if (keyName != null && keyName.equals(itemName)) {
            throw new BexException(operator + ".key must use a different binding name than " + operator + ".item");
        }
        if (indexName != null && indexName.equals(itemName)) {
            throw new BexException(operator + ".index must use a different binding name than " + operator + ".item");
        }
        if (keyName != null && indexName != null && keyName.equals(indexName)) {
            throw new BexException(operator + ".key must use a different binding name than " + operator + ".index");
        }
    }

    private CompiledExpression isExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body == null || body.getProperties() == null) {
            throw new BexException("$is expects an object body");
        }
        FrozenNode pattern = required(prop(body, "pattern"), "$is.pattern");
        rejectBexAnywhereInStaticPattern(pattern, pointer + "/pattern");
        return new IsExpr(
                compileExpr(required(prop(body, "node"), "$is.node"), scope, pointer + "/node"),
                pattern);
    }

    private CompiledExpression documentExpr(FrozenNode body, CompileScope scope, String pointer) {
        boolean resolved = false;
        FrozenNode pointerNode = body;
        if (body != null && body.getProperties() != null && body.getProperties().containsKey("path")) {
            pointerNode = prop(body, "path");
            String view = text(prop(body, "view"));
            resolved = "resolved".equals(view);
        }
        return new DocumentExpr(pointerOperand(pointerNode, scope, pointer), resolved);
    }

    private CompiledExpression contextPointerExpr(FrozenNode body, CompileScope scope, ContextKind kind, String pointer) {
        return new ContextPointerExpr(valuePointerOperand(body, scope, pointer), kind);
    }

    private CompiledExpression bindingExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body != null && body.getValue() != null && body.getProperties() == null && body.getItems() == null) {
            String selector = String.valueOf(body.getValue());
            int slash = selector.indexOf('/');
            String name = slash >= 0 ? selector.substring(0, slash) : selector;
            if (name.isEmpty()) {
                throw new BexException("$binding short form requires a binding name");
            }
            String path = slash >= 0 ? selector.substring(slash) : "/";
            return new BindingExpr(new StaticTextExpr(name), StaticValuePointerOperand.of(path));
        }
        if (body == null || body.getProperties() == null) {
            throw new BexException("$binding expects a binding name or object form");
        }
        TextOperand name = textOrExpr(required(prop(body, "name"), "$binding.name"), scope, null, pointer + "/name");
        FrozenNode path = prop(body, "path") != null ? prop(body, "path") : scalarNode("/");
        return new BindingExpr(name, valuePointerOperand(path, scope, pointer + "/path"));
    }

    private CompiledExpression stepsExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body.getValue() != null) {
            String selector = String.valueOf(body.getValue());
            int dot = selector.indexOf('.');
            String step = dot >= 0 ? selector.substring(0, dot) : selector;
            String path = dot >= 0 ? "/" + selector.substring(dot + 1) : "/";
            return new StepsExpr(new StaticTextExpr(step), StaticValuePointerOperand.of(path));
        }
        return new StepsExpr(textOrExpr(required(prop(body, "step"), "$steps.step"), scope, null, pointer + "/step"),
                valuePointerOperand(prop(body, "path") != null ? prop(body, "path") : scalarNode("/"), scope, pointer + "/path"));
    }

    private CallExpr compileCall(FrozenNode body, CompileScope scope, String pointer) {
        String function = requiredText(prop(body, "function"), "$call.function");
        FunctionSignature signature = functionSignatures.get(function);
        if (signature == null) {
            throw new BexException("Unknown function: " + function);
        }
        List<CompiledExpression> argExpressions = new ArrayList<>();
        List<Integer> targetSlots = new ArrayList<>();
        Set<String> providedArgs = new LinkedHashSet<>();
        FrozenNode argsNode = prop(body, "args");
        if (argsNode != null) {
            validatePlainObjectContainer(argsNode, "$call.args");
            if (argsNode.getProperties() == null) {
                if (!argsNode.isEmptyNode()) {
                    throw new BexException("$call.args must be an object at " + pointer + "/args");
                }
            } else {
                for (String argName : BexUnicodeOrder.sortedCopy(argsNode.getProperties().keySet())) {
                    BexCompiledProgram.ArgSpec arg = signature.arg(argName);
                    if (arg == null) {
                        throw new BexException("Unknown argument " + argName + " for function " + function);
                    }
                    providedArgs.add(argName);
                    targetSlots.add(arg.slot());
                    argExpressions.add(compileExpr(argsNode.getProperties().get(argName), scope,
                            pointer + "/args/" + escape(argName)));
                }
            }
        }
        for (BexCompiledProgram.ArgSpec arg : signature.args()) {
            if (!providedArgs.contains(arg.name())) {
                throw new BexException("Missing argument " + arg.name() + " for function " + function);
            }
        }
        int[] slots = new int[targetSlots.size()];
        for (int i = 0; i < targetSlots.size(); i++) {
            slots[i] = targetSlots.get(i);
        }
        return new CallExpr(function,
                slots,
                argExpressions.toArray(new CompiledExpression[0]));
    }

    private List<CompiledExpression> compileExprList(FrozenNode node, CompileScope scope, String pointer) {
        if (node == null || node.getItems() == null) {
            throw new BexException("Operator expects a list");
        }
        List<CompiledExpression> expressions = new ArrayList<>();
        for (int i = 0; i < node.getItems().size(); i++) {
            expressions.add(compileExpr(node.getItems().get(i), scope, pointer + "/" + i));
        }
        return expressions;
    }

    private TextOperand textOrExpr(FrozenNode node, CompileScope scope, String defaultText, String pointer) {
        if (node == null) {
            return new StaticTextExpr(defaultText);
        }
        if (node.getValue() != null && node.getProperties() == null && node.getItems() == null) {
            return new StaticTextExpr(String.valueOf(node.getValue()));
        }
        return new DynamicTextExpr(compileExpr(node, scope, pointer), pointer);
    }

    private PointerOperand pointerOperand(FrozenNode node, CompileScope scope, String pointer) {
        if (node != null && node.getProperties() != null && node.getProperties().containsKey("path")) {
            node = prop(node, "path");
        }
        if (node != null && node.getValue() != null && node.getProperties() == null && node.getItems() == null) {
            return StaticPointerOperand.of(String.valueOf(node.getValue()));
        }
        return new DynamicPointerOperand(compileExpr(node, scope, pointer));
    }

    private PointerOperand valuePointerOperand(FrozenNode node, CompileScope scope, String pointer) {
        if (node != null && node.getProperties() != null && node.getProperties().containsKey("path")) {
            node = prop(node, "path");
        }
        if (node != null && node.getValue() != null && node.getProperties() == null && node.getItems() == null) {
            return StaticValuePointerOperand.of(String.valueOf(node.getValue()));
        }
        return new DynamicValuePointerOperand(compileExpr(node, scope, pointer));
    }

    private FrozenNode prop(FrozenNode node, String key) {
        if (node == null) {
            return null;
        }
        if (node.getProperties() != null && node.getProperties().containsKey(key)) {
            return node.getProperties().get(key);
        }
        if ("name".equals(key) && node.getName() != null) {
            return scalarNode(node.getName());
        }
        if ("description".equals(key) && node.getDescription() != null) {
            return scalarNode(node.getDescription());
        }
        if ("type".equals(key) && node.getType() != null) {
            return node.getType();
        }
        if ("itemType".equals(key) && node.getItemType() != null) {
            return node.getItemType();
        }
        if ("keyType".equals(key) && node.getKeyType() != null) {
            return node.getKeyType();
        }
        if ("valueType".equals(key) && node.getValueType() != null) {
            return node.getValueType();
        }
        if ("value".equals(key) && node.getValue() != null) {
            return scalarNode(node.getValue());
        }
        if ("blueId".equals(key) && node.getReferenceBlueId() != null) {
            return scalarNode(node.getReferenceBlueId());
        }
        if ("blue".equals(key) && node.getBlue() != null) {
            return node.getBlue();
        }
        if ("contracts".equals(key) && node.getContracts() != null) {
            return node.getContracts();
        }
        return null;
    }

    private FrozenNode explicitProp(FrozenNode node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private boolean hasExplicitProperty(FrozenNode node, String key) {
        return node != null && node.getProperties() != null && node.getProperties().containsKey(key);
    }

    private boolean hasAuthoredField(FrozenNode node, String key) {
        return authoredFieldNames(node).contains(key);
    }

    private Set<String> authoredFieldNames(FrozenNode node) {
        Set<String> fields = new LinkedHashSet<>();
        if (node == null) {
            return fields;
        }
        if (node.getProperties() != null) {
            fields.addAll(node.getProperties().keySet());
        }
        if (node.getName() != null) fields.add("name");
        if (node.getDescription() != null) fields.add("description");
        if (node.getType() != null) fields.add("type");
        if (node.getItemType() != null) fields.add("itemType");
        if (node.getKeyType() != null) fields.add("keyType");
        if (node.getValueType() != null) fields.add("valueType");
        if (node.getValue() != null) fields.add("value");
        if (node.getItems() != null) fields.add("items");
        if (node.getReferenceBlueId() != null) fields.add("blueId");
        if (node.getBlue() != null) fields.add("blue");
        if (node.getSchema() != null) fields.add("schema");
        if (node.getMergePolicy() != null) fields.add("mergePolicy");
        if (node.getContracts() != null) fields.add("contracts");
        if (node.getPreviousBlueId() != null) fields.add("$previous");
        if (node.getPosition() != null) fields.add("$pos");
        return fields;
    }

    private FrozenNode required(FrozenNode node, String label) {
        if (node == null) {
            throw new BexException("Missing required field: " + label);
        }
        return node;
    }

    private String requiredText(FrozenNode node, String label) {
        String value = text(node);
        if (value == null) {
            throw new BexException("Missing required text field: " + label);
        }
        return value;
    }

    private String requiredNonEmptyText(FrozenNode node, String label) {
        String value = requiredText(node, label);
        if (value.isEmpty()) {
            throw new BexException("Required text field is empty: " + label);
        }
        return value;
    }

    private String text(FrozenNode node) {
        return node != null && node.getValue() instanceof String
                ? (String) node.getValue()
                : null;
    }

    private boolean isExpressionOperatorShape(FrozenNode node) {
        if (node == null
                || node.getProperties() == null
                || node.getProperties().size() != 1
                || authoredFieldNames(node).size() != 1) {
            return false;
        }
        String key = node.getProperties().keySet().iterator().next();
        return key.startsWith("$");
    }

    private boolean isOperator(FrozenNode node, String op) {
        return isExpressionOperatorShape(node) && node.getProperties().containsKey(op);
    }

    private FrozenNode onlyValue(FrozenNode node) {
        return node.getProperties().values().iterator().next();
    }

    private void addMetadataFields(Map<String, CompiledExpression> fields, FrozenNode node, CompileScope scope, String pointer) {
        if (node.getName() != null) {
            fields.put("name", new TransientLiteralExpr(node.getName()));
        }
        if (node.getDescription() != null) {
            fields.put("description", new TransientLiteralExpr(node.getDescription()));
        }
        if (node.getType() != null) {
            fields.put("type", compileExpr(node.getType(), scope, pointer + "/type"));
        }
        if (node.getItemType() != null) {
            fields.put("itemType", compileExpr(node.getItemType(), scope, pointer + "/itemType"));
        }
        if (node.getKeyType() != null) {
            fields.put("keyType", compileExpr(node.getKeyType(), scope, pointer + "/keyType"));
        }
        if (node.getValueType() != null) {
            fields.put("valueType", compileExpr(node.getValueType(), scope, pointer + "/valueType"));
        }
        if (node.getValue() != null) {
            fields.put("value", new TransientLiteralExpr(node.getValue()));
        }
        if (node.getReferenceBlueId() != null) {
            fields.put("blueId", new TransientLiteralExpr(node.getReferenceBlueId()));
        }
        if (node.getBlue() != null) {
            fields.put("blue", compileExpr(node.getBlue(), scope, pointer + "/blue"));
        }
        if (node.getContracts() != null) {
            fields.put("contracts", compileExpr(node.getContracts(), scope, pointer + "/contracts"));
        }
        if (node.getSchema() != null) {
            fields.put("schema", new LiteralExpr(BexValues.nodeSnapshot(new blue.language.model.Node().schema(node.getSchema()))));
        }
        if (node.getMergePolicy() != null) {
            fields.put("mergePolicy", new TransientLiteralExpr(node.getMergePolicy()));
        }
    }

    private boolean hasLanguageFields(FrozenNode node) {
        return node.getName() != null
                || node.getDescription() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getReferenceBlueId() != null
                || node.getBlue() != null
                || node.getContracts() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null;
    }

    private FrozenNode scalarNode(Object value) {
        return FrozenNode.fromResolvedNode(new blue.language.model.Node().value(value));
    }

    private CompiledExpression sourceExpr(String functionName, String pointer, String operator, CompiledExpression expression) {
        return new SourceExpr(BexSourcePath.of(functionName, pointer, operator), expression);
    }

    private CompiledStatement sourceStatement(String functionName, String pointer, String operator, CompiledStatement statement) {
        return new SourceStatement(BexSourcePath.of(functionName, pointer, operator), statement);
    }

    private void validateDistinctForEachBindings(String itemName, String keyName, String indexName) {
        if (keyName != null && keyName.equals(itemName)) {
            throw new BexException("$forEach.key must use a different binding name than $forEach.item");
        }
        if (indexName != null && indexName.equals(itemName)) {
            throw new BexException("$forEach.index must use a different binding name than $forEach.item");
        }
        if (keyName != null && indexName != null && keyName.equals(indexName)) {
            throw new BexException("$forEach.key must use a different binding name than $forEach.index");
        }
    }

    private String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private void validatePlainObjectContainer(FrozenNode node, String label) {
        if (node == null) {
            return;
        }
        if (node.getValue() != null || node.getItems() != null || node.getReferenceBlueId() != null) {
            throw new BexException(label + " must be a plain object with non-reserved field names");
        }
        if (node.getPreviousBlueId() != null || node.getPosition() != null) {
            throw new BexException(label + " contains a Blue list-control key; use non-reserved names");
        }
        if (node.getContracts() != null) {
            throw new BexException(label + " contains reserved Blue key: contracts");
        }
        if (node.getName() != null
                || node.getDescription() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getBlue() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null) {
            throw new BexException(label + " contains a Blue language key; use non-reserved names");
        }
        if (node.getProperties() != null) {
            for (String key : node.getProperties().keySet()) {
                if (RESERVED_BLUE_KEYS.contains(key)) {
                    throw new BexException(label + " contains reserved Blue key: " + key);
                }
            }
        }
    }

    private void rejectBexAnywhereInStaticPattern(FrozenNode pattern, String pointer) {
        if (pattern != null && containsCache.containsBex(pattern, metrics)) {
            throw new BexException("BEX expressions inside static Blue patterns are not supported at " + pointer);
        }
        rejectBexInStaticBlueDefinitionFields(pattern, pointer);
    }

    private void rejectBexInStaticBlueDefinitionFields(FrozenNode node, String pointer) {
        if (node == null) {
            return;
        }
        rejectBexInStaticField(node.getType(), pointer + "/type", "type");
        rejectBexInStaticField(node.getItemType(), pointer + "/itemType", "itemType");
        rejectBexInStaticField(node.getKeyType(), pointer + "/keyType", "keyType");
        rejectBexInStaticField(node.getValueType(), pointer + "/valueType", "valueType");
        rejectBexInStaticField(node.getBlue(), pointer + "/blue", "blue");
        rejectBexInStaticField(node.getContracts(), pointer + "/contracts", "contracts");
        if (node.getSchema() != null) {
            rejectBexInSchema(node.getSchema(), pointer + "/schema");
        }
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                rejectBexInStaticBlueDefinitionFields(node.getItems().get(i), pointer + "/" + i);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
                rejectBexInStaticBlueDefinitionFields(entry.getValue(), pointer + "/" + escape(entry.getKey()));
            }
        }
    }

    private void rejectBexInStaticField(FrozenNode field, String pointer, String fieldName) {
        if (field != null && containsCache.containsBex(field, metrics)) {
            throw new BexException("BEX expressions inside Blue " + fieldName
                    + " fields are not supported at " + pointer);
        }
        rejectBexInStaticBlueDefinitionFields(field, pointer);
    }

    private void rejectBexInSchema(Schema schema, String pointer) {
        rejectSchemaNode(schema.getRequired(), pointer);
        rejectSchemaNode(schema.getMinLength(), pointer);
        rejectSchemaNode(schema.getMaxLength(), pointer);
        rejectSchemaNode(schema.getMinimum(), pointer);
        rejectSchemaNode(schema.getMaximum(), pointer);
        rejectSchemaNode(schema.getExclusiveMinimum(), pointer);
        rejectSchemaNode(schema.getExclusiveMaximum(), pointer);
        rejectSchemaNode(schema.getMultipleOf(), pointer);
        rejectSchemaNode(schema.getMinItems(), pointer);
        rejectSchemaNode(schema.getMaxItems(), pointer);
        rejectSchemaNode(schema.getUniqueItems(), pointer);
        rejectSchemaNode(schema.getMinFields(), pointer);
        rejectSchemaNode(schema.getMaxFields(), pointer);
        if (schema.getEnum() != null) {
            for (Node node : schema.getEnum()) {
                rejectSchemaNode(node, pointer);
            }
        }
    }

    private void rejectSchemaNode(Node node, String pointer) {
        if (node == null) {
            return;
        }
        FrozenNode frozen = FrozenNode.fromResolvedNode(node);
        if (containsCache.containsBex(frozen, metrics)) {
            throw new BexException("BEX expressions inside schema are not supported at " + pointer);
        }
        rejectBexInStaticBlueDefinitionFields(frozen, pointer);
    }

    private static Set<String> reservedBlueKeys() {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys,
                "name",
                "description",
                "type",
                "itemType",
                "keyType",
                "valueType",
                "value",
                "items",
                "blueId",
                "blue",
                "schema",
                "constraints",
                "mergePolicy",
                "properties",
                "contracts",
                "$previous",
                "$pos",
                "$replace",
                "$empty");
        return Collections.unmodifiableSet(keys);
    }

    private static final class FunctionSignature {
        private final List<BexCompiledProgram.ArgSpec> args;
        private final Map<String, BexCompiledProgram.ArgSpec> argsByName;

        private FunctionSignature(List<BexCompiledProgram.ArgSpec> args) {
            this.args = args;
            Map<String, BexCompiledProgram.ArgSpec> byName = new LinkedHashMap<>();
            for (BexCompiledProgram.ArgSpec arg : args) {
                byName.put(arg.name(), arg);
            }
            this.argsByName = Collections.unmodifiableMap(byName);
        }

        private List<BexCompiledProgram.ArgSpec> args() {
            return args;
        }

        private BexCompiledProgram.ArgSpec arg(String name) {
            return argsByName.get(name);
        }
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }

    private static final class CallSite {
        private final String target;
        private final BexSourcePath sourcePath;

        private CallSite(String target, BexSourcePath sourcePath) {
            this.target = target;
            this.sourcePath = sourcePath;
        }
    }

}
