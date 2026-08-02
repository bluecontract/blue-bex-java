package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import blue.bex.test.TestBlue;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BexCompilerScalarNormalizationTest {
    private static final String TEXT_TYPE_BLUE_ID =
            BlueCoreTypeRegistry.INSTANCE.blueId("Text");
    private final TestBlue blue = new TestBlue();
    private final BexEngine engine = BexEngine.builder().language(blue.runtime()).build();

    @Test
    void blueAuthoredCoreTypedScalarsRemainBexScalars() {
        assertEquals("hello", simple(runYaml("expr: hello").value()));
        assertEquals(BigInteger.valueOf(42), simple(runYaml("expr: 42").value()));
        assertEquals(new BigDecimal("1.25"), simple(runYaml("expr: 1.25").value()));
        assertEquals(true, simple(runYaml("expr: true").value()));
    }

    @Test
    void blueAuthoredScalarShorthandWorksForVarConstBindingAndSteps() {
        BexExecutionResult result = runYaml(String.join("\n",
                "constants:",
                "  configured: constant-value",
                "do:",
                "  - $let:",
                "      name: local",
                "      expr: variable-value",
                "  - $return:",
                "      variable:",
                "        $var: local",
                "      constant:",
                "        $const: configured",
                "      binding:",
                "        $binding: external",
                "      step:",
                "        $steps: Build.result"));

        assertEquals(m(
                "variable", "variable-value",
                "constant", "constant-value",
                "binding", "binding-value",
                "step", "step-value"), simple(result.value()));
    }

    @Test
    void computedTypeAndValuePropertiesRemainAnObjectExpression() {
        Node computed = obj(
                "type", obj("blueId", TEXT_TYPE_BLUE_ID),
                "value", op("$concat", list("hel", "lo")));

        BexExecutionResult result = engine.compileAndExecute(
                BexProgramSource.expression(FrozenNode.fromResolvedNode(computed)),
                context());

        assertEquals("object", BexValues.kind(result.value()));
        assertEquals(TEXT_TYPE_BLUE_ID,
                result.value().get("type").get("blueId").asText());
        assertEquals("hello", result.value().get("value").asText());
    }

    private BexExecutionResult runYaml(String programBody) {
        Node program = blue.yamlToNode(programBody);
        return engine.compileAndExecute(
                BexProgramSource.inline(FrozenNode.fromResolvedNode(program)),
                context());
    }

    private BexExecutionContext context() {
        return BexExecutionContext.builder()
                .document(defaultDocumentView())
                .event(BexValues.fromSimple(m("kind", "Created")))
                .currentContract(BexValues.fromSimple(m("channel", "main")))
                .steps(BexStepResults.builder()
                        .put("Build", BexValues.fromSimple(m("result", "step-value")))
                        .build())
                .binding("external", BexValues.scalar("binding-value"))
                .gasLimit(1_000_000)
                .build();
    }
}
