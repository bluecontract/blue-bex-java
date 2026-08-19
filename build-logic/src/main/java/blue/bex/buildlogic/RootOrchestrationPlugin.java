package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.GenerateModernizationReportTask;
import blue.bex.buildlogic.tasks.GenerateReleaseReportTask;
import blue.bex.buildlogic.tasks.GenerateWorkingReportTask;
import blue.bex.buildlogic.tasks.VerifyPublishedLanguageTask;
import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.bundling.Zip;

/** Thin root lifecycle wiring; implementation remains in focused plugins/tasks. */
public final class RootOrchestrationPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new IllegalStateException(
                    "blue.bex.root-orchestration applies only to the root");
        }
        project.getPluginManager().apply("base");
        project.getPluginManager().apply(ArchitectureVerificationPlugin.class);
        project.getPluginManager().apply(ReleaseEvidencePlugin.class);

        TaskProvider<Task> check = lifecycle(project, "bexCheck",
                "Runs module tests and architecture/API checks.");
        TaskProvider<Task> conformance = lifecycle(project, "bexConformance",
                "Runs all normative BEX conformance evidence.");
        TaskProvider<Task> local = lifecycle(
                project, "bexLocalLanguageVerification",
                "Verifies every module against the explicit local Language composite.");
        TaskProvider<Task> compatibility = lifecycle(
                project, "bexCompatibilityCheck",
                "Verifies API, bytecode, dependency, and semantic compatibility.");
        TaskProvider<Task> reproducibility = lifecycle(
                project, "bexReproducibilityCheck",
                "Verifies deterministic BEX-owned module and aggregate archives.");
        TaskProvider<Task> working = lifecycle(
                project, "bexWorkingVerification",
                "Runs the mandatory local-composite working gate.");
        TaskProvider<Task> modern = lifecycle(
                project, "bexModernizationVerification",
                "Runs the complete architecture, documentation, property, and "
                        + "serious benchmark gate.");
        TaskProvider<Task> release = lifecycle(
                project, "bexReleaseVerify",
                "Runs the strict published/local public-release gate.");
        TaskProvider<Task> sdkStage = lifecycle(
                project, "bexSdkStageVerify",
                "Runs the isolated local-only SDK staging gate.");
        TaskProvider<VerifyPublishedLanguageTask> publishedLanguage =
                project.getTasks().named(
                        "bexPublishedLanguageVerification",
                        VerifyPublishedLanguageTask.class);
        TaskProvider<Zip> sourceArchive = sourceArchive(
                project, "sourceReleaseArchive", "distributions");
        TaskProvider<Zip> sourceArchiveReplica = sourceArchive(
                project, "replicaSourceReleaseArchive",
                "reproducibility/source-release");
        TaskProvider<Task> verifySourceArchive = project.getTasks().register(
                "verifySourceReleaseArchiveReproducibility", task -> {
                    task.setGroup("verification");
                    task.dependsOn(sourceArchive, sourceArchiveReplica);
                    task.doLast(unused -> {
                        try {
                            long mismatch = Files.mismatch(
                                    sourceArchive.get().getArchiveFile().get()
                                            .getAsFile().toPath(),
                                    sourceArchiveReplica.get().getArchiveFile().get()
                                            .getAsFile().toPath());
                            if (mismatch != -1L) {
                                throw new GradleException(
                                        "Source release archive differs at byte "
                                                + mismatch);
                            }
                        } catch (IOException exception) {
                            throw new GradleException(
                                    "Cannot compare source release archives",
                                    exception);
                        }
                    });
                });
        TaskProvider<GenerateModernizationReportTask> modernization =
                project.getTasks().register(
                        "generateBexModernizationReport",
                        GenerateModernizationReportTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Aggregates same-run architecture, conformance, "
                                            + "API, benchmark, and artifact evidence.");
                            task.getFailOnIncomplete().set(true);
                            task.getTestResultsDirectory().set(
                                    project.getLayout().getProjectDirectory().dir(
                                            "blue-bex-conformance/build/"
                                                    + "test-results/test"));
                            task.getEvidenceFiles().from(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/architecture.json"),
                                    project.getLayout().getProjectDirectory().file(
                                            "blue-bex-conformance/build/reports/"
                                                    + "bex-conformance/report.json"),
                                    project.getLayout().getProjectDirectory().file(
                                            "blue-bex-conformance/build/reports/"
                                                    + "jmh/results.json"),
                                    project.getLayout().getProjectDirectory().file(
                                            "blue-bex-conformance/build/reports/"
                                                    + "jmh/environment.json"),
                                    project.getLayout().getProjectDirectory().file(
                                            "docs/public-api-classification.json"),
                                    project.getLayout().getProjectDirectory().file(
                                            "docs/latest-language-api-migration.json"),
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/"
                                                    + "published-language.json"),
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/latest-language-migration/"
                                                    + "final.json"),
                                    project.fileTree(project.getProjectDir(), spec ->
                                            spec.include("docs/*.md")));
                            task.getSourceFiles().from(
                                    project.fileTree(project.getProjectDir(), spec ->
                                            spec.include(
                                                    "blue-bex-core/src/main/"
                                                            + "java/**/*.java",
                                                    "blue-bex-contracts/src/main/"
                                                            + "java/**/*.java")));
                            task.getArtifacts().from(
                                    project.fileTree(project.getProjectDir(), spec -> {
                                        spec.include("blue-bex-core/build/libs/*.jar");
                                        spec.include("blue-bex-contracts/build/libs/*.jar");
                                        spec.include("blue-bex-java/build/libs/*.jar");
                                        spec.include("build/distributions/"
                                                + "*-source-release.zip");
                                    }));
                            task.getJsonOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/final.json"));
                            task.getMarkdownOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/final.md"));
                        });
        File latestLanguageBaseline = project.getLayout().getProjectDirectory().file(
                "gradle/verification/latest-language-baseline.json").getAsFile();
        LanguageBaseline languageBaseline = readLanguageBaseline(
                latestLanguageBaseline);
        TaskProvider<Copy> baselineReceipt = project.getTasks().register(
                "writeLatestLanguageBaselineReport", Copy.class, task -> {
                    task.setGroup("verification");
                    task.from(latestLanguageBaseline);
                    task.into(project.getLayout().getBuildDirectory().dir(
                            "reports/latest-language-migration"));
                    task.rename(ignored -> "baseline.json");
                });
        TaskProvider<GenerateWorkingReportTask> workingReport =
                project.getTasks().register(
                        "generateBexWorkingReport",
                        GenerateWorkingReportTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Writes the exact local-composite BEX "
                                            + "working-readiness receipt.");
                            task.getTestResultsDirectory().set(
                                    project.getLayout().getProjectDirectory().dir(
                                            "blue-bex-conformance/build/"
                                                    + "test-results/test"));
                            task.getBexRepository().set(
                                    project.getLayout().getProjectDirectory());
                            task.getLanguageRepositoryPath().set(
                                    project.getProviders().gradleProperty(
                                            "blueLanguageCompositePath")
                                            .orElse(""));
                            task.getExpectedLanguageCommit().set(
                                    languageBaseline.exactHead);
                            task.getVerifiedImplementationCommit().set(
                                    languageBaseline.verifiedImplementationCommit);
                            task.getAllowedLanguageDeltaPaths().set(
                                    languageBaseline.documentationOnlyDiffPaths);
                            task.getLocalCompositeCommand().set(
                                    "./gradlew --no-daemon clean "
                                            + "bexWorkingVerification "
                                            + "-PblueLanguageCompositePath="
                                            + project.getProviders().gradleProperty(
                                                    "blueLanguageCompositePath")
                                                    .orElse("").get());
                            task.getProjectVersion().set(project.provider(
                                    () -> String.valueOf(project.getVersion())));
                            task.getFailOnIncomplete().set(true);
                            task.getOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/latest-language-migration/"
                                                    + "final.json"));
                            task.getOutputs().upToDateWhen(ignored -> false);
                        });
        TaskProvider<GenerateReleaseReportTask> releaseReport =
                project.getTasks().register(
                        "generateBexReleaseReport",
                        GenerateReleaseReportTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Writes and enforces the strict public-release "
                                            + "decision.");
                            task.getModernizationReport().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/final.json"));
                            task.getPublishedLanguageReport().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/"
                                                    + "published-language.json"));
                            task.getRepositoryDirectory().set(
                                    project.getLayout().getProjectDirectory());
                            task.getExpectedReleaseTag().set(project.provider(
                                    () -> "v" + project.getVersion()));
                            Object independent = project.findProperty(
                                    "bexIndependentCleanBuildReport");
                            if (independent != null
                                    && !independent.toString().trim().isEmpty()) {
                                task.getIndependentCleanBuildReport().fileValue(
                                        project.file(independent.toString()));
                            } else {
                                File retained = project.getLayout()
                                        .getBuildDirectory().file(
                                                "reports/bex-release/inputs/"
                                                        + "independent-clean-"
                                                        + "builds.json")
                                        .get().getAsFile();
                                if (retained.isFile()) {
                                    task.getIndependentCleanBuildReport()
                                            .fileValue(retained);
                                }
                            }
                            Object differential = project.findProperty(
                                    "bexLocalPublishedDifferential");
                            if (differential != null
                                    && !differential.toString().trim().isEmpty()) {
                                task.getDifferentialReport().fileValue(
                                        project.file(differential.toString()));
                            } else {
                                File retained = project.getLayout()
                                        .getBuildDirectory().file(
                                                "reports/bex-release/inputs/"
                                                        + "local-published-"
                                                        + "differential.json")
                                        .get().getAsFile();
                                if (retained.isFile()) {
                                    task.getDifferentialReport().fileValue(retained);
                                }
                            }
                            task.getJsonOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-release/final.json"));
                            task.getMarkdownOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-release/final.md"));
                            task.getOutputs().upToDateWhen(ignored -> false);
                        });

        project.getGradle().projectsEvaluated(ignored -> {
            Project core = project.project(":blue-bex-core");
            Project contracts = project.project(":blue-bex-contracts");
            Project suite = project.project(":blue-bex-conformance");
            Project aggregate = project.project(":blue-bex-java");
            Project examples = project.project(":examples");

            local.configure(task -> task.doFirst(unused -> {
                Object configured = project.findProperty(
                        "blueLanguageCompositePath");
                if (configured == null
                        || configured.toString().trim().isEmpty()) {
                    throw new GradleException(
                            "bexLocalLanguageVerification requires "
                                    + "-PblueLanguageCompositePath=<checkout>");
                }
                File checkout = project.file(configured.toString().trim());
                if (!checkout.isDirectory()) {
                    throw new GradleException(
                            "Blue Language composite is not a directory: "
                                    + checkout);
                }
            }));

            project.getTasks().named("check").configure(task ->
                    task.dependsOn(check));
            project.getTasks().named("assemble").configure(task ->
                    task.dependsOn(
                            core.getTasks().named("assemble"),
                            contracts.getTasks().named("assemble"),
                            aggregate.getTasks().named("assemble"),
                            sourceArchive));

            check.configure(task -> task.dependsOn(
                    core.getTasks().named("check"),
                    contracts.getTasks().named("check"),
                    suite.getTasks().named("check"),
                    aggregate.getTasks().named("check"),
                    examples.getTasks().named("check"),
                    project.getTasks().named("verifyBexArchitecture")));
            conformance.configure(task -> task.dependsOn(
                    suite.getTasks().named("bexConformance")));
            local.configure(task -> task.dependsOn(Arrays.asList(
                    core.getTasks().named("verifyLanguageDependencyMode"),
                    contracts.getTasks().named("verifyLanguageDependencyMode"),
                    suite.getTasks().named("verifyLanguageDependencyMode"),
                    aggregate.getTasks().named("verifyLanguageDependencyMode"),
                    examples.getTasks().named("verifyLanguageDependencyMode"),
                    core.getTasks().named("writeLanguageDependencyEvidence"),
                    contracts.getTasks().named("writeLanguageDependencyEvidence"),
                    suite.getTasks().named("writeLanguageDependencyEvidence"),
                    aggregate.getTasks().named("writeLanguageDependencyEvidence"),
                    examples.getTasks().named("writeLanguageDependencyEvidence"))));
            compatibility.configure(task -> task.dependsOn(
                    check, conformance,
                    suite.getTasks().named("bexApiEvidence")));
            reproducibility.configure(task -> task.dependsOn(
                    core.getTasks().named("verifyReproducibleArchives"),
                    contracts.getTasks().named("verifyReproducibleArchives"),
                    aggregate.getTasks().named("verifyReproducibleArchives"),
                    verifySourceArchive));
            sdkStage.configure(task -> {
                task.dependsOn(
                        compatibility,
                        reproducibility,
                        core.getTasks().named("verifyLanguageDependencyMode"),
                        contracts.getTasks().named("verifyLanguageDependencyMode"),
                        aggregate.getTasks().named("verifyLanguageDependencyMode"));
                task.doFirst(unused -> {
                    requireLocalStageProperty(
                            project, "blueLanguageRepository", true);
                    requireLocalStageProperty(
                            project, "bexSdkStagingRepository", false);
                    String selectedVersion = requireLocalStageProperty(
                            project, "bexLocalStageVersion", false);
                    if (!selectedVersion.equals(
                            String.valueOf(project.getVersion()))) {
                        throw new GradleException(
                                "bexLocalStageVersion does not match project "
                                        + "version " + project.getVersion());
                    }
                    Object composite = project.findProperty(
                            "blueLanguageCompositePath");
                    if (composite != null
                            && !composite.toString().trim().isEmpty()) {
                        throw new GradleException(
                                "SDK staging forbids included-build Language "
                                        + "substitution");
                    }
                });
            });
            modernization.configure(task -> task.dependsOn(
                    working,
                    publishedLanguage,
                    project.getTasks().named("verifyBexArchitecture"),
                    suite.getTasks().named("writeBexConformanceReport"),
                    suite.getTasks().named("jmh"),
                    suite.getTasks().named("bexApiEvidence"),
                    core.getTasks().named("assemble"),
                    contracts.getTasks().named("assemble"),
                    aggregate.getTasks().named("assemble"),
                    sourceArchive));
            workingReport.configure(task -> {
                task.dependsOn(
                        local, compatibility, reproducibility,
                        suite.getTasks().named("jmhSmoke"),
                        project.getTasks().named("verifyBexArchitecture"),
                        core.getTasks().named("assemble"),
                        contracts.getTasks().named("assemble"),
                        aggregate.getTasks().named("assemble"),
                        sourceArchive,
                        project.getTasks().named("generateBexSourceFingerprint"),
                        baselineReceipt);
                task.getEvidenceFiles().from(
                        project.getLayout().getBuildDirectory().file(
                                "reports/bex-modernization/architecture.json"),
                        suite.getLayout().getBuildDirectory().file(
                                "reports/bex-conformance/report.json"),
                        suite.getLayout().getBuildDirectory().file(
                                "reports/jmh/smoke-results.json"),
                        suite.getLayout().getBuildDirectory().file(
                                "reports/bex-release/public-api.txt"),
                        suite.getLayout().getBuildDirectory().file(
                                "reports/bex-release/"
                                        + "public-api-classification.json"),
                        project.getLayout().getProjectDirectory().file(
                                "src/test/resources/hosted-release/"
                                        + "required-public-api.txt"),
                        project.getLayout().getProjectDirectory().file(
                                "docs/latest-language-api-migration.json"),
                        project.getLayout().getProjectDirectory().file(
                                "docs/public-api-classification.json"),
                        project.getLayout().getProjectDirectory().file(
                                "gradle/verification/"
                                        + "latest-language-baseline.json"),
                        project.getLayout().getBuildDirectory().file(
                                "reports/bex-modernization/source.json"),
                        core.getLayout().getBuildDirectory().file(
                                "reports/dependencies/language.json"),
                        contracts.getLayout().getBuildDirectory().file(
                                "reports/dependencies/language.json"),
                        suite.getLayout().getBuildDirectory().file(
                                "reports/dependencies/language.json"),
                        aggregate.getLayout().getBuildDirectory().file(
                                "reports/dependencies/language.json"),
                        examples.getLayout().getBuildDirectory().file(
                                "reports/dependencies/language.json"));
                task.getPrimaryArtifacts().from(project.fileTree(
                        project.getProjectDir(), spec -> spec.include(
                                "blue-bex-core/build/libs/*.jar",
                                "blue-bex-contracts/build/libs/*.jar",
                                "blue-bex-java/build/libs/*.jar",
                                "build/distributions/*-source-release.zip")));
                task.getReplicaArtifacts().from(project.fileTree(
                        project.getProjectDir(), spec -> spec.include(
                                "blue-bex-core/build/reproducibility/**/*.jar",
                                "blue-bex-contracts/build/reproducibility/**/*.jar",
                                "blue-bex-java/build/reproducibility/**/*.jar",
                                "build/reproducibility/source-release/"
                                        + "*-source-release.zip")));
                task.getProductionSources().from(project.fileTree(
                        project.getProjectDir(), spec -> spec.include(
                                "blue-bex-core/src/main/java/**/*.java",
                                "blue-bex-contracts/src/main/java/**/*.java")));
            });
            working.configure(task -> task.dependsOn(
                    local, compatibility, reproducibility,
                    workingReport,
                    project.getTasks().named("generateBexSourceFingerprint")));
            modern.configure(task -> task.dependsOn(working, modernization));
            releaseReport.configure(task -> task.dependsOn(
                    modern, publishedLanguage));
            release.configure(task -> task.dependsOn(releaseReport));
        });
    }

    private static TaskProvider<Zip> sourceArchive(
            Project project, String taskName, String destination) {
        return project.getTasks().register(taskName, Zip.class, task -> {
            task.setGroup("distribution");
            task.setDescription("Creates the reproducible BEX source release.");
            task.setPreserveFileTimestamps(false);
            task.setReproducibleFileOrder(true);
            task.getArchiveBaseName().set("blue-bex-java");
            task.getArchiveVersion().set(project.provider(
                    () -> String.valueOf(project.getVersion())));
            task.getArchiveClassifier().set("source-release");
            task.getDestinationDirectory().set(
                    project.getLayout().getBuildDirectory().dir(destination));
            task.into(project.provider(() -> "blue-bex-java-"
                    + project.getVersion()), copy -> copy.from(
                            project.getRootProject().getProjectDir(), spec ->
                                    spec.exclude(
                                            ".git/**", ".gradle/**", "**/.gradle/**",
                                            "**/build/**",
                                            ".idea/**", "**/.idea/**",
                                            "out/**", "**/out/**", "*.iml",
                                            "**/.DS_Store", "*.zip", "work-status.txt")));
        });
    }

    private static String requireLocalStageProperty(
            Project project, String name, boolean mustBeDirectory) {
        Object configured = project.findProperty(name);
        if (configured == null || configured.toString().trim().isEmpty()) {
            throw new GradleException(
                    "bexSdkStageVerify requires -P" + name + "=<value>");
        }
        String selected = configured.toString().trim();
        if (mustBeDirectory && !project.file(selected).isDirectory()) {
            throw new GradleException(
                    name + " is not a directory: " + project.file(selected));
        }
        return selected;
    }

    private static LanguageBaseline readLanguageBaseline(File baselineFile) {
        final Object parsed;
        try {
            parsed = new JsonSlurper().parseText(
                    Files.readString(baselineFile.toPath()));
        } catch (IOException | RuntimeException exception) {
            throw new GradleException(
                    "Cannot read latest Language baseline: " + baselineFile,
                    exception);
        }

        Map<?, ?> root = requireObject(parsed, "Language baseline root");
        Map<?, ?> language = requireObject(
                root.get("language"), "Language baseline language");
        return new LanguageBaseline(
                requireString(language, "exactHead"),
                requireString(language, "verifiedImplementationCommit"),
                requireStringList(language, "documentationOnlyDiffPaths"));
    }

    private static Map<?, ?> requireObject(Object value, String description) {
        if (!(value instanceof Map)) {
            throw new GradleException(description + " must be a JSON object");
        }
        return (Map<?, ?>) value;
    }

    private static String requireString(Map<?, ?> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String)
                || ((String) value).trim().isEmpty()) {
            throw new GradleException(
                    "Language baseline " + field + " must be a non-empty string");
        }
        return (String) value;
    }

    private static List<String> requireStringList(
            Map<?, ?> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof List)) {
            throw new GradleException(
                    "Language baseline " + field + " must be a JSON array");
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)
                    || ((String) item).trim().isEmpty()) {
                throw new GradleException(
                        "Language baseline " + field
                                + " must contain only non-empty strings");
            }
            result.add((String) item);
        }
        return result;
    }

    private static final class LanguageBaseline {
        private final String exactHead;
        private final String verifiedImplementationCommit;
        private final List<String> documentationOnlyDiffPaths;

        private LanguageBaseline(
                String exactHead,
                String verifiedImplementationCommit,
                List<String> documentationOnlyDiffPaths) {
            this.exactHead = exactHead;
            this.verifiedImplementationCommit = verifiedImplementationCommit;
            this.documentationOnlyDiffPaths = documentationOnlyDiffPaths;
        }
    }

    private static TaskProvider<Task> lifecycle(
            Project project, String name, String description) {
        return project.getTasks().register(name, task -> {
            task.setGroup("verification");
            task.setDescription(description);
        });
    }
}
