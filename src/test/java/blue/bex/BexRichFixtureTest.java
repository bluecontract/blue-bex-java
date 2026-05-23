package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexFrozenWriter;
import blue.bex.value.BexNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BexRichFixtureTest {
    private static final String FIXTURE_ROOT = "rich-fixtures";
    private static final Blue YAML_BLUE = new Blue();
    private static final String TINY_EVENT_PROGRAM = String.join("\n",
            "type: Blue/BEX Program",
            "do:",
            "  - $appendEvent:",
            "      eventKind: Tiny",
            "      payload: x");

    private final Blue blue = new Blue(blueId -> {
        if ("HotelOrderType".equals(blueId)) {
            return Collections.singletonList(YAML_BLUE.yamlToNode(String.join("\n",
                    "status:",
                    "  type: Text")));
        }
        if ("RestaurantOrderType".equals(blueId)) {
            return Collections.singletonList(YAML_BLUE.yamlToNode(String.join("\n",
                    "restaurantStatus:",
                    "  type: Text")));
        }
        return Collections.emptyList();
    });
    private final BexEngine engine = BexEngine.builder().blue(blue).build();
    private final Yaml yaml = new Yaml();

    @TestFactory
    Collection<DynamicTest> richFixtures() throws Exception {
        List<Path> paths = fixturePaths();
        List<DynamicTest> tests = new ArrayList<>();
        for (Path path : paths) {
            tests.add(DynamicTest.dynamicTest(displayName(path), () -> runFixture(path)));
        }
        return tests;
    }

    private void runFixture(Path path) throws Exception {
        Map<String, Object> fixture = readFixture(path);
        Map<String, Object> expectation = map(fixture.get("expectation"));
        String outcome = string(expectation.get("outcome"));
        assertNotNull(outcome, "Fixture outcome is required: " + path);

        if ("parse-error".equals(outcome)) {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> parseProgram(fixture));
            assertErrorContains(ex, expectation);
            return;
        }
        if ("parse-error-or-output-conversion-error".equals(outcome)) {
            assertParseOrOutputConversionError(fixture, expectation);
            return;
        }

        Node program = parseProgram(fixture);
        BexProgramSource source = BexProgramSource.inline(FrozenNode.fromResolvedNode(program));
        BexExecutionContext context = context(fixture);

        if ("compile-error".equals(outcome)) {
            BexException ex = assertThrows(BexException.class, () -> engine.compile(source));
            assertErrorContains(ex, expectation);
            return;
        }

        BexCompiledProgram compiled = engine.compile(source);
        if ("runtime-error".equals(outcome)) {
            BexException ex = assertThrows(BexException.class, () -> engine.execute(compiled, context));
            assertErrorContains(ex, expectation);
            return;
        }
        if ("output-conversion-error".equals(outcome)) {
            BexExecutionResult result = engine.execute(compiled, context);
            assertOutputConversionError(result.value(), expectation);
            return;
        }
        if ("gas-property".equals(outcome)) {
            assertGasProperty(compiled, context, expectation);
            return;
        }
        if (!"success".equals(outcome)) {
            fail("Unsupported fixture outcome: " + outcome);
        }

        BexExecutionResult result = engine.execute(compiled, context);
        assertSuccessExpectations(result, expectation);
    }

    private void assertParseOrOutputConversionError(Map<String, Object> fixture, Map<String, Object> expectation) {
        Node program;
        try {
            program = parseProgram(fixture);
        } catch (RuntimeException ex) {
            assertErrorContains(ex, expectation);
            return;
        }
        BexProgramSource source = BexProgramSource.inline(FrozenNode.fromResolvedNode(program));
        try {
            BexExecutionResult result = engine.compileAndExecute(source, context(fixture));
            assertOutputConversionError(result.value(), expectation);
        } catch (BexException ex) {
            assertErrorContains(ex, expectation);
        }
    }

    private void assertOutputConversionError(BexValue value, Map<String, Object> expectation) {
        BexException thrown = null;
        try {
            BexNodeWriter.toNode(value);
        } catch (BexException ex) {
            thrown = ex;
        }
        if (thrown == null) {
            thrown = assertThrows(BexException.class, () -> BexFrozenWriter.toFrozen(value));
        }
        assertErrorContains(thrown, expectation);
    }

    private void assertGasProperty(BexCompiledProgram compiled, BexExecutionContext context, Map<String, Object> expectation) {
        String property = string(expectation.get("property"));
        if (!"gasUsedGreaterThanEquivalentTinyEvent".equals(property)) {
            fail("Unsupported gas property: " + property);
        }
        long large = engine.execute(compiled, context).gasUsed();
        long tiny = engine.compileAndExecute(source(TINY_EVENT_PROGRAM), context).gasUsed();
        assertTrue(large > tiny, "Expected large output gas " + large + " to be greater than tiny output gas " + tiny);
    }

    private void assertSuccessExpectations(BexExecutionResult result, Map<String, Object> expectation) {
        if (expectation.containsKey("resultSimple")) {
            assertEquals(normalize(expectation.get("resultSimple")), normalize(result.value().toSimple()));
        }
        if (expectation.containsKey("changeset")) {
            assertEquals(normalize(expectation.get("changeset")), normalize(result.changeset().asValue().toSimple()));
        }
        if (expectation.containsKey("events")) {
            assertEquals(normalize(expectation.get("events")), normalize(result.events().asValue().toSimple()));
        }
    }

    private BexExecutionContext context(Map<String, Object> fixture) {
        Map<String, Object> context = map(fixture.get("context"));
        String scope = string(context.get("documentScope"));
        if (scope == null) {
            scope = "/";
        }
        Node root = parseNodeSource(string(context.get("rootDocumentSource")));
        Node event = parseNodeSource(string(context.get("eventSource")));
        Node currentContract = parseNodeSource(string(context.get("currentContractSource")));

        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(FrozenNode.fromResolvedNode(root), FrozenNode.fromResolvedNode(root), scope))
                .event(BexValues.nodeSnapshot(event))
                .currentContract(BexValues.nodeSnapshot(currentContract))
                .steps(steps(context.get("stepsBinding")))
                .gasLimit(1_000_000)
                .build();
    }

    private BexStepResults steps(Object stepsObject) {
        Map<String, Object> stepsMap = map(stepsObject);
        BexStepResults.Builder builder = BexStepResults.builder();
        for (Map.Entry<String, Object> entry : stepsMap.entrySet()) {
            builder.put(entry.getKey(), BexValues.fromSimple(normalize(entry.getValue())));
        }
        return builder.build();
    }

    private Node parseProgram(Map<String, Object> fixture) {
        return blue.yamlToNode(requiredString(fixture.get("programSource"), "programSource"));
    }

    private Node parseNodeSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return blue.yamlToNode("{}");
        }
        return blue.yamlToNode(source);
    }

    private BexProgramSource source(String source) {
        return BexProgramSource.inline(FrozenNode.fromResolvedNode(blue.yamlToNode(source)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFixture(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Fixture must be a map: " + path);
            }
            return (Map<String, Object>) loaded;
        }
    }

    private List<Path> fixturePaths() throws URISyntaxException, IOException {
        URL url = Thread.currentThread().getContextClassLoader().getResource(FIXTURE_ROOT);
        assertNotNull(url, "Missing fixture resource root: " + FIXTURE_ROOT);
        final Path root = Paths.get(url.toURI());
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".yaml"))
                    .forEach(paths::add);
        }
        Collections.sort(paths);
        return paths;
    }

    private String displayName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    private void assertErrorContains(Throwable ex, Map<String, Object> expectation) {
        String expected = string(expectation.get("errorContains"));
        if (expected == null || expected.isEmpty()) {
            return;
        }
        String message = ex.getMessage();
        assertTrue(message != null && message.contains(expected),
                "Expected error to contain <" + expected + "> but was <" + message + ">");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected map but found: " + value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private String requiredString(Object value, String label) {
        String text = string(value);
        if (text == null) {
            throw new IllegalArgumentException("Missing fixture field: " + label);
        }
        return text;
    }

    private String string(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    @SuppressWarnings("unchecked")
    private Object normalize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                out.add(normalize(item));
            }
            return out;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigDecimal || value instanceof BigInteger || value instanceof String || value instanceof Boolean || value == null) {
            return value;
        }
        if (value instanceof Float || value instanceof Double) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return value;
    }
}
