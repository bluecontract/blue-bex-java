package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgramCache;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static blue.bex.test.BexTestFixtures.bi;
import static blue.bex.test.BexTestFixtures.defaultContext;
import static blue.bex.test.BexTestFixtures.defaultDocumentView;
import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.simple;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexIntrinsicTest {
    private static final Blue BLUE = new Blue();
    private static final String ECHO_BLUE_ID = "TestIntrinsicEcho";
    private static final String GAS_BLUE_ID = "TestIntrinsicGas";

    @Test
    void registeredIntrinsicReceivesBlueIdAndEvaluatedFields() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(ECHO_BLUE_ID, invocation -> {
                    invocation.chargeGas(7);
                    Map<String, BexValue> out = new LinkedHashMap<>();
                    out.put("blueId", BexValues.scalar(invocation.blueId()));
                    out.put("payload", invocation.field("payload"));
                    out.put("missingIsUndefined", BexValues.scalar(invocation.field("missing").isUndefined()));
                    out.put("fieldCount", BexValues.scalar(invocation.fields().size()));
                    return BexValues.map(out);
                })
                .build();

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho",
                "    payload:",
                "      $concat:",
                "        - hel",
                "        - lo",
                "    omitted:",
                "      $document: /missing"), defaultContext());

        assertEquals(m("blueId", ECHO_BLUE_ID,
                "payload", "hello",
                "missingIsUndefined", true,
                "fieldCount", bi(1)), simple(result.value()));
        assertTrue(result.gasUsed() >= 7);
    }

    @Test
    void intrinsicCanBeRegisteredByAnnotatedTypeClass() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(AnnotatedEchoIntrinsic.class, invocation -> invocation.field("payload"))
                .build();

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho",
                "    payload: ok"), defaultContext());

        assertEquals("ok", simple(result.value()));
    }

    @Test
    void intrinsicTypeClassRegistrationRequiresTypeBlueId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> BexEngine.builder()
                .intrinsic(UnannotatedIntrinsic.class, invocation -> BexValues.scalar(true)));

        assertTrue(ex.getMessage().contains("@TypeBlueId"));
    }

    @Test
    void intrinsicTypeCanBeInlineBlueTypeDefinition() {
        Node typeNode = BLUE.yamlToNode(yaml(
                "description: Test intrinsic operation shape",
                "x:",
                "  type: Text"));
        String inlineBlueId = FrozenNode.fromResolvedNode(typeNode).blueId();
        BexEngine engine = BexEngine.builder()
                .intrinsic(inlineBlueId, invocation -> {
                    Map<String, BexValue> out = new LinkedHashMap<>();
                    out.put("blueId", BexValues.scalar(invocation.blueId()));
                    out.put("x", invocation.field("x"));
                    return BexValues.map(out);
                })
                .build();

        BexExecutionResult result = engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      description: Test intrinsic operation shape",
                "      x:",
                "        type: Text",
                "    x:",
                "      $literal: ok"), defaultContext());

        assertEquals(m("blueId", inlineBlueId, "x", "ok"), simple(result.value()));
    }

    @Test
    void unsupportedIntrinsicFailsAtCompileTime() {
        BexException ex = assertThrows(BexException.class, () -> BexEngine.builder().build().compile(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho")));

        assertTrue(ex.getMessage().contains("Unsupported intrinsic BlueId: " + ECHO_BLUE_ID));
    }

    @Test
    void cachedProgramStillRequiresSupportInCurrentEngine() {
        BexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        BexProgramSource source = source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicEcho");
        BexEngine withSupport = BexEngine.builder()
                .cache(cache)
                .intrinsic(ECHO_BLUE_ID, invocation -> BexValues.scalar(true))
                .build();
        withSupport.compile(source);

        BexEngine withoutSupport = BexEngine.builder()
                .cache(cache)
                .build();
        BexException ex = assertThrows(BexException.class, () -> withoutSupport.compile(source));
        assertTrue(ex.getMessage().contains("Unsupported intrinsic BlueId: " + ECHO_BLUE_ID));
    }

    @Test
    void intrinsicProcessorGasChargeIsEnforced() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(GAS_BLUE_ID, invocation -> {
                    invocation.chargeGas(25);
                    return BexValues.scalar(true);
                })
                .build();
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .gasLimit(10)
                .build();

        BexException ex = assertThrows(BexException.class, () -> engine.compileAndExecute(source(
                "type: Blue/BEX Program",
                "expr:",
                "  $intrinsic:",
                "    type:",
                "      blueId: TestIntrinsicGas"), context));

        assertTrue(ex.getMessage().contains("BEX gas exhausted"));
    }

    @Test
    void intrinsicTypeMustBeStatic() {
        BexEngine engine = BexEngine.builder()
                .intrinsic(ECHO_BLUE_ID, invocation -> BexValues.scalar(true))
                .build();
        Node program = stepExpr(op("$intrinsic", obj(
                "type", obj("blueId", op("$concat", list("TestIntrinsic", "Echo"))))));

        BexException ex = assertThrows(BexException.class,
                () -> engine.compile(BexProgramSource.inline(frozen(program))));

        assertTrue(ex.getMessage().contains("BEX expressions inside static Blue patterns"));
    }

    private static BexProgramSource source(String... lines) {
        Node node = BLUE.yamlToNode(yaml(lines));
        return BexProgramSource.inline(FrozenNode.fromResolvedNode(node));
    }

    private static String yaml(String... lines) {
        return String.join("\n", lines);
    }

    @TypeBlueId(ECHO_BLUE_ID)
    private static final class AnnotatedEchoIntrinsic {
    }

    private static final class UnannotatedIntrinsic {
    }
}
