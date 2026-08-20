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
        task.getProjectVersion().set("1.1.0-rc.4");
        task.getOutputFile().fileValue(
                temporaryDirectory.resolve("receipt.json").toFile());
        return task;
    }
}
