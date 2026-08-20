package blue.bex.buildlogic.tasks;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Fails closed unless the isolated SDK-stage conformance evidence is exact. */
public abstract class VerifySdkStageReportTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getConformanceReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCandidateBaseline();

    @Input
    public abstract Property<String> getProjectVersion();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void verify() {
        File reportFile = getConformanceReport().get().getAsFile();
        File baselineFile = getCandidateBaseline().get().getAsFile();
        Map<?, ?> report = object(new JsonSlurper().parse(reportFile));
        Map<?, ?> baseline = object(new JsonSlurper().parse(baselineFile));
        Map<?, ?> language = object(baseline.get("language"));
        Map<?, ?> bex = object(baseline.get("bex"));
        Map<?, ?> dependency = object(report.get("dependency"));
        Map<?, ?> resolution = object(dependency.get("resolution"));
        Map<?, ?> provenance = object(resolution.get("provenance"));
        Map<?, ?> versionAutomation = object(report.get("versionAutomation"));
        Map<?, ?> languageIdentity = object(
                report.get("languageReleaseIdentity"));
        Map<?, ?> publishedInspection = object(
                report.get("publishedHostApiInspection"));

        String languageVersion = string(language.get("candidateVersion"));
        String languageCoordinate =
                "blue.language:blue-language-java:" + languageVersion;
        String candidateVersion = string(bex.get("candidateVersion"));
        String projectVersion = getProjectVersion().get();
        List<String> blockers = new ArrayList<>();
        require(blockers,
                "blue-bex-sdk-stage-baseline/1.0".equals(
                        baseline.get("schema")),
                "unknown-candidate-baseline-schema");
        require(blockers,
                "candidate-source-lock".equals(baseline.get("status")),
                "candidate-baseline-not-source-locked");
        require(blockers,
                string(language.get("sourceCommit"))
                        .matches("[0-9a-f]{40}"),
                "language-source-commit-not-exact");
        require(blockers,
                candidateVersion.equals(projectVersion),
                "bex-candidate-version-mismatch");
        require(blockers,
                "blue-bex-hosted-release-report/2.0".equals(
                        report.get("schema")),
                "unknown-conformance-report-schema");
        require(blockers,
                projectVersion.equals(report.get("projectVersion")),
                "conformance-project-version-mismatch");
        require(blockers,
                "staged-repository".equals(dependency.get("mode")),
                "dependency-mode-is-not-staged-repository");
        require(blockers,
                languageCoordinate.equals(
                        dependency.get("declaredCoordinate")),
                "language-candidate-coordinate-mismatch");
        require(blockers,
                "passed".equals(resolution.get("status")),
                "staged-language-resolution-not-passing");
        require(blockers,
                "isolated-staged-repository".equals(
                        provenance.get("kind")),
                "staged-language-provenance-kind-mismatch");
        require(blockers,
                "explicit-staged-repository-before-maven-central".equals(
                        provenance.get("repositoryPolicy")),
                "staged-language-repository-policy-mismatch");
        require(blockers,
                Boolean.TRUE.equals(provenance.get(
                        "stagedRepositoryArtifactsMatchResolved")),
                "staged-language-artifact-hash-mismatch");
        require(blockers,
                Boolean.TRUE.equals(
                        versionAutomation.get("matchesProjectVersion"))
                        && "explicit-staged-candidate".equals(
                        versionAutomation.get("selectionKind")),
                "explicit-bex-candidate-version-not-accepted");
        require(blockers,
                string(bex.get("historicalReleaseVersion")).equals(
                        versionAutomation.get("historicalConfiguredVersion")),
                "historical-bex-release-version-mismatch");
        require(blockers,
                ("blue.language:blue-language-java:"
                        + language.get("historicalPublishedVersion"))
                        .equals(publishedInspection.get("coordinate")),
                "historical-published-language-evidence-mismatch");
        require(blockers,
                Boolean.TRUE.equals(
                        languageIdentity.get("exactSelectedArtifactProven"))
                        && "staged-repository-candidate".equals(
                        languageIdentity.get("selectionKind")),
                "exact-staged-language-artifact-not-proven");
        Object currentFailures = report.get("currentModeFailures");
        require(blockers,
                currentFailures instanceof List<?>
                        && ((List<?>) currentFailures).isEmpty(),
                "staged-conformance-blockers-present");

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schema", "blue-bex-sdk-stage-verification/1.0");
        receipt.put("status", blockers.isEmpty() ? "passed" : "failed");
        receipt.put("blockers", blockers);
        receipt.put("bexCandidateVersion", projectVersion);
        receipt.put("historicalBexReleaseVersion",
                bex.get("historicalReleaseVersion"));
        receipt.put("languageCandidateVersion", languageVersion);
        receipt.put("languageSourceCommit", language.get("sourceCommit"));
        receipt.put("languageCoordinate", languageCoordinate);
        receipt.put("historicalPublishedLanguageVersion",
                language.get("historicalPublishedVersion"));
        receipt.put("stagedRepository", provenance.get("recordedRepository"));
        receipt.put("resolvedLanguageArtifacts",
                resolution.get("artifacts") instanceof List<?>
                        ? resolution.get("artifacts")
                        : Collections.emptyList());
        receipt.put("conformanceReport", reportFile.getAbsolutePath());

        try {
            File output = getOutputFile().get().getAsFile();
            Files.createDirectories(output.toPath().getParent());
            Files.write(
                    output.toPath(),
                    (JsonOutput.prettyPrint(JsonOutput.toJson(receipt)) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new GradleException(
                    "Cannot write BEX SDK-stage verification receipt",
                    exception);
        }
        if (!blockers.isEmpty()) {
            throw new GradleException(
                    "BEX SDK staging is blocked: "
                            + String.join(", ", blockers));
        }
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map<?, ?>
                ? (Map<?, ?>) value
                : Collections.emptyMap();
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static void require(
            List<String> blockers, boolean condition, String blocker) {
        if (!condition) {
            blockers.add(blocker);
        }
    }
}
