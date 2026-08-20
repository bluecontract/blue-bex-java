package blue.bex.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexConformanceReportTruthfulnessTest {
    @Test
    void aggregateVariantEvidenceDoesNotAttributeFailureToEveryVariant() {
        assertEquals(
                "passed",
                BexConformanceReportMain.aggregateVariantStatus("passed"));
        assertEquals(
                "indeterminate-after-fixture-failure",
                BexConformanceReportMain.aggregateVariantStatus("failed"));
        assertEquals(
                "not-executed",
                BexConformanceReportMain.aggregateVariantStatus("skipped"));
        assertEquals(
                "not-executed",
                BexConformanceReportMain.aggregateVariantStatus(
                        "not-executed"));
    }

    @Test
    void normativeVectorStatusIsDerivedFromEveryMappedFixture() {
        assertEquals(
                "passed",
                BexConformanceReportMain.aggregateVectorStatus(
                        Arrays.asList("passed", "passed")));
        assertEquals(
                "failed",
                BexConformanceReportMain.aggregateVectorStatus(
                        Arrays.asList("passed", "failed")));
        assertEquals(
                "not-executed",
                BexConformanceReportMain.aggregateVectorStatus(
                        Arrays.asList("passed", "not-executed")));
        assertEquals(
                "invalid-reference",
                BexConformanceReportMain.aggregateVectorStatus(
                        Collections.<String>emptyList()));
    }

    @Test
    void cleanBuildReceiptMustMatchEveryAggregateInput() {
        Map<String, String> receipt =
                new LinkedHashMap<String, String>();
        receipt.put(
                "schema",
                "blue-bex-clean-build-artifacts/1.1");
        receipt.put("status", "passed");
        receipt.put("checkout.clean", "true");
        receipt.put("checkout.root", "/checkout/one");
        receipt.put("checkout.gitDirectory", "/checkout/one/.git");
        receipt.put("checkout.gitStatusSha256", "status");
        receipt.put("checkout.workspaceSha256", "workspace");
        receipt.put("checkout.pathCount", "12");
        receipt.put("commit", "commit");
        receipt.put("project.version", "2.0.0");
        receipt.put("dependency.mode", "standalone-published");
        receipt.put("dependency.coordinate", "group:name:version");
        receipt.put(
                "dependency.effectiveCoordinate",
                "group:name:version");
        receipt.put(
                "dependency.artifact.path",
                "build/reports/bex-release/clean-build-inputs/"
                        + "blue-language-java.jar");
        receipt.put("dependency.artifact.bytes", "8");
        receipt.put("dependency.artifact.sha256", "language");
        receipt.put("composite.path", "");
        receipt.put("composite.commit", "");
        receipt.put("composite.dirty", "false");
        receipt.put("composite.gitStatusSha256", "");
        receipt.put("composite.workspaceSha256", "");
        receipt.put("composite.pathCount", "0");
        receipt.put(
                "artifact.main.path",
                "build/libs/blue-bex-java-2.0.0.jar");
        receipt.put("artifact.main.bytes", "4");
        receipt.put("artifact.main.sha256", "main");

        Map<String, String> aggregate =
                new LinkedHashMap<String, String>(receipt);
        aggregate.put("first.checkout.clean", "true");
        aggregate.put(
                "first.checkout.root",
                receipt.get("checkout.root"));
        aggregate.put(
                "first.checkout.gitDirectory",
                receipt.get("checkout.gitDirectory"));
        aggregate.put(
                "first.checkout.gitStatusSha256",
                receipt.get("checkout.gitStatusSha256"));
        aggregate.put(
                "first.checkout.workspaceSha256",
                receipt.get("checkout.workspaceSha256"));
        aggregate.put(
                "first.checkout.pathCount",
                receipt.get("checkout.pathCount"));
        aggregate.put(
                "first.dependency.artifact.path",
                receipt.get("dependency.artifact.path"));
        aggregate.put(
                "first.dependency.artifact.bytes",
                receipt.get("dependency.artifact.bytes"));
        aggregate.put(
                "first.dependency.artifact.sha256",
                receipt.get("dependency.artifact.sha256"));
        aggregate.put(
                "first.artifact.main.path",
                receipt.get("artifact.main.path"));
        aggregate.put(
                "first.artifact.main.bytes",
                receipt.get("artifact.main.bytes"));
        aggregate.put(
                "first.artifact.main.sha256",
                receipt.get("artifact.main.sha256"));
        aggregate.put(
                "artifact.main.byteIdentical",
                "true");

        assertTrue(
                BexConformanceReportMain
                        .cleanBuildReceiptMatchesAggregate(
                                aggregate,
                                "first",
                                receipt,
                                Collections.singleton("main")));

        receipt.put("commit", "different");
        assertFalse(
                BexConformanceReportMain
                        .cleanBuildReceiptMatchesAggregate(
                                aggregate,
                                "first",
                                receipt,
                                Collections.singleton("main")));
    }

    @Test
    void emptyDirectoriesCannotMasqueradeAsCleanGitCheckouts(
            @TempDir Path temporaryDirectory) throws Exception {
        Path checkout =
                Files.createDirectory(
                        temporaryDirectory.resolve("checkout"));
        Path fakeGitDirectory =
                Files.createDirectory(
                        temporaryDirectory.resolve("fake-git"));

        assertFalse(
                BexConformanceReportMain.liveCleanCheckoutMatches(
                        checkout,
                        fakeGitDirectory,
                        "first",
                        Collections.<String, String>emptyMap(),
                        "0000000000000000000000000000000000000000"));
    }

    @Test
    void cleanBuildReceiptMustDescribeLiveArtifactBytes(
            @TempDir Path temporaryDirectory) throws Exception {
        Path artifact = temporaryDirectory.resolve(
                "build/libs/blue-bex-java-2.0.0.jar");
        Files.createDirectories(artifact.getParent());
        byte[] content = "real-artifact".getBytes(
                StandardCharsets.UTF_8);
        Files.write(artifact, content);

        Map<String, String> receipt =
                new LinkedHashMap<String, String>();
        receipt.put(
                "artifact.main.path",
                "build/libs/blue-bex-java-2.0.0.jar");
        receipt.put(
                "artifact.main.bytes",
                String.valueOf(content.length));
        receipt.put(
                "artifact.main.sha256",
                ConformancePackage.sha256(content));

        assertTrue(
                BexConformanceReportMain
                        .cleanBuildReceiptArtifactsMatch(
                                temporaryDirectory,
                                receipt,
                                Collections.singleton("main"),
                                "2.0.0"));

        Files.write(
                artifact,
                "mutated".getBytes(StandardCharsets.UTF_8));
        assertFalse(
                BexConformanceReportMain
                        .cleanBuildReceiptArtifactsMatch(
                                temporaryDirectory,
                                receipt,
                                Collections.singleton("main"),
                                "2.0.0"));
    }

    @Test
    void cleanBuildReceiptMustPreserveExactLanguageArtifact(
            @TempDir Path temporaryDirectory) throws Exception {
        Path artifact = temporaryDirectory.resolve(
                "build/reports/bex-release/clean-build-inputs/"
                        + "blue-language-java.jar");
        Files.createDirectories(artifact.getParent());
        byte[] content = "language-artifact".getBytes(
                StandardCharsets.UTF_8);
        Files.write(artifact, content);

        Map<String, String> receipt =
                new LinkedHashMap<String, String>();
        receipt.put(
                "dependency.artifact.path",
                "build/reports/bex-release/clean-build-inputs/"
                        + "blue-language-java.jar");
        receipt.put(
                "dependency.artifact.bytes",
                String.valueOf(content.length));
        receipt.put(
                "dependency.artifact.sha256",
                ConformancePackage.sha256(content));

        assertTrue(
                BexConformanceReportMain
                        .cleanBuildReceiptDependencyMatches(
                                temporaryDirectory,
                                receipt));

        Files.write(
                artifact,
                "different-language".getBytes(
                        StandardCharsets.UTF_8));
        assertFalse(
                BexConformanceReportMain
                        .cleanBuildReceiptDependencyMatches(
                                temporaryDirectory,
                                receipt));
    }

    @Test
    void rotatingRcVersionIsCheckedSemantically(
            @TempDir Path temporaryDirectory) throws Exception {
        Files.write(
                temporaryDirectory.resolve(".cz.toml"),
                Arrays.asList(
                        "[tool.commitizen]",
                        "version_scheme = \"semver\"",
                        "version = \"2.0.0-rc.7\""),
                StandardCharsets.UTF_8);

        Map<String, Object> release =
                BexConformanceReportMain.versionAutomationEvidence(
                        temporaryDirectory,
                        "2.0.0-rc.7",
                        Collections.<String, String>emptyMap(),
                        "standalone-published");
        Map<String, Object> snapshot =
                BexConformanceReportMain.versionAutomationEvidence(
                        temporaryDirectory,
                        "2.0.0-rc.7-SNAPSHOT",
                        Collections.<String, String>emptyMap(),
                        "standalone-published");
        Map<String, Object> mismatched =
                BexConformanceReportMain.versionAutomationEvidence(
                        temporaryDirectory,
                        "2.0.0-rc.8",
                        Collections.<String, String>emptyMap(),
                        "standalone-published");
        Map<String, Object> staged =
                BexConformanceReportMain.versionAutomationEvidence(
                        temporaryDirectory,
                        "2.0.0-rc.8",
                        Collections.<String, String>emptyMap(),
                        "staged-repository");

        assertTrue(Boolean.TRUE.equals(
                release.get("matchesProjectVersion")));
        assertTrue(Boolean.TRUE.equals(
                snapshot.get("matchesProjectVersion")));
        assertFalse(Boolean.TRUE.equals(
                mismatched.get("matchesProjectVersion")));
        assertTrue(Boolean.TRUE.equals(
                staged.get("matchesProjectVersion")));
        assertEquals(
                "2.0.0-rc.7",
                staged.get("historicalConfiguredVersion"));
        assertEquals(
                "explicit-staged-candidate",
                staged.get("selectionKind"));
    }

    @Test
    void onlyFreshStandaloneRunCanReplaceModeEvidence() {
        Map<String, Object> dependency =
                new LinkedHashMap<String, Object>();
        Map<String, Object> cache =
                new LinkedHashMap<String, Object>();
        dependency.put("cleanDependencyCacheAcceptance", cache);

        cache.put("freshProofRequired", Boolean.TRUE);
        cache.put("status", "passed");
        assertTrue(
                BexConformanceReportMain.modeRunCanPersistEvidence(
                        "standalone-published", dependency));

        cache.put("freshProofRequired", Boolean.FALSE);
        assertFalse(
                BexConformanceReportMain.modeRunCanPersistEvidence(
                        "standalone-published", dependency));

        cache.put("freshProofRequired", Boolean.TRUE);
        cache.put("status", "failed");
        assertFalse(
                BexConformanceReportMain.modeRunCanPersistEvidence(
                        "standalone-published", dependency));

        assertFalse(
                BexConformanceReportMain.modeRunCanPersistEvidence(
                        "local-composite", dependency));
    }

    @Test
    void stagedCandidateDoesNotReadHistoricalPublicReleaseModes(
            @TempDir Path temporaryDirectory) {
        Map<String, Object> modes =
                BexConformanceReportMain.publicReleaseMatrixNotSelected(
                        temporaryDirectory);

        Map<?, ?> standalone = (Map<?, ?>) modes.get("standalonePublished");
        Map<?, ?> local = (Map<?, ?>) modes.get("localComposite");
        assertEquals(
                "not-applicable-to-staged-candidate",
                standalone.get("status"));
        assertEquals(Boolean.FALSE, standalone.get("evidenceRead"));
        assertEquals(
                "not-applicable-to-published-only-release",
                local.get("status"));
        assertEquals(Boolean.FALSE, local.get("evidenceRead"));
        assertEquals(Boolean.FALSE, modes.get("localCompositeRequired"));
        assertFalse(Boolean.TRUE.equals(
                modes.get("allRequiredPublishedEvidencePassed")));
    }

    @Test
    void standaloneModeReceiptUsesTheStrictValidationVocabulary() {
        Map<String, Object> provenance =
                new LinkedHashMap<String, Object>();
        provenance.put("status", "passed");
        provenance.put(
                "resolvedHashMatchesRecordedMavenCentralHash",
                Boolean.TRUE);
        Map<String, Object> cache =
                new LinkedHashMap<String, Object>();
        cache.put("scope", "all focused and aggregate Language modules");

        assertEquals(
                "verified-against-recorded-maven-central-hash",
                BexConformanceReportMain.modeEvidenceProvenanceStatus(
                        "standalone-published", provenance));
        assertEquals(
                "standalone-published-blue-language-module-version-cache",
                BexConformanceReportMain.modeEvidenceCacheScope(
                        "standalone-published", cache));
        assertEquals(
                "not-applicable-to-published-only-release",
                BexConformanceReportMain.modeEvidenceProvenanceStatus(
                        "local-composite", provenance));
        assertEquals(
                "not-applicable-to-published-only-release",
                BexConformanceReportMain.modeEvidenceCacheScope(
                        "local-composite", cache));

        provenance.put(
                "resolvedHashMatchesRecordedMavenCentralHash",
                Boolean.FALSE);
        assertEquals(
                "failed",
                BexConformanceReportMain.modeEvidenceProvenanceStatus(
                        "standalone-published", provenance));
    }

    @Test
    void publishedSourceIdentityRequiresExactRc21CommitAndVersionTag() {
        String coordinate =
                "blue.language:blue-language-java:3.1.0-rc.21";
        String commit =
                "0123456789abcdef0123456789abcdef01234567";
        Map<String, String> inspection =
                new LinkedHashMap<String, String>();
        inspection.put("coordinate", coordinate);
        inspection.put("source.commit", commit);
        inspection.put("source.tag", "v3.1.0-rc.21");

        assertTrue(
                BexConformanceReportMain
                        .publishedSourceIdentityMatches(
                                coordinate,
                                inspection,
                                commit,
                                Collections.singleton(
                                        "v3.1.0-rc.21")));
        assertFalse(
                BexConformanceReportMain
                        .publishedSourceIdentityMatches(
                                coordinate,
                                inspection,
                                "1123456789abcdef0123456789abcdef01234567",
                                Collections.singleton(
                                        "v3.1.0-rc.21")));
        assertFalse(
                BexConformanceReportMain
                        .publishedSourceIdentityMatches(
                                coordinate,
                                inspection,
                                commit,
                                Collections.singleton(
                                        "v3.1.0-rc.21-invalid")));

        inspection.put("source.tag", "release-3.1.0-rc.21");
        assertFalse(
                BexConformanceReportMain
                        .publishedSourceIdentityMatches(
                                coordinate,
                                inspection,
                                commit,
                                Collections.singleton(
                                        "release-3.1.0-rc.21")));
    }

    @Test
    void finalIdentityRequiresValidatedStandalonePublishedMode() {
        Map<String, Object> identity =
                new LinkedHashMap<String, Object>();
        identity.put(
                "currentDependencyExactFinalArtifactProven",
                Boolean.TRUE);
        identity.put("exactFinalArtifactProven", Boolean.TRUE);
        identity.put(
                "failures",
                Collections.emptyList());

        Map<String, Object> standalone =
                new LinkedHashMap<String, Object>();
        standalone.put("status", "stale-or-failed");
        Map<String, Object> modes =
                Collections.singletonMap(
                        "standalonePublished",
                        standalone);

        assertFalse(
                BexConformanceReportMain
                        .bindLanguageReleaseIdentityToModes(
                                identity,
                                modes));
        assertEquals(
                Boolean.FALSE,
                identity.get(
                        "exactFinalArtifactProven"));
        assertEquals(
                Boolean.FALSE,
                identity.get(
                        "validatedStandalonePublishedMode"));
        assertEquals(
                Boolean.FALSE,
                identity.get(
                        "validatedStandalonePublishedModeAuthenticatesArtifact"));
        assertEquals(
                "not-applicable-to-published-only-release",
                identity.get("localCompositeStatus"));
        assertTrue(
                String.valueOf(identity.get("failures"))
                        .contains(
                                "validated-standalone-published-mode-not-authenticated"));

        Map<String, Object> authenticatedIdentity =
                new LinkedHashMap<String, Object>();
        authenticatedIdentity.put(
                "currentDependencyExactFinalArtifactProven",
                Boolean.TRUE);
        authenticatedIdentity.put("failures", Collections.emptyList());
        standalone.put("status", "passed");

        assertTrue(
                BexConformanceReportMain
                        .bindLanguageReleaseIdentityToModes(
                                authenticatedIdentity,
                                modes));
        assertEquals(
                Boolean.TRUE,
                authenticatedIdentity.get("exactFinalArtifactProven"));
        assertEquals(
                Boolean.TRUE,
                authenticatedIdentity.get(
                        "validatedStandalonePublishedModeAuthenticatesArtifact"));
    }
}
