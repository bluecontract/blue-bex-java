package blue.bex.api;

import blue.bex.gas.BexGasSchedule;
import blue.bex.gas.BexGasLedger;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bex20ApiSurfaceTest {
    private static final String[] BEX_1_GAS_ALIASES = {
            "expressionBase",
            "statementBase",
            "varRead",
            "pointerGetBase",
            "pointerSetBase",
            "objectSetBase",
            "appendChangeBase",
            "appendEventBase",
            "forEachItem",
            "functionCall",
            "estimatedSize",
            "gasConsumed"
    };

    @Test
    void intrinsicRegistrationAlwaysRequiresRegistryIdentityAndNamedWeights()
            throws Exception {
        assertThrows(
                NoSuchMethodException.class,
                () -> BexEngine.Builder.class.getMethod(
                        "intrinsic",
                        String.class,
                        BexIntrinsicProcessor.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> BexEngine.Builder.class.getMethod(
                        "intrinsic",
                        Class.class,
                        BexIntrinsicProcessor.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> BexIntrinsicRegistry.Builder.class.getMethod(
                        "register",
                        String.class,
                        BexIntrinsicProcessor.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> BexIntrinsicRegistry.Builder.class.getMethod(
                        "register",
                        Class.class,
                        BexIntrinsicProcessor.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> BexIntrinsicRegistry.class.getMethod(
                        "with",
                        String.class,
                        BexIntrinsicProcessor.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> BexIntrinsicRegistry.class.getMethod(
                        "with",
                        Class.class,
                        BexIntrinsicProcessor.class));

        assertThrows(
                NullPointerException.class,
                () -> BexIntrinsicRegistry.builder().register(
                        "test-type",
                        "test-registry/1",
                        null,
                        invocation -> BexValues.scalar(true)));

        BexIntrinsicRegistry first = BexIntrinsicRegistry.builder()
                .register(
                        "test-type",
                        "test-registry/1",
                        Collections.singletonMap("work", 1L),
                        invocation -> BexValues.scalar(true))
                .build();
        BexIntrinsicRegistry second = BexIntrinsicRegistry.builder()
                .register(
                        "test-type",
                        "test-registry/2",
                        Collections.singletonMap("work", 1L),
                        invocation -> BexValues.scalar(true))
                .build();
        assertNotEquals(first.identity(), second.identity());
    }

    @Test
    void gasScheduleExposesOnlyBex20CounterNames() {
        for (String alias : BEX_1_GAS_ALIASES) {
            assertThrows(
                    NoSuchFieldException.class,
                    () -> BexGasSchedule.class.getField(alias));
            assertThrows(
                    NoSuchMethodException.class,
                    () -> BexGasSchedule.Builder.class.getMethod(
                            alias, long.class));
        }
    }

    @Test
    void executionResultHasNoAggregateGasConstructor() {
        assertNoAggregateGasConstructor(BexExecutionResult.class);
        assertNoAggregateGasConstructor(BexGasLedger.class);
    }

    @Test
    void intrinsicInvocationHasNoOpaqueGasChargeMethod() {
        assertThrows(
                NoSuchMethodException.class,
                () -> BexIntrinsicInvocation.class.getMethod(
                        "chargeGas", long.class));
        for (Method method : BexIntrinsicInvocation.class.getMethods()) {
            assertFalse(
                    "chargeGas".equals(method.getName()),
                    "intrinsics must charge registry-declared named counters");
        }
    }

    @Test
    void intrinsicBoundaryExposesNoLedgerOrPortableGasEvidencePath()
            throws Exception {
        Method execute = BexIntrinsicProcessor.class.getMethod(
                "execute", BexIntrinsicInvocation.class);
        assertEquals(BexValue.class, execute.getReturnType());
        assertArrayEquals(
                new Class<?>[]{BexIntrinsicInvocation.class},
                execute.getParameterTypes());
        assertEquals(
                1,
                BexIntrinsicProcessor.class.getDeclaredMethods().length,
                "the intrinsic processor must expose only its BexValue "
                        + "execution boundary");

        for (Constructor<?> constructor
                : BexIntrinsicInvocation.class.getConstructors()) {
            assertNoForbiddenIntrinsicGasType(
                    constructor.toString(),
                    constructor.getParameterTypes());
            assertNoForbiddenIntrinsicGasSignature(
                    constructor.toGenericString());
        }
        for (Field field : BexIntrinsicInvocation.class.getFields()) {
            assertNoForbiddenIntrinsicGasType(
                    field.toString(), field.getType());
            assertNoForbiddenIntrinsicGasSignature(
                    field.toGenericString());
        }
        for (Method method : BexIntrinsicInvocation.class.getMethods()) {
            String normalized =
                    method.getName().toLowerCase(Locale.ROOT);
            assertFalse(
                    normalized.contains("submit")
                            || normalized.contains("merge")
                            || normalized.contains("openledger")
                            || normalized.contains("childledger")
                            || normalized.contains("gasledger")
                            || normalized.contains("ledgerhost")
                            || "host".equals(normalized)
                            || normalized.contains("chargegas")
                            || normalized.contains("gasevidence")
                            || normalized.contains("gasresult")
                            || (normalized.startsWith("set")
                            && normalized.contains("gas"))
                            || (normalized.contains("aggregate")
                            && normalized.contains("gas")),
                    method + " exposes a portable gas submission, merge, "
                            + "or aggregate-evidence path");
            assertNoForbiddenIntrinsicGasType(
                    method.toString(), method.getReturnType());
            assertNoForbiddenIntrinsicGasType(
                    method.toString(), method.getParameterTypes());
            assertNoForbiddenIntrinsicGasSignature(
                    method.toGenericString());
        }
    }

    @Test
    void recursiveSizeEstimatorAndMetricsAreAbsent() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("blue.bex.gas.BexSizeEstimator"));

        String[] removedMetricMethods = {
                "incrementSizeEstimateCalls",
                "incrementSizeEstimateCacheHits",
                "incrementSizeEstimateCacheMisses",
                "sizeEstimateCalls",
                "sizeEstimateCacheHits",
                "sizeEstimateCacheMisses"
        };
        for (String method : removedMetricMethods) {
            assertThrows(
                    NoSuchMethodException.class,
                    () -> BexMetrics.class.getMethod(method));
        }

        String[] mutableMetricMethods = {
                "incrementCompiledExecutions",
                "incrementCompileCacheHits",
                "incrementExpressionEvaluations",
                "incrementStatementExecutions",
                "incrementFunctionCalls",
                "incrementLoopIterations",
                "addCompileNanos",
                "addExecuteNanos"
        };
        for (String method : mutableMetricMethods) {
            assertThrows(
                    NoSuchMethodException.class,
                    () -> BexMetrics.class.getMethod(method));
        }
    }

    private static void assertNoAggregateGasConstructor(Class<?> type) {
        for (Constructor<?> constructor : type.getConstructors()) {
            boolean hasAggregateGasParameter = false;
            for (Class<?> parameter : constructor.getParameterTypes()) {
                hasAggregateGasParameter |= parameter == long.class;
            }
            assertFalse(
                    hasAggregateGasParameter,
                    type.getSimpleName()
                            + " must accept a named gas ledger or trace, "
                            + "not an aggregate gas integer");
        }
    }

    private static void assertNoForbiddenIntrinsicGasType(
            String api,
            Class<?>... types) {
        for (Class<?> type : types) {
            String name = type.getName();
            assertFalse(
                    "blue.language.processor.GasMeter$ChildGasLedger"
                            .equals(name)
                            || "blue.bex.gas.BexGasLedger".equals(name)
                            || "blue.bex.api.BexGasLedgerHost".equals(name)
                            || "blue.bex.gas.BexGasMeter".equals(name),
                    api + " exposes a child ledger, ledger host, or "
                            + "BEX gas meter");
        }
    }

    private static void assertNoForbiddenIntrinsicGasSignature(
            String signature) {
        assertFalse(
                signature.contains(
                        "blue.language.processor.GasMeter.ChildGasLedger")
                        || signature.contains(
                        "blue.language.processor.GasMeter$ChildGasLedger")
                        || signature.contains(
                        "blue.bex.gas.BexGasLedger")
                        || signature.contains(
                        "blue.bex.api.BexGasLedgerHost")
                        || signature.contains(
                        "blue.bex.gas.BexGasMeter"),
                signature + " exposes a child ledger, ledger host, or "
                        + "BEX gas meter");
    }
}
