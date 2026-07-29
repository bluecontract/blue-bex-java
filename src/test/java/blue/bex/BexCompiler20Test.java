package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.compile.BexCompiledProgramKey;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexCompiler20Test {
    @Test
    void explicitEmptyExprWinsOverDoByFieldPresence() {
        BexExecutionResult result = runStep(obj(
                "expr", null,
                "do", list(op("$appendEvent", "wrong"))
        ), defaultContext());

        assertTrue(result.events().events().isEmpty());
    }

    @Test
    void validatesUnselectedRootBodyBeforeExecution() {
        Node program = obj(
                "expr", "selected",
                "do", list(op("$unknownStatement", true))
        );

        BexException failure = assertThrows(BexException.class, () -> compile(program));
        assertTrue(failure.getMessage().contains("$unknownStatement"));
        assertTrue(failure.sourcePath().isPresent());
    }

    @Test
    void rejectsNullEmptyAndBluePlaceholderStatements() {
        assertThrows(BexException.class,
                () -> compile(stepDo(list((Object) null))));
        assertThrows(BexException.class,
                () -> compile(stepDo(list(obj()))));
        BexException placeholder = assertThrows(BexException.class,
                () -> compile(stepDo(list(obj("$empty", true)))));
        assertTrue(placeholder.getMessage().contains("$empty"));
    }

    @Test
    void objectAndCallArgumentsEvaluateInUnicodeCodePointOrder() {
        String supplementary = "\uD83D\uDE00";
        String privateUse = "\uE000";
        Node emitPrivate = obj("do", list(op("$appendEvent", "private")));
        Node emitSupplementary = obj("do", list(op("$appendEvent", "supplementary")));
        Node sink = obj(
                "args", obj(supplementary, obj(), privateUse, obj()),
                "expr", "done"
        );
        Node functions = obj(
                "emitPrivate", emitPrivate,
                "emitSupplementary", emitSupplementary,
                "sink", sink
        );

        BexExecutionResult objectOrder = runStep(obj(
                "functions", functions,
                "expr", obj(
                        supplementary, call("emitSupplementary"),
                        privateUse, call("emitPrivate")
                )
        ), defaultContext());
        assertEquals(l("private", "supplementary"),
                simple(objectOrder.events().asValue()));

        BexExecutionResult callOrder = runStep(obj(
                "functions", functions,
                "expr", op("$call", obj(
                        "function", "sink",
                        "args", obj(
                                supplementary, call("emitSupplementary"),
                                privateUse, call("emitPrivate")
                        )
                ))
        ), defaultContext());
        assertEquals(l("private", "supplementary"),
                simple(callOrder.events().asValue()));
    }

    @Test
    void recursionDiagnosticNamesReasonAndClosingCallPath() {
        Node program = obj(
                "functions", obj(
                        "f", obj("expr", call("g")),
                        "g", obj("expr", call("f"))
                ),
                "entry", "f"
        );

        BexException failure = assertThrows(BexException.class, () -> compile(program));
        assertTrue(failure.getMessage().contains("reason=recursive-call-graph"));
        assertTrue(failure.sourcePath().isPresent());
        assertEquals("$call", failure.sourcePath().get().operator());
        assertTrue(failure.sourcePath().get().pointer().contains("/functions/"));
    }

    @Test
    void comparisonArityFailsAtCompileTimeWithOperatorPath() {
        BexException failure = assertThrows(BexException.class,
                () -> compile(stepExpr(op("$eq", list(1)))));

        assertTrue(failure.getMessage().contains("exactly 2 operands"));
        assertEquals("$eq", failure.sourcePath().get().operator());
    }

    @Test
    void parallelLetPeersCompileButReadAsUninitialized() {
        Node program = stepDo(list(
                op("$let", obj("vars", obj(
                        "a", op("$var", "b"),
                        "b", 1
                )))
        ));

        assertDoesNotThrow(() -> compile(program));
        BexException failure = assertThrows(BexException.class,
                () -> runStep(program, defaultContext()));
        assertTrue(failure.getMessage().contains("uninitialized"));
    }

    @Test
    void collectionQueryBindingsDoNotLeakIntoFollowingStatements() {
        Node program = stepDo(list(
                op("$let", obj(
                        "name", "mapped",
                        "expr", op("$map", obj(
                                "in", list(1),
                                "item", "temporary",
                                "expr", op("$var", "temporary")
                        ))
                )),
                op("$return", op("$var", "temporary"))
        ));

        assertThrows(BexException.class, () -> compile(program));
    }

    @Test
    void stepsShortFormSplitsOnlyTheFirstDot() {
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .steps(BexStepResults.builder()
                        .put("Build", BexValues.fromSimple(m(
                                "a.b", "first-dot-only",
                                "a", m("b", "all-dots")
                        )))
                        .build())
                .gasLimit(1_000_000)
                .build();

        BexExecutionResult result = runStep(
                stepExpr(op("$steps", "Build.a.b")), context);
        assertEquals("first-dot-only", simple(result.value()));
    }

    @Test
    void failTreatsObjectAsMessageWrapperOnlyWhenMessageIsPresent() {
        BexException objectFailure = assertThrows(BexException.class,
                () -> runStep(stepDo(list(
                        op("$fail", obj("messageText", "boom"))
                )), defaultContext()));
        assertTrue(objectFailure.getMessage().contains("Value cannot be converted to text"));

        BexException wrapped = assertThrows(BexException.class,
                () -> runStep(stepDo(list(
                        op("$fail", obj(
                                "message", "wrapped",
                                "ignored", op("$integer", "not-an-integer")
                        ))
                )), defaultContext()));
        assertTrue(wrapped.getMessage().contains("wrapped"));
        assertFalse(wrapped.getMessage().contains("converted to integer"));
    }

    @Test
    void failExpressionCompilesLazilyAndFailsOnlyWhenEvaluated() {
        BexExecutionResult skipped = runStep(stepExpr(op("$or", list(
                true,
                op("$fail", "must-not-run")
        ))), defaultContext());
        assertEquals(true, simple(skipped.value()));

        BexException evaluated = assertThrows(BexException.class,
                () -> runStep(stepExpr(op("$fail", obj(
                        "message", "expression-failure",
                        "ignored", op("$integer", "not-an-integer")
                ))), defaultContext()));
        assertTrue(evaluated.getMessage().contains("expression-failure"));
        assertFalse(evaluated.getMessage().contains("converted to integer"));
        assertEquals("$fail", evaluated.sourcePath().get().operator());
    }

    @Test
    void intrinsicStaticTypeUsesExplicitBlueIdBeforeWrapperIdentity() {
        String explicitBlueId = "fixture-explicit-intrinsic";
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        explicitBlueId,
                        "test-compiler-intrinsics/1",
                        Collections.singletonMap("constantWork", 1L),
                        invocation -> {
                            invocation.charge(
                                    "constantWork",
                                    1L,
                                    "explicit-type-work");
                            return invocation.field("x");
                        })
                .build();
        Node program = stepExpr(op("$intrinsic", obj(
                "type", obj("blueId", explicitBlueId),
                "x", "ok"
        )));

        BexExecutionResult result = engine.compileAndExecute(
                BexProgramSource.inline(frozen(program)),
                defaultContext());

        assertEquals("ok", simple(result.value()));
    }

    @Test
    void cacheKeyIncludesCompileEnvironmentIdentity() {
        BexProgramSource source = BexProgramSource.inline(
                frozen(stepExpr(new Node().value("ok"))));
        assertNotEquals(
                BexCompiledProgramKey.from(source, "environment-a"),
                BexCompiledProgramKey.from(source, "environment-b"));
    }

    private static Node call(String function) {
        return op("$call", obj("function", function));
    }

    private static void compile(Node program) {
        BexEngine.builder().build().compile(
                BexProgramSource.inline(frozen(program)));
    }
}
