package blue.bex.conformance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps human-readable conformance claims bound to the machine-readable
 * package rather than to copied historical counts or identities.
 */
class BexConformanceDocumentationConsistencyTest {
    private static final String SPECIFICATION =
            "specifications/blue-bex-specification-2.0.md";
    private static final String FIXTURE_GUIDE = "docs/FIXTURES.md";

    @Test
    void specificationAndFixtureGuideMatchMachineReadablePackage()
            throws Exception {
        Map<String, Object> fixtureManifest =
                ConformancePackage.fixtureManifest();
        Map<String, Object> gasManifest =
                ConformancePackage.gasManifest();
        Map<String, Object> registryManifest =
                ConformancePackage.registryManifest();
        Map<String, Object> operatorCoverage = ConformancePackage.loadMap(
                ConformancePackage.FIXTURE_ROOT + "operator-coverage.yaml");

        int vectors = integer(fixtureManifest, "vectorCount");
        int behaviorFixtures =
                integer(fixtureManifest, "behaviorFixtureCount");
        int gasFixtures = integer(fixtureManifest, "gasFixtureCount");
        int operators = integer(operatorCoverage, "operatorCount");
        String fixtureIdentity = text(fixtureManifest, "packageIdentity");
        String gasIdentity = text(gasManifest, "packageIdentity");
        String registryIdentity = text(registryManifest, "packageIdentity");

        assertEquals(registryIdentity,
                fixtureManifest.get("registryPackageIdentity"));
        assertEquals(gasIdentity,
                fixtureManifest.get("gasManifestPackageIdentity"));
        assertEquals(fixtureIdentity,
                registryManifest.get("fixturePackageIdentity"));

        assertDocument(
                SPECIFICATION,
                vectors,
                behaviorFixtures,
                gasFixtures,
                operators,
                registryIdentity,
                gasIdentity,
                fixtureIdentity);
        assertDocument(
                FIXTURE_GUIDE,
                vectors,
                behaviorFixtures,
                gasFixtures,
                operators,
                registryIdentity,
                gasIdentity,
                fixtureIdentity);
    }

    private static void assertDocument(
            String relativePath,
            int vectors,
            int behaviorFixtures,
            int gasFixtures,
            int operators,
            String registryIdentity,
            String gasIdentity,
            String fixtureIdentity) throws Exception {
        Path path = Paths.get("").toAbsolutePath().resolve(relativePath);
        assertTrue(Files.isRegularFile(path),
                "Missing conformance document: " + relativePath);
        String document = new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);

        assertEquals(String.valueOf(vectors),
                field(document, "normative vectors", relativePath));
        assertEquals(String.valueOf(behaviorFixtures),
                field(document, "behavior fixtures", relativePath));
        assertEquals(String.valueOf(gasFixtures),
                field(document, "gas microfixtures", relativePath));
        assertEquals(String.valueOf(operators),
                field(document, "normative operators", relativePath));
        assertEquals(registryIdentity,
                field(document, "runtime registry", relativePath));
        assertEquals(gasIdentity,
                field(document, "gas manifest", relativePath));
        assertEquals(fixtureIdentity,
                field(document, "fixture package", relativePath));
    }

    private static String field(
            String document,
            String name,
            String relativePath) {
        Pattern pattern = Pattern.compile(
                "(?m)^" + Pattern.quote(name) + ":[ \\t]*(\\S+)[ \\t]*$");
        Matcher matcher = pattern.matcher(document);
        assertTrue(matcher.find(),
                relativePath + " must state " + name);
        String value = matcher.group(1);
        assertFalse(matcher.find(),
                relativePath + " must state " + name + " exactly once");
        return value;
    }

    private static int integer(Map<String, Object> source, String key) {
        return ConformancePackage.integer(
                source.get(key), key).intValueExact();
    }

    private static String text(Map<String, Object> source, String key) {
        return ConformancePackage.text(source.get(key), key);
    }
}
