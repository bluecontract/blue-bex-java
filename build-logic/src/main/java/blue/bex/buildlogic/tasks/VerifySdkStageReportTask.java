package blue.bex.buildlogic.tasks;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Fails closed unless the isolated SDK-stage conformance evidence is exact. */
public abstract class VerifySdkStageReportTask extends DefaultTask {
    private static final String LANGUAGE_MANIFEST_FILE =
            "artifact-manifest.json";
    private static final String LANGUAGE_MANIFEST_SCHEMA =
            "blue-development-maven-repository/1.0";
    private static final Pattern LANGUAGE_DEVELOPMENT_VERSION =
            Pattern.compile("3\\.1\\.0-dev\\.([0-9a-f]{40})");
    private static final Pattern SHA_256_IDENTITY =
            Pattern.compile("sha256:[0-9a-f]{64}");

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getConformanceReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCandidateBaseline();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCurrentSpecification();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getLanguageRepository();

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
        Map<?, ?> sourceState = object(report.get("sourceState"));
        Map<?, ?> languageIdentity = object(
                report.get("languageReleaseIdentity"));
        Map<?, ?> publishedInspection = object(
                report.get("publishedHostApiInspection"));
        Map<?, ?> specification = object(report.get("specification"));

        String languageVersion = string(language.get("candidateVersion"));
        String languageSourceCommit = string(language.get("sourceCommit"));
        String lockedLanguageManifestIdentity =
                string(language.get("artifactManifestIdentity"));
        String languageCoordinate =
                "blue.language:blue-language-java:" + languageVersion;
        String candidateVersion = string(bex.get("candidateVersion"));
        String projectVersion = getProjectVersion().get();
        String commitBoundSource = commitBoundSource(projectVersion);
        boolean commitBoundDevelopment = !commitBoundSource.isEmpty();
        String lockedSpecificationSha256 =
                string(bex.get("currentSpecificationSha256"));
        String currentSpecificationSha256 =
                sha256(getCurrentSpecification().get().getAsFile());
        List<String> blockers = new ArrayList<>();
        LanguageManifestEvidence languageManifest = inspectLanguageManifest(
                getLanguageRepository().get().getAsFile(), blockers);
        require(blockers,
                "blue-bex-sdk-stage-baseline/1.0".equals(
                        baseline.get("schema")),
                "unknown-candidate-baseline-schema");
        require(blockers,
                "candidate-source-lock".equals(baseline.get("status")),
                "candidate-baseline-not-source-locked");
        require(blockers,
                lockedSpecificationSha256.matches("[0-9a-f]{64}"),
                "bex-current-specification-lock-missing");
        require(blockers,
                currentSpecificationSha256.equals(
                        lockedSpecificationSha256),
                "bex-current-specification-lock-mismatch");
        require(blockers,
                languageSourceCommit.matches("[0-9a-f]{40}"),
                "language-source-commit-not-exact");
        String baselineLanguageVersionSource =
                languageCommitBoundSource(languageVersion);
        require(blockers,
                !baselineLanguageVersionSource.isEmpty()
                        && languageSourceCommit.equals(
                                baselineLanguageVersionSource),
                "language-candidate-version-source-commit-mismatch");
        require(blockers,
                SHA_256_IDENTITY.matcher(
                        lockedLanguageManifestIdentity).matches(),
                "language-artifact-manifest-lock-missing");
        require(blockers,
                lockedLanguageManifestIdentity.equals(
                        languageManifest.identity),
                "language-artifact-manifest-lock-mismatch");
        require(blockers,
                languageVersion.equals(languageManifest.version),
                "language-candidate-version-manifest-mismatch");
        require(blockers,
                languageSourceCommit.equals(languageManifest.sourceCommit),
                "language-source-commit-manifest-mismatch");
        String manifestLanguageVersionSource =
                languageCommitBoundSource(languageManifest.version);
        require(blockers,
                !manifestLanguageVersionSource.isEmpty()
                        && languageManifest.sourceCommit.equals(
                                manifestLanguageVersionSource),
                "language-artifact-manifest-not-commit-bound");
        require(blockers,
                commitBoundDevelopment
                        ? "commit-bound-development".equals(candidateVersion)
                        : candidateVersion.equals(projectVersion),
                "bex-candidate-version-policy-mismatch");
        require(blockers,
                "blue-bex-hosted-release-report/2.0".equals(
                        report.get("schema")),
                "unknown-conformance-report-schema");
        require(blockers,
                projectVersion.equals(report.get("projectVersion")),
                "conformance-project-version-mismatch");
        require(blockers,
                !commitBoundDevelopment
                        || (commitBoundSource.equals(
                                    sourceState.get("commit"))
                                && Boolean.TRUE.equals(
                                    sourceState.get("releaseInputsCommitted"))
                                && Boolean.FALSE.equals(
                                    sourceState.get("worktreeDirty"))),
                "bex-development-version-is-not-clean-source-bound");
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
                sameRepository(
                        string(provenance.get("recordedRepository")),
                        languageManifest.repository),
                "staged-language-repository-path-mismatch");
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
        require(blockers,
                "specifications/blue-bex-specification-2.0.md".equals(
                        specification.get("path")),
                "conformance-specification-path-mismatch");
        require(blockers,
                currentSpecificationSha256.equals(
                                specification.get("sha256"))
                        && lockedSpecificationSha256.equals(
                                specification.get("sha256")),
                "conformance-specification-hash-mismatch");
        require(blockers,
                Boolean.TRUE.equals(
                        specification.get(
                                "currentSpecificationAvailable")),
                "conformance-current-specification-unavailable");
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
        receipt.put("bexSourceCommit", sourceState.get("commit"));
        receipt.put("historicalBexReleaseVersion",
                bex.get("historicalReleaseVersion"));
        receipt.put("bexSpecificationPath",
                "specifications/blue-bex-specification-2.0.md");
        receipt.put("bexSpecificationSha256",
                currentSpecificationSha256);
        receipt.put("languageCandidateVersion", languageVersion);
        receipt.put("languageSourceCommit", languageSourceCommit);
        receipt.put("languageCoordinate", languageCoordinate);
        receipt.put("expectedLanguageArtifactManifestIdentity",
                lockedLanguageManifestIdentity);
        receipt.put("languageArtifactManifestIdentity",
                languageManifest.identity);
        receipt.put("languageArtifactManifestVersion",
                languageManifest.version);
        receipt.put("languageArtifactManifestSourceCommit",
                languageManifest.sourceCommit);
        receipt.put("verifiedLanguageRepository",
                languageManifest.repository);
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

    private static String commitBoundSource(String version) {
        if (version != null && version.matches(
                "[0-9]+\\.[0-9]+\\.[0-9]+-dev\\.[0-9a-f]{40}")) {
            return version.substring(version.length() - 40);
        }
        return "";
    }

    private static String languageCommitBoundSource(String version) {
        java.util.regex.Matcher matcher =
                LANGUAGE_DEVELOPMENT_VERSION.matcher(version);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private static LanguageManifestEvidence inspectLanguageManifest(
            File repositoryFile,
            List<String> blockers) {
        Path repository = repositoryFile.toPath().toAbsolutePath().normalize();
        String repositoryPath = repository.toString();
        String identity = "";
        String version = "";
        String sourceCommit = "";
        if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(repository)) {
            blockers.add("language-repository-missing-or-symbolic");
            return new LanguageManifestEvidence(
                    repositoryPath, identity, version, sourceCommit);
        }
        try {
            repository = repository.toRealPath();
            repositoryPath = repository.toString();
            Path manifest = repository.resolve(LANGUAGE_MANIFEST_FILE);
            Path sidecar = repository.resolve(
                    LANGUAGE_MANIFEST_FILE + ".sha256");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(manifest)) {
                blockers.add("language-artifact-manifest-missing-or-symbolic");
                return new LanguageManifestEvidence(
                        repositoryPath, identity, version, sourceCommit);
            }
            String digest = sha256(manifest.toFile());
            identity = "sha256:" + digest;
            if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(sidecar)) {
                blockers.add(
                        "language-artifact-manifest-sidecar-missing-or-symbolic");
            } else {
                String expectedSidecar = digest + "  "
                        + LANGUAGE_MANIFEST_FILE + "\n";
                require(blockers,
                        expectedSidecar.equals(Files.readString(
                                sidecar, StandardCharsets.UTF_8)),
                        "language-artifact-manifest-sidecar-mismatch");
            }
            Object parsed = new JsonSlurper().parse(manifest.toFile());
            if (!(parsed instanceof Map<?, ?>)) {
                blockers.add("language-artifact-manifest-not-object");
                return new LanguageManifestEvidence(
                        repositoryPath, identity, version, sourceCommit);
            }
            Map<?, ?> document = (Map<?, ?>) parsed;
            version = string(document.get("version"));
            sourceCommit = string(document.get("sourceCommit"));
            require(blockers,
                    canonicalLanguageManifestFields().equals(
                            document.keySet().stream()
                                    .map(String::valueOf)
                                    .collect(Collectors.toCollection(
                                            LinkedHashSet::new))),
                    "language-artifact-manifest-fields-noncanonical");
            require(blockers,
                    LANGUAGE_MANIFEST_SCHEMA.equals(document.get("schema")),
                    "language-artifact-manifest-schema-mismatch");
            require(blockers,
                    "DEVELOPMENT".equals(document.get("stagePurpose")),
                    "language-artifact-manifest-not-development");
            require(blockers,
                    Boolean.FALSE.equals(
                            document.get("releaseReadinessClaimed")),
                    "language-artifact-manifest-claims-release-readiness");
            require(blockers,
                    exactNumber(document.get("builtWithJava"), 17),
                    "language-artifact-manifest-java-version-mismatch");
            require(blockers,
                    "blue.language".equals(document.get("groupId")),
                    "language-artifact-manifest-group-mismatch");
            require(blockers,
                    string(document.get("sourceTree"))
                            .matches("[0-9a-f]{40}"),
                    "language-artifact-manifest-source-tree-not-exact");
            require(blockers,
                    Boolean.FALSE.equals(document.get("sourceDirty")),
                    "language-artifact-manifest-source-dirty");
            for (String field : Arrays.asList(
                    "contractsSpecificationIdentity",
                    "contractsFixturePackageIdentity",
                    "contractsReleaseIdentity")) {
                require(blockers,
                        SHA_256_IDENTITY.matcher(
                                string(document.get(field))).matches(),
                        "language-artifact-manifest-" + field
                                + "-not-exact");
            }
            require(blockers,
                    document.get("artifacts") instanceof List<?>,
                    "language-artifact-manifest-artifacts-not-list");
        } catch (Exception exception) {
            blockers.add("language-artifact-manifest-unreadable");
        }
        return new LanguageManifestEvidence(
                repositoryPath, identity, version, sourceCommit);
    }

    private static Set<String> canonicalLanguageManifestFields() {
        return new LinkedHashSet<>(Arrays.asList(
                "schema",
                "stagePurpose",
                "releaseReadinessClaimed",
                "builtWithJava",
                "groupId",
                "version",
                "sourceCommit",
                "sourceTree",
                "sourceDirty",
                "contractsSpecificationIdentity",
                "contractsFixturePackageIdentity",
                "contractsReleaseIdentity",
                "artifacts"));
    }

    private static boolean sameRepository(
            String recordedRepository,
            String actualRepository) {
        if (recordedRepository.isEmpty() || actualRepository.isEmpty()) {
            return false;
        }
        try {
            return Files.isSameFile(
                    Path.of(recordedRepository), Path.of(actualRepository));
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean exactNumber(Object value, long expected) {
        return value instanceof Number
                && value.toString().equals(Long.toString(expected));
    }

    private static String sha256(File file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file.toPath()));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new GradleException(
                    "Cannot hash file " + file, exception);
        }
    }

    private static void require(
            List<String> blockers, boolean condition, String blocker) {
        if (!condition) {
            blockers.add(blocker);
        }
    }

    private static final class LanguageManifestEvidence {
        private final String repository;
        private final String identity;
        private final String version;
        private final String sourceCommit;

        private LanguageManifestEvidence(
                String repository,
                String identity,
                String version,
                String sourceCommit) {
            this.repository = repository;
            this.identity = identity;
            this.version = version;
            this.sourceCommit = sourceCommit;
        }
    }
}
