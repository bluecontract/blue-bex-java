package blue.bex.conformance;

import blue.bex.test.TestBlue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrity gate for the unmodified normative BEX 2.0 conformance package.
 */
class BexConformancePackageIntegrityTest {

    @Test
    void exact162FilePackageAndManifestInventoryAreIntact() {
        Map<String, Object> manifest = ConformancePackage.fixtureManifest();
        assertEquals("blue-bex-conformance", manifest.get("fixturePackage"));
        assertEquals("2.0", manifest.get("specificationVersion"));
        assertEquals("blue-bex-fixture/2.0", manifest.get("schemaVersion"));
        assertEquals(ConformancePackage.VECTOR_COUNT,
                intValue(manifest.get("vectorCount")));
        assertEquals(ConformancePackage.BEHAVIOR_FIXTURE_COUNT,
                intValue(manifest.get("behaviorFixtureCount")));
        assertEquals(ConformancePackage.GAS_FIXTURE_COUNT,
                intValue(manifest.get("gasFixtureCount")));

        List<ConformancePackage.ManifestFile> entries =
                ConformancePackage.manifestFiles();
        assertEquals(156, entries.size(),
                "The fixture manifest inventories every fixture/support file except itself");
        assertEquals(ConformancePackage.BEHAVIOR_FIXTURE_COUNT,
                countRole(entries, "behavior-fixture"));
        assertEquals(ConformancePackage.GAS_FIXTURE_COUNT,
                countRole(entries, "gas-fixture"));
        assertEquals(6, countRole(entries, "support"));

        Set<String> listedPaths = new LinkedHashSet<String>();
        List<String> listedInOrder = new ArrayList<String>();
        for (ConformancePackage.ManifestFile entry : entries) {
            assertTrue(listedPaths.add(entry.path),
                    "Duplicate manifest path: " + entry.path);
            listedInOrder.add(entry.path);
            assertSafeRelativePath(entry.path);
            assertTrue(ConformancePackage.stringSet(
                            "behavior-fixture", "gas-fixture", "support")
                    .contains(entry.role), "Unknown role for " + entry.path);
            assertTrue(ConformancePackage.SHA_256.matcher(entry.sha256).matches(),
                    "Invalid SHA-256 syntax for " + entry.path);

            String resource = ConformancePackage.FIXTURE_ROOT + entry.path;
            byte[] normalized = ConformancePackage.lfNormalizedBytes(resource);
            assertEquals(entry.bytes, normalized.length,
                    "LF-normalized byte length changed for " + entry.path);
            assertEquals(entry.sha256, ConformancePackage.sha256(normalized),
                    "SHA-256 changed for " + entry.path);
        }

        List<String> sorted = new ArrayList<String>(listedInOrder);
        Collections.sort(sorted);
        assertEquals(sorted, listedInOrder,
                "Manifest inventory order must remain deterministic");

        Set<String> physicalFixtureFiles =
                new LinkedHashSet<String>(
                        ConformancePackage.regularResourcePaths(
                                ConformancePackage.FIXTURE_ROOT));
        assertTrue(physicalFixtureFiles.remove("manifest.yaml"));
        assertEquals(listedPaths, physicalFixtureFiles,
                "Unlisted or missing fixture-package files");

        assertEquals(ConformancePackage.FILE_COUNT,
                ConformancePackage.regularResourcePaths(
                        ConformancePackage.ROOT).size(),
                "The imported BEX package must remain exactly 162 files");
    }

