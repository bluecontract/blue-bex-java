package blue.bex.conformance;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexIdentityMigrationInventoryTest {
    private static final Path PROJECT = Paths.get("")
            .toAbsolutePath().normalize();
    private static final Path REPORT = PROJECT.resolve(
            "reports/migration/empty-object-identity-impact.json");

    private static final String OLD_LIST =
            "8DSFoWG9MqRSUhStqoPLrwVQiYByRh18NWbDEarN8MKF";
    private static final String OLD_SORT =
            "2R1WaEk8LVwFRMEGnsZ8HTj15QTz3tQEj9LDYYjGFJJG";
    private static final String OLD_REGISTRY =
            "sha256:23d282ec1c0bb016263922b1b49c369fdd537efdcf23e005eceeb888d7763fe1";
    private static final String OLD_CURRENT_FIXTURES =
            "sha256:dc2a1a999d8af0cf67ec96c8deb5c0242c9b9ec5a6a290574b8365c4e5545ec2";

    @Test
    void inventoryBindsEveryCurrentBexIdentity() throws Exception {
        Map<String, Object> report = report();
        assertEquals("blue-identity-impact/1.0", report.get("schema"));
        assertEquals(
                "behavior-identity-closure-complete-release-sealing-pending",
                report.get("status"));
        assertEquals(Boolean.TRUE, report.get("noCompatibilityAliases"));
        assertTrue(ConformancePackage.list(
                report.get("compatibilityAliases"),
                "compatibilityAliases").isEmpty());

        List<?> artifacts = ConformancePackage.list(
                report.get("artifacts"), "artifacts");
        Map<String, Object> summary = ConformancePackage.map(
                report.get("summary"), "summary");
        assertEquals(summary.get("artifactCount"), artifacts.size());
        Map<String, Integer> actualClassifications =
                new LinkedHashMap<String, Integer>();
        for (Object value : artifacts) {
            String classification = String.valueOf(
                    ConformancePackage.map(value, "artifact")
                            .get("classification"));
            Integer count = actualClassifications.get(classification);
            actualClassifications.put(
                    classification, count == null ? 1 : count + 1);
        }
        assertEquals(
                ConformancePackage.map(
                        summary.get("classifications"),
                        "summary.classifications"),
                actualClassifications);

        Map<String, Object> registry = ConformancePackage.registryManifest();
        Map<String, Object> fixtures = ConformancePackage.fixtureManifest();
        assertEquals(
                registry.get("packageIdentity"),
                artifact(report, "package:bex-runtime-registry")
                        .get("newExactIdentity"));
        assertEquals(
                fixtures.get("packageIdentity"),
                artifact(report, "package:bex-fixtures")
                        .get("newExactIdentity"));
        assertEquals(
                registryBlueId(registry, "SortFixtureIntrinsic"),
                artifact(report, "bex:SortFixtureIntrinsic")
                        .get("newExactIdentity"));

        assertEquals(
                ConformancePackage.packageIdentity(
                        ConformancePackage.gasManifest(),
                        "packageIdentity"),
                artifact(report, "package:bex-gas")
                        .get("newExactIdentity"));
        Path fixture = PROJECT.resolve(
                "src/test/resources/conformance/bex/fixtures/g/bex-g-09.yaml");
        assertEquals(
                "sha256:" + ConformancePackage.sha256(
                        Files.readAllBytes(fixture)),
                artifact(report, "fixture:bex-g-09")
                        .get("newExactIdentity"));

        Path specification = PROJECT.resolve(
                "specifications/blue-bex-specification-2.0.md");
        assertEquals(
                "sha256:" + ConformancePackage.sha256(
                        Files.readAllBytes(specification)),
                artifact(report, "document:bex-specification")
                        .get("newExactIdentity"));
    }

    @Test
    void oldRotatedIdentitiesAreAbsentFromActiveSurfaces()
            throws Exception {
        List<String> oldIdentities = Arrays.asList(
                OLD_LIST,
                OLD_SORT,
                OLD_REGISTRY,
                OLD_CURRENT_FIXTURES);
        List<Path> activeSurfaces = Arrays.asList(
                PROJECT.resolve("blue-bex-core/src/main/java"),
                PROJECT.resolve("src/test/java"),
                PROJECT.resolve("src/test/resources/conformance"),
                PROJECT.resolve("specifications"),
                PROJECT.resolve("docs/FIXTURES.md"),
                PROJECT.resolve("docs/conformance.md"),
                PROJECT.resolve(
                        "gradle/verification/sdk-stage-language-baseline.json"));

        for (Path surface : activeSurfaces) {
            try (Stream<Path> paths = Files.isDirectory(surface)
                    ? Files.walk(surface)
                    : Stream.of(surface)) {
                for (Path path : (Iterable<Path>) paths
                        .filter(Files::isRegularFile)
                        .filter(candidate -> !candidate.equals(
                                PROJECT.resolve(
                                        "src/test/java/blue/bex/conformance/"
                                                + "BexIdentityMigrationInventoryTest.java")))
                        ::iterator) {
                    String content = new String(
                            Files.readAllBytes(path),
                            StandardCharsets.UTF_8);
                    for (String oldIdentity : oldIdentities) {
                        assertFalse(
                                content.contains(oldIdentity),
                                path + " retains obsolete identity "
                                        + oldIdentity);
                    }
                }
            }
        }
    }

    @Test
    void historicalBaselinesRemainExplicitAndReleaseClosureStaysOpen()
            throws Exception {
        String hostedBaseline = text(
                "src/test/resources/hosted-release/baseline.properties");
        String migrationBaseline = text(
                "gradle/verification/latest-language-baseline.json");
        assertTrue(hostedBaseline.contains(OLD_REGISTRY));
        assertTrue(hostedBaseline.contains(
                "sha256:a1b7bb2b3687389409bc9d0aa450c734f7856d2bcb818c95f4d7ecb19095d20e"));
        assertTrue(hostedBaseline.contains(
                "1725878bcb59f2d2a60bae2ada582a18dc964f4dbc61377aaae195a773765f92"));
        assertTrue(migrationBaseline.contains(OLD_REGISTRY));

        Map<String, Object> report = report();
        assertTrue(ConformancePackage.list(
                report.get("activeOldIdentityReferences"),
                "activeOldIdentityReferences").isEmpty());
        assertEquals(
                4,
                ConformancePackage.list(
                        report.get("pendingReleaseBindings"),
                        "pendingReleaseBindings").size());
        Map<String, Object> summary = ConformancePackage.map(
                report.get("summary"), "summary");
        assertEquals(4, summary.get("unresolvedArtifactCount"));
    }

    private static Map<String, Object> report() throws Exception {
        try (InputStream input = Files.newInputStream(REPORT)) {
            return ConformancePackage.map(
                    new Yaml().load(input), "identity impact report");
        }
    }

    private static Map<String, Object> artifact(
            Map<String, Object> report,
            String stableKey) {
        for (Object value : ConformancePackage.list(
                report.get("artifacts"), "artifacts")) {
            Map<String, Object> artifact =
                    ConformancePackage.map(value, "artifact");
            if (stableKey.equals(artifact.get("stableKey"))) {
                return artifact;
            }
        }
        throw new AssertionError("Missing identity artifact " + stableKey);
    }

    private static String registryBlueId(
            Map<String, Object> registry,
            String key) {
        for (Object value : ConformancePackage.list(
                registry.get("entries"), "registry.entries")) {
            Map<String, Object> entry =
                    ConformancePackage.map(value, "registry entry");
            if (key.equals(entry.get("key"))) {
                return String.valueOf(entry.get("blueId"));
            }
        }
        throw new AssertionError("Missing registry entry " + key);
    }

    private static String text(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(PROJECT.resolve(relativePath)),
                StandardCharsets.UTF_8);
    }
}
