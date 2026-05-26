package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.BexCompiledProgramKey;
import blue.bex.compile.BexNodeIdentity;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.language.Blue;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexCompiledProgramCacheTest {
    private final Blue blue = new Blue();

    @Test
    void differentNodesWithoutBlueIdDoNotCollide() {
        FrozenNode first = frozen(stepExpr(op("$document", "/status")));
        FrozenNode second = frozen(stepExpr(op("$document", "/count")));

        assertNotEquals(BexNodeIdentity.stable(first), BexNodeIdentity.stable(second));
        assertNotEquals(BexCompiledProgramKey.from(BexProgramSource.inline(first)),
                BexCompiledProgramKey.from(BexProgramSource.inline(second)));
    }

    @Test
    void sameNodeProducesSameIdentityAndCacheHit() {
        LruBexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        BexEngine engine = BexEngine.builder().cache(cache).build();
        BexProgramSource source = BexProgramSource.inline(frozen(stepExpr(op("$document", "/status"))));

        BexCompiledProgram first = engine.compile(source);
        BexCompiledProgram second = engine.compile(source);

        assertSame(first, second);
        assertEquals(BexNodeIdentity.stable(source.programNode()), BexNodeIdentity.stable(source.programNode()));
    }

    @Test
    void entryNameParticipatesInCacheKey() {
        FrozenNode step = frozen(obj("type", "Blue/BEX Program"));
        FrozenNode definition = frozen(obj("functions", obj(
                "a", obj("expr", "A"),
                "b", obj("expr", "B")
        )));

        assertNotEquals(BexCompiledProgramKey.from(BexProgramSource.withDefinition(step, definition, "a")),
                BexCompiledProgramKey.from(BexProgramSource.withDefinition(step, definition, "b")));
    }

    @Test
    void sourceKindParticipatesInCacheKey() {
        FrozenNode expression = frozen(op("$document", "/status"));

        assertNotEquals(BexCompiledProgramKey.from(BexProgramSource.inline(expression)),
                BexCompiledProgramKey.from(BexProgramSource.expression(expression)));
    }

    @Test
    void schemaDifferencesParticipateInCacheKeyAndCompiledBehavior() {
        BexProgramSource minLengthOne = source(String.join("\n",
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      input:",
                "        type: Text",
                "        schema:",
                "          minLength: 1",
                "    expr:",
                "      $var: input",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      input: abc"));
        BexProgramSource minLengthFive = source(String.join("\n",
                "type: Blue/BEX Program",
                "functions:",
                "  f:",
                "    args:",
                "      input:",
                "        type: Text",
                "        schema:",
                "          minLength: 5",
                "    expr:",
                "      $var: input",
                "expr:",
                "  $call:",
                "    function: f",
                "    args:",
                "      input: abc"));

        assertNotEquals(BexNodeIdentity.stable(minLengthOne.programNode()),
                BexNodeIdentity.stable(minLengthFive.programNode()));
        assertNotEquals(BexCompiledProgramKey.from(minLengthOne), BexCompiledProgramKey.from(minLengthFive));

        BexEngine engine = BexEngine.builder()
                .blue(blue)
                .cache(new LruBexCompiledProgramCache())
                .build();

        assertEquals("abc", engine.compileAndExecute(minLengthOne, defaultContext()).value().toSimple());
        assertThrows(BexException.class, () -> engine.compileAndExecute(minLengthFive, defaultContext()));
    }

    @Test
    void valueTypeDifferencesParticipateInCacheKey() {
        BexProgramSource integerValueType = source(String.join("\n",
                "type: Blue/BEX Program",
                "expr:",
                "  valueType:",
                "    type: Integer"));
        BexProgramSource textValueType = source(String.join("\n",
                "type: Blue/BEX Program",
                "expr:",
                "  valueType:",
                "    type: Text"));

        assertNotEquals(BexNodeIdentity.stable(integerValueType.programNode()),
                BexNodeIdentity.stable(textValueType.programNode()));
        assertNotEquals(BexCompiledProgramKey.from(integerValueType), BexCompiledProgramKey.from(textValueType));
    }

    @Test
    void itemTypeDifferencesParticipateInCacheKey() {
        assertDifferentProgramIdentities(
                String.join("\n",
                        "type: Blue/BEX Program",
                        "expr:",
                        "  itemType:",
                        "    type: Integer",
                        "  items:",
                        "    - 1"),
                String.join("\n",
                        "type: Blue/BEX Program",
                        "expr:",
                        "  itemType:",
                        "    type: Text",
                        "  items:",
                        "    - 1"));
    }

    @Test
    void keyTypeDifferencesParticipateInCacheKey() {
        assertDifferentProgramIdentities(
                String.join("\n",
                        "type: Blue/BEX Program",
                        "expr:",
                        "  keyType:",
                        "    type: Text",
                        "  a: 1"),
                String.join("\n",
                        "type: Blue/BEX Program",
                        "expr:",
                        "  keyType:",
                        "    type: Integer",
                        "  a: 1"));
    }

    @Test
    void blueDifferencesParticipateInCacheKey() {
        BexProgramSource first = BexProgramSource.inline(frozen(stepExpr(new blue.language.model.Node()
                .blue(obj("source", "A"))
                .value("payload"))));
        BexProgramSource second = BexProgramSource.inline(frozen(stepExpr(new blue.language.model.Node()
                .blue(obj("source", "B"))
                .value("payload"))));

        assertNotEquals(BexNodeIdentity.stable(first.programNode()), BexNodeIdentity.stable(second.programNode()));
        assertNotEquals(BexCompiledProgramKey.from(first), BexCompiledProgramKey.from(second));
    }

    @Test
    void mergePolicyDifferencesParticipateInCacheKey() {
        BexProgramSource first = BexProgramSource.inline(frozen(stepExpr(new blue.language.model.Node()
                .mergePolicy("replace")
                .properties(props("a", v(1))))));
        BexProgramSource second = BexProgramSource.inline(frozen(stepExpr(new blue.language.model.Node()
                .mergePolicy("append")
                .properties(props("a", v(1))))));

        assertNotEquals(BexNodeIdentity.stable(first.programNode()), BexNodeIdentity.stable(second.programNode()));
        assertNotEquals(BexCompiledProgramKey.from(first), BexCompiledProgramKey.from(second));
    }

    private BexProgramSource source(String yaml) {
        return BexProgramSource.inline(FrozenNode.fromResolvedNode(blue.yamlToNode(yaml)));
    }

    private void assertDifferentProgramIdentities(String firstYaml, String secondYaml) {
        BexProgramSource first = source(firstYaml);
        BexProgramSource second = source(secondYaml);

        assertNotEquals(BexNodeIdentity.stable(first.programNode()), BexNodeIdentity.stable(second.programNode()));
        assertNotEquals(BexCompiledProgramKey.from(first), BexCompiledProgramKey.from(second));
    }
}
