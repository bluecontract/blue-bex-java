package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexFrozenWriter;
import blue.bex.value.BexNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static blue.bex.test.BexTestFixtures.bi;
import static blue.bex.test.BexTestFixtures.defaultContext;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.m;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.simple;
import static blue.bex.test.BexTestFixtures.stepExpr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexBlueTypeSupportTest {
    private static final Blue YAML_BLUE = new Blue();
    private final Blue blue = new Blue(blueId -> {
        if ("HotelOrderType".equals(blueId)) {
            return Collections.singletonList(YAML_BLUE.yamlToNode(yaml(
                    "status:",
                    "  type: Text")));
        }
        if ("RestaurantOrderType".equals(blueId)) {
            return Collections.singletonList(YAML_BLUE.yamlToNode(yaml(
                    "restaurantStatus:",
                    "  type: Text")));
        }
        return Collections.emptyList();
    });
    private final BexEngine engine = BexEngine.builder().blue(blue).build();

    @Test
    void functionArgAcceptsMatchingPrimitiveType() {
        BexExecutionResult result = run(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      amount:",
                "        type: Integer",
                "    expr:",
                "      $var: amount",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      amount: 400"));

        assertEquals(BigInteger.valueOf(400), simple(result.value()));
    }

    @Test
    void functionArgRejectsWrongPrimitiveTypeAtRuntime() {
        BexCompiledProgram program = compile(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      amount:",
                "        type: Integer",
                "    expr:",
                "      $var: amount",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      amount: \"400\""));

        BexException ex = assertThrows(BexException.class, () -> engine.execute(program, defaultContext()));
        assertTrue(ex.getMessage().contains("Function f argument amount does not match declared Blue pattern at /functions/f/args/amount"));
    }

    @Test
    void functionArgAcceptsStructuralObjectPattern() {
        BexExecutionResult result = run(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      request:",
                "        customerName:",
                "          type: Text",
                "          schema:",
                "            required: true",
                "        nights:",
                "          type: Integer",
                "          schema:",
                "            required: true",
                "    expr:",
                "      $var: request",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      request:",
                "        customerName: Jan",
                "        nights: 2"));

        assertEquals(m("customerName", "Jan", "nights", bi(2)), simple(result.value()));
    }

    @Test
    void functionArgRejectsMissingRequiredStructuralField() {
        BexCompiledProgram program = compile(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      request:",
                "        customerName:",
                "          type: Text",
                "          schema:",
                "            required: true",
                "        nights:",
                "          type: Integer",
                "          schema:",
                "            required: true",
                "    expr:",
                "      $var: request",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      request:",
                "        customerName: Jan"));

        assertThrows(BexException.class, () -> engine.execute(program, defaultContext()));
    }

    @Test
    void functionArgRejectsUnknownArgAtCompileTime() {
        assertThrows(BexException.class, () -> compile(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      amount:",
                "        type: Integer",
                "    expr:",
                "      $var: amount",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      amount: 400",
                "      extra: true")));
    }

    @Test
    void functionArgRejectsMissingArgAtCompileTime() {
        assertThrows(BexException.class, () -> compile(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      amount:",
                "        type: Integer",
                "    expr:",
                "      $var: amount",
                "expr:",
                "  $call:",
                "    function: f",
                "    args: {}")));
    }

    @Test
    void untypedArgStillWorks() {
        BexExecutionResult result = run(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      input: {}",
                "    expr:",
                "      $var: input",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      input:",
                "        anything: works"));

        assertEquals(m("anything", "works"), simple(result.value()));
    }

    @Test
    void isUsesNodeFieldInYamlSyntax() {
        BexExecutionResult result = run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: 400",
                "    pattern:",
                "      type: Integer"));

        assertEquals(true, simple(result.value()));
    }

    @Test
    void isUsesNodeFieldAndReturnsFalseForWrongPrimitiveInYamlSyntax() {
        BexExecutionResult result = run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: \"400\"",
                "    pattern:",
                "      type: Integer"));

        assertEquals(false, simple(result.value()));
    }

    @Test
    void isIntegerPatternExamplesMatchOnlyIntegerNodes() {
        assertEquals(true, simple(run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: 400",
                "    pattern:",
                "      type: Integer")).value()));
        assertEquals(true, simple(run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      $integer: \"400\"",
                "    pattern:",
                "      type: Integer")).value()));
        assertEquals(true, simple(run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      type: Integer",
                "      value: 400",
                "    pattern:",
                "      type: Integer")).value()));
        assertEquals(false, simple(run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: \"400\"",
                "    pattern:",
                "      type: Integer")).value()));
        assertEquals(false, simple(run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: 4.5",
                "    pattern:",
                "      type: Integer")).value()));
        assertEquals(false, simple(run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: true",
                "    pattern:",
                "      type: Integer")).value()));
        assertEquals(false, simple(run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      $document: /missing",
                "    pattern:",
                "      type: Integer")).value()));
    }

    @Test
    void oldIsValueShapeIsInvalidBlueYaml() {
        assertThrows(IllegalArgumentException.class, () -> blue.yamlToNode(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    value: 400",
                "    pattern:",
                "      type: Integer")));
    }

    @Test
    void joinUsesListFieldInYamlSyntax() {
        BexExecutionResult result = run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $join:",
                "    list:",
                "      - a",
                "      - b",
                "    separator: \":\""));

        assertEquals("a:b", simple(result.value()));
    }

    @Test
    void oldJoinItemsShapeIsInvalidBlueYaml() {
        assertThrows(IllegalArgumentException.class, () -> blue.yamlToNode(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $join:",
                "    items:",
                "      - a",
                "      - b",
                "    separator: \":\"")));
    }

    @Test
    void reservedArgNameFromInternalShapeFailsCompileTime() {
        BexException ex = assertThrows(BexException.class, () -> engine.compile(BexProgramSource.inline(FrozenNode.fromResolvedNode(obj(
                "type", "Blue/BEX Program",
                "functions", obj("f", obj(
                        "args", obj("value", obj()),
                        "expr", op("$var", "value"))),
                "expr", op("$call", obj(
                        "function", "f",
                        "args", obj("value", 1))))))));

        assertTrue(ex.getMessage().contains("reserved Blue key: value"));
    }

    @Test
    void reservedArgNameIsInvalidOrRejectedInYaml() {
        assertThrows(RuntimeException.class, () -> run(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      value: {}",
                "    expr:",
                "      $var: value",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      value: 1")));
    }

    @Test
    void contractsIsReservedAsFunctionArgumentName() {
        BexException ex = assertThrows(BexException.class, () -> engine.compile(BexProgramSource.inline(FrozenNode.fromResolvedNode(obj(
                "type", "Blue/BEX Program",
                "functions", obj("f", obj(
                        "args", obj("contracts", obj()),
                        "expr", op("$var", "contracts"))),
                "expr", op("$call", obj(
                        "function", "f",
                        "args", obj("contracts", 1))))))));

        assertTrue(ex.getMessage().contains("reserved Blue key: contracts"));
    }

    @Test
    void functionArgAcceptsMatchingBlueIdPattern() {
        Node hotelOrderPattern = pattern("blueId: HotelOrderBlueId");
        BexExecutionResult result = run(obj(
                "type", "Blue/BEX Program",
                "functions", obj("f", obj(
                        "args", obj("hotelOrder", hotelOrderPattern),
                        "expr", op("$is", obj(
                                "node", op("$var", "hotelOrder"),
                                "pattern", hotelOrderPattern)))),
                "expr", op("$call", obj(
                        "function", "f",
                        "args", obj("hotelOrder", hotelOrderPattern)))));

        assertEquals(true, simple(result.value()));
    }

    @Test
    void isReturnsTrueForComputedTypedObjectWithBlueIdPattern() {
        Node hotelOrderPattern = pattern("blueId: HotelOrderType");
        BexExecutionResult result = run(stepExpr(op("$is", obj(
                "node", obj(
                        "type", hotelOrderPattern,
                        "status", op("$literal", "confirmed")),
                "pattern", hotelOrderPattern))));

        assertEquals(true, simple(result.value()));
    }

    @Test
    void isReturnsFalseForComputedTypedObjectWithDifferentBlueIdPattern() {
        BexExecutionResult result = run(stepExpr(op("$is", obj(
                "node", obj(
                        "type", pattern("blueId: RestaurantOrderType"),
                        "status", op("$literal", "confirmed")),
                "pattern", pattern("blueId: HotelOrderType")))));

        assertEquals(false, simple(result.value()));
    }

    @Test
    void functionArgAcceptsComputedTypedObjectMatchingBlueIdPattern() {
        Node hotelOrderPattern = pattern("blueId: HotelOrderType");
        BexExecutionResult result = run(obj(
                "type", "Blue/BEX Program",
                "functions", obj("f", obj(
                        "args", obj("hotelOrder", hotelOrderPattern),
                        "expr", op("$is", obj(
                                "node", op("$var", "hotelOrder"),
                                "pattern", hotelOrderPattern)))),
                "expr", op("$call", obj(
                        "function", "f",
                        "args", obj("hotelOrder", obj(
                                "type", hotelOrderPattern,
                                "status", op("$literal", "confirmed")))))));

        assertEquals(true, simple(result.value()));
    }

    @Test
    void functionArgRejectsComputedTypedObjectWithDifferentBlueIdPattern() {
        BexCompiledProgram program = engine.compile(BexProgramSource.inline(FrozenNode.fromResolvedNode(obj(
                "type", "Blue/BEX Program",
                "functions", obj("f", obj(
                        "args", obj("hotelOrder", pattern("blueId: HotelOrderType")),
                        "expr", op("$var", "hotelOrder"))),
                "expr", op("$call", obj(
                        "function", "f",
                        "args", obj("hotelOrder", obj(
                                "type", pattern("blueId: RestaurantOrderType"),
                                "status", op("$literal", "confirmed")))))))));

        assertThrows(BexException.class, () -> engine.execute(program, defaultContext()));
    }

    @Test
    void isReturnsFalseForBlueIdReferenceWithSiblingFields() {
        BexExecutionResult result = run(stepExpr(op("$is", obj(
                "node", obj(
                        "blueId", "HotelOrderType",
                        "status", op("$literal", "confirmed")),
                "pattern", pattern("blueId: HotelOrderType")))));

        assertEquals(false, simple(result.value()));
    }

    @Test
    void isReturnsTrueForPrimitiveType() {
        assertEquals(true, simple(run(stepExpr(op("$is", obj(
                "node", 400,
                "pattern", pattern("type: Integer"))))).value()));
    }

    @Test
    void isReturnsFalseForWrongPrimitiveType() {
        assertEquals(false, simple(run(stepExpr(op("$is", obj(
                "node", "400",
                "pattern", pattern("type: Integer"))))).value()));
    }

    @Test
    void isReturnsTrueForStructuralPattern() {
        assertEquals(true, simple(run(stepExpr(op("$is", obj(
                "node", obj("customerName", "Jan", "nights", 2),
                "pattern", pattern(
                        "customerName:",
                        "  type: Text",
                        "  schema:",
                        "    required: true",
                        "nights:",
                        "  type: Integer",
                        "  schema:",
                        "    required: true"))))).value()));
    }

    @Test
    void isReturnsTrueForStructuralPatternWithComputedFields() {
        assertEquals(true, simple(run(stepExpr(op("$is", obj(
                "node", obj(
                        "customerName", op("$literal", "Jan"),
                        "nights", op("$integer", "2")),
                "pattern", pattern(
                        "customerName:",
                        "  type: Text",
                        "  schema:",
                        "    required: true",
                        "nights:",
                        "  type: Integer",
                        "  schema:",
                        "    required: true"))))).value()));
    }

    @Test
    void isReturnsFalseForStructuralPatternMismatch() {
        assertEquals(false, simple(run(stepExpr(op("$is", obj(
                "node", obj("customerName", "Jan", "nights", "2"),
                "pattern", pattern(
                        "customerName:",
                        "  type: Text",
                        "  schema:",
                        "    required: true",
                        "nights:",
                        "  type: Integer",
                        "  schema:",
                        "    required: true"))))).value()));
    }

    @Test
    void isReturnsFalseForUndefinedValue() {
        assertEquals(false, simple(run(stepExpr(op("$is", obj(
                "node", op("$document", "/missing"),
                "pattern", pattern("type: Text"))))).value()));
    }

    @Test
    void computedObjectPreservesValueTypeLanguageField() {
        BexExecutionResult result = run(stepExpr(obj(
                "valueType", pattern("type: Integer"),
                "amount", op("$integer", "2"))));
        Node node = BexNodeWriter.toNode(result.value());

        assertEquals(BigInteger.valueOf(2), node.getProperties().get("amount").getValue());
        assertTrue(node.getValueType() != null);
        assertTrue(node.getValueType().getType() != null);
    }

    @Test
    void bexInsideTypeLanguageFieldIsRejected() {
        assertThrows(BexException.class, () -> run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      type:",
                "        $choose:",
                "          cond: true",
                "          then: Integer",
                "          else: Text",
                "      value: 10",
                "    pattern:",
                "      type: Integer")));
    }

    @Test
    void bexInsideSchemaIsRejectedAtCompileTime() {
        assertThrows(BexException.class, () -> run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: abc",
                "    pattern:",
                "      type: Text",
                "      schema:",
                "        minLength:",
                "          $integer: \"1\"")));
    }

    @Test
    void bexInsideIsPatternIsRejectedAtCompileTime() {
        assertThrows(BexException.class, () -> run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node: 10",
                "    pattern:",
                "      type:",
                "        $choose:",
                "          cond: true",
                "          then: Integer",
                "          else: Text")));
    }

    @Test
    void bexInsideFunctionArgumentPatternIsRejectedAtCompileTime() {
        assertThrows(BexException.class, () -> compile(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      input:",
                "        type:",
                "          $choose:",
                "            cond: true",
                "            then: Integer",
                "            else: Text",
                "    expr:",
                "      $var: input",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      input: abc")));
    }

    @Test
    void bexInsideFunctionArgumentSchemaIsRejectedAtCompileTime() {
        assertThrows(BexException.class, () -> compile(yaml(
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      input:",
                "        type: Text",
                "        schema:",
                "          minLength:",
                "            $integer: \"1\"",
                "    expr:",
                "      $var: input",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      input: abc")));
    }

    @Test
    void bexInsideNestedTypeBlueIdIsRejectedAtCompileTime() {
        Node expr = new Node()
                .type(obj("blueId", op("$concat", list("Hotel", "OrderType"))))
                .properties("status", new Node().value("confirmed"));

        assertThrows(BexException.class, () -> run(stepExpr(expr)));
    }

    @Test
    void staticAuthoredTypeStillWorks() {
        BexExecutionResult result = run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $is:",
                "    node:",
                "      type: Integer",
                "      value: 10",
                "    pattern:",
                "      type: Integer"));

        assertEquals(true, simple(result.value()));
    }

    @Test
    void computedScalarTypeIsNotTreatedAsAuthoredTypeAlias() {
        BexValue value = BexValues.fromSimple(m(
                "type", "Integer",
                "value", BigInteger.TEN));
        Node node = BexNodeWriter.toNode(value);
        Node pattern = blue.yamlToNode("type: Integer");

        assertTrue(node.getType() != null);
        assertEquals("Integer", node.getType().getValue());
        assertFalse(node.getType().isInlineValue());
        assertFalse(blue.nodeMatchesType(node, pattern));
    }

    @Test
    void nameAndDescriptionAreNeutralForBluePatternMatching() {
        BexExecutionResult result = run(stepExpr(op("$is", obj(
                "node", obj(
                        "name", "Runtime name",
                        "description", "Runtime description",
                        "type", pattern("type: Integer"),
                        "value", op("$literal", 10)),
                "pattern", pattern(
                        "name: Pattern name",
                        "description: Pattern description",
                        "type: Integer")))));

        assertEquals(true, simple(result.value()));
    }

    @Test
    void entryFunctionWithArgsFailsAtCompileTime() {
        BexException ex = assertThrows(BexException.class, () -> compile(yaml(
                "type: Blue/BEX Program",
                "entry: f",
                "functions:",
                "  f:",
                "    args:",
                "      amount:",
                "        type: Integer",
                "    expr:",
                "      $var: amount")));

        assertTrue(ex.getMessage().contains("Entry function f declares arguments but entry invocation provides none"));
    }

    @Test
    void staticPatternsRejectBexOperators() {
        Node recursiveLookingPattern = obj("$call", obj("function", "f"));

        assertThrows(BexException.class, () -> run(obj(
                "type", "Blue/BEX Program",
                "functions", obj("f", obj(
                        "args", obj("x", recursiveLookingPattern),
                        "expr", op("$is", obj(
                                "node", op("$literal", recursiveLookingPattern),
                                "pattern", recursiveLookingPattern)))),
                "expr", op("$call", obj(
                        "function", "f",
                        "args", obj("x", op("$literal", recursiveLookingPattern)))))));
    }

    @Test
    void literalPayloadStillRejectsBexInsideTypeDefinitionFields() {
        assertThrows(BexException.class, () -> run(yaml(
                "type: Blue/BEX Program",
                "expr:",
                "  $literal:",
                "    type:",
                "      $choose:",
                "        cond: true",
                "        then: Integer",
                "        else: Text")));
    }

    @Test
    void nodeWriterMapsComputedObjectLanguageKeysToBlueNodeFields() {
        BexValue value = BexValues.fromSimple(m(
                "type", m("blueId", "HotelOrderType"),
                "status", "confirmed"));

        Node node = BexNodeWriter.toNode(value);

        assertEquals("HotelOrderType", node.getType().getBlueId());
        assertEquals("confirmed", node.getProperties().get("status").getValue());
        assertFalse(node.getProperties().containsKey("type"));
    }

    @Test
    void frozenWriterMapsComputedObjectLanguageKeysToBlueNodeFields() {
        BexValue value = BexValues.fromSimple(m(
                "type", m("blueId", "HotelOrderType"),
                "status", "confirmed"));

        FrozenNode node = BexFrozenWriter.toFrozen(value);

        assertEquals("HotelOrderType", node.getType().getReferenceBlueId());
        assertEquals("confirmed", node.property("status").getValue());
        assertNull(node.property("type"));
    }

    @Test
    void nodeWriterMapsSchemaAndValueLanguageKeysToBlueNodeFields() {
        BexValue value = BexValues.fromSimple(m(
                "name", "Amount",
                "type", m("blueId", "Integer"),
                "schema", m("required", true),
                "value", bi(10)));

        Node node = BexNodeWriter.toNode(value);

        assertEquals("Amount", node.getName());
        assertEquals("Integer", node.getType().getBlueId());
        assertTrue(node.getSchema().getRequiredValue());
        assertEquals(bi(10), node.getValue());
        assertNull(node.getProperties());
    }

    @Test
    void nodeWriterRejectsBlueIdReferenceWithSiblingFields() {
        BexValue value = BexValues.fromSimple(m(
                "blueId", "HotelOrderType",
                "status", "confirmed"));

        assertThrows(BexException.class, () -> BexNodeWriter.toNode(value));
    }

    @Test
    void nodeWriterRejectsPropertiesInternalField() {
        BexValue value = BexValues.fromSimple(m(
                "properties", m("status", "confirmed")));

        assertThrows(BexException.class, () -> BexNodeWriter.toNode(value));
        assertThrows(BexException.class, () -> BexFrozenWriter.toFrozen(value));
    }

    @Test
    void nodeWriterRejectsMixedPayloadKinds() {
        BexValue value = BexValues.fromSimple(m(
                "value", "confirmed",
                "status", "confirmed"));

        assertThrows(BexException.class, () -> BexNodeWriter.toNode(value));
    }

    @Test
    void nodeWriterRejectsSchemaAndConstraintsTogether() {
        BexValue value = BexValues.fromSimple(m(
                "schema", m("required", true),
                "constraints", m("minLength", 1)));

        assertThrows(BexException.class, () -> BexNodeWriter.toNode(value));
    }

    @Test
    void nodeWriterRejectsListControlFields() {
        assertThrows(BexException.class, () -> BexNodeWriter.toNode(BexValues.fromSimple(m(
                "$previous", "abc"))));
        assertThrows(BexException.class, () -> BexNodeWriter.toNode(BexValues.fromSimple(m(
                "$pos", 1))));
        assertThrows(BexException.class, () -> BexFrozenWriter.toFrozen(BexValues.fromSimple(m(
                "$previous", "abc"))));
    }

    private BexExecutionResult run(String yaml) {
        return engine.compileAndExecute(source(yaml), defaultContext());
    }

    private BexExecutionResult run(Node step) {
        return engine.compileAndExecute(BexProgramSource.inline(FrozenNode.fromResolvedNode(step)), defaultContext());
    }

    private BexCompiledProgram compile(String yaml) {
        return engine.compile(source(yaml));
    }

    private BexProgramSource source(String yaml) {
        Node node = blue.yamlToNode(yaml);
        return BexProgramSource.inline(FrozenNode.fromResolvedNode(node));
    }

    private static String yaml(String... lines) {
        return String.join("\n", lines);
    }

    private Node pattern(String... lines) {
        return blue.yamlToNode(yaml(lines));
    }
}
