package blue.bex.compile;

import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.api.BexProgramSource;
import blue.bex.runtime.BexRuntime;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexCompileBoundaryTest {
    @Test
    void immutableApiTypesAdaptToCompileOwnedViews() {
        FrozenNode node = FrozenNode.fromResolvedNode(new Node().value("value"));
        BexProgramSource source = BexProgramSource.expression(node);
        BexCompilationInput input = source;
        BexIntrinsicCatalog catalog = BexIntrinsicRegistry.empty();

        assertTrue(input.isExpression());
        assertSame(node, input.programNode());
        assertFalse(input.definitionNode().isPresent());
        assertFalse(input.entry().isPresent());
        assertFalse(catalog.supports("unsupported"));
    }

    @Test
    void publicCompilerBoundaryNamesOnlyCompileOwnedViews() throws Exception {
        Method compile = BexCompilerRuntimeAccess.class.getMethod(
                "compile",
                BexCompilationInput.class,
                blue.bex.result.BexMetricsRecorder.class,
                BexIntrinsicCatalog.class,
                String.class);

        assertFalse(Modifier.isPublic(BexCompiler.class.getModifiers()));
        assertEquals(BexCompilationInput.class,
                compile.getParameterTypes()[0]);
        assertEquals(BexIntrinsicCatalog.class,
                compile.getParameterTypes()[2]);
    }

    @Test
    void cacheIdentityRetainsSourceKindAcrossBoundary() {
        FrozenNode node = FrozenNode.fromResolvedNode(new Node().value("value"));

        BexCompiledProgramKey full = BexCompiledProgramKey.from(
                BexProgramSource.inline(node));
        BexCompiledProgramKey expression = BexCompiledProgramKey.from(
                BexProgramSource.expression(node));

        assertEquals(BexCompilationInput.Kind.FULL_PROGRAM, full.kind());
        assertEquals(BexCompilationInput.Kind.EXPRESSION, expression.kind());
        assertNotEquals(full, expression);
    }

    @Test
    void concreteRuntimeImplementsTheCompileOwnedExecutionPort()
            throws Exception {
        Method execute = BexCompiledProgramRuntimeAccess.class.getMethod(
                "execute", BexCompiledProgram.class,
                BexExecutionMachine.class);

        assertTrue(BexExecutionMachine.class.isAssignableFrom(
                BexRuntime.class));
        assertEquals(BexExecutionMachine.class,
                execute.getParameterTypes()[1]);
        for (Method method : BexCompiledProgram.class.getMethods()) {
            assertFalse(method.getReturnType() == BexExecutionMachine.class
                    || method.getReturnType() == CompiledExpression.class
                    || method.getReturnType() == CompiledStatement.class);
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == BexExecutionMachine.class
                        || parameter == CompiledExpression.class
                        || parameter == CompiledStatement.class);
            }
        }
    }
}
