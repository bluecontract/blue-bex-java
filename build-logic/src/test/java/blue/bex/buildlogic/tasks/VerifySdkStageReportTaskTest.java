package blue.bex.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.json.JsonOutput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VerifySdkStageReportTaskTest {
    private static final String LANGUAGE_COMMIT =
            "e0dfc897ea7d158895325fae2bf84e103b8c1989";
    private static final String LANGUAGE_VERSION =
            "3.1.0-dev." + LANGUAGE_COMMIT;
    private static final String SPECIFICATION_SHA256 =
            "208510d68ae278c63ad60d90c6a9724cf1e52e2e533ac5753904a645b7c13751";
    private static final List<String> LANGUAGE_ARTIFACTS = Arrays.asList(
            "blue-language-model",
            "blue-language-core",
            "blue-language-mapping",
            "blue-language-ipfs",
            "blue-contracts-core",
            "blue-language-java");

    @TempDir
    Path temporaryDirectory;

    private String languageManifestIdentity;

    @Test
    void localRcRequiresCompleteLanguagePublicationsAndCleanLockedBexSource() throws Exception {
        VerifySdkStageReportTask task = task(true);
        task.verify();
        Path report = task.getConformanceReport().get().getAsFile().toPath();
        String clean = Files.readString(report);
        Files.writeString(report, clean.replace("\"worktreeDirty\": false", "\"worktreeDirty\": true"));
        assertThrows(GradleException.class, task::verify);
        Files.writeString(report, clean);
        Path sources = task.getLanguageRepository().get().getAsFile().toPath().resolve(
                "blue/language/blue-conformance/3.1.0-rc.24/blue-conformance-3.1.0-rc.24-sources.jar");
        Files.writeString(sources, "tampered source archive");
        assertThrows(GradleException.class, task::verify);
    }

    @Test
    void passesOnlyExactBlockerFreeStagedEvidence() throws Exception {
        VerifySdkStageReportTask task = task();
        task.verify();

        String receipt = Files.readString(
                task.getOutputFile().get().getAsFile().toPath());
        assertTrue(receipt.contains("\"status\": \"passed\""));
        assertTrue(receipt.contains(
                LANGUAGE_COMMIT));
        assertTrue(receipt.contains(
                SPECIFICATION_SHA256));
        assertTrue(receipt.contains(languageManifestIdentity));
    }

    @Test
    void writesFailureReceiptBeforeRejectingConformanceBlockers()
            throws Exception {
        VerifySdkStageReportTask task = task();
        Path report = task.getConformanceReport().get().getAsFile().toPath();
        Files.writeString(
                report,
                Files.readString(report).replace(
                        "\"currentModeFailures\": []",
                        "\"currentModeFailures\": [\"failed-test\"]"));

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("\"status\": \"failed\""));
    }

    @Test
    void acceptsOnlyCleanCommitBoundDevelopmentVersion() throws Exception {
        String commit = "2222222222222222222222222222222222222222";
        String version = "1.1.0-dev." + commit;
        VerifySdkStageReportTask task = task();
        Path baseline = task.getCandidateBaseline().get().getAsFile().toPath();
        Files.writeString(
                baseline,
                Files.readString(baseline).replace(
                        "\"candidateVersion\": \"1.1.0-rc.4\"",
                        "\"candidateVersion\": \"commit-bound-development\""));
        Path report = task.getConformanceReport().get().getAsFile().toPath();
        Files.writeString(
                report,
                Files.readString(report)
                        .replace("\"projectVersion\": \"1.1.0-rc.4\"",
                                "\"projectVersion\": \"" + version + "\"")
                        .replace("\"currentModeFailures\": []",
                                "\"sourceState\": {"
                                        + "\"commit\": \"" + commit + "\","
                                        + "\"releaseInputsCommitted\": true,"
                                        + "\"worktreeDirty\": false},"
                                        + "\"currentModeFailures\": []"));
        task.getProjectVersion().set(version);

        task.verify();
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("\"bexSourceCommit\": \"" + commit + "\""));

        Files.writeString(
                report,
                Files.readString(report).replace(
                        "\"commit\": \"" + commit + "\"",
                        "\"commit\": \"3333333333333333333333333333333333333333\""));
        assertThrows(GradleException.class, task::verify);
    }

    @Test
    void rejectsTamperedCurrentSpecification() throws Exception {
        VerifySdkStageReportTask task = task();
        Files.writeString(
                task.getCurrentSpecification().get().getAsFile().toPath(),
                "tampered specification\n");

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("bex-current-specification-lock-mismatch"));
    }

    @Test
    void rejectsMissingSpecificationLock() throws Exception {
        VerifySdkStageReportTask task = task();
        Path baseline = task.getCandidateBaseline().get().getAsFile().toPath();
        Files.writeString(
                baseline,
                Files.readString(baseline).replace(
                        "\"currentSpecificationSha256\": "
                                + "\"208510d68ae278c63ad60d90c6a9724cf1e52e2e533ac5753904a645b7c13751\",",
                        ""));

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("bex-current-specification-lock-missing"));
    }

    @Test
    void rejectsMismatchedSpecificationReport() throws Exception {
        VerifySdkStageReportTask task = task();
        Path report = task.getConformanceReport().get().getAsFile().toPath();
        Files.writeString(
                report,
                Files.readString(report).replace(
                        "208510d68ae278c63ad60d90c6a9724cf1e52e2e533ac5753904a645b7c13751",
                        "0000000000000000000000000000000000000000000000000000000000000000"));

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("conformance-specification-hash-mismatch"));
    }

    @Test
    void rejectsLanguageManifestWhoseBytesDifferFromPinnedIdentity()
            throws Exception {
        VerifySdkStageReportTask task = task();
        Path manifest = task.getLanguageRepository().get().getAsFile()
                .toPath().resolve("artifact-manifest.json");
        Files.writeString(manifest, Files.readString(manifest) + " \n");

        assertThrows(GradleException.class, task::verify);
        String receipt = Files.readString(
                task.getOutputFile().get().getAsFile().toPath());
        assertTrue(receipt.contains(
                "language-artifact-manifest-lock-mismatch"));
        assertTrue(receipt.contains(
                "language-artifact-manifest-sidecar-mismatch"));
    }

    @Test
    void rejectsMissingLanguageManifestIdentityLock() throws Exception {
        VerifySdkStageReportTask task = task();
        Path baseline = task.getCandidateBaseline().get().getAsFile().toPath();
        Files.writeString(baseline, Files.readString(baseline).replace(
                "    \"artifactManifestIdentity\": \""
                        + languageManifestIdentity + "\",\n",
                ""));

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("language-artifact-manifest-lock-missing"));
    }

    @Test
    void rejectsManifestCandidateAndSourceDifferentFromBaseline()
            throws Exception {
        VerifySdkStageReportTask task = task();
        String otherCommit = "4444444444444444444444444444444444444444";
        rewriteManifestAndPin(task, contents -> contents.replace(
                LANGUAGE_COMMIT, otherCommit));

        assertThrows(GradleException.class, task::verify);
        String receipt = Files.readString(
                task.getOutputFile().get().getAsFile().toPath());
        assertTrue(receipt.contains(
                "language-candidate-version-manifest-mismatch"));
        assertTrue(receipt.contains(
                "language-source-commit-manifest-mismatch"));
    }

    @Test
    void rejectsInternallyInconsistentManifestVersionAndSourceCommit()
            throws Exception {
        VerifySdkStageReportTask task = task();
        String otherCommit = "5555555555555555555555555555555555555555";
        rewriteManifestAndPin(task, contents -> contents.replace(
                "\"version\": \"" + LANGUAGE_VERSION + "\"",
                "\"version\": \"3.1.0-dev." + otherCommit + "\""));

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("language-artifact-manifest-not-commit-bound"));
    }

    @Test
    void rejectsReportBoundToDifferentLanguageRepository() throws Exception {
        VerifySdkStageReportTask task = task();
        Path otherRepository = temporaryDirectory.resolve("other-repository");
        Files.createDirectory(otherRepository);
        Path report = task.getConformanceReport().get().getAsFile().toPath();
        Files.writeString(report, Files.readString(report).replace(
                task.getLanguageRepository().get().getAsFile()
                        .getCanonicalPath(),
                otherRepository.toFile().getCanonicalPath()));

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains("staged-language-repository-path-mismatch"));
    }

    @Test
    void rejectsGradleMetadataOutsideExactLanguageRepositoryClosure()
            throws Exception {
        VerifySdkStageReportTask task = task();
        Path repository = task.getLanguageRepository().get().getAsFile()
                .toPath();
        Path metadata = repository.resolve(
                "blue/language/blue-language-java/" + LANGUAGE_VERSION
                        + "/blue-language-java-" + LANGUAGE_VERSION
                        + ".module");
        write(metadata, "not part of the handoff\n");

        assertThrows(GradleException.class, task::verify);
        String receipt = Files.readString(
                task.getOutputFile().get().getAsFile().toPath());
        assertTrue(receipt.contains(
                "language-development-repository-file-count-mismatch"));
        assertTrue(receipt.contains(
                "language-development-repository-file-closure-mismatch"));
        assertTrue(receipt.contains(
                "language-development-repository-gradle-module-metadata-present"));
    }

    @Test
    void rejectsRepinnedManifestWithNoncanonicalArtifactShape()
            throws Exception {
        VerifySdkStageReportTask task = task();
        rewriteManifestAndPin(task, contents -> contents.replaceFirst(
                "\"bytes\":",
                "\"classifier\": null,\n            \"bytes\":"));

        assertThrows(GradleException.class, task::verify);
        assertTrue(Files.readString(
                task.getOutputFile().get().getAsFile().toPath())
                .contains(
                        "language-artifact-manifest-artifact-fields-noncanonical"));
    }

    @Test
    void rejectsArtifactWhoseBytesNoLongerMatchManifestOrSidecar()
            throws Exception {
        VerifySdkStageReportTask task = task();
        Path repository = task.getLanguageRepository().get().getAsFile()
                .toPath();
        Path runtime = repository.resolve(
                "blue/language/blue-language-java/" + LANGUAGE_VERSION
                        + "/blue-language-java-" + LANGUAGE_VERSION
                        + ".jar");
        write(runtime, "tampered runtime\n");

        assertThrows(GradleException.class, task::verify);
        String receipt = Files.readString(
                task.getOutputFile().get().getAsFile().toPath());
        assertTrue(receipt.contains(
                "language-development-repository-artifact-hash-mismatch"));
        assertTrue(receipt.contains(
                "language-development-repository-artifact-checksum-mismatch"));
    }

    private VerifySdkStageReportTask task() throws Exception {
        return task(false);
    }

    private VerifySdkStageReportTask task(boolean localRc) throws Exception {
        String languageVersion = localRc ? "3.1.0-rc.24" : LANGUAGE_VERSION;
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();
        VerifySdkStageReportTask task = project.getTasks().create(
                "verifySdkStage",
                VerifySdkStageReportTask.class);
        Path languageRepository = languageRepository(localRc);
        Path manifest = languageRepository.resolve("artifact-manifest.json");
        languageManifestIdentity = "sha256:" + sha256(manifest);
        Path baseline = temporaryDirectory.resolve("baseline.json");
        Files.writeString(baseline, """
                {
                  "schema": "blue-bex-sdk-stage-baseline/1.0",
                  "status": "candidate-source-lock",
                  "language": {
                    "candidateVersion": "%s",
                    "sourceCommit": "%s",
                    "artifactManifestIdentity": "%s",
                    "historicalPublishedVersion": "3.1.0-rc.20"
                  },
                  "bex": {
                    "candidateVersion": "1.1.0-rc.4",
                    "currentSpecificationSha256": "208510d68ae278c63ad60d90c6a9724cf1e52e2e533ac5753904a645b7c13751",
                    "historicalReleaseVersion": "1.1.0-rc.3"
                  }
                }
                """.formatted(
                languageVersion,
                LANGUAGE_COMMIT,
                languageManifestIdentity));
        Path report = temporaryDirectory.resolve("report.json");
        Files.writeString(report, """
                {
                  "schema": "blue-bex-hosted-release-report/2.0",
                  "projectVersion": "1.1.0-rc.4",
                  "currentModeFailures": [],
                  "specification": {
                    "path": "specifications/blue-bex-specification-2.0.md",
                    "sha256": "208510d68ae278c63ad60d90c6a9724cf1e52e2e533ac5753904a645b7c13751",
                    "currentSpecificationAvailable": true
                  },
                  "dependency": {
                    "mode": "staged-repository",
                    "declaredCoordinate": "blue.language:blue-language-java:%s",
                    "resolution": {
                      "status": "passed",
                      "artifacts": [],
                      "provenance": {
                        "kind": "isolated-staged-repository",
                        "repositoryPolicy": "explicit-staged-repository-before-maven-central",
                        "stagedRepositoryArtifactsMatchResolved": true,
                        "recordedRepository": "%s"
                      }
                    }
                  },
                  "versionAutomation": {
                    "matchesProjectVersion": true,
                    "selectionKind": "explicit-staged-candidate",
                    "historicalConfiguredVersion": "1.1.0-rc.3"
                  },
                  "languageReleaseIdentity": {
                    "exactSelectedArtifactProven": true,
                    "selectionKind": "staged-repository-candidate"
                  },
                  "publishedHostApiInspection": {
                    "coordinate": "blue.language:blue-language-java:3.1.0-rc.20"
                  }
                }
                """.formatted(
                languageVersion,
                languageRepository.toFile().getCanonicalPath()));
        task.getCandidateBaseline().fileValue(baseline.toFile());
        task.getConformanceReport().fileValue(report.toFile());
        task.getLanguageRepository().set(languageRepository.toFile());
        Path specification = temporaryDirectory.resolve(
                "blue-bex-specification-2.0.md");
        Files.writeString(specification, "current specification\n");
        task.getCurrentSpecification().fileValue(specification.toFile());
        task.getProjectVersion().set("1.1.0-rc.4");
        task.getOutputFile().fileValue(
                temporaryDirectory.resolve("receipt.json").toFile());
        if (localRc) {
            String commit = "2222222222222222222222222222222222222222";
            Files.writeString(baseline, Files.readString(baseline).replace(
                    "\"candidateVersion\": \"1.1.0-rc.4\"",
                    "\"candidateVersion\": \"1.1.0-rc.5\", \"sourceCommit\": \"" + commit + "\""));
            Files.writeString(report, Files.readString(report)
                    .replace("\"projectVersion\": \"1.1.0-rc.4\"", "\"projectVersion\": \"1.1.0-rc.5\"")
                    .replace("\"currentModeFailures\": []", "\"sourceState\": {\"commit\": \"" + commit
                            + "\", \"releaseInputsCommitted\": true, \"worktreeDirty\": false}, \"currentModeFailures\": []"));
            task.getProjectVersion().set("1.1.0-rc.5");
        }
        return task;
    }

    private Path languageRepository(boolean localRc) throws Exception {
        String version = localRc ? "3.1.0-rc.24" : LANGUAGE_VERSION;
        Path repository = temporaryDirectory.resolve("language-repository");
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> artifacts = new ArrayList<>(LANGUAGE_ARTIFACTS);
        if (localRc) artifacts.add("blue-conformance");
        artifacts.sort(String::compareTo);
        for (String artifact : artifacts) {
            String base = "blue/language/" + artifact + "/"
                    + version + "/" + artifact + "-"
                    + version;
            addLanguageArtifact(
                    repository, records, artifact, base, "pom", ".pom", version);
            addLanguageArtifact(
                    repository, records, artifact, base, "runtime", ".jar", version);
            if (localRc) {
                addLanguageArtifact(repository, records, artifact, base, "sources", "-sources.jar", version);
                addLanguageArtifact(repository, records, artifact, base, "javadoc", "-javadoc.jar", version);
            }
        }
        records.sort(Comparator
                .comparing((Map<String, Object> record) ->
                        String.valueOf(record.get("coordinate")))
                .thenComparing(record -> String.valueOf(record.get("kind")))
                .thenComparing(record -> String.valueOf(record.get("path"))));

        Map<String, Object> document = new TreeMap<>();
        document.put("artifacts", records);
        document.put("builtWithJava", 17);
        document.put("contractsFixturePackageIdentity",
                "sha256:3333333333333333333333333333333333333333333333333333333333333333");
        document.put("contractsReleaseIdentity",
                "sha256:4444444444444444444444444444444444444444444444444444444444444444");
        document.put("contractsSpecificationIdentity",
                "sha256:2222222222222222222222222222222222222222222222222222222222222222");
        document.put("groupId", "blue.language");
        document.put("releaseReadinessClaimed", false);
        document.put("schema", localRc ? "blue-local-rc-maven-repository/1.0" : "blue-development-maven-repository/1.0");
        document.put("sourceCommit", LANGUAGE_COMMIT);
        document.put("sourceDirty", false);
        document.put("sourceTree",
                "1111111111111111111111111111111111111111");
        document.put("stagePurpose", localRc ? "LOCAL_RC" : "DEVELOPMENT");
        document.put("version", version);
        Path manifest = repository.resolve("artifact-manifest.json");
        write(manifest,
                JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n");
        writeManifestSidecar(manifest);
        return repository;
    }

    private static void addLanguageArtifact(
            Path repository,
            List<Map<String, Object>> records,
            String artifact,
            String base,
            String kind,
            String suffix, String version) throws Exception {
        String relative = base + suffix;
        Path payload = repository.resolve(relative);
        write(payload, artifact + " " + kind + "\n");
        writeManifestSidecar(payload);
        Map<String, Object> record = new TreeMap<>();
        record.put("bytes", Files.size(payload));
        record.put("checksumPath", relative + ".sha256");
        record.put("coordinate",
                "blue.language:" + artifact + ":" + version);
        record.put("kind", kind);
        record.put("path", relative);
        record.put("sha256", "sha256:" + sha256(payload));
        records.add(record);
    }

    private void rewriteManifestAndPin(
            VerifySdkStageReportTask task,
            java.util.function.UnaryOperator<String> rewrite)
            throws Exception {
        Path manifest = task.getLanguageRepository().get().getAsFile()
                .toPath().resolve("artifact-manifest.json");
        Files.writeString(manifest, rewrite.apply(Files.readString(manifest)));
        writeManifestSidecar(manifest);
        String newIdentity = "sha256:" + sha256(manifest);
        Path baseline = task.getCandidateBaseline().get().getAsFile().toPath();
        Files.writeString(baseline, Files.readString(baseline).replace(
                languageManifestIdentity, newIdentity));
        languageManifestIdentity = newIdentity;
    }

    private static void writeManifestSidecar(Path manifest) throws Exception {
        write(manifest.resolveSibling(manifest.getFileName() + ".sha256"),
                sha256(manifest) + "  " + manifest.getFileName() + "\n");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
