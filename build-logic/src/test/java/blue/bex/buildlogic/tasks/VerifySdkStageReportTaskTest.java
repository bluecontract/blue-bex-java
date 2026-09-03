package blue.bex.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VerifySdkStageReportTaskTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void passesOnlyExactBlockerFreeStagedEvidence() throws Exception {
        VerifySdkStageReportTask task = task();
        task.verify();

        String receipt = Files.readString(
                task.getOutputFile().get().getAsFile().toPath());
        assertTrue(receipt.contains("\"status\": \"passed\""));
        assertTrue(receipt.contains(
                "e0dfc897ea7d158895325fae2bf84e103b8c1989"));
        assertTrue(receipt.contains(
                "208510d68ae278c63ad60d90c6a9724cf1e52e2e533ac5753904a645b7c13751"));
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

    private VerifySdkStageReportTask task() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();
        VerifySdkStageReportTask task = project.getTasks().create(
                "verifySdkStage",
                VerifySdkStageReportTask.class);
        Path baseline = temporaryDirectory.resolve("baseline.json");
        Files.writeString(baseline, """
                {
                  "schema": "blue-bex-sdk-stage-baseline/1.0",
                  "status": "candidate-source-lock",
                  "language": {
                    "candidateVersion": "3.1.0-rc.21",
                    "sourceCommit": "e0dfc897ea7d158895325fae2bf84e103b8c1989",
                    "historicalPublishedVersion": "3.1.0-rc.20"
                  },
                  "bex": {
                    "candidateVersion": "1.1.0-rc.4",
                    "currentSpecificationSha256": "208510d68ae278c63ad60d90c6a9724cf1e52e2e533ac5753904a645b7c13751",
                    "historicalReleaseVersion": "1.1.0-rc.3"
                  }
                }
                """);
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
                    "declaredCoordinate": "blue.language:blue-language-java:3.1.0-rc.21",
                    "resolution": {
                      "status": "passed",
                      "artifacts": [],
                      "provenance": {
                        "kind": "isolated-staged-repository",
                        "repositoryPolicy": "explicit-staged-repository-before-maven-central",
                        "stagedRepositoryArtifactsMatchResolved": true,
                        "recordedRepository": "/stage"
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
                """);
        task.getCandidateBaseline().fileValue(baseline.toFile());
        task.getConformanceReport().fileValue(report.toFile());
        Path specification = temporaryDirectory.resolve(
                "blue-bex-specification-2.0.md");
        Files.writeString(specification, "current specification\n");
        task.getCurrentSpecification().fileValue(specification.toFile());
        task.getProjectVersion().set("1.1.0-rc.4");
        task.getOutputFile().fileValue(
                temporaryDirectory.resolve("receipt.json").toFile());
        return task;
    }
}
