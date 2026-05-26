package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.runtime.CompileScope;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledStatement;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.result.BexMetrics;
import blue.language.model.Node;
import blue.language.model.Schema;
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
    private static final Set<String> RESERVED_BLUE_KEYS = reservedBlueKeys();

    private final BexContainsCache containsCache = new BexContainsCache();
    private final BexMetrics metrics;
    private Map<String, FunctionSignature> functionSignatures = Collections.emptyMap();
    private Map<String, BexValue> constants = Collections.emptyMap();
    private String currentFunction = "$root";

    public BexCompiler(BexMetrics metrics) {
        this.metrics = metrics;
    }

    public BexCompiledProgram compile(blue.bex.api.BexProgramSource source) {
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
            return new BexCompiledProgram(root, Collections.emptyMap(), constants, scope.frameSize(), BexNodeIdentity.safeBlueId(step));
        }

        Map<String, BexValue> loadedConstants = new LinkedHashMap<>();
        loadConstants(loadedConstants, prop(definition, "constants"));
        loadConstants(loadedConstants, prop(step, "constants"));
        constants = Collections.unmodifiableMap(new LinkedHashMap<>(loadedConstants));

        Map<String, FrozenNode> functionNodes = new LinkedHashMap<>();
        loadFunctions(functionNodes, prop(definition, "functions"));
        loadFunctions(functionNodes, prop(step, "functions"));
        rejectRecursion(functionNodes);
        functionSignatures = compileFunctionSignatures(functionNodes);

        Map<String, BexCompiledProgram.CompiledFunction> compiledFunctions = new LinkedHashMap<>();
        for (String name : functionNodes.keySet()) {
            compiledFunctions.put(name, compileFunction(name, functionNodes.get(name), functionSignatures.get(name)));
        }

        FrozenNode stepExpr = meaningful(prop(step, "expr"));
        String entryName = source.entry().orElse(text(meaningful(prop(step, "entry"))));
        BexCompiledProgram.CompiledFunction root;
        int rootFrameSize = 0;
        if (entryName != null && !entryName.isEmpty()) {
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
        } else if (stepExpr != null) {
            currentFunction = "$root";
            root = new BexCompiledProgram.CompiledFunction("$root", Collections.<BexCompiledProgram.ArgSpec>emptyList(),
                    Collections.<CompiledStatement>emptyList(), compileExpr(stepExpr, new CompileScope(), "/expr"), 0);
        } else {
            CompileScope scope = new CompileScope();
            currentFunction = "$root";
            List<CompiledStatement> statements = compileStatements(meaningful(prop(step, "do")), scope, "/do");
            rootFrameSize = scope.frameSize();
            root = new BexCompiledProgram.CompiledFunction("$root", Collections.<BexCompiledProgram.ArgSpec>emptyList(), statements, null, rootFrameSize);
        }

        return new BexCompiledProgram(root, compiledFunctions, constants, rootFrameSize, BexNodeIdentity.safeBlueId(step));
    }

    private BexCompiledProgram.CompiledFunction compileFunction(String name, FrozenNode functionNode, FunctionSignature signature) {
        String previousFunction = currentFunction;
        currentFunction = name;
        CompileScope scope = new CompileScope();
        for (BexCompiledProgram.ArgSpec arg : signature.args()) {
            int slot = scope.declareOrGetSlot(arg.name());
            if (slot != arg.slot()) {
                throw new BexException("Internal function arg slot mismatch for " + name + "." + arg.name());
            }
        }
        FrozenNode functionExpr = meaningful(prop(functionNode, "expr"));
        CompiledExpression expression = functionExpr != null ? compileExpr(functionExpr, scope, "/functions/" + escape(name) + "/expr") : null;
        List<CompiledStatement> statements = expression == null
                ? compileStatements(meaningful(prop(functionNode, "do")), scope, "/functions/" + escape(name) + "/do")
                : Collections.<CompiledStatement>emptyList();
        currentFunction = previousFunction;
        return new BexCompiledProgram.CompiledFunction(name, signature.args(),
                statements, expression, scope.frameSize());
    }

    private Map<String, FunctionSignature> compileFunctionSignatures(Map<String, FrozenNode> functionNodes) {
        Map<String, FunctionSignature> signatures = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenNode> entry : functionNodes.entrySet()) {
            signatures.put(entry.getKey(), compileFunctionSignature(entry.getKey(), entry.getValue()));
        }
        return signatures;
    }

    private FunctionSignature compileFunctionSignature(String name, FrozenNode functionNode) {
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
                Collections.sort(names);
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
        for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
            constants.put(entry.getKey(), BexValues.frozen(entry.getValue()));
        }
    }

    private void loadFunctions(Map<String, FrozenNode> functions, FrozenNode node) {
        validatePlainObjectContainer(node, "functions");
        if (node == null || node.getProperties() == null) {
            return;
        }
        functions.putAll(node.getProperties());
    }

    private void rejectRecursion(Map<String, FrozenNode> functions) {
        Map<String, Set<String>> calls = new LinkedHashMap<>();
        for (String name : functions.keySet()) {
            Set<String> targets = new LinkedHashSet<>();
            FrozenNode function = functions.get(name);
            collectCalls(meaningful(prop(function, "expr")), targets);
            collectCalls(meaningful(prop(function, "do")), targets);
            calls.put(name, targets);
        }
        for (String name : calls.keySet()) {
            detectCycle(name, name, calls, new ArrayDeque<String>());
        }
    }

    private void detectCycle(String root, String current, Map<String, Set<String>> calls, ArrayDeque<String> stack) {
        if (stack.contains(current)) {
            throw new BexException("Recursive BEX function call rejected: " + current);
        }
        stack.push(current);
        for (String next : calls.get(current)) {
            if (root.equals(next)) {
                throw new BexException("Recursive BEX function call rejected: " + root);
            }
            if (calls.containsKey(next)) {
                detectCycle(root, next, calls, stack);
            }
        }
        stack.pop();
    }

    private void collectCalls(FrozenNode node, Set<String> calls) {
        if (node == null) {
            return;
        }
        if (isOperator(node, "$literal")) {
            return;
        }
        if (isOperator(node, "$is")) {
            collectCalls(prop(onlyValue(node), "node"), calls);
            return;
        }
        if (isOperator(node, "$call")) {
            FrozenNode body = onlyValue(node);
            String function = text(prop(body, "function"));
            if (function != null) {
                calls.add(function);
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode child : node.getProperties().values()) {
                collectCalls(child, calls);
            }
        }
        if (node.getItems() != null) {
            for (FrozenNode child : node.getItems()) {
                collectCalls(child, calls);
            }
        }
    }

    private List<CompiledStatement> compileStatements(FrozenNode node, CompileScope scope, String pointer) {
        if (node == null) {
            return Collections.emptyList();
        }
        if (node.getItems() == null) {
            throw new BexException("Statement body must be a list");
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
            return sourceStatement(currentFunction, pointer, "$return", new ReturnStatement(null));
        }
        if (statement.getProperties() == null) {
            throw new BexException("Statement must be an operator object at " + pointer);
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
        if (count != 1 || statement.getProperties().size() != 1) {
            throw new BexException("Statement must have exactly one $ operator at " + pointer);
        }
        String bodyPointer = pointer + "/" + escape(op);
        CompiledStatement compiled;
        if ("$let".equals(op)) {
            String name = requiredText(prop(body, "name"), "$let.name");
            int slot = scope.declareOrGetSlot(name);
            compiled = new LetStatement(slot, compileExpr(required(prop(body, "expr"), "$let.expr"), scope, bodyPointer + "/expr"));
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
            int slot = scope.declareOrGetSlot(itemName);
            int keySlot = keyName != null ? scope.declareOrGetSlot(keyName) : -1;
            int indexSlot = indexName != null ? scope.declareOrGetSlot(indexName) : -1;
            compiled = new ForEachStatement(compileExpr(required(prop(body, "in"), "$forEach.in"), scope, bodyPointer + "/in"),
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
        } else if ("$fail".equals(op)) {
            FrozenNode message = body != null && body.getProperties() != null ? prop(body, "message") : body;
            compiled = new FailStatement(compileExpr(message, scope, bodyPointer + "/message"));
        } else {
            throw new BexException("Unknown statement operator: " + op);
        }
        return sourceStatement(currentFunction, bodyPointer, op, compiled);
    }

    private boolean isEmptyStatement(FrozenNode statement, String pointer) {
        if (statement == null || statement.isEmptyNode()) {
            return true;
        }
        if (statement.getProperties() == null || !statement.getProperties().containsKey("$empty")) {
            return false;
        }
        if (statement.getProperties().size() == 1
                && !hasLanguageFields(statement)
                && statement.getItems() == null
                && (statement.getValue() == null || Boolean.TRUE.equals(statement.getValue()))
                && statement.getPreviousBlueId() == null
                && statement.getPosition() == null
                && isEmptyMarkerValue(statement.getProperties().get("$empty"))) {
            return true;
        }
        throw new BexException("Statement $empty placeholder must be exactly $empty: true at " + pointer);
    }

    private boolean isEmptyMarkerValue(FrozenNode node) {
        return node != null && (node.isEmptyNode() || isTrueScalar(node));
    }

    private boolean isTrueScalar(FrozenNode node) {
        return node != null
                && Boolean.TRUE.equals(node.getValue())
                && node.getProperties() == null
                && node.getItems() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null;
    }

    private CompiledExpression compileExpr(FrozenNode node, CompileScope scope, String pointer) {
        if (node == null) {
            return sourceExpr(currentFunction, pointer, null, new LiteralExpr(BexValues.nullValue()));
        }
        rejectBexInStaticBlueDefinitionFields(node, pointer);
        if (!containsCache.containsBex(node, metrics)) {
            return sourceExpr(currentFunction, pointer, null, new LiteralExpr(BexValues.frozen(node)));
        }
        if (node.getProperties() != null && node.getProperties().size() == 1) {
            String op = node.getProperties().keySet().iterator().next();
            FrozenNode body = node.getProperties().values().iterator().next();
            if (op.startsWith("$")) {
                BexSourcePath sourcePath = BexSourcePath.of(currentFunction, pointer + "/" + escape(op), op);
                try {
                    return new SourceExpr(sourcePath, compileOperator(op, body, scope, pointer + "/" + escape(op)));
                } catch (BexException ex) {
                    throw ex.withSourcePath(sourcePath);
                }
            }
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
                for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
                    fields.put(entry.getKey(), compileExpr(entry.getValue(), scope, pointer + "/" + escape(entry.getKey())));
                }
            }
            return sourceExpr(currentFunction, pointer, null, new ObjectExpr(fields));
        }
        return sourceExpr(currentFunction, pointer, null, new LiteralExpr(BexValues.frozen(node)));
    }

    private CompiledExpression compileOperator(String op, FrozenNode body, CompileScope scope, String pointer) {
        if ("$literal".equals(op)) return new LiteralExpr(BexValues.frozen(body));
        if ("$null".equals(op)) return new LiteralExpr(BexValues.nullValue());
        if ("$emptyObject".equals(op)) return new LiteralExpr(BexValues.map(Collections.<String, BexValue>emptyMap()));
        if ("$emptyList".equals(op)) return new LiteralExpr(BexValues.list(Collections.<BexValue>emptyList()));
        if ("$document".equals(op)) return documentExpr(body, scope, pointer);
        if ("$binding".equals(op)) return bindingExpr(body, scope, pointer);
        if ("$event".equals(op)) return contextPointerExpr(body, scope, ContextKind.EVENT, pointer);
        if ("$steps".equals(op)) return stepsExpr(body, scope, pointer);
        if ("$currentContract".equals(op)) return contextPointerExpr(body, scope, ContextKind.CURRENT_CONTRACT, pointer);
        if ("$var".equals(op)) return new VarExpr(scope.resolveSlot(requiredText(body, "$var")));
        if ("$const".equals(op)) {
            String name = requiredText(body, "$const");
            if (!constants.containsKey(name)) {
                throw new BexException("Unknown constant: " + name);
            }
            return new ConstExpr(name);
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
        if ("$choose".equals(op)) return new ChooseExpr(compileExpr(required(prop(body, "cond"), "$choose.cond"), scope, pointer + "/cond"), compileExpr(required(prop(body, "then"), "$choose.then"), scope, pointer + "/then"), prop(body, "else") != null ? compileExpr(prop(body, "else"), scope, pointer + "/else") : new LiteralExpr(BexValues.undefined()));
        if ("$call".equals(op)) return compileCall(body, scope, pointer);
        throw new BexException("Unknown expression operator: " + op);
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
            String path = dot >= 0 ? "/" + selector.substring(dot + 1).replace('.', '/') : "/";
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
                for (Map.Entry<String, FrozenNode> entry : argsNode.getProperties().entrySet()) {
                    String argName = entry.getKey();
                    BexCompiledProgram.ArgSpec arg = signature.arg(argName);
                    if (arg == null) {
                        throw new BexException("Unknown argument " + argName + " for function " + function);
                    }
                    providedArgs.add(argName);
                    targetSlots.add(arg.slot());
                    argExpressions.add(compileExpr(entry.getValue(), scope, pointer + "/args/" + escape(argName)));
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

    private FrozenNode meaningful(FrozenNode node) {
        return node == null || node.isEmptyNode() ? null : node;
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

    private String text(FrozenNode node) {
        return node != null && node.getValue() != null ? String.valueOf(node.getValue()) : null;
    }

    private boolean isOperator(FrozenNode node, String op) {
        return node != null
                && node.getProperties() != null
                && node.getProperties().size() == 1
                && node.getProperties().containsKey(op);
    }

    private FrozenNode onlyValue(FrozenNode node) {
        return node.getProperties().values().iterator().next();
    }

    private void addMetadataFields(Map<String, CompiledExpression> fields, FrozenNode node, CompileScope scope, String pointer) {
        if (node.getName() != null) {
            fields.put("name", new LiteralExpr(BexValues.scalar(node.getName())));
        }
        if (node.getDescription() != null) {
            fields.put("description", new LiteralExpr(BexValues.scalar(node.getDescription())));
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
            fields.put("value", new LiteralExpr(BexValues.scalar(node.getValue())));
        }
        if (node.getReferenceBlueId() != null) {
            fields.put("blueId", new LiteralExpr(BexValues.scalar(node.getReferenceBlueId())));
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
            fields.put("mergePolicy", new LiteralExpr(BexValues.scalar(node.getMergePolicy())));
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
                "$pos");
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

}
