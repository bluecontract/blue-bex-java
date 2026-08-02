package blue.bex.compile;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BexCompiledIrImmutabilityTest {
    private static final CompiledExpression FIRST_EXPRESSION =
            frame -> BexValues.scalar("first");
    private static final CompiledExpression SECOND_EXPRESSION =
            frame -> BexValues.scalar("second");
    private static final CompiledStatement FIRST_STATEMENT =
            frame -> Control.CONTINUE;
    private static final CompiledStatement SECOND_STATEMENT =
            frame -> Control.RETURN;

    @Test
    void compiledIrConstructionIsCompilerOwned() {
        Arrays.stream(BexCompiledProgram.class.getDeclaredConstructors())
                .forEach(constructor -> assertEquals(false,
                        Modifier.isPublic(constructor.getModifiers())));
        Arrays.stream(BexCompiledProgram.CompiledFunction.class
                        .getDeclaredConstructors())
                .forEach(constructor -> assertEquals(false,
                        Modifier.isPublic(constructor.getModifiers())));
        Arrays.stream(BexCompiledProgram.ArgSpec.class.getDeclaredConstructors())
                .forEach(constructor -> assertEquals(false,
                        Modifier.isPublic(constructor.getModifiers())));
    }

    @Test
    void compiledProgramAndFunctionCopyAndFreezeCollectionInputs()
            throws ReflectiveOperationException {
        List<BexCompiledProgram.ArgSpec> args = new ArrayList<>();
        args.add(new BexCompiledProgram.ArgSpec("arg", 0, null, "/arg"));
        List<CompiledStatement> statements = new ArrayList<>();
        statements.add(FIRST_STATEMENT);
        BexCompiledProgram.CompiledFunction function =
                new BexCompiledProgram.CompiledFunction(
                        "function", args, statements, null, 1);

        args.clear();
        statements.clear();
        assertEquals(1, function.args().size());
        List<?> storedStatements = field(function, "statements", List.class);
        assertEquals(Collections.singletonList(FIRST_STATEMENT), storedStatements);
        assertThrows(UnsupportedOperationException.class,
                () -> storedStatements.clear());

        Map<String, BexCompiledProgram.CompiledFunction> functions =
                new LinkedHashMap<>();
        functions.put("function", function);
        Map<String, BexValue> constants = new LinkedHashMap<>();
        constants.put("constant", BexValues.scalar("value"));
        Set<String> intrinsicIds = new LinkedHashSet<>();
        intrinsicIds.add("intrinsic");
        BexCompiledProgram program = new BexCompiledProgram(function,
                functions, constants, 1, "program", intrinsicIds);

        functions.clear();
        constants.clear();
        intrinsicIds.clear();
        assertEquals(1, program.functions().size());
        assertEquals(1, program.constants().size());
        assertEquals(1, program.requiredIntrinsicBlueIds().size());
        assertThrows(UnsupportedOperationException.class,
                () -> program.functions().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> program.constants().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> program.requiredIntrinsicBlueIds().clear());
    }

    @Test
    void callAndMultiLetCloneArrayInputs()
            throws ReflectiveOperationException {
        int[] callSlots = {1};
        CompiledExpression[] callExpressions = {FIRST_EXPRESSION};
        CallExpr call = new CallExpr("function", callSlots, callExpressions);
        callSlots[0] = 99;
        callExpressions[0] = SECOND_EXPRESSION;
        int[] storedCallSlots = field(call, "targetSlots", int[].class);
        CompiledExpression[] storedCallExpressions =
                field(call, "argExpressions", CompiledExpression[].class);
        assertNotSame(callSlots, storedCallSlots);
        assertNotSame(callExpressions, storedCallExpressions);
        assertArrayEquals(new int[]{1}, storedCallSlots);
        assertArrayEquals(new CompiledExpression[]{FIRST_EXPRESSION},
                storedCallExpressions);

        int[] letSlots = {2};
        CompiledExpression[] letExpressions = {FIRST_EXPRESSION};
        MultiLetStatement multiLet =
                new MultiLetStatement(letSlots, letExpressions, false);
        letSlots[0] = 88;
        letExpressions[0] = SECOND_EXPRESSION;
        int[] storedLetSlots = field(multiLet, "slots", int[].class);
        CompiledExpression[] storedLetExpressions =
                field(multiLet, "expressions", CompiledExpression[].class);
        assertNotSame(letSlots, storedLetSlots);
        assertNotSame(letExpressions, storedLetExpressions);
        assertArrayEquals(new int[]{2}, storedLetSlots);
        assertArrayEquals(new CompiledExpression[]{FIRST_EXPRESSION},
                storedLetExpressions);
    }

    @Test
    void expressionAndStatementNodesCopyAndFreezeListsMapsAndSets()
            throws ReflectiveOperationException {
        assertExpressionListCopied(
                input -> new CompareExpr(input, CompareOp.EQ), "expressions");
        assertExpressionListCopied(
                input -> new LogicalExpr(input, true), "expressions");
        assertExpressionListCopied(CoalesceExpr::new, "expressions");
        assertExpressionListCopied(
                input -> new NumericExpr(input, NumericOp.ADD), "expressions");
        assertExpressionListCopied(
                input -> new VariadicExpr(input, VariadicOp.CONCAT), "expressions");
        assertExpressionListCopied(PointerJoinExpr::new, "segments");
        assertExpressionListCopied(
                input -> new BinaryTextExpr(input, BinaryTextOp.STARTS_WITH),
                "expressions");
        assertExpressionListCopied(ListExpr::new, "items");

        List<CompiledStatement> branch = new ArrayList<>();
        branch.add(FIRST_STATEMENT);
        IfStatement ifStatement = new IfStatement(
                FIRST_EXPRESSION, branch, branch);
        ForEachStatement forEach = new ForEachStatement(
                FIRST_EXPRESSION, 0, -1, -1, branch);
        branch.set(0, SECOND_STATEMENT);
        assertFrozenSingleton(ifStatement, "thenStatements", FIRST_STATEMENT);
        assertFrozenSingleton(ifStatement, "elseStatements", FIRST_STATEMENT);
        assertFrozenSingleton(forEach, "body", FIRST_STATEMENT);

        Set<String> kinds = new LinkedHashSet<>();
        kinds.add("text");
        IsKindExpr isKind = new IsKindExpr(FIRST_EXPRESSION, kinds);
        kinds.clear();
        Set<?> storedKinds = field(isKind, "kinds", Set.class);
        assertEquals(Collections.singleton("text"), storedKinds);
        assertThrows(UnsupportedOperationException.class,
                () -> storedKinds.clear());

        List<String> segments = new ArrayList<>(Arrays.asList("a", "b"));
        ResolvedPointer pointer = new ResolvedPointer(
                "/a/b", "/a/b", segments);
        segments.clear();
        assertEquals(Arrays.asList("a", "b"), pointer.segments());
        assertThrows(UnsupportedOperationException.class,
                () -> pointer.segments().clear());

        Map<String, CompiledExpression> fields = new LinkedHashMap<>();
        fields.put("field", FIRST_EXPRESSION);
        ObjectExpr object = new ObjectExpr(fields);
        IntrinsicExpr intrinsic = new IntrinsicExpr(
                "intrinsic", BexValues.nullValue(), fields);
        fields.clear();
        assertFrozenMap(object, "fields");
        assertFrozenMap(intrinsic, "fields");
    }

    private static void assertExpressionListCopied(
            ExpressionListFactory factory, String fieldName)
            throws ReflectiveOperationException {
        List<CompiledExpression> input = new ArrayList<>();
        input.add(FIRST_EXPRESSION);
        Object node = factory.create(input);
        input.set(0, SECOND_EXPRESSION);
        List<?> stored = field(node, fieldName, List.class);
        assertEquals(Collections.singletonList(FIRST_EXPRESSION), stored);
        assertThrows(UnsupportedOperationException.class, () -> stored.clear());
    }

    private static void assertFrozenSingleton(
            Object target, String fieldName, Object expected)
            throws ReflectiveOperationException {
        List<?> stored = field(target, fieldName, List.class);
        assertEquals(Collections.singletonList(expected), stored);
        assertThrows(UnsupportedOperationException.class, () -> stored.clear());
    }

    private static void assertFrozenMap(Object target, String fieldName)
            throws ReflectiveOperationException {
        Map<?, ?> stored = field(target, fieldName, Map.class);
        assertEquals(1, stored.size());
        assertEquals(FIRST_EXPRESSION, stored.get("field"));
        assertThrows(UnsupportedOperationException.class, () -> stored.clear());
    }

    private static <T> T field(Object target, String name, Class<T> type)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private interface ExpressionListFactory {
        Object create(List<CompiledExpression> expressions);
    }
}
