package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.result.BexMetricsRecorder;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexOperatorCatalogTest {
    private static final String FIXTURE_ROOT =
            "conformance/bex/fixtures/";
    private static final String COVERAGE_RESOURCE =
            FIXTURE_ROOT + "operator-coverage.yaml";
    private static final String VECTOR_COVERAGE_RESOURCE =
            FIXTURE_ROOT + "vector-coverage.yaml";

    @Test
    void catalogExactlyMatchesTheClosedOperatorCoveragePackage() {
        CoveragePackage coverage = coveragePackage();
        List<String> cataloguedOperators = new ArrayList<>();
        for (BexOperatorCatalog.Entry entry : BexOperatorCatalog.entries()) {
            cataloguedOperators.add(entry.canonicalName());
        }

        assertEquals(BexOperatorCatalog.OPERATOR_COUNT,
                coverage.declaredOperatorCount);
        assertEquals(BexOperatorCatalog.OPERATOR_COUNT,
                cataloguedOperators.size());
        assertEquals(BexOperatorCatalog.OPERATOR_COUNT,
                new LinkedHashSet<>(cataloguedOperators).size());
        assertEquals(new ArrayList<>(coverage.fixturesByOperator.keySet()),
                cataloguedOperators,
                "Catalog order and membership must track the normative coverage evidence");

        for (BexOperatorCatalog.Entry entry : BexOperatorCatalog.entries()) {
            Set<String> expectedFixtures = coverage.fixturesByOperator.get(
                    entry.canonicalName());
            assertEquals(expectedFixtures, entry.fixturePaths(),
                    entry.canonicalName() + " fixture coverage drifted");
            assertEquals(coverage.vectorsFor(expectedFixtures),
                    entry.vectorIdentifiers(),
                    entry.canonicalName() + " vector coverage drifted");
            for (String fixture : entry.fixturePaths()) {
                assertNotNull(BexOperatorCatalogTest.class.getClassLoader()
                                .getResource(FIXTURE_ROOT + fixture),
                        "Catalog references missing fixture " + fixture);
            }
        }
    }

    @Test
    void everyEntryHasClosedCompilerRuntimeAndCoverageMetadata() {
        int expressionCount = 0;
        int statementCount = 0;
        int dualRoleCount = 0;
        for (BexOperatorCatalog.Entry entry : BexOperatorCatalog.entries()) {
            assertTrue(entry.canonicalName().startsWith("$"));
            assertFalse(entry.specificationSection().isEmpty());
            assertFalse(entry.operandGrammar().isEmpty());
            assertNotNull(entry.staticOperands());
            assertNotNull(entry.dynamicOperands());
            assertFalse(entry.evaluationContract().isEmpty());
            assertNotNull(entry.compilerFamily());
            assertNotNull(entry.runtimeFamily());
            assertFalse(entry.fixturePaths().isEmpty());
            assertFalse(entry.vectorIdentifiers().isEmpty());
            assertEquals(entry.role().supportsExpression(),
                    BexOperatorCatalog.supportsExpression(entry.canonicalName()));
            assertEquals(entry.role().supportsStatement(),
                    BexOperatorCatalog.supportsStatement(entry.canonicalName()));
            if (entry.role().supportsExpression()) {
                expressionCount++;
            }
            if (entry.role().supportsStatement()) {
                statementCount++;
            }
            if (entry.role() == BexOperatorCatalog.Role.EXPRESSION_AND_STATEMENT) {
                dualRoleCount++;
            }
        }

        assertEquals(75, expressionCount);
        assertEquals(13, statementCount);
        assertEquals(2, dualRoleCount);
        assertEquals(BexOperatorCatalog.Role.EXPRESSION_AND_STATEMENT,
                BexOperatorCatalog.find("$call").role());
        assertEquals(BexOperatorCatalog.Role.EXPRESSION_AND_STATEMENT,
                BexOperatorCatalog.find("$fail").role());
        assertFalse(BexOperatorCatalog.supportsExpression("$unknown"));
        assertFalse(BexOperatorCatalog.supportsStatement("$unknown"));
    }

    @Test
    void representativeEntriesExplicitlyDistinguishStaticDynamicAndLazyOperands() {
        BexOperatorCatalog.Entry is = BexOperatorCatalog.find("$is");
        assertEquals(singleton("pattern"), is.staticOperands());
        assertEquals(singleton("node"), is.dynamicOperands());

        BexOperatorCatalog.Entry intrinsic =
                BexOperatorCatalog.find("$intrinsic");
        assertEquals(setOf("type", "payload.keys"),
                intrinsic.staticOperands());
        assertEquals(singleton("payload.values"),
                intrinsic.dynamicOperands());

        BexOperatorCatalog.Entry appendChange =
                BexOperatorCatalog.find("$appendChange");
        assertEquals(setOf("op", "path"),
                appendChange.staticOperands());
        assertEquals(setOf("op", "path", "val"),
                appendChange.dynamicOperands());
        assertTrue(appendChange.evaluationContract()
                .contains("val-skipped-for-remove"));

        BexOperatorCatalog.Entry choose = BexOperatorCatalog.find("$choose");
        assertTrue(choose.evaluationContract()
                .contains("selected-branch-only"));
        assertTrue(BexOperatorCatalog.find("$and").evaluationContract()
                .startsWith("short-circuit"));
    }

    @Test
    void catalogViewsCannotBeMutated() {
        assertThrows(UnsupportedOperationException.class,
                () -> BexOperatorCatalog.entries().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> BexOperatorCatalog.find("$add")
                        .fixturePaths().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> BexOperatorCatalog.find("$add")
                        .vectorIdentifiers().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> BexOperatorCatalog.find("$is")
                        .staticOperands().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> BexOperatorCatalog.find("$is")
                        .dynamicOperands().clear());
    }

    @Test
    void compilerRecognitionFailsClosedWithStableUnknownOperatorDiagnostics() {
        BexProgramCompiler compiler = new BexProgramCompiler(
                new BexMetricsRecorder(), null);

        assertUnknownExpression(compiler, "$unknown");
        assertUnknownExpression(compiler, "$let");
        assertNotNull(compiler.compileOperator(
                "$null", null, new CompileScope(), "/$null"));
    }

    @SuppressWarnings("unchecked")
    private static CoveragePackage coveragePackage() {
        Map<String, Object> operatorSource = loadYaml(COVERAGE_RESOURCE);
        Map<String, Set<String>> fixturesByOperator = new LinkedHashMap<>();
        for (Map<String, Object> row
                : (List<Map<String, Object>>) operatorSource.get("operators")) {
            fixturesByOperator.put((String) row.get("operator"),
                    immutableSet((List<String>) row.get("fixtures")));
        }

        Map<String, Object> vectorSource = loadYaml(VECTOR_COVERAGE_RESOURCE);
        Map<String, Set<String>> vectorsByFixture = new LinkedHashMap<>();
        Map<String, List<String>> vectors =
                (Map<String, List<String>>) vectorSource.get("vectors");
        for (Map.Entry<String, List<String>> vector : vectors.entrySet()) {
            for (String fixture : vector.getValue()) {
                Set<String> fixtureVectors = vectorsByFixture.get(fixture);
                if (fixtureVectors == null) {
                    fixtureVectors = new LinkedHashSet<>();
                    vectorsByFixture.put(fixture, fixtureVectors);
                }
                fixtureVectors.add(vector.getKey());
            }
        }
        return new CoveragePackage(
                ((Number) operatorSource.get("operatorCount")).intValue(),
                fixturesByOperator, vectorsByFixture);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream stream = BexOperatorCatalogTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, "Missing " + resource);
            return (Map<String, Object>) new Yaml().load(stream);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + resource, ex);
        }
    }

    private static Set<String> singleton(String value) {
        Set<String> result = new LinkedHashSet<>();
        result.add(value);
        return result;
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private static Set<String> immutableSet(List<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static final class CoveragePackage {
        private final int declaredOperatorCount;
        private final Map<String, Set<String>> fixturesByOperator;
        private final Map<String, Set<String>> vectorsByFixture;

        private CoveragePackage(int declaredOperatorCount,
                                Map<String, Set<String>> fixturesByOperator,
                                Map<String, Set<String>> vectorsByFixture) {
            this.declaredOperatorCount = declaredOperatorCount;
            this.fixturesByOperator = fixturesByOperator;
            this.vectorsByFixture = vectorsByFixture;
        }

        private Set<String> vectorsFor(Set<String> fixtures) {
            Set<String> result = new LinkedHashSet<>();
            for (String fixture : fixtures) {
                Set<String> vectors = vectorsByFixture.get(fixture);
                assertNotNull(vectors,
                        "Fixture missing from vector coverage: " + fixture);
                result.addAll(vectors);
            }
            return result;
        }
    }

    private static void assertUnknownExpression(BexProgramCompiler compiler,
                                                String operator) {
        BexException failure = assertThrows(BexException.class,
                () -> compiler.compileOperator(operator, null,
                        new CompileScope(), "/" + operator));
        assertEquals("Unknown expression operator: " + operator,
                failure.getMessage());
    }
}
