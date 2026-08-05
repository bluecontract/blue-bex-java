package blue.bex.compile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed, immutable compiler catalog for the BEX 2.0 operator surface.
 *
 * <p>The catalog is deliberately package-private: it is compiler metadata,
 * not an extension registry. Adding an entry is therefore a specification and
 * conformance change, not a host-side registration operation.</p>
 */
final class BexOperatorCatalog {
    static final int OPERATOR_COUNT = 86;

    enum Role {
        EXPRESSION(true, false),
        STATEMENT(false, true),
        EXPRESSION_AND_STATEMENT(true, true);

        private final boolean expression;
        private final boolean statement;

        Role(boolean expression, boolean statement) {
            this.expression = expression;
            this.statement = statement;
        }

        boolean supportsExpression() {
            return expression;
        }

        boolean supportsStatement() {
            return statement;
        }
    }

    enum CompilerFamily {
        LITERAL,
        READ,
        TYPE_AND_IDENTITY,
        TEXT,
        LOGIC_AND_COMPARISON,
        NUMERIC,
        OBJECT_AND_LIST,
        COLLECTION_QUERY,
        RESULT,
        CONTROL,
        INTRINSIC,
        LOCAL_STATEMENT,
        CONTROL_STATEMENT,
        EFFECT_STATEMENT
    }

    enum RuntimeFamily {
        LITERAL_VALUE,
        HOST_VIEW,
        FRAME_LOOKUP,
        TYPE_MATCHING,
        SCALAR_CONVERSION,
        IDENTITY,
        TEXT_PROCESSING,
        LOGICAL,
        NUMERIC,
        VALUE_COLLECTION,
        COLLECTION_QUERY,
        POINTER,
        RESULT_ACCUMULATOR,
        FUNCTION,
        INTRINSIC,
        FRAME_MUTATION,
        STATEMENT_CONTROL,
        RESULT_EFFECT,
        FAILURE
    }

    static final class Entry {
        private final String canonicalName;
        private final Role role;
        private final String specificationSection;
        private final String operandGrammar;
        private final Set<String> staticOperands;
        private final Set<String> dynamicOperands;
        private final String evaluationContract;
        private final CompilerFamily compilerFamily;
        private final RuntimeFamily runtimeFamily;
        private final Set<String> fixturePaths;
        private final Set<String> vectorIdentifiers;

        private Entry(String canonicalName,
                      Role role,
                      String specificationSection,
                      String operandGrammar,
                      String evaluationContract,
                      CompilerFamily compilerFamily,
                      RuntimeFamily runtimeFamily) {
            this.canonicalName = required(canonicalName, "canonicalName");
            this.role = required(role, "role");
            this.specificationSection = required(
                    specificationSection, "specificationSection");
            this.operandGrammar = required(
                    operandGrammar, "operandGrammar");
            OperandMetadata operands = operandMetadata(canonicalName);
            this.staticOperands = operands.staticOperands;
            this.dynamicOperands = operands.dynamicOperands;
            this.evaluationContract = required(
                    evaluationContract, "evaluationContract");
            this.compilerFamily = required(
                    compilerFamily, "compilerFamily");
            this.runtimeFamily = required(runtimeFamily, "runtimeFamily");
            Coverage coverage = coverage(canonicalName);
            this.fixturePaths = coverage.fixturePaths;
            this.vectorIdentifiers = coverage.vectorIdentifiers;
        }

        String canonicalName() {
            return canonicalName;
        }

        Role role() {
            return role;
        }

        String specificationSection() {
            return specificationSection;
        }

        String operandGrammar() {
            return operandGrammar;
        }

        Set<String> staticOperands() {
            return staticOperands;
        }

        Set<String> dynamicOperands() {
            return dynamicOperands;
        }

        String evaluationContract() {
            return evaluationContract;
        }

        CompilerFamily compilerFamily() {
            return compilerFamily;
        }

        RuntimeFamily runtimeFamily() {
            return runtimeFamily;
        }

        Set<String> fixturePaths() {
            return fixturePaths;
        }

        Set<String> vectorIdentifiers() {
            return vectorIdentifiers;
        }
    }

    private static final class OperandMetadata {
        private final Set<String> staticOperands;
        private final Set<String> dynamicOperands;

        private OperandMetadata(String staticOperands,
                                String dynamicOperands) {
            this.staticOperands = immutableNames(staticOperands);
            this.dynamicOperands = immutableNames(dynamicOperands);
        }
    }

    private static final class Coverage {
        private final Set<String> fixturePaths;
        private final Set<String> vectorIdentifiers;

