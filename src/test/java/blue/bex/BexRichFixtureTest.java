package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasSchedule;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        validateFixtureShape(fixture, path);
        Blue blue = blueForFixture(fixture);
        Map<String, Object> expectation = map(fixture.get("expectation"));
        String outcome = string(expectation.get("outcome"));
        assertNotNull(outcome, "Fixture outcome is required: " + path);

        if ("parse-error".equals(outcome)) {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> parseProgram(fixture, blue));
            assertErrorContains(ex, expectation);
            return;
        }
        if ("parse-error-or-output-conversion-error".equals(outcome)) {
            assertParseOrOutputConversionError(fixture, expectation, blue);
            return;
        }

        Node program = parseProgram(fixture, blue);
        BexProgramSource source = BexProgramSource.inline(FrozenNode.fromResolvedNode(program));
        BexExecutionContext context = context(fixture, blue);
        BexEngine engine = engineForFixture(fixture, blue);

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
            assertGasProperty(engine, compiled, context, expectation, blue);
            return;
        }
        if (!"success".equals(outcome)) {
            fail("Unsupported fixture outcome: " + outcome);
        }

        BexExecutionResult result = engine.execute(compiled, context);
        assertSuccessExpectations(result, expectation);
    }

    private void assertParseOrOutputConversionError(Map<String, Object> fixture, Map<String, Object> expectation, Blue blue) {
        Node program;
        try {
            program = parseProgram(fixture, blue);
        } catch (RuntimeException ex) {
            assertErrorContains(ex, expectation);
            return;
        }
        BexProgramSource source = BexProgramSource.inline(FrozenNode.fromResolvedNode(program));
        try {
            BexExecutionResult result = engineForFixture(fixture, blue).compileAndExecute(source, context(fixture, blue));
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

    private void assertGasProperty(BexEngine engine, BexCompiledProgram compiled, BexExecutionContext context, Map<String, Object> expectation, Blue blue) {
        String property = string(expectation.get("property"));
        if (!"gasUsedGreaterThanEquivalentTinyEvent".equals(property)) {
            fail("Unsupported gas property: " + property);
        }
        long large = engine.execute(compiled, context).gasUsed();
        long tiny = engine.compileAndExecute(source(TINY_EVENT_PROGRAM, blue), context).gasUsed();
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
        if (expectation.containsKey("gasUsed")) {
            assertEquals(longValue(expectation.get("gasUsed")), result.gasUsed(), "gasUsed mismatch");
        }
    }

    private BexExecutionContext context(Map<String, Object> fixture, Blue blue) {
        Map<String, Object> context = map(fixture.get("context"));
        String scope = string(context.get("documentScope"));
        if (scope == null) {
            scope = "/";
        }
        Node root = parseNodeSource(string(context.get("rootDocumentSource")), blue);
        Node event = parseNodeSource(string(context.get("eventSource")), blue);
        Node currentContract = parseNodeSource(string(context.get("currentContractSource")), blue);
        long gasLimit = context.containsKey("gasLimit") ? longValue(context.get("gasLimit")) : 1_000_000L;

        BexExecutionContext.Builder builder = BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(FrozenNode.fromResolvedNode(root), FrozenNode.fromResolvedNode(root), scope))
                .event(BexValues.nodeSnapshot(event))
                .currentContract(BexValues.nodeSnapshot(currentContract))
                .steps(steps(context.get("stepsBinding")))
                .gasLimit(gasLimit);
        for (Map.Entry<String, Object> entry : map(context.get("bindings")).entrySet()) {
            builder.binding(entry.getKey(), BexValues.fromSimple(normalize(entry.getValue())));
        }
        return builder.build();
    }

    private BexEngine engineForFixture(Map<String, Object> fixture, Blue blue) {
        return BexEngine.builder()
                .blue(blue)
                .gasSchedule(gasSchedule(fixture))
                .build();
    }

    private BexGasSchedule gasSchedule(Map<String, Object> fixture) {
        Map<String, Object> overrides = map(fixture.get("gasSchedule"));
        if (overrides.isEmpty()) {
            return BexGasSchedule.defaults();
        }
        BexGasSchedule.Builder builder = BexGasSchedule.builder();
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            long value = longValue(entry.getValue());
            switch (entry.getKey()) {
                case "expressionBase":
                    builder.expressionBase(value);
                    break;
                case "statementBase":
                    builder.statementBase(value);
                    break;
                case "documentRead":
                    builder.documentRead(value);
                    break;
                case "eventRead":
                    builder.eventRead(value);
                    break;
                case "stepsRead":
                    builder.stepsRead(value);
                    break;
                case "currentContractRead":
                    builder.currentContractRead(value);
                    break;
                case "varRead":
                    builder.varRead(value);
                    break;
                case "resultValueRead":
                    builder.resultValueRead(value);
                    break;
                case "pointerGetBase":
                    builder.pointerGetBase(value);
                    break;
                case "pointerSetBase":
                    builder.pointerSetBase(value);
                    break;
                case "objectSetBase":
                    builder.objectSetBase(value);
                    break;
                case "appendChangeBase":
                    builder.appendChangeBase(value);
                    break;
                case "appendEventBase":
                    builder.appendEventBase(value);
                    break;
                case "forEachItem":
                    builder.forEachItem(value);
                    break;
                case "functionCall":
                    builder.functionCall(value);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported gasSchedule field: " + entry.getKey());
            }
        }
        return builder.build();
    }

    private BexStepResults steps(Object stepsObject) {
        Map<String, Object> stepsMap = map(stepsObject);
        BexStepResults.Builder builder = BexStepResults.builder();
        for (Map.Entry<String, Object> entry : stepsMap.entrySet()) {
            builder.put(entry.getKey(), BexValues.fromSimple(normalize(entry.getValue())));
        }
        return builder.build();
    }

    private Blue blueForFixture(Map<String, Object> fixture) {
        Map<String, Object> definitions = map(fixture.get("blueDefinitions"));
        if (definitions.isEmpty()) {
            return new Blue();
        }
        Map<String, List<Node>> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : definitions.entrySet()) {
            parsed.put(entry.getKey(), Collections.singletonList(YAML_BLUE.yamlToNode(requiredString(entry.getValue(),
                    "blueDefinitions." + entry.getKey()))));
        }
        return new Blue(blueId -> {
            List<Node> nodes = parsed.get(blueId);
            return nodes != null ? nodes : Collections.emptyList();
        });
    }

    private Node parseProgram(Map<String, Object> fixture, Blue blue) {
        return blue.yamlToNode(requiredString(fixture.get("programSource"), "programSource"));
    }

    private Node parseNodeSource(String source, Blue blue) {
        if (source == null || source.trim().isEmpty()) {
            return blue.yamlToNode("{}");
        }
        return blue.yamlToNode(source);
    }

    private BexProgramSource source(String source, Blue blue) {
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
            stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".yaml")
                            && !"manifest.yaml".equals(path.getFileName().toString()))
                    .forEach(paths::add);
        }
        Collections.sort(paths);
        return paths;
    }

    private String displayName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    private void validateFixtureShape(Map<String, Object> fixture, Path path) {
        validateAllowedKeys(fixture, set("fixtureId", "title", "targetStatus", "tags", "context",
                "blueDefinitions", "gasSchedule", "programSource", "expectation"), "fixture " + path);
        requiredString(fixture.get("fixtureId"), "fixtureId");
        requiredString(fixture.get("title"), "title");
        validateTags(fixture.get("tags"), path);
        validateTargetStatus(fixture.get("targetStatus"), path);
        if (!fixture.containsKey("programSource")) {
            throw new IllegalArgumentException("Fixture missing programSource: " + path);
        }
        if (!fixture.containsKey("expectation")) {
            throw new IllegalArgumentException("Fixture missing expectation: " + path);
        }

        Map<String, Object> context = map(fixture.get("context"));
        validateAllowedKeys(context, set("documentScope", "rootDocumentSource", "eventSource",
                "currentContractSource", "stepsBinding", "gasLimit", "bindings"), "context in " + path);
        if (context.containsKey("bindings")) {
            map(context.get("bindings"));
        }

        Map<String, Object> definitions = map(fixture.get("blueDefinitions"));
        for (Map.Entry<String, Object> entry : definitions.entrySet()) {
            requiredString(entry.getValue(), "blueDefinitions." + entry.getKey());
        }

        validateAllowedKeys(map(fixture.get("gasSchedule")), gasScheduleFields(), "gasSchedule in " + path);

        Map<String, Object> expectation = map(fixture.get("expectation"));
        String outcome = string(expectation.get("outcome"));
        if (outcome == null) {
            throw new IllegalArgumentException("Fixture expectation missing outcome: " + path);
        }
        Set<String> allowedExpectation;
        if ("success".equals(outcome)) {
            allowedExpectation = set("outcome", "resultSimple", "changeset", "events", "gasUsed");
        } else if ("gas-property".equals(outcome)) {
            allowedExpectation = set("outcome", "property");
        } else if ("parse-error".equals(outcome)
                || "parse-error-or-output-conversion-error".equals(outcome)
                || "compile-error".equals(outcome)
                || "runtime-error".equals(outcome)
                || "output-conversion-error".equals(outcome)) {
            allowedExpectation = set("outcome", "errorContains");
        } else {
            throw new IllegalArgumentException("Unsupported fixture outcome: " + outcome + " in " + path);
        }
        validateAllowedKeys(expectation, allowedExpectation, "expectation in " + path);
    }

    private void validateTags(Object tags, Path path) {
        if (tags == null) {
            return;
        }
        if (!(tags instanceof List)) {
            throw new IllegalArgumentException("Fixture tags must be a list: " + path);
        }
        for (Object tag : (List<?>) tags) {
            String text = string(tag);
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("Fixture tags must contain only non-empty text values: " + path);
            }
        }
    }

    private void validateTargetStatus(Object targetStatus, Path path) {
        if (targetStatus == null) {
            return;
        }
        String status = requiredString(targetStatus, "targetStatus");
        if (!targetStatuses().contains(status)) {
            throw new IllegalArgumentException("Unsupported targetStatus " + status + " in " + path);
        }
    }

    private void validateAllowedKeys(Map<String, Object> values, Set<String> allowed, String label) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(label + " contains unsupported field: " + key);
            }
        }
    }

    private Set<String> gasScheduleFields() {
        return set("expressionBase", "statementBase", "documentRead", "eventRead", "stepsRead",
                "currentContractRead", "varRead", "resultValueRead", "pointerGetBase",
                "pointerSetBase", "objectSetBase", "appendChangeBase", "appendEventBase",
                "forEachItem", "functionCall");
    }

    private Set<String> targetStatuses() {
        return set("current-pass", "current-compile-error", "current-runtime-error",
                "current-output-conversion-error", "current-parse-error",
                "current-parse-error-or-output-conversion-error", "current-gas-property");
    }

    private Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
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

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("Expected integer value but found: " + value);
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
