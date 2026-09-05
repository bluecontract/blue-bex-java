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
import java.util.stream.Stream;
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
    private static final String LANGUAGE_GROUP = "blue.language";
    private static final List<String> LANGUAGE_ARTIFACTS =
            Collections.unmodifiableList(Arrays.asList(
                    "blue-language-model",
                    "blue-language-core",
                    "blue-language-mapping",
                    "blue-language-ipfs",
                    "blue-contracts-core",
                    "blue-language-java"));
    private static final List<LanguageArtifactKind> LANGUAGE_KINDS =
            Collections.unmodifiableList(Arrays.asList(
                    new LanguageArtifactKind("pom", ".pom"),
                    new LanguageArtifactKind("runtime", ".jar")));

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
        require(blockers,
                matchesLanguageSource(languageVersion, languageSourceCommit),
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
        require(blockers,
                matchesLanguageSource(languageManifest.version, languageManifest.sourceCommit),
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
        if (isLocalRcLanguage(languageVersion)) {
            String lockedSource = string(bex.get("sourceCommit"));
            require(blockers,
                    projectVersion.matches("1\\.1\\.0-rc\\.[1-9][0-9]*")
                            && lockedSource.matches("[0-9a-f]{40}")
                            && lockedSource.equals(sourceState.get("commit"))
                            && Boolean.TRUE.equals(sourceState.get("releaseInputsCommitted"))
                            && Boolean.FALSE.equals(sourceState.get("worktreeDirty")),
                    "local-rc-bex-source-lock-mismatch-or-dirty");
        }
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

    private static boolean isLocalRcLanguage(String version) {
        return version != null && version.matches("3\\.1\\.0-rc\\.[1-9][0-9]*");
    }

    private static boolean matchesLanguageSource(String version, String commit) {
        return commit.matches("[0-9a-f]{40}") && (isLocalRcLanguage(version)
                || commit.equals(languageCommitBoundSource(version)));
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
                    (isLocalRcLanguage(version) ? "blue-local-rc-maven-repository/1.0"
                            : LANGUAGE_MANIFEST_SCHEMA).equals(document.get("schema")),
                    "language-artifact-manifest-schema-mismatch");
            require(blockers,
                    (isLocalRcLanguage(version) ? "LOCAL_RC" : "DEVELOPMENT").equals(document.get("stagePurpose")),
                    "language-artifact-manifest-not-development");
            require(blockers,
                    Boolean.FALSE.equals(
                            document.get("releaseReadinessClaimed")),
                    "language-artifact-manifest-claims-release-readiness");
            require(blockers,
                    exactNumber(document.get("builtWithJava"), 17),
                    "language-artifact-manifest-java-version-mismatch");
            require(blockers,
                    LANGUAGE_GROUP.equals(document.get("groupId")),
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
            if (matchesLanguageSource(version, sourceCommit)) {
                inspectLanguageArtifacts(
                        repository,
                        version,
                        document.get("artifacts"),
                        blockers);
            } else {
                blockers.add(
                        "language-artifact-manifest-version-not-commit-bound");
            }
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

    private static void inspectLanguageArtifacts(
            Path repository,
            String version,
            Object artifactsValue,
            List<String> blockers) throws Exception {
        List<ExpectedLanguageArtifact> expected =
                expectedLanguageArtifacts(version);
        List<?> artifacts;
        if (artifactsValue instanceof List<?>) {
            artifacts = (List<?>) artifactsValue;
        } else {
            blockers.add("language-artifact-manifest-artifacts-not-list");
            artifacts = Collections.emptyList();
        }
        require(blockers,
                artifacts.size() == expected.size(),
                "language-artifact-manifest-artifact-count-mismatch");

        Set<String> canonicalRecordFields = new LinkedHashSet<>(Arrays.asList(
                "bytes",
                "checksumPath",
                "coordinate",
                "kind",
                "path",
                "sha256"));
        for (int index = 0; index < expected.size(); index++) {
            ExpectedLanguageArtifact expectedArtifact = expected.get(index);
            if (index >= artifacts.size()) {
                blockers.add("language-artifact-manifest-artifact-record-missing");
                continue;
            }
            Object recordValue = artifacts.get(index);
            if (!(recordValue instanceof Map<?, ?>)) {
                blockers.add("language-artifact-manifest-artifact-not-object");
                continue;
            }
            Map<?, ?> record = (Map<?, ?>) recordValue;
            Set<String> actualRecordFields = record.keySet().stream()
                    .map(String::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            require(blockers,
                    canonicalRecordFields.equals(actualRecordFields),
                    "language-artifact-manifest-artifact-fields-noncanonical");
            require(blockers,
                    expectedArtifact.coordinate.equals(
                                    string(record.get("coordinate")))
                            && expectedArtifact.kind.equals(
                                    string(record.get("kind")))
                            && expectedArtifact.path.equals(
                                    string(record.get("path")))
                            && (expectedArtifact.path + ".sha256").equals(
                                    string(record.get("checksumPath"))),
                    "language-artifact-manifest-artifact-record-noncanonical");
            require(blockers,
                    SHA_256_IDENTITY.matcher(
                            string(record.get("sha256"))).matches(),
                    "language-artifact-manifest-artifact-sha256-not-exact");

            Path payload = repository.resolve(expectedArtifact.path);
            Path checksum = repository.resolve(
                    expectedArtifact.path + ".sha256");
            boolean payloadRegular = regularNonSymbolic(payload);
            boolean checksumRegular = regularNonSymbolic(checksum);
            require(blockers,
                    payloadRegular,
                    "language-development-repository-artifact-missing-or-symbolic");
            require(blockers,
                    checksumRegular,
                    "language-development-repository-checksum-missing-or-symbolic");
            if (payloadRegular) {
                String digest = sha256(payload.toFile());
                require(blockers,
                        ("sha256:" + digest).equals(
                                string(record.get("sha256"))),
                        "language-development-repository-artifact-hash-mismatch");
                require(blockers,
                        exactNumber(record.get("bytes"), Files.size(payload)),
                        "language-development-repository-artifact-size-mismatch");
                if (checksumRegular) {
                    String expectedChecksum = digest + "  "
                            + payload.getFileName() + "\n";
                    require(blockers,
                            expectedChecksum.equals(Files.readString(
                                    checksum, StandardCharsets.UTF_8)),
                            "language-development-repository-artifact-checksum-mismatch");
                }
            }
        }
        if (artifacts.size() > expected.size()) {
            blockers.add("language-artifact-manifest-artifact-record-unexpected");
        }
        inspectLanguageRepositoryClosure(repository, expected, blockers);
    }

    private static List<ExpectedLanguageArtifact> expectedLanguageArtifacts(
            String version) {
        List<String> artifacts = new ArrayList<>(LANGUAGE_ARTIFACTS);
        boolean localRc = isLocalRcLanguage(version);
        if (localRc) artifacts.add("blue-conformance");
        List<LanguageArtifactKind> kinds = localRc ? Arrays.asList(
                new LanguageArtifactKind("javadoc", "-javadoc.jar"),
                new LanguageArtifactKind("pom", ".pom"),
                new LanguageArtifactKind("runtime", ".jar"),
                new LanguageArtifactKind("sources", "-sources.jar")) : LANGUAGE_KINDS;
        Collections.sort(artifacts);
        List<ExpectedLanguageArtifact> expected = new ArrayList<>();
        for (String artifact : artifacts) {
            String base = "blue/language/" + artifact + "/" + version + "/"
                    + artifact + "-" + version;
            for (LanguageArtifactKind kind : kinds) {
                expected.add(new ExpectedLanguageArtifact(
                        LANGUAGE_GROUP + ":" + artifact + ":" + version,
                        kind.name,
                        base + kind.suffix));
            }
        }
        return expected;
    }

    private static void inspectLanguageRepositoryClosure(
            Path repository,
            List<ExpectedLanguageArtifact> expectedArtifacts,
            List<String> blockers) throws Exception {
        Set<String> expectedFiles = new LinkedHashSet<>();
        expectedFiles.add(LANGUAGE_MANIFEST_FILE);
        expectedFiles.add(LANGUAGE_MANIFEST_FILE + ".sha256");
        for (ExpectedLanguageArtifact artifact : expectedArtifacts) {
            expectedFiles.add(artifact.path);
            expectedFiles.add(artifact.path + ".sha256");
        }

        Set<String> actualFiles = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(repository)) {
            for (Path path : paths.collect(Collectors.toList())) {
                if (path.equals(repository)) {
                    continue;
                }
                String relative = repository.relativize(path).toString()
                        .replace(path.getFileSystem().getSeparator(), "/");
                if (Files.isSymbolicLink(path)) {
                    blockers.add(
                            "language-development-repository-symbolic-link-present");
                } else if (Files.isRegularFile(
                        path, LinkOption.NOFOLLOW_LINKS)) {
                    actualFiles.add(relative);
                } else if (!Files.isDirectory(
                        path, LinkOption.NOFOLLOW_LINKS)) {
                    blockers.add(
                            "language-development-repository-nonregular-path-present");
                }
            }
        }
        require(blockers,
                actualFiles.size() == expectedArtifacts.size() * 2 + 2,
                "language-development-repository-file-count-mismatch");
        require(blockers,
                expectedFiles.equals(actualFiles),
                "language-development-repository-file-closure-mismatch");
        require(blockers,
                actualFiles.stream().noneMatch(path -> path.endsWith(".module")),
                "language-development-repository-gradle-module-metadata-present");
    }

    private static boolean regularNonSymbolic(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
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

    private static final class LanguageArtifactKind {
        private final String name;
        private final String suffix;

        private LanguageArtifactKind(String name, String suffix) {
            this.name = name;
            this.suffix = suffix;
        }
    }

    private static final class ExpectedLanguageArtifact {
        private final String coordinate;
        private final String kind;
        private final String path;

        private ExpectedLanguageArtifact(
                String coordinate, String kind, String path) {
            this.coordinate = coordinate;
            this.kind = kind;
            this.path = path;
        }
    }
}