        private Coverage(String fixturePaths, String vectorIdentifiers) {
            this.fixturePaths = immutableNames(fixturePaths);
            this.vectorIdentifiers = immutableNames(vectorIdentifiers);
            if (this.fixturePaths.isEmpty()) {
                throw new ExceptionInInitializerError(
                        "BEX operator coverage must name an executable fixture");
            }
            if (this.vectorIdentifiers.isEmpty()) {
                throw new ExceptionInInitializerError(
                        "BEX operator coverage must name a normative vector");
            }
        }
    }

    private static final Map<String, Entry> BY_NAME;
    private static final List<Entry> ENTRIES;

    static {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        add(entries, "$add", Role.EXPRESSION, "6.7",
                "[expression, expression+]", "eager-left-to-right",
                CompilerFamily.NUMERIC, RuntimeFamily.NUMERIC);
        add(entries, "$and", Role.EXPRESSION, "6.6",
                "[expression*]", "short-circuit-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$appendChange", Role.STATEMENT, "7.6",
                "{op, path, val?}", "op-and-path-eager; val-skipped-for-remove",
                CompilerFamily.EFFECT_STATEMENT, RuntimeFamily.RESULT_EFFECT);
        add(entries, "$appendChanges", Role.STATEMENT, "7.7",
                "expression", "eager",
                CompilerFamily.EFFECT_STATEMENT, RuntimeFamily.RESULT_EFFECT);
        add(entries, "$appendEvent", Role.STATEMENT, "7.8",
                "expression", "eager",
                CompilerFamily.EFFECT_STATEMENT, RuntimeFamily.RESULT_EFFECT);
        add(entries, "$appendEvents", Role.STATEMENT, "7.8",
                "expression", "eager",
                CompilerFamily.EFFECT_STATEMENT, RuntimeFamily.RESULT_EFFECT);
        add(entries, "$binding", Role.EXPRESSION, "6.3",
                "name/path | {name, path?}", "eager",
                CompilerFamily.READ, RuntimeFamily.HOST_VIEW);
        add(entries, "$boolean", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.SCALAR_CONVERSION);
        add(entries, "$call", Role.EXPRESSION_AND_STATEMENT, "6.10; 7.2",
                "{function, args}", "args-eager-in-canonical-name-order",
                CompilerFamily.CONTROL, RuntimeFamily.FUNCTION);
        add(entries, "$changeset", Role.EXPRESSION, "6.9",
                "ignored", "no-runtime-operands",
                CompilerFamily.RESULT, RuntimeFamily.RESULT_ACCUMULATOR);
        add(entries, "$choose", Role.EXPRESSION, "6.10",
                "{cond, then, else?}", "condition-eager; selected-branch-only",
                CompilerFamily.CONTROL, RuntimeFamily.LOGICAL);
        add(entries, "$coalesce", Role.EXPRESSION, "6.6",
                "[expression*]", "short-circuit-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$concat", Role.EXPRESSION, "6.5",
                "[expression*]", "eager-left-to-right",
                CompilerFamily.TEXT, RuntimeFamily.TEXT_PROCESSING);
        add(entries, "$const", Role.EXPRESSION, "6.3",
                "name | {name, path?}", "static-name; dynamic-path-eager",
                CompilerFamily.READ, RuntimeFamily.FRAME_LOOKUP);
        add(entries, "$currentContract", Role.EXPRESSION, "6.3",
                "pointer", "eager",
                CompilerFamily.READ, RuntimeFamily.HOST_VIEW);
        add(entries, "$default", Role.EXPRESSION, "6.6",
                "[expression*]", "short-circuit-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$divide", Role.EXPRESSION, "6.7",
                "[expression, expression+]", "eager-left-to-right",
                CompilerFamily.NUMERIC, RuntimeFamily.NUMERIC);
        add(entries, "$document", Role.EXPRESSION, "6.3",
                "pointer | {path, view?}", "eager",
                CompilerFamily.READ, RuntimeFamily.HOST_VIEW);
        add(entries, "$empty", Role.EXPRESSION, "6.6",
                "expression", "eager",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$emptyList", Role.EXPRESSION, "6.2",
                "ignored", "no-runtime-operands",
                CompilerFamily.LITERAL, RuntimeFamily.LITERAL_VALUE);
        add(entries, "$emptyObject", Role.EXPRESSION, "6.2",
                "ignored", "no-runtime-operands",
                CompilerFamily.LITERAL, RuntimeFamily.LITERAL_VALUE);
        add(entries, "$entries", Role.EXPRESSION, "6.8",
                "expression", "eager",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$eq", Role.EXPRESSION, "6.6",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$event", Role.EXPRESSION, "6.3",
                "pointer", "eager",
                CompilerFamily.READ, RuntimeFamily.HOST_VIEW);
        add(entries, "$events", Role.EXPRESSION, "6.9",
                "ignored", "no-runtime-operands",
                CompilerFamily.RESULT, RuntimeFamily.RESULT_ACCUMULATOR);
        add(entries, "$exists", Role.EXPRESSION, "6.6",
                "expression", "eager",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$fail", Role.EXPRESSION_AND_STATEMENT, "7.11",
                "expression | {message}", "message-eager-then-terminal",
                CompilerFamily.CONTROL, RuntimeFamily.FAILURE);
        add(entries, "$failIf", Role.STATEMENT, "7.10",
                "{cond, message}", "condition-eager; message-only-when-truthy",
                CompilerFamily.CONTROL_STATEMENT, RuntimeFamily.FAILURE);
        add(entries, "$filter", Role.EXPRESSION, "6.8.1",
                "{in, item, key?, index?, where}", "input-eager; where-per-item",
                CompilerFamily.COLLECTION_QUERY, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$find", Role.EXPRESSION, "6.8.1",
                "{in, item, key?, index?, where}", "short-circuit-per-item",
                CompilerFamily.COLLECTION_QUERY, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$findEntry", Role.EXPRESSION, "6.8.1",
                "{in, item, key?, index?, where}", "short-circuit-per-item",
                CompilerFamily.COLLECTION_QUERY, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$flatMap", Role.EXPRESSION, "6.8.1",
                "{in, item, key?, index?, expr}", "input-eager; expr-per-item",
                CompilerFamily.COLLECTION_QUERY, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$forEach", Role.STATEMENT, "7.5",
                "{in, item, key?, index?, do}", "input-eager; body-per-item",
                CompilerFamily.CONTROL_STATEMENT, RuntimeFamily.STATEMENT_CONTROL);
        add(entries, "$get", Role.EXPRESSION, "6.3",
                "{object, key}", "eager-object-then-key",
                CompilerFamily.READ, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$gt", Role.EXPRESSION, "6.6",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$gte", Role.EXPRESSION, "6.6",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$hasKey", Role.EXPRESSION, "6.8",
                "{object, key}", "eager-object-then-key",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$if", Role.STATEMENT, "7.4",
                "{cond, then?, else?}", "condition-eager; selected-branch-only",
                CompilerFamily.CONTROL_STATEMENT, RuntimeFamily.STATEMENT_CONTROL);
        add(entries, "$includes", Role.EXPRESSION, "6.8.1",
                "{list, val}", "operands-eager; comparison-short-circuit",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$integer", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.SCALAR_CONVERSION);
        add(entries, "$intrinsic", Role.EXPRESSION, "6.11",
                "{type: static-blue, payload-field: expression*}",
                "static-type; payload-eager-in-canonical-name-order; host-dispatch-last",
                CompilerFamily.INTRINSIC, RuntimeFamily.INTRINSIC);
        add(entries, "$is", Role.EXPRESSION, "6.4",
                "{node, pattern: static-blue}", "node-eager; pattern-static",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.TYPE_MATCHING);
        add(entries, "$isEmpty", Role.EXPRESSION, "6.6",
                "expression", "eager",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$isKind", Role.EXPRESSION, "6.4",
                "{val, kind: static-kind-or-list}", "val-eager; kind-static",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.TYPE_MATCHING);
        add(entries, "$join", Role.EXPRESSION, "6.5",
                "{list, separator}", "eager-list-then-separator",
                CompilerFamily.TEXT, RuntimeFamily.TEXT_PROCESSING);
        add(entries, "$keys", Role.EXPRESSION, "6.8",
                "expression", "eager",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$kind", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.TYPE_MATCHING);
        add(entries, "$let", Role.STATEMENT, "7.3",
                "{name, expr} | {vars, order?}",
                "single-eager; multi-parallel-unless-explicitly-ordered",
                CompilerFamily.LOCAL_STATEMENT, RuntimeFamily.FRAME_MUTATION);
        add(entries, "$list", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.SCALAR_CONVERSION);
        add(entries, "$listConcat", Role.EXPRESSION, "6.8",
                "[expression*]", "eager-left-to-right",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$listGet", Role.EXPRESSION, "6.8",
                "{list, index, default?}", "list-and-index-eager; default-only-when-missing",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$literal", Role.EXPRESSION, "2.6; 6.10",
                "static-blue-value", "no-nested-runtime-evaluation",
                CompilerFamily.LITERAL, RuntimeFamily.LITERAL_VALUE);
        add(entries, "$lt", Role.EXPRESSION, "6.6",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$lte", Role.EXPRESSION, "6.6",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$map", Role.EXPRESSION, "6.8.1",
                "{in, item, key?, index?, expr}", "input-eager; expr-per-item",
                CompilerFamily.COLLECTION_QUERY, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$merge", Role.EXPRESSION, "6.8",
                "[expression*]", "eager-left-to-right",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$multiply", Role.EXPRESSION, "6.7",
                "[expression, expression+]", "eager-left-to-right",
                CompilerFamily.NUMERIC, RuntimeFamily.NUMERIC);
        add(entries, "$ne", Role.EXPRESSION, "6.6",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$nodeBlueId", Role.EXPRESSION, "6.4; 11.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.IDENTITY);
        add(entries, "$not", Role.EXPRESSION, "6.6",
                "expression", "eager",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$null", Role.EXPRESSION, "6.2",
                "ignored", "no-runtime-operands",
                CompilerFamily.LITERAL, RuntimeFamily.LITERAL_VALUE);
        add(entries, "$number", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.SCALAR_CONVERSION);
        add(entries, "$object", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.SCALAR_CONVERSION);
        add(entries, "$objectFromEntries", Role.EXPRESSION, "6.8",
                "expression", "eager",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$objectSet", Role.EXPRESSION, "6.8",
                "{object, key, val}", "eager-object-then-key-then-val",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$or", Role.EXPRESSION, "6.6",
                "[expression*]", "short-circuit-left-to-right",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$pointerGet", Role.EXPRESSION, "6.8",
                "{object, path, default?}", "object-and-path-eager; default-only-when-missing",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.POINTER);
        add(entries, "$pointerJoin", Role.EXPRESSION, "6.5; 9.5",
                "[expression*]", "eager-left-to-right",
                CompilerFamily.TEXT, RuntimeFamily.POINTER);
        add(entries, "$pointerSet", Role.EXPRESSION, "6.8",
                "{object, path, op?, val?}", "object-path-op-eager; val-skipped-for-remove",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.POINTER);
        add(entries, "$processingEvent", Role.EXPRESSION, "6.3",
                "pointer", "eager",
                CompilerFamily.READ, RuntimeFamily.HOST_VIEW);
        add(entries, "$reduce", Role.EXPRESSION, "6.8.1",
                "{in, acc, init, item, key?, index?, expr}",
                "input-then-init-eager; expr-per-item",
                CompilerFamily.COLLECTION_QUERY, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$resultValue", Role.EXPRESSION, "6.9; 10.4",
                "pointer", "eager",
                CompilerFamily.RESULT, RuntimeFamily.RESULT_ACCUMULATOR);
        add(entries, "$return", Role.STATEMENT, "7.9",
                "expression | empty", "expression-eager-when-present; terminal",
                CompilerFamily.CONTROL_STATEMENT, RuntimeFamily.STATEMENT_CONTROL);
        add(entries, "$returnIf", Role.STATEMENT, "7.10",
                "{cond, expr?}", "condition-eager; expr-only-when-truthy",
                CompilerFamily.CONTROL_STATEMENT, RuntimeFamily.STATEMENT_CONTROL);
        add(entries, "$set", Role.STATEMENT, "7.3",
                "{name, expr}", "expression-eager-before-assignment",
                CompilerFamily.LOCAL_STATEMENT, RuntimeFamily.FRAME_MUTATION);
        add(entries, "$size", Role.EXPRESSION, "6.8",
                "expression", "eager",
                CompilerFamily.OBJECT_AND_LIST, RuntimeFamily.VALUE_COLLECTION);
        add(entries, "$sliceAfter", Role.EXPRESSION, "6.5",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.TEXT, RuntimeFamily.TEXT_PROCESSING);
        add(entries, "$some", Role.EXPRESSION, "6.8.1",
                "{in, item, key?, index?, where}", "short-circuit-per-item",
                CompilerFamily.COLLECTION_QUERY, RuntimeFamily.COLLECTION_QUERY);
        add(entries, "$split", Role.EXPRESSION, "6.5",
                "{text, separator, limit?}", "eager-text-then-separator-then-limit",
                CompilerFamily.TEXT, RuntimeFamily.TEXT_PROCESSING);
        add(entries, "$startsWith", Role.EXPRESSION, "6.5",
                "[expression, expression]", "eager-left-to-right",
                CompilerFamily.TEXT, RuntimeFamily.TEXT_PROCESSING);
        add(entries, "$steps", Role.EXPRESSION, "6.3",
                "step.path | {step, path?}", "eager",
                CompilerFamily.READ, RuntimeFamily.HOST_VIEW);
        add(entries, "$subtract", Role.EXPRESSION, "6.7",
                "[expression, expression+]", "eager-left-to-right",
                CompilerFamily.NUMERIC, RuntimeFamily.NUMERIC);
        add(entries, "$text", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.SCALAR_CONVERSION);
        add(entries, "$truthy", Role.EXPRESSION, "6.6",
                "expression", "eager",
                CompilerFamily.LOGIC_AND_COMPARISON, RuntimeFamily.LOGICAL);
        add(entries, "$unwrap", Role.EXPRESSION, "6.4",
                "expression", "eager",
                CompilerFamily.TYPE_AND_IDENTITY, RuntimeFamily.SCALAR_CONVERSION);
        add(entries, "$var", Role.EXPRESSION, "6.3",
                "name | {name, path?}", "static-name; dynamic-path-eager",
                CompilerFamily.READ, RuntimeFamily.FRAME_LOOKUP);

        if (entries.size() != OPERATOR_COUNT) {
            throw new ExceptionInInitializerError(
                    "BEX operator catalog must contain exactly "
                            + OPERATOR_COUNT + " entries, found " + entries.size());
        }
        BY_NAME = Collections.unmodifiableMap(entries);
        ENTRIES = Collections.unmodifiableList(
                new ArrayList<>(entries.values()));
    }

    private BexOperatorCatalog() {
    }

    static List<Entry> entries() {
        return ENTRIES;
    }

    static Entry find(String canonicalName) {
        return BY_NAME.get(canonicalName);
    }

    static boolean supportsExpression(String canonicalName) {
        Entry entry = find(canonicalName);
        return entry != null && entry.role().supportsExpression();
    }

    static boolean supportsStatement(String canonicalName) {
        Entry entry = find(canonicalName);
        return entry != null && entry.role().supportsStatement();
    }

    private static void add(Map<String, Entry> entries,
                            String canonicalName,
                            Role role,
                            String specificationSection,
                            String operandGrammar,
                            String evaluationContract,
                            CompilerFamily compilerFamily,
                            RuntimeFamily runtimeFamily) {
        if (!canonicalName.startsWith("$")) {
            throw new ExceptionInInitializerError(
                    "BEX operator name must start with $: " + canonicalName);
        }
        Entry entry = new Entry(canonicalName, role, specificationSection,
                operandGrammar, evaluationContract, compilerFamily,
                runtimeFamily);
        if (entries.put(canonicalName, entry) != null) {
            throw new ExceptionInInitializerError(
                    "Duplicate BEX operator catalog entry: " + canonicalName);
        }
    }

    /**
     * Returns the authored operands that are static and/or evaluated at
     * runtime. A name present in both sets accepts either authored scalar data
     * or a dynamic BEX expression. Empty sets are intentional for operators
     * whose bodies are ignored.
     */
    private static OperandMetadata operandMetadata(String operator) {
        switch (operator) {
            case "$changeset":
            case "$emptyList":
            case "$emptyObject":
            case "$events":
            case "$null":
                return operands("", "");
            case "$literal":
                return operands("body", "");
            case "$binding":
                return operands("selector,name,path", "name,path");
            case "$const":
            case "$var":
                return operands("name,path", "path");
            case "$document":
                return operands("path,view", "path");
            case "$currentContract":
            case "$event":
            case "$processingEvent":
            case "$resultValue":
                return operands("path", "path");
            case "$steps":
                return operands("selector,step,path", "step,path");
            case "$get":
            case "$hasKey":
                return operands("key", "object,key");
            case "$is":
                return operands("pattern", "node");
            case "$isKind":
                return operands("kind", "val");
            case "$call":
                return operands("function,args.keys", "args.values");
            case "$intrinsic":
                return operands("type,payload.keys", "payload.values");
            case "$let":
                return operands("name,vars.keys,order", "expr,vars.values");
            case "$set":
                return operands("name", "expr");
            case "$forEach":
                return operands("item,key,index", "in,do");
            case "$filter":
            case "$find":
            case "$findEntry":
            case "$some":
                return operands("item,key,index", "in,where");
            case "$flatMap":
            case "$map":
                return operands("item,key,index", "in,expr");
            case "$reduce":
                return operands("acc,item,key,index", "in,init,expr");
            case "$appendChange":
                return operands("op,path", "op,path,val");
            case "$pointerSet":
                return operands("op,path", "object,op,path,val");
            case "$pointerGet":
                return operands("path", "object,path,default");
            case "$objectSet":
                return operands("key", "object,key,val");
            case "$if":
            case "$choose":
                return operands("", "cond,then,else");
            case "$returnIf":
                return operands("", "cond,expr");
            case "$failIf":
                return operands("", "cond,message");
            case "$listGet":
                return operands("", "list,index,default");
            case "$split":
                return operands("", "text,separator,limit");
            case "$join":
                return operands("", "list,separator");
            case "$includes":
                return operands("", "list,val");
            case "$appendChanges":
            case "$appendEvent":
            case "$appendEvents":
            case "$boolean":
            case "$empty":
            case "$entries":
            case "$exists":
            case "$integer":
            case "$isEmpty":
            case "$keys":
            case "$kind":
            case "$list":
            case "$nodeBlueId":
            case "$not":
            case "$number":
            case "$object":
            case "$objectFromEntries":
            case "$return":
            case "$size":
            case "$text":
            case "$truthy":
            case "$unwrap":
            case "$fail":
                return operands("", "value");
            case "$add":
            case "$and":
            case "$coalesce":
            case "$concat":
            case "$default":
            case "$divide":
            case "$eq":
            case "$gt":
            case "$gte":
            case "$listConcat":
            case "$lt":
            case "$lte":
            case "$merge":
            case "$multiply":
            case "$ne":
            case "$or":
            case "$pointerJoin":
            case "$sliceAfter":
            case "$startsWith":
            case "$subtract":
                return operands("", "operands");
            default:
                throw new ExceptionInInitializerError(
                        "Missing operand metadata for " + operator);
        }
    }

    private static OperandMetadata operands(String staticOperands,
                                            String dynamicOperands) {
        return new OperandMetadata(staticOperands, dynamicOperands);
    }

    private static Coverage coverage(String operator) {
        switch (operator) {
            case "$add": return coverage("operators/bex-op-add.yaml,operators/bex-op-reduce.yaml", "BEX-E-08,BEX-E-11");
            case "$and": return coverage("operators/bex-op-and.yaml", "BEX-E-07");
            case "$appendChange": return coverage("h/bex-h-04.yaml,operators/bex-op-changeset.yaml,s/bex-s-02.yaml,s/bex-s-03.yaml,s/bex-s-05.yaml", "BEX-H-04,BEX-S-05,BEX-S-02,BEX-S-03");
            case "$appendChanges": return coverage("operators/bex-op-appendchanges.yaml", "BEX-S-02");
            case "$appendEvent": return coverage("g/bex-g-10.yaml,operators/bex-op-events.yaml,r/bex-r-06.yaml,s/bex-s-01.yaml,s/bex-s-04.yaml", "BEX-G-10,BEX-S-04,BEX-R-06,BEX-S-01");
            case "$appendEvents": return coverage("operators/bex-op-appendevents.yaml", "BEX-S-04");
            case "$binding": return coverage("e/bex-e-02.yaml", "BEX-E-02");
            case "$boolean": return coverage("operators/bex-op-boolean.yaml", "BEX-E-08");
            case "$call": return coverage("c/bex-c-04.yaml", "BEX-C-04");
            case "$changeset": return coverage("operators/bex-op-changeset.yaml", "BEX-S-05");
            case "$choose": return coverage("operators/bex-op-choose.yaml", "BEX-E-07");
            case "$coalesce": return coverage("operators/bex-op-coalesce.yaml", "BEX-E-07");
            case "$concat": return coverage("g/bex-g-02.yaml,g/bex-g-05.yaml,r/bex-r-07.yaml", "BEX-G-02,BEX-G-05,BEX-R-07");
            case "$const": return coverage("c/bex-c-06.yaml,e/bex-e-02.yaml", "BEX-C-06,BEX-E-02");
            case "$currentContract": return coverage("e/bex-e-02.yaml", "BEX-E-02");
            case "$default": return coverage("operators/bex-op-default.yaml", "BEX-E-07");
            case "$divide": return coverage("e/bex-e-08.yaml", "BEX-E-08");
            case "$document": return coverage("e/bex-e-01.yaml,e/bex-e-05.yaml,e/bex-e-10.yaml,g/bex-g-11.yaml,h/bex-h-01.yaml,h/bex-h-02.yaml,h/bex-h-05.yaml,operators/bex-op-coalesce.yaml,operators/bex-op-default.yaml,r/bex-r-01.yaml,r/bex-r-02.yaml,r/bex-r-03.yaml,r/bex-r-04.yaml,r/bex-r-06.yaml,r/bex-r-08.yaml", "BEX-E-01,BEX-E-05,BEX-E-10,BEX-G-11,BEX-H-01,BEX-H-02,BEX-H-05,BEX-E-07,BEX-R-01,BEX-R-02,BEX-R-03,BEX-R-04,BEX-R-06,BEX-R-08");
            case "$empty": return coverage("operators/bex-op-empty.yaml", "BEX-E-06");
            case "$emptyList": return coverage("operators/bex-op-emptylist.yaml", "BEX-E-06");
            case "$emptyObject": return coverage("operators/bex-op-emptyobject.yaml", "BEX-E-06");
            case "$entries": return coverage("operators/bex-op-entries.yaml", "BEX-E-09");
            case "$eq": return coverage("e/bex-e-14.yaml,g/bex-g-08.yaml", "BEX-E-14,BEX-G-08");
            case "$event": return coverage("e/bex-e-02.yaml,h/bex-h-06.yaml", "BEX-E-02,BEX-H-06");
            case "$events": return coverage("operators/bex-op-events.yaml", "BEX-S-04");
            case "$exists": return coverage("e/bex-e-05.yaml,r/bex-r-02.yaml,r/bex-r-03.yaml", "BEX-E-05,BEX-R-02,BEX-R-03");
            case "$fail": return coverage("e/bex-e-07.yaml,g/bex-g-03.yaml,operators/bex-op-and.yaml,operators/bex-op-choose.yaml,operators/bex-op-coalesce.yaml,s/bex-s-02.yaml,s/bex-s-06.yaml", "BEX-E-07,BEX-G-03,BEX-S-02,BEX-S-06");
            case "$failIf": return coverage("operators/bex-op-failif.yaml", "BEX-S-06");
            case "$filter": return coverage("operators/bex-op-filter.yaml", "BEX-E-11");
            case "$find": return coverage("operators/bex-op-find.yaml", "BEX-E-11");
            case "$findEntry": return coverage("operators/bex-op-findentry.yaml", "BEX-E-11");
            case "$flatMap": return coverage("operators/bex-op-flatmap.yaml", "BEX-E-11");
            case "$forEach": return coverage("s/bex-s-01.yaml", "BEX-S-01");
            case "$get": return coverage("operators/bex-op-get.yaml", "BEX-E-02");
            case "$gt": return coverage("operators/bex-op-filter.yaml,operators/bex-op-find.yaml,operators/bex-op-findentry.yaml,operators/bex-op-gt.yaml,operators/bex-op-some.yaml", "BEX-E-11,BEX-E-08");
            case "$gte": return coverage("operators/bex-op-gte.yaml", "BEX-E-08");
            case "$hasKey": return coverage("operators/bex-op-haskey.yaml", "BEX-E-11");
            case "$if": return coverage("s/bex-s-01.yaml", "BEX-S-01");
            case "$includes": return coverage("operators/bex-op-includes.yaml", "BEX-E-11");
            case "$integer": return coverage("e/bex-e-08.yaml", "BEX-E-08");
            case "$intrinsic": return coverage("g/bex-g-09.yaml,g/bex-g-14.yaml", "BEX-G-09,BEX-G-14");
            case "$is": return coverage("c/bex-c-06.yaml", "BEX-C-06");
            case "$isEmpty": return coverage("operators/bex-op-isempty.yaml", "BEX-E-06");
            case "$isKind": return coverage("operators/bex-op-iskind.yaml", "BEX-E-10");
            case "$join": return coverage("operators/bex-op-join.yaml", "BEX-E-08");
            case "$keys": return coverage("e/bex-e-09.yaml,r/bex-r-02.yaml", "BEX-E-09,BEX-R-02");
            case "$kind": return coverage("e/bex-e-10.yaml,r/bex-r-02.yaml", "BEX-E-10,BEX-R-02");
            case "$let": return coverage("e/bex-e-02.yaml,e/bex-e-13.yaml,s/bex-s-07.yaml", "BEX-E-02,BEX-E-13,BEX-S-07");
            case "$list": return coverage("operators/bex-op-list.yaml", "BEX-E-08");
            case "$listConcat": return coverage("operators/bex-op-listconcat.yaml", "BEX-E-11");
            case "$listGet": return coverage("operators/bex-op-listget.yaml", "BEX-E-11");
            case "$literal": return coverage("c/bex-c-03.yaml", "BEX-C-03");
            case "$lt": return coverage("operators/bex-op-lt.yaml", "BEX-E-08");
            case "$lte": return coverage("operators/bex-op-lte.yaml", "BEX-E-08");
            case "$map": return coverage("e/bex-e-11.yaml,g/bex-g-07.yaml", "BEX-E-11,BEX-G-07");
            case "$merge": return coverage("operators/bex-op-merge.yaml", "BEX-E-11");
            case "$multiply": return coverage("g/bex-g-06.yaml", "BEX-G-06");
            case "$ne": return coverage("operators/bex-op-ne.yaml", "BEX-E-14");
            case "$nodeBlueId": return coverage("e/bex-e-14.yaml,r/bex-r-04.yaml,r/bex-r-05.yaml", "BEX-E-14,BEX-R-04,BEX-R-05");
            case "$not": return coverage("operators/bex-op-not.yaml", "BEX-E-06");
            case "$null": return coverage("e/bex-e-03.yaml,e/bex-e-05.yaml,e/bex-e-06.yaml", "BEX-E-03,BEX-E-05,BEX-E-06");
            case "$number": return coverage("h/bex-h-05.yaml", "BEX-H-05");
            case "$object": return coverage("operators/bex-op-object.yaml", "BEX-E-08");
            case "$objectFromEntries": return coverage("operators/bex-op-objectfromentries.yaml", "BEX-E-11");
            case "$objectSet": return coverage("operators/bex-op-objectset.yaml", "BEX-E-12");
            case "$or": return coverage("e/bex-e-07.yaml,g/bex-g-03.yaml", "BEX-E-07,BEX-G-03");
            case "$pointerGet": return coverage("e/bex-e-03.yaml", "BEX-E-03");
            case "$pointerJoin": return coverage("e/bex-e-04.yaml", "BEX-E-04");
            case "$pointerSet": return coverage("e/bex-e-12.yaml,g/bex-g-04.yaml", "BEX-E-12,BEX-G-04");
            case "$processingEvent": return coverage("e/bex-e-02.yaml,h/bex-h-06.yaml", "BEX-E-02,BEX-H-06");
            case "$reduce": return coverage("operators/bex-op-reduce.yaml", "BEX-E-11");
            case "$resultValue": return coverage("h/bex-h-04.yaml,s/bex-s-05.yaml", "BEX-H-04,BEX-S-05");
            case "$return": return coverage("e/bex-e-02.yaml,e/bex-e-13.yaml,h/bex-h-04.yaml,operators/bex-op-changeset.yaml,operators/bex-op-events.yaml,s/bex-s-05.yaml", "BEX-E-02,BEX-E-13,BEX-H-04,BEX-S-05,BEX-S-04");
            case "$returnIf": return coverage("s/bex-s-06.yaml", "BEX-S-06");
            case "$set": return coverage("c/bex-c-07.yaml", "BEX-C-07");
            case "$size": return coverage("r/bex-r-02.yaml", "BEX-R-02");
            case "$sliceAfter": return coverage("operators/bex-op-sliceafter.yaml", "BEX-E-08");
            case "$some": return coverage("operators/bex-op-some.yaml", "BEX-E-11");
            case "$split": return coverage("operators/bex-op-split.yaml", "BEX-E-08");
            case "$startsWith": return coverage("operators/bex-op-startswith.yaml", "BEX-E-08");
            case "$steps": return coverage("e/bex-e-02.yaml", "BEX-E-02");
            case "$subtract": return coverage("operators/bex-op-subtract.yaml", "BEX-E-08");
            case "$text": return coverage("e/bex-e-08.yaml", "BEX-E-08");
            case "$truthy": return coverage("e/bex-e-06.yaml", "BEX-E-06");
            case "$unwrap": return coverage("operators/bex-op-unwrap.yaml", "BEX-E-08");
            case "$var": return coverage("e/bex-e-02.yaml,e/bex-e-11.yaml,e/bex-e-13.yaml,g/bex-g-07.yaml,operators/bex-op-filter.yaml,operators/bex-op-find.yaml,operators/bex-op-findentry.yaml,operators/bex-op-flatmap.yaml,operators/bex-op-reduce.yaml,operators/bex-op-some.yaml,s/bex-s-01.yaml,s/bex-s-07.yaml", "BEX-E-02,BEX-E-11,BEX-E-13,BEX-G-07,BEX-S-01,BEX-S-07");
            default:
                throw new ExceptionInInitializerError(
                        "Missing fixture/vector coverage for " + operator);
        }
    }

    private static Coverage coverage(String fixturePaths,
                                     String vectorIdentifiers) {
        return new Coverage(fixturePaths, vectorIdentifiers);
    }

    private static Set<String> immutableNames(String csv) {
        if (csv.isEmpty()) {
            return Collections.emptySet();
        }
        List<String> names = Arrays.asList(csv.split(",", -1));
        LinkedHashSet<String> uniqueNames = new LinkedHashSet<>();
        for (String name : names) {
            if (name.isEmpty()) {
                throw new ExceptionInInitializerError(
                        "BEX operator metadata contains an empty name");
            }
            if (!uniqueNames.add(name)) {
                throw new ExceptionInInitializerError(
                        "Duplicate BEX operator metadata name: " + name);
            }
        }
        return Collections.unmodifiableSet(uniqueNames);
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new ExceptionInInitializerError(
                    "BEX operator catalog " + field + " must be non-empty");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new ExceptionInInitializerError(
                    "BEX operator catalog " + field + " must be non-null");
        }
        return value;
    }
}