    @Test
    void packageIdentitiesAndCrossPackageBindingsRecomputeExactly() {
        Map<String, Object> fixtureManifest =
                ConformancePackage.fixtureManifest();
        Map<String, Object> gasManifest = ConformancePackage.gasManifest();
        Map<String, Object> registryManifest =
                ConformancePackage.registryManifest();

        String fixtureIdentity = text(fixtureManifest.get("packageIdentity"));
        String gasIdentity = text(gasManifest.get("packageIdentity"));
        String registryIdentity = text(registryManifest.get("packageIdentity"));
        assertPackageIdentity(fixtureIdentity);
        assertPackageIdentity(gasIdentity);
        assertPackageIdentity(registryIdentity);

        assertEquals(fixtureIdentity,
                ConformancePackage.packageIdentity(
                        fixtureManifest, "packageIdentity"));
        assertEquals(gasIdentity,
                ConformancePackage.packageIdentity(
                        gasManifest, "packageIdentity"));
        assertEquals(registryIdentity,
                ConformancePackage.packageIdentity(
                        registryManifest,
                        "packageIdentity", "fixturePackageIdentity"));

        String gasSha = ConformancePackage.sha256(
                ConformancePackage.lfNormalizedBytes(
                        ConformancePackage.GAS_MANIFEST));
        assertEquals(fixtureManifest.get("gasManifestSha256"), gasSha);
        assertEquals(fixtureManifest.get("gasManifestPackageIdentity"), gasIdentity);
        assertEquals(fixtureManifest.get("registryPackageIdentity"), registryIdentity);
        assertEquals(registryManifest.get("fixturePackageIdentity"), fixtureIdentity);
    }

    @Test
    void everyFixtureValidatesAgainstTheClosedSchema() {
        Map<String, Object> schema = ConformancePackage.loadMap(
                ConformancePackage.FIXTURE_ROOT + "fixture-schema.yaml");
        assertEquals("blue-bex-fixture/2.0", schema.get("$id"));
        assertEquals(Boolean.FALSE,
                ConformancePackage.map(schema, "fixture schema")
                        .get("additionalProperties"));

        Set<String> ids = new LinkedHashSet<String>();
        for (ConformancePackage.Fixture fixture
                : allFixtures()) {
            BexFixtureSchemaValidator.validate(fixture);
            assertTrue(ids.add(fixture.id()),
                    "Duplicate fixture id: " + fixture.id());
            if (fixture.path.startsWith("gas-micro/")) {
                assertEquals("gas", fixture.category(), fixture.path);
            } else {
                assertFalse("gas".equals(fixture.category()), fixture.path);
            }
        }
        assertEquals(
                ConformancePackage.BEHAVIOR_FIXTURE_COUNT
                        + ConformancePackage.GAS_FIXTURE_COUNT,
                ids.size());
    }

