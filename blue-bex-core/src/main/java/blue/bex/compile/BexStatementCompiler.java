package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.result.BexMetricsRecorder;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Statement recognition, validation, and IR construction. */
abstract class BexStatementCompiler extends BexExpressionCompiler {
    BexStatementCompiler(BexMetricsRecorder metrics, BexIntrinsicCatalog intrinsics) {
        super(metrics, intrinsics);
    }

    List<CompiledStatement> compileStatements(FrozenNode node, CompileScope scope, String pointer) {
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

    CompiledStatement compileStatement(FrozenNode statement, CompileScope scope, String pointer) {
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
            if (!BexOperatorCatalog.supportsStatement(op)) {
                throw new BexException("Unknown statement operator: " + op);
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

    CompiledStatement compileMultiLet(FrozenNode body, CompileScope scope, String pointer) {
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

    List<String> orderedLetNames(FrozenNode orderNode, FrozenNode varsNode, String pointer) {
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

    boolean isEmptyStatement(FrozenNode statement, String pointer) {
        return statement == null || statement.isEmptyNode();
    }


    void validateStatementBody(String op, FrozenNode body) {
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
}