    @Test
    void vectorOperatorAndProjectionCoverageAreClosedAndBidirectional() {
        List<ConformancePackage.Fixture> behavior =
                ConformancePackage.behaviorFixtures();
        Map<String, ConformancePackage.Fixture> fixturesByPath =
                fixturesByPath(behavior);
        assertEquals(ConformancePackage.BEHAVIOR_FIXTURE_COUNT,
                fixturesByPath.size());

        List<ConformancePackage.Fixture> vectorFixtures =
                new ArrayList<ConformancePackage.Fixture>(behavior);
        vectorFixtures.addAll(ConformancePackage.gasFixtures());
        Map<String, List<String>> expectedVectors =
                reverseVectorMap(vectorFixtures);
        Map<String, Object> vectorCoverage = ConformancePackage.loadMap(
                ConformancePackage.FIXTURE_ROOT + "vector-coverage.yaml");
        assertEquals("blue-bex/2.0", vectorCoverage.get("specification"));
        Map<String, Object> declaredVectors = ConformancePackage.map(
                vectorCoverage.get("vectors"), "vector-coverage.vectors");
        assertEquals(ConformancePackage.VECTOR_COUNT, declaredVectors.size(),
                "The authoritative manifest's 75-vector count wins");
        assertEquals(expectedVectors.keySet(), declaredVectors.keySet());
        for (Map.Entry<String, Object> entry : declaredVectors.entrySet()) {
            assertEquals(expectedVectors.get(entry.getKey()),
                    textList(entry.getValue(),
                            "vector-coverage." + entry.getKey()),
                    "Vector reverse mapping changed for " + entry.getKey());
        }

        Map<String, Object> operatorCoverage = ConformancePackage.loadMap(
                ConformancePackage.FIXTURE_ROOT + "operator-coverage.yaml");
        assertEquals("blue-bex-operator-coverage/2.0",
                operatorCoverage.get("schema"));
        assertEquals(ConformancePackage.OPERATOR_COUNT,
                intValue(operatorCoverage.get("operatorCount")));
        List<?> operators = ConformancePackage.list(
                operatorCoverage.get("operators"),
                "operator-coverage.operators");
        assertEquals(ConformancePackage.OPERATOR_COUNT, operators.size());

        Set<String> operatorNames = new LinkedHashSet<String>();
        for (Object value : operators) {
            Map<String, Object> declaration =
                    ConformancePackage.map(value, "operator coverage entry");
            String operator = text(declaration.get("operator"));
            assertTrue(operator.startsWith("$"));
            assertTrue(operatorNames.add(operator),
                    "Duplicate operator coverage: " + operator);
            List<String> paths = textList(
                    declaration.get("fixtures"), operator + ".fixtures");
            assertFalse(paths.isEmpty(), "No direct coverage for " + operator);
            for (String path : paths) {
                ConformancePackage.Fixture fixture = fixturesByPath.get(path);
                assertNotNull(fixture,
                        "Operator coverage references non-behavior fixture " + path);
                assertTrue(operatorOccurrences(fixture).contains(operator),
                        path + " does not directly contain " + operator);
            }
        }

        assertEquals(45,
                ConformancePackage.regularResourcePaths(
                        ConformancePackage.FIXTURE_ROOT + "operators/").size(),
                "Every direct operator fixture must remain present");

        Map<String, Object> projectionCatalog = ConformancePackage.loadMap(
                ConformancePackage.FIXTURE_ROOT + "projection-catalog.yaml");
        assertEquals("blue-bex-projection-catalog/2.0",
                projectionCatalog.get("schema"));
        Set<String> projections = new LinkedHashSet<String>();
        for (Object entry : ConformancePackage.list(
                projectionCatalog.get("entries"),
                "projection-catalog.entries")) {
            String projection = text(
                    ConformancePackage.map(entry, "projection entry").get("path"));
            assertTrue(projections.add(projection),
                    "Duplicate projection: " + projection);
        }
        for (ConformancePackage.Fixture fixture : behavior) {
            assertDeclaredProjections(
                    projections,
                    fixture.expected(),
                    fixture.path + ".expected");
            Object cases = fixture.expected().get("cases");
            if (cases != null) {
                for (Object caseValue : ConformancePackage.list(
                        cases, fixture.path + ".expected.cases")) {
                    Map<String, Object> fixtureCase = ConformancePackage.map(
                            caseValue, fixture.path + " case");
                    assertDeclaredProjections(
                            projections,
                            fixtureCase,
                            fixture.path + ".expected.cases["
                                    + fixtureCase.get("name") + "]");
                }
            }
        }
    }

    private static void assertDeclaredProjections(
            Set<String> projections,
            Map<String, Object> expected,
            String path) {
        Object assertionsValue = expected.get("assertions");
        if (assertionsValue == null) {
            return;
        }
        for (Object assertionValue : ConformancePackage.list(
                assertionsValue, path + ".assertions")) {
            Map<String, Object> assertion = ConformancePackage.map(
                    assertionValue, path + " assertion");
            String actual = text(assertion.get("actual"));
            assertTrue(projections.contains(actual),
                    path + " uses undeclared projection " + actual);
        }
    }

    @Test
    void everyNamedCounterHasOneExactMicrofixture() {
        Map<String, Object> gasManifest = ConformancePackage.gasManifest();
        assertEquals("blue-bex-gas-manifest",
                gasManifest.get("manifestType"));
        assertEquals("blue-bex/gas/2.0", gasManifest.get("schedule"));
        assertEquals(ConformancePackage.GAS_FIXTURE_COUNT,
                intValue(gasManifest.get("counterCount")));
        Map<String, Object> counters = ConformancePackage.map(
                gasManifest.get("counters"), "gas-manifest.counters");
        assertEquals(ConformancePackage.GAS_FIXTURE_COUNT, counters.size());
        assertFalse(counters.containsKey("estimatedSize"),
                "Recursive size gas is forbidden");

        Set<String> covered = new LinkedHashSet<String>();
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.gasFixtures()) {
            Map<String, Object> direct = ConformancePackage.map(
                    fixture.context().get("directCounterFixture"),
                    fixture.path + ".directCounterFixture");
            String counter = text(direct.get("counter"));
            assertTrue(counters.containsKey(counter),
                    "Unknown counter microfixture: " + counter);
            assertTrue(covered.add(counter),
                    "Duplicate counter microfixture: " + counter);
            long quantity = longValue(direct.get("quantity"));
            long weight = longValue(counters.get(counter));

            Map<String, Object> expected = fixture.expected();
            List<?> trace = ConformancePackage.list(
                    expected.get("gasTrace"), fixture.path + ".gasTrace");
            assertEquals(1, trace.size(), fixture.path);
            Map<String, Object> charge = ConformancePackage.map(
                    trace.get(0), fixture.path + ".gasTrace[0]");
            assertEquals(0L, longValue(charge.get("sequence")), fixture.path);
            assertEquals(counter, charge.get("counter"), fixture.path);
            assertEquals(quantity, longValue(charge.get("quantity")), fixture.path);
            assertEquals(weight, longValue(charge.get("weight")), fixture.path);
            assertEquals(quantity * weight,
                    longValue(charge.get("gas")), fixture.path);
            assertEquals(quantity * weight,
                    longValue(expected.get("totalGas")), fixture.path);
        }
        assertEquals(counters.keySet(), covered);
    }

    @Test
    void registryFilesBlueIdsAndFixtureIntrinsicBindingsAreExact() {
        Map<String, Object> registry =
                ConformancePackage.registryManifest();
        assertEquals("blue-bex-runtime", registry.get("registry"));
        assertEquals("runtime-type", registry.get("registryKind"));
        assertEquals("2.0", registry.get("specificationVersion"));
        assertEquals("1.0", registry.get("languageVersion"));

        Set<String> declaredBlueIds = new LinkedHashSet<String>();
        Set<String> fixtureOnlyBlueIds = new LinkedHashSet<String>();
        try (TestBlue blue = new TestBlue()) {
            for (Object value : ConformancePackage.list(
                    registry.get("entries"), "registry.entries")) {
                Map<String, Object> entry =
                        ConformancePackage.map(value, "registry entry");
                String path = text(entry.get("path"));
                String expectedSha = text(entry.get("sha256"));
                String expectedBlueId = text(entry.get("blueId"));
                byte[] bytes = ConformancePackage.lfNormalizedBytes(
                        ConformancePackage.REGISTRY_ROOT + path);
                assertEquals(expectedSha, ConformancePackage.sha256(bytes), path);

                Node node = blue.yamlToNode(
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(expectedBlueId,
                        FrozenNode.fromResolvedNode(node).blueId(),
                        "Registry BlueId changed for " + path);
                assertTrue(declaredBlueIds.add(expectedBlueId),
                        "Duplicate registry BlueId: " + expectedBlueId);
                if (Boolean.TRUE.equals(entry.get("fixtureOnly"))) {
                    fixtureOnlyBlueIds.add(expectedBlueId);
                }
            }
        }

        Set<String> invokedIntrinsics = new LinkedHashSet<String>();
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            collectIntrinsicBlueIds(fixture.program(), invokedIntrinsics);
        }
        assertEquals(fixtureOnlyBlueIds, invokedIntrinsics,
                "Behavior fixtures may invoke exactly the declared fixture intrinsics");
        assertEquals(2, invokedIntrinsics.size());
        assertTrue(declaredBlueIds.containsAll(invokedIntrinsics));
    }

    @Test
    void knownBaselineReconciliationsRemainExplicitAndFixturePreserving() {
        Map<String, ConformancePackage.Fixture> fixtures =
                fixturesByPath(ConformancePackage.behaviorFixtures());

        Map<String, Object> manifest = ConformancePackage.fixtureManifest();
        assertEquals(75, intValue(manifest.get("vectorCount")));
        assertEquals(120, intValue(manifest.get("behaviorFixtureCount")));

        ConformancePackage.Fixture s07 = fixtures.get("s/bex-s-07.yaml");
        assertEquals("runtime-error", s07.expected().get("errorClass"),
                "Parallel $let observes an uninitialized local at runtime");
        Map<String, Object> s07Let = firstStatementBody(s07.program(), "$let");
        assertTrue(s07Let.containsKey("vars"));
        assertFalse(s07Let.containsKey("order"));

        ConformancePackage.Fixture c09 = fixtures.get("c/bex-c-09.yaml");
        assertEquals("recursive-call-graph", c09.expected().get("reason"));
        assertEquals("rejected", c09.expected().get("compileStatus"));
        assertEquals(Boolean.FALSE,
                firstAssertionExpected(c09, "runtime.started"));

        ConformancePackage.Fixture e14 = fixtures.get("e/bex-e-14.yaml");
        Map<String, Object> identityAssertion =
                firstAssertion(e14, "result.identityA");
        assertEquals("notEquals", identityAssertion.get("op"));
        assertEquals("result.identityB", identityAssertion.get("expected"),
                "The expected operand is a projection reference, not literal text");

        ConformancePackage.Fixture findEntry =
                fixtures.get("operators/bex-op-findentry.yaml");
        Map<String, Object> expectedFindEntry = ConformancePackage.map(
                findEntry.expected().get("result"), "findEntry.expected.result");
        assertEquals(ConformancePackage.stringSet("key", "val"),
                expectedFindEntry.keySet(),
                "The expected map is a subset; the normative result retains index");

        ConformancePackage.Fixture g09 = fixtures.get("g/bex-g-09.yaml");
        assertEquals("canonical-merge-sort",
                firstAssertionExpected(g09, "gas.sortComparison"),
                "G09 asserts algorithm evidence, not a string-valued quantity");
    }

    private static List<ConformancePackage.Fixture> allFixtures() {
        List<ConformancePackage.Fixture> fixtures =
                new ArrayList<ConformancePackage.Fixture>();
        fixtures.addAll(ConformancePackage.behaviorFixtures());
        fixtures.addAll(ConformancePackage.gasFixtures());
        return fixtures;
    }

    private static Map<String, ConformancePackage.Fixture> fixturesByPath(
            List<ConformancePackage.Fixture> fixtures) {
        Map<String, ConformancePackage.Fixture> byPath =
                new LinkedHashMap<String, ConformancePackage.Fixture>();
        for (ConformancePackage.Fixture fixture : fixtures) {
            assertEquals(null, byPath.put(fixture.path, fixture),
                    "Duplicate fixture path " + fixture.path);
        }
        return byPath;
    }

    private static Set<String> operatorOccurrences(
            ConformancePackage.Fixture fixture) {
        Set<String> operators = new LinkedHashSet<String>(
                ConformancePackage.operatorOccurrences(fixture.program()));
        Object cases = fixture.expected().get("cases");
        if (cases != null) {
            for (Object value : ConformancePackage.list(
                    cases, fixture.path + ".expected.cases")) {
                Map<String, Object> testcase = ConformancePackage.map(
                        value, fixture.path + ".expected.cases[]");
                operators.addAll(ConformancePackage.operatorOccurrences(
                        testcase.get("program")));
            }
        }
        return operators;
    }

    private static Map<String, List<String>> reverseVectorMap(
            List<ConformancePackage.Fixture> fixtures) {
        Map<String, List<String>> vectors =
                new LinkedHashMap<String, List<String>>();
        for (ConformancePackage.Fixture fixture : fixtures) {
            for (String vector : textList(
                    fixture.data.get("vectors"), fixture.path + ".vectors")) {
                List<String> paths = vectors.get(vector);
                if (paths == null) {
                    paths = new ArrayList<String>();
                    vectors.put(vector, paths);
                }
                paths.add(fixture.path);
            }
        }
        return vectors;
    }

    private static void collectIntrinsicBlueIds(
            Object value,
            Set<String> result) {
        if (value instanceof Map) {
            Map<String, Object> map =
                    ConformancePackage.map(value, "program");
            if (map.containsKey("$intrinsic")) {
                Map<String, Object> body = ConformancePackage.map(
                        map.get("$intrinsic"), "$intrinsic");
                Map<String, Object> type = ConformancePackage.map(
                        body.get("type"), "$intrinsic.type");
                result.add(text(type.get("blueId")));
            }
            for (Object child : map.values()) {
                collectIntrinsicBlueIds(child, result);
            }
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) {
                collectIntrinsicBlueIds(child, result);
            }
        }
    }

    private static Map<String, Object> firstStatementBody(
            Map<String, Object> program,
            String operator) {
        List<?> statements = ConformancePackage.list(
                program.get("do"), "program.do");
        Map<String, Object> statement =
                ConformancePackage.map(statements.get(0), "program.do[0]");
        return ConformancePackage.map(
                statement.get(operator), "program.do[0]." + operator);
    }

    private static Map<String, Object> firstAssertion(
            ConformancePackage.Fixture fixture,
            String projection) {
        for (Object value : ConformancePackage.list(
                fixture.expected().get("assertions"),
                fixture.path + ".assertions")) {
            Map<String, Object> assertion =
                    ConformancePackage.map(value, fixture.path + " assertion");
            if (projection.equals(assertion.get("actual"))) {
                return assertion;
            }
        }
        throw new AssertionError(
                fixture.path + " has no assertion for " + projection);
    }

    private static Object firstAssertionExpected(
            ConformancePackage.Fixture fixture,
            String projection) {
        return firstAssertion(fixture, projection).get("expected");
    }

    private static int countRole(
            List<ConformancePackage.ManifestFile> entries,
            String role) {
        int count = 0;
        for (ConformancePackage.ManifestFile entry : entries) {
            if (role.equals(entry.role)) {
                count++;
            }
        }
        return count;
    }

    private static void assertSafeRelativePath(String value) {
        Path path = Paths.get(value);
        assertFalse(path.isAbsolute(), value);
        assertFalse(value.contains("\\"),
                "Manifest paths always use portable '/' separators");
        assertEquals(value, path.normalize().toString().replace('\\', '/'));
        assertFalse(value.startsWith("../") || value.contains("/../"), value);
    }

    private static void assertPackageIdentity(String identity) {
        assertTrue(ConformancePackage.PACKAGE_IDENTITY.matcher(identity).matches(),
                "Non-release package identity: " + identity);
    }

    private static List<String> textList(Object value, String path) {
        List<String> result = new ArrayList<String>();
        for (Object child : ConformancePackage.list(value, path)) {
            result.add(text(child));
        }
        return result;
    }

    private static String text(Object value) {
        assertTrue(value instanceof String, "Expected text but got " + value);
        return (String) value;
    }

    private static int intValue(Object value) {
        return ConformancePackage.integer(value, "integer").intValueExact();
    }

    private static long longValue(Object value) {
        return ConformancePackage.integer(value, "integer").longValueExact();
    }
}
