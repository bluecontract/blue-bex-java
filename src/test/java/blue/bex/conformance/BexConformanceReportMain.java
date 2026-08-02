package blue.bex.conformance;

import blue.language.processor.RuntimeWorkSession;
import blue.language.processor.RuntimeWorkBudget;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes deterministic, machine-readable evidence from the exact JUnit XML
 * and artifacts present in the build directory. It never turns declared tests
 * into claimed executions.
 */
public final class BexConformanceReportMain {
    private BexConformanceReportMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            throw new IllegalArgumentException(
                    "Expected projectDir, buildDir, Gradle version, project "
                            + "version, dependency mode, declared dependency, "
                            + "persistent evidence root, composite path, and "
                            + "artifact build directory");
        }
        Path projectDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path buildDir = Paths.get(args[1]).toAbsolutePath().normalize();
        String gradleVersion = args[2];
        String projectVersion = args[3];
        String dependencyMode = args[4];
        String declaredDependency = args[5];
        Path persistentEvidenceRoot =
                Paths.get(args[6]).toAbsolutePath().normalize();
        Path compositePath = args[7].isEmpty()
                ? null
                : Paths.get(args[7]).toAbsolutePath().normalize();
        Path artifactBuildDir =
                Paths.get(args[8]).toAbsolutePath().normalize();

        TestEvidence tests = readTests(
                buildDir.resolve("test-results").resolve("test"));
        SourceState sourceState = sourceState(projectDir);
        Map<String, String> baseline = readEvidence(
                projectDir.resolve("src/test/resources/hosted-release")
                        .resolve("baseline.properties"));
        Map<String, String> publishedApiInspection = readEvidence(
                projectDir.resolve("src/test/resources/hosted-release")
                        .resolve("published-api-inspection.properties"));
        Map<String, Object> operatorCoverage = operatorCoverage(tests);
        Map<String, Object> counterCoverage = counterCoverage(tests);
        Map<String, Object> normativeVectorCoverage =
                normativeVectorCoverage(tests);
        List<Object> artifacts = artifactEvidence(
                projectDir, artifactBuildDir, projectVersion);
        Map<String, Object> dependencyResolution =
                dependencyResolutionEvidence(
                        buildDir,
                        dependencyMode,
                        declaredDependency,
                        publishedApiInspection,
                        compositePath);
        Map<String, Object> releaseGates = releaseGateEvidence(
                projectDir,
                buildDir,
                persistentEvidenceRoot,
                projectVersion,
                sourceState.commit,
                dependencyMode,
                declaredDependency,
                dependencyResolution,
                compositePath);
        Map<String, Object> specification =
                specificationEvidence(projectDir, baseline);
        Map<String, Object> versionAutomation =
                versionAutomationEvidence(
                        projectDir, projectVersion, baseline);
        Map<String, Object> namedEvidence =
                namedReleaseEvidence(tests);
        Map<String, Object> gasExhaustionTraceExamples =
                gasExhaustionTraceExamples(projectDir, tests);
        namedEvidence.put(
                "gasExhaustionTraceExamples",
                gasExhaustionTraceExamples);
        Map<String, Object> hostedLocalLimitCapability =
                hostedLocalLimitCapability(tests);
        Map<String, Object> cyclicProofUnavailabilityCapability =
                cyclicProofUnavailabilityCapability(tests);
        Map<String, Object> hostLongTrace =
                hostLongTraceEvidence(buildDir);
        Map<String, Object> hostedOutcomes =
                hostedOutcomeEvidence(tests);
        Map<String, Object> languageReleaseIdentity =
                languageReleaseIdentity(
                        dependencyResolution,
                        compositePath,
                        publishedApiInspection,
                        declaredDependency);
        List<Object> representationMatrix =
                representationMatrix(tests);
        Map<String, Object> representationMatrixResult =
                representationMatrixResult(
                        representationMatrix,
                        namedEvidence);
        Map<String, Object> finalTotals = finalTotals(
                tests,
                operatorCoverage,
                counterCoverage,
                normativeVectorCoverage);
        List<String> currentModeFailures =
                currentModeFailures(
                        tests,
                        operatorCoverage,
                        counterCoverage,
                        normativeVectorCoverage,
                        artifacts,
                        releaseGates,
                        dependencyResolution,
                        specification,
                        versionAutomation,
                        namedEvidence,
                        hostedLocalLimitCapability,
                        cyclicProofUnavailabilityCapability,
                        sourceState,
                        hostLongTrace,
                        hostedOutcomes,
                        languageReleaseIdentity,
                        representationMatrixResult);

        if (currentModeFailures.isEmpty()
                && modeRunCanPersistEvidence(
                        dependencyMode, dependencyResolution)) {
            persistModeEvidence(
                    persistentEvidenceRoot,
                    dependencyMode,
                    declaredDependency,
                    projectVersion,
                    sourceState,
                    compositePath,
                    dependencyResolution,
                    tests,
                    normativeVectorCoverage,
                    artifacts,
                    projectDir,
                    specification,
                    namedEvidence,
                    publishedApiInspection);
        }
        Map<String, Object> buildModes = buildModeMatrix(
                persistentEvidenceRoot,
                declaredDependency,
                projectVersion,
                sourceState,
                compositePath,
                publishedApiInspection);
        boolean exactFinalArtifactProven =
                bindLanguageReleaseIdentityToModes(
                        languageReleaseIdentity,
                        buildModes);
        releaseGates.put(
                "cleanDependencyCacheAcceptance",
                cleanDependencyCacheAcceptance(buildModes));
        boolean bothModesPassed =
                Boolean.TRUE.equals(buildModes.get("allRequiredModesPassed"));
        boolean releaseReady =
                currentModeFailures.isEmpty()
                        && bothModesPassed
                        && exactFinalArtifactProven;

        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("schema", "blue-bex-hosted-release-report/2.0");
        report.put("releaseReady", releaseReady);
        report.put("currentModeFailures", currentModeFailures);
        report.put("commit", sourceState.commit);
        report.put("worktreeDirty", sourceState.worktreeDirty);
        report.put("sourceState", sourceState.report());
        report.put("baseline", evidenceMap(baseline));
        report.put("finalTotals", finalTotals);
        report.put("specification", specification);
        report.put("versionAutomation", versionAutomation);
        report.put("projectVersion", projectVersion);
        report.put("toolchain", map(
                "javaVersion", System.getProperty("java.version"),
                "javaVendor", System.getProperty("java.vendor"),
                "gradleVersion", gradleVersion));
        report.put("dependency", map(
                "mode", dependencyMode,
                "declaredCoordinate", declaredDependency,
                "resolution", dependencyResolution,
                "localComposite",
                compositeDependencyEvidence(compositePath)));
        report.put("hostedStandaloneMatrix", buildModes);
        report.put("publishedHostApiInspection",
                evidenceMap(publishedApiInspection));
        report.put("languageReleaseIdentity",
                languageReleaseIdentity);
        report.put("hostedLocalLimitCapability",
                hostedLocalLimitCapability);
        report.put("cyclicProofUnavailabilityCapability",
                cyclicProofUnavailabilityCapability);
        report.put("identities", identities());
        report.put("tests", tests.report());
        report.put("operatorCoverage", operatorCoverage);
        report.put("counterCoverage", counterCoverage);
        report.put("normativeVectorCoverage",
                normativeVectorCoverage);
        report.put("representationMatrix", representationMatrix);
        report.put("representationMatrixResult",
                representationMatrixResult);
        report.put("cacheMatrix", cacheMatrix(tests));
        report.put("representationInvarianceEvidence",
                namedEvidence.get(
                        "representationInvarianceEvidence"));
        report.put("semanticBoundaryInvocationEvidence",
                namedEvidence.get("semanticBoundaryInvocationEvidence"));
        report.put("ledgerLifecycleEvidence",
                namedEvidence.get("ledgerLifecycleEvidence"));
        report.put("gasExhaustionEvidence",
                namedEvidence.get("gasExhaustionEvidence"));
        report.put("gasExhaustionTraceExamples",
                gasExhaustionTraceExamples);
        report.put("hostedOutcomes", hostedOutcomes);
        report.put("hostLongTrace", hostLongTrace);
        report.put(
                "maximumObservedOrderedTraceEntries",
                hostLongTrace.get(
                        "maximumObservedOrderedTraceEntries"));
        report.put("cyclicProofEvidence",
                namedEvidence.get("cyclicProofEvidence"));
        report.put("intrinsicEvidence",
                namedEvidence.get("intrinsicEvidence"));
        report.put("referenceEvidenceClassificationEvidence",
                namedEvidence.get(
                        "referenceEvidenceClassificationEvidence"));
        report.put("recursionEvidence", recursionEvidence(tests));
        report.put("finiteLoopEvidence", finiteLoopEvidence(tests));
        report.put("artifacts", artifacts);
        report.put("releaseGates", releaseGates);
        report.put("knownLimitations", knownLimitations(
                buildModes,
                publishedApiInspection,
                hostedLocalLimitCapability,
                cyclicProofUnavailabilityCapability,
                hostLongTrace,
                languageReleaseIdentity,
                sourceState));
        report.put("releaseCoordinate",
                "blue.bex:blue-bex-java:" + projectVersion);
        report.put("releaseIdentity", releaseIdentity(
                projectVersion,
                sourceState,
                declaredDependency,
                dependencyResolution,
                specification,
                artifacts));

        Path outputRoot = buildDir.resolve("reports")
                .resolve("bex-conformance")
                .toAbsolutePath().normalize();
        Path output = outputRoot.resolve("report.json");
        Path markdown = outputRoot.resolve("report.md");
        Path readiness = outputRoot.resolve(
                "release-readiness.properties");
        Files.createDirectories(outputRoot);
        Files.write(
                output,
                (ConformancePackage.json(report, true) + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        Files.write(
                markdown,
                markdownReport(
                        report,
                        baseline,
                        tests,
                        operatorCoverage,
                        counterCoverage,
                        buildModes,
                        currentModeFailures,
                        artifacts,
                        releaseGates,
                        publishedApiInspection)
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        writeEvidence(
                readiness,
                stringMap(
                        "schema",
                        "blue-bex-release-readiness/1.0",
                        "releaseReady",
                        String.valueOf(releaseReady),
                        "cyclicProofUnavailabilityCapability",
                        String.valueOf(
                                cyclicProofUnavailabilityCapability.get(
                                        "status")),
                        "reason",
                        releaseReady
                                ? "all-required-evidence-passed"
                                : readinessReason(
                                        currentModeFailures,
                                        bothModesPassed)));
        System.out.println("BEX conformance report: " + output);
        System.out.println("BEX conformance report: " + markdown);
    }

    private static Map<String, Object> identities() {
        Map<String, Object> fixture =
                ConformancePackage.fixtureManifest();
        Map<String, Object> gas =
                ConformancePackage.gasManifest();
        Map<String, Object> registry =
                ConformancePackage.registryManifest();
        return map(
                "bexRegistry", registry.get("packageIdentity"),
                "gasManifest", gas.get("packageIdentity"),
                "fixturePackage", fixture.get("packageIdentity"),
                "fixtureBindsRegistry",
                fixture.get("registryPackageIdentity"),
                "fixtureBindsGas",
                fixture.get("gasManifestPackageIdentity"));
    }

    private static Map<String, Object> finalTotals(
            TestEvidence tests,
            Map<String, Object> operatorCoverage,
            Map<String, Object> counterCoverage,
            Map<String, Object> normativeVectorCoverage) {
        int passingBehaviorFixtures = 0;
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            if ("passed".equals(tests.fixtureStatus(fixture.id()))) {
                passingBehaviorFixtures++;
            }
        }
        return map(
                "tests", tests.report(),
                "behaviorFixtures", map(
                        "required",
                        ConformancePackage.BEHAVIOR_FIXTURE_COUNT,
                        "executedAndPassing",
                        passingBehaviorFixtures),
                "gasMicrofixtures", map(
                        "required",
                        ConformancePackage.GAS_FIXTURE_COUNT,
                        "executedAndPassing",
                        counterCoverage.get(
                                "passingMicrofixtureCount")),
                "normativeVectors", map(
                        "required",
                        normativeVectorCoverage.get("required"),
                        "executedAndPassing",
                        normativeVectorCoverage.get(
                                "passingVectorCount"),
                        "coverageIntegrityTest",
                        normativeVectorCoverage.get(
                                "coverageIntegrityTest"),
                        "allPassing",
                        normativeVectorCoverage.get("allPassing")),
                "operators", map(
                        "required", ConformancePackage.OPERATOR_COUNT,
                        "executedAndPassing",
                        operatorCoverage.get(
                                "passingOperatorCount")));
    }

    private static Map<String, Object> normativeVectorCoverage(
            TestEvidence tests) {
        Map<String, Object> source = ConformancePackage.loadMap(
                ConformancePackage.FIXTURE_ROOT
                        + "vector-coverage.yaml");
        Map<String, Object> declarations = ConformancePackage.map(
                source.get("vectors"), "vector-coverage.vectors");
        Map<String, ConformancePackage.Fixture> fixturesByPath =
                new LinkedHashMap<
                        String, ConformancePackage.Fixture>();
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            fixturesByPath.put(fixture.path, fixture);
        }
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.gasFixtures()) {
            fixturesByPath.put(fixture.path, fixture);
        }

        List<Object> matrix = new ArrayList<Object>();
        int passing = 0;
        for (Map.Entry<String, Object> declaration
                : declarations.entrySet()) {
            List<String> statuses = new ArrayList<String>();
            List<Object> fixtureEvidence =
                    new ArrayList<Object>();
            for (Object pathValue : ConformancePackage.list(
                    declaration.getValue(),
                    "vector-coverage." + declaration.getKey())) {
                String path = String.valueOf(pathValue);
                ConformancePackage.Fixture fixture =
                        fixturesByPath.get(path);
                String status = fixture == null
                        ? "invalid-reference"
                        : tests.fixtureStatus(fixture.id());
                statuses.add(status);
                fixtureEvidence.add(map(
                        "path", path,
                        "fixtureId",
                        fixture == null ? null : fixture.id(),
                        "status", status));
            }
            String status = aggregateVectorStatus(statuses);
            if ("passed".equals(status)) {
                passing++;
            }
            matrix.add(map(
                    "vector", declaration.getKey(),
                    "status", status,
                    "fixtures", fixtureEvidence));
        }
        String integrityStatus = tests.namedStatus(
                "vectorOperatorAndProjectionCoverageAreClosedAndBidirectional");
        boolean allPassing =
                "passed".equals(integrityStatus)
                        && passing == declarations.size();
        return map(
                "required", declarations.size(),
                "passingVectorCount", passing,
                "coverageIntegrityTest", integrityStatus,
                "allPassing", allPassing,
                "matrix", matrix);
    }

    static String aggregateVectorStatus(List<String> statuses) {
        if (statuses.isEmpty()
                || statuses.contains("invalid-reference")) {
            return "invalid-reference";
        }
        if (statuses.contains("failed")) {
            return "failed";
        }
        if (statuses.contains("skipped")) {
            return "skipped";
        }
        if (statuses.contains("not-executed")) {
            return "not-executed";
        }
        for (String status : statuses) {
            if (!"passed".equals(status)) {
                return "indeterminate";
            }
        }
        return "passed";
    }

    private static Map<String, Object> dependencyResolutionEvidence(
            Path buildDir,
            String expectedMode,
            String declaredDependency,
            Map<String, String> publishedInspection,
            Path compositePath)
            throws Exception {
        Path evidencePath = buildDir.resolve("reports")
                .resolve("dependencies")
                .resolve("language.json");
        if (!Files.isRegularFile(evidencePath)) {
            return map(
                    "status", "not-executed",
                    "evidencePresent", false);
        }
        String evidence = new String(
                Files.readAllBytes(evidencePath), StandardCharsets.UTF_8);
        String mode = jsonString(evidence, "mode");
        String declaredVersion = jsonString(
                evidence, "declaredLanguageVersion");
        String languageCommit = jsonString(evidence, "languageCommit");
        String languageCheckoutState = jsonString(
                evidence, "languageCheckoutState");
        boolean standalone = "standalone-published".equals(expectedMode);
        boolean cleanCacheInitiallyAbsent = jsonBoolean(
                evidence, "exactVersionCacheInitiallyAbsent");
        List<Map<String, Object>> artifacts =
                dependencyArtifacts(evidence);
        boolean artifactsValid = !artifacts.isEmpty();
        Map<String, Object> aggregateArtifact = null;
        for (Map<String, Object> artifact : artifacts) {
            Path path = Paths.get(String.valueOf(artifact.get("path")));
            boolean valid = Files.isRegularFile(path)
                    && Files.size(path) == ((Long) artifact.get("bytes"))
                    && sha256(path).equals(artifact.get("sha256"));
            artifact.put("matchesRecordedEvidence", valid);
            artifactsValid &= valid;
            if (String.valueOf(artifact.get("name"))
                    .startsWith("blue-language-java-")) {
                aggregateArtifact = artifact;
            }
        }
        String[] focusedModules = {
                "blue-language-model",
                "blue-language-core",
                "blue-language-mapping",
                "blue-contracts-core"
        };
        boolean focusedModulesResolved = true;
        for (String module : focusedModules) {
            focusedModulesResolved &= evidence.contains(module);
        }
        String expectedVersion = declaredDependency.substring(
                declaredDependency.lastIndexOf(':') + 1);
        boolean sourceProvenanceValid = standalone
                ? "compatible-with-final-hosted-adapter".equals(
                        publishedInspection.get("status"))
                        && aggregateArtifact != null
                        && publishedInspection.get("artifact.sha256").equals(
                                aggregateArtifact.get("sha256"))
                : languageCommit.matches("[0-9a-f]{40}")
                        && "clean".equals(languageCheckoutState);
        boolean cleanCacheAccepted = !standalone
                || cleanCacheInitiallyAbsent;
        boolean passed = "passed".equals(jsonString(evidence, "status"))
                && expectedMode.equals(mode)
                && expectedVersion.equals(declaredVersion)
                && artifactsValid
                && aggregateArtifact != null
                && focusedModulesResolved
                && sourceProvenanceValid
                && cleanCacheAccepted;
        return map(
                "status", passed ? "passed" : "stale-or-failed",
                "evidencePresent", true,
                "receiptPath", evidencePath.toString(),
                "receiptSha256", sha256(evidencePath),
                "mode", mode,
                "declaredCoordinate", declaredDependency,
                "effectiveComponent", standalone
                        ? declaredDependency : "project :blue-language-java",
                "effectiveCoordinate", declaredDependency,
                "declaredLanguageVersion", declaredVersion,
                "languageCommit", languageCommit,
                "languageCheckoutState", languageCheckoutState,
                "focusedModulesResolved", focusedModulesResolved,
                "artifactCount", artifacts.size(),
                "artifacts", artifacts,
                "artifact", aggregateArtifact != null
                        ? aggregateArtifact : Collections.emptyMap(),
                "provenance", map(
                        "status", sourceProvenanceValid
                                ? "passed" : "failed",
                        "kind", standalone
                                ? "reviewed-published-focused-modules"
                                : "exact-clean-local-composite",
                        "publishedReviewStatus",
                        publishedInspection.get("status"),
                        "repositoryPolicy", "maven-central-only",
                        "recordedRepository",
                        publishedInspection.get("repository"),
                        "recordedCoordinate",
                        publishedInspection.get("coordinate"),
                        "recordedSha256",
                        publishedInspection.get("artifact.sha256"),
                        "resolvedHashMatchesRecordedMavenCentralHash",
                        standalone && sourceProvenanceValid,
                        "networkFetchObservation",
                        standalone ? "isolated-resolution" : "not-applicable"),
                "cleanDependencyCacheAcceptance", map(
                        "status", cleanCacheAccepted
                                ? "passed" : "failed",
                        "freshProofRequired", standalone,
                        "scope", "all focused and aggregate Language modules",
                        "moduleVersionPath", "Gradle module cache for exact version",
                        "moduleVersionInitiallyAbsentAtProjectConfiguration",
                        cleanCacheInitiallyAbsent,
                        "reason", standalone
                                ? "exact focused-module version cache must be absent before isolated resolution"
                                : "fresh module cache proof is not required for local composite mode"),
                "compositePath", compositePath != null
                        ? compositePath.toString() : "");
    }

    static boolean modeRunCanPersistEvidence(
            String dependencyMode,
            Map<String, Object> dependencyResolution) {
        if (!"standalone-published".equals(dependencyMode)) {
            return true;
        }
        Map<String, Object> cache = castMap(
                dependencyResolution.get(
                        "cleanDependencyCacheAcceptance"));
        return Boolean.TRUE.equals(cache.get("freshProofRequired"))
                && "passed".equals(cache.get("status"));
    }

    private static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
                .matcher(json);
        return matcher.find()
                ? matcher.group(1).replace("\\\\", "\\")
                        .replace("\\\"", "\"")
                : "";
    }

    private static boolean jsonBoolean(String json, String field) {
        return Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*true").matcher(json).find();
    }

    private static List<Map<String, Object>> dependencyArtifacts(
            String json) {
        Pattern artifactPattern = Pattern.compile(
                "\\{\\\"name\\\":\\\"((?:\\\\.|[^\\\"])*)\\\","
                        + "\\\"path\\\":\\\"((?:\\\\.|[^\\\"])*)\\\","
                        + "\\\"bytes\\\":([0-9]+),"
                        + "\\\"sha256\\\":\\\"([0-9a-f]{64})\\\"\\}");
        Matcher matcher = artifactPattern.matcher(json);
        List<Map<String, Object>> artifacts =
                new ArrayList<Map<String, Object>>();
        while (matcher.find()) {
            artifacts.add(map(
                    "name", matcher.group(1),
                    "path", matcher.group(2).replace("\\\\", "\\")
                            .replace("\\\"", "\""),
                    "bytes", Long.valueOf(matcher.group(3)),
                    "sha256", matcher.group(4)));
        }
        return artifacts;
    }

    private static Map<String, Object> specificationEvidence(
            Path projectDir,
            Map<String, String> baseline) throws IOException {
        Path specification = projectDir.resolve("specifications")
                .resolve("blue-bex-specification-2.0.md");
        String actual = Files.isRegularFile(specification)
                ? sha256(specification)
                : "unavailable";
        String expected = baseline.get("specificationSha256");
        return map(
                "path",
                "specifications/blue-bex-specification-2.0.md",
                "sha256", actual,
                "baselineSha256", expected,
                "matchesBaseline",
                actual.equals(expected));
    }

    static Map<String, Object> versionAutomationEvidence(
            Path projectDir,
            String projectVersion,
            Map<String, String> baseline) throws IOException {
        Path czToml = projectDir.resolve(".cz.toml");
        String actual = Files.isRegularFile(czToml)
                ? sha256(czToml)
                : "unavailable";
        String expected = baseline.get("czTomlSha256");
        String configuredVersion = readCommitizenVersion(czToml);
        String expectedVersion = projectVersion.endsWith("-SNAPSHOT")
                ? projectVersion.substring(
                        0,
                        projectVersion.length()
                                - "-SNAPSHOT".length())
                : projectVersion;
        return map(
                "path", ".cz.toml",
                "sha256", actual,
                "historicalBaselineSha256", expected,
                "matchesHistoricalBaseline", actual.equals(expected),
                "configuredVersion", configuredVersion,
                "projectVersion", projectVersion,
                "matchesProjectVersion",
                configuredVersion.equals(expectedVersion));
    }

    private static String readCommitizenVersion(Path czToml)
            throws IOException {
        if (!Files.isRegularFile(czToml)) {
            return "unavailable";
        }
        for (String line : Files.readAllLines(
                czToml, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int equals = trimmed.indexOf('=');
            if (equals < 0
                    || !"version".equals(
                            trimmed.substring(0, equals).trim())) {
                continue;
            }
            int firstQuote = trimmed.indexOf('"', equals + 1);
            int lastQuote = trimmed.lastIndexOf('"');
            if (equals >= 0
                    && firstQuote > equals
                    && lastQuote > firstQuote) {
                return trimmed.substring(firstQuote + 1, lastQuote);
            }
        }
        return "unavailable";
    }

    private static Map<String, Object> namedReleaseEvidence(
            TestEvidence tests) {
        Map<String, Object> evidence =
                new LinkedHashMap<String, Object>();
        evidence.put(
                "semanticBoundaryInvocationEvidence",
                tests.namedEvidence(
                        "standaloneAndCustomBoundariesReturnTheirFrozenExactResult",
                        "admittedScalarsRetainTheirRawKindsEqualityAndTruthiness",
                        "hostNormalizationWinsWhileMatchingExactDescendantsStayLocal",
                        "invokesBoundaryExactlyOnceForEveryDistinctTransientSemanticValue",
                        "reusesAliasesButAdmitsEveryRecreatedTransientStructure",
                        "nodeIdentityAdmissionIsReusedByPatchEventOverlayAndRootOutput",
                        "outputAdmissionIsReusedByALaterNodeIdentityRequest",
                        "ordinaryNonCyclicExactRootBypassesHostSemanticBoundary",
                        "blue.bex.BexCompositeExhaustionEvidenceTest"
                                + "#transientNodeBlueIdStopsBeforeSemanticIdentityBoundary",
                        "preservesHostFailureClassificationWithoutBexWrapping",
                        "processorExecutionContextUsesItsInvocationSemanticOutputBoundary"));
        evidence.put(
                "representationInvarianceEvidence",
                tests.namedEvidence(
                        "programDocumentAndEventAreInvariantAcrossPhysicalRepresentations",
                        "coldBatchedRepresentationsUseIndependentRuntimeCaches",
                        "fixtureAdapterActuallyExecutesDeclaredBatchingPreparation"));
        evidence.put(
                "ledgerLifecycleEvidence",
                tests.classAndNamedEvidence(
                        new String[] {
                                "BexHostedRuntimeWorkSession",
                                "BexExecutionEvidenceLedger"
                        },
                        "blue.language.processor.BexHostedRuntimeWorkSessionTest"
                                + "#providerUnavailableUsesHostedSuspensionAndRestoresParentBudget"));
        evidence.put(
                "gasExhaustionEvidence",
                tests.classAndNamedEvidence(
                        new String[] {
                                "BexCompositeExhaustion",
                                "BexPrimitiveExhaustionEvidence"
                        },
                        "blue.bex.BexCompositeExhaustionEvidenceTest"
                                + "#exhaustionIsStableAcrossInlineColdReferenceAndWarmReferenceDocuments",
                        "blue.bex.BexCompositeExhaustionEvidenceTest"
                                + "#rejectedPatchAppendDoesNotMutateChangesetOrOverlay"));
        evidence.put(
                "cyclicProofEvidence",
                tests.namedEvidence(
                        "exactCyclicMemberStaysOpaqueAndTransientCounterfeitsFailClosed",
                        "cyclicMemberStructuralReadRequiresAndAcceptsCompleteSetProof",
                        "cyclicMemberContentFetchUnavailabilityStopsBeforeProofQuery",
                        "nullCyclicProofAfterFoundContentIsInvalidNotUnavailable",
                        "malformedCyclicProofIsDeterministicInvalidEvidence",
                        "blue.bex.BexExecutionEvidenceLedgerTest"
                                + "#hostedCyclicStructuralReadWithMissingProofIsDeterministic",
                        "blue.bex.BexExactReferenceDocumentTest"
                                + "#nestedCyclicResolvedBodyRemainsOpaqueUntilCompleteProof",
                        "hostedOpaqueCyclicMemberSupportsIdentityAndOutputWithoutProofDemand"));
        evidence.put(
                "intrinsicEvidence",
                tests.namedEvidence(
                        "duplicateIntrinsicBlueIdIsRejectedInsteadOfReplacingRegistration",
                        "intrinsicCounterCatalogAndInvocationFieldsAreImmutableSnapshots",
                        "rejectedNamedChargePreventsAllLaterIntrinsicWork",
                        "intrinsicUnavailableUsesHostedDiscardLifecycle",
                        "intrinsicInvalidEvidenceUsesHostedDeterministicLifecycle",
                        "arbitraryIntrinsicFailureIsNotReclassifiedAsUnavailable",
                        "intrinsicExactFieldUsesSharedSemanticAdmissionAndMemoization",
                        "intrinsicBoundaryExposesNoLedgerOrPortableGasEvidencePath",
                        "blue.bex.api.Bex20ApiSurfaceTest"
                                + "#intrinsicRegistrationAlwaysRequiresRegistryIdentityAndNamedWeights",
                        "blue.bex.BexIntrinsicTest"
                                + "#registryIdentityEncodingAndRuntimeNamespacesAreUnambiguous",
                        "blue.bex.BexIntrinsicTest"
                                + "#unsupportedIntrinsicFailsAtCompileTime"));
        evidence.put(
                "referenceEvidenceClassificationEvidence",
                tests.namedEvidence(
                        "providerNotFoundIsIncompleteExecutionEvidenceNotSemanticAbsence",
                        "providerUnavailableRetainsItsDiagnosticAndRequiredIdentity",
                        "invalidProviderEvidenceIsASeparateDeterministicFailure",
                        "foundContentWithMismatchedIdentityIsInvalidEvidence",
                        "arbitraryProviderBugIsNeverReclassifiedAsTransientUnavailability",
                        "priorValidMaterializationDoesNotHideAChangedProviderOutcome"));
        return evidence;
    }

    private static Map<String, Object> gasExhaustionTraceExamples(
            Path projectDir,
            TestEvidence tests) throws IOException {
        Path relativePath = Paths.get(
                "src/test/resources/hosted-release/"
                        + "gas-exhaustion-trace-examples.properties");
        Path evidencePath = projectDir.resolve(relativePath)
                .toAbsolutePath().normalize();
        Map<String, String> expected =
                readEvidence(evidencePath);
        List<String> definitionFailures =
                new ArrayList<String>();
        if (!"blue-bex-gas-exhaustion-trace-examples/1.0"
                .equals(expected.get("schema"))) {
            definitionFailures.add("unknown-schema");
        }
        String testClass = expected.get("test.class");
        if (!"blue.bex.BexPrimitiveExhaustionEvidenceTest"
                .equals(testClass)) {
            definitionFailures.add("unexpected-test-class");
        }

        Map<String, Object> manifestCounters =
                ConformancePackage.map(
                        ConformancePackage.gasManifest().get(
                                "counters"),
                        "gas-manifest.counters");
        String admittedNamespace =
                expected.get("admittedTrace.namespace");
        String admittedCounter =
                expected.get("admittedTrace.counterName");
        long admittedQuantity = parseLong(
                expected.get("admittedTrace.quantity"));
        long admittedWeight = parseLong(
                expected.get("admittedTrace.weight"));
        long admittedTraceSize = parseLong(
                expected.get("admittedTrace.size"));
        long admittedTraceTotal = parseLong(
                expected.get("admittedTrace.totalGas"));
        Object manifestAdmittedWeight =
                manifestCounters.get(admittedCounter);
        if (!"bex".equals(admittedNamespace)
                || !"expressionEvaluated".equals(admittedCounter)
                || admittedQuantity != 1L
                || !(manifestAdmittedWeight instanceof Number)
                || admittedWeight
                != ((Number) manifestAdmittedWeight).longValue()
                || admittedTraceSize != 1L
                || admittedTraceTotal
                != admittedQuantity * admittedWeight) {
            definitionFailures.add("invalid-admitted-prefix");
        }

        String rawIds = expected.get("example.ids");
        List<String> ids = new ArrayList<String>();
        Set<String> uniqueIds = new LinkedHashSet<String>();
        if (rawIds != null) {
            for (String rawId : rawIds.split(",")) {
                String id = rawId.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                    if (!uniqueIds.add(id)) {
                        definitionFailures.add(
                                "duplicate-example-id:" + id);
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            definitionFailures.add("no-examples");
        }

        List<Object> examples = new ArrayList<Object>();
        List<Object> requiredSelectors =
                new ArrayList<Object>();
        int matchedTestCount = 0;
        boolean allExamplesPassed = !ids.isEmpty();
        for (String id : ids) {
            String prefix = "example." + id + ".";
            String testName =
                    expected.get(prefix + "testName");
            String namespace =
                    expected.get(prefix + "namespace");
            String counterName =
                    expected.get(prefix + "counterName");
            long quantity = parseLong(
                    expected.get(prefix + "quantity"));
            long weight = parseLong(
                    expected.get(prefix + "weight"));
            long admittedGas = parseLong(
                    expected.get(prefix + "admittedGas"));
            long effectiveBudget = parseLong(
                    expected.get(prefix + "effectiveBudget"));
            String rejectedChargePresentText =
                    expected.get(
                            prefix + "rejectedChargePresent");
            boolean rejectedChargePresent =
                    Boolean.parseBoolean(
                            rejectedChargePresentText);
            long laterWorkCount = parseLong(
                    expected.get(prefix + "laterWorkCount"));

            List<String> exampleFailures =
                    new ArrayList<String>();
            Object manifestWeight =
                    manifestCounters.get(counterName);
            if (!"bex".equals(namespace)) {
                exampleFailures.add("namespace");
            }
            if (counterName == null
                    || !counterName.equals(testName)) {
                exampleFailures.add("counter-test-selector");
            }
            if (!(manifestWeight instanceof Number)
                    || weight != ((Number) manifestWeight)
                    .longValue()) {
                exampleFailures.add("manifest-weight");
            }
            if (quantity != 1L) {
                exampleFailures.add("quantity");
            }
            if (admittedGas != admittedTraceTotal
                    || effectiveBudget != admittedTraceTotal) {
                exampleFailures.add(
                        "admitted-gas-or-effective-budget");
            }
            if (weight <= 0L
                    || quantity <= 0L
                    || admittedGas + quantity * weight
                    <= effectiveBudget) {
                exampleFailures.add(
                        "charge-would-not-exhaust-budget");
            }
            if (!"false".equals(
                    rejectedChargePresentText)) {
                exampleFailures.add(
                        "rejected-charge-absence");
            }
            if (laterWorkCount != 0L) {
                exampleFailures.add("later-work");
            }

            Map<String, Object> test =
                    tests.exactEvidence(testClass, testName);
            int matched = asInteger(
                    test.get("matchedTestCount")) == null
                    ? 0
                    : asInteger(
                            test.get("matchedTestCount"));
            matchedTestCount += matched;
            String executionStatus =
                    String.valueOf(test.get("status"));
            String status = exampleFailures.isEmpty()
                    ? executionStatus
                    : "invalid-source-controlled-evidence";
            allExamplesPassed &= "passed".equals(status);
            for (String failure : exampleFailures) {
                definitionFailures.add(
                        id + ":" + failure);
            }
            requiredSelectors.add(map(
                    "className", testClass,
                    "testName", testName,
                    "status", executionStatus,
                    "matchedTestCount", matched));
            examples.add(map(
                    "id", id,
                    "status", status,
                    "namespace", namespace,
                    "counterName", counterName,
                    "quantity", quantity,
                    "weight", weight,
                    "admittedGas", admittedGas,
                    "effectiveBudget", effectiveBudget,
                    "rejectedChargePresent",
                    rejectedChargePresent,
                    "rejectedChargeAbsent",
                    !rejectedChargePresent,
                    "laterWorkCount", laterWorkCount,
                    "noLaterWork", laterWorkCount == 0L,
                    "admittedTrace", map(
                            "namespace", admittedNamespace,
                            "counterName", admittedCounter,
                            "quantity", admittedQuantity,
                            "weight", admittedWeight,
                            "size", admittedTraceSize,
                            "totalGas", admittedTraceTotal),
                    "executedTest", test,
                    "definitionFailures",
                    exampleFailures));
        }
        boolean definitionValid =
                definitionFailures.isEmpty();
        return map(
                "status",
                definitionValid && allExamplesPassed
                        ? "passed"
                        : "not-passing",
                "scope",
                "source-controlled-exact-expected-values-confirmed-by-executed-dynamic-tests",
                "sourceControlledEvidence", map(
                        "path", unix(relativePath),
                        "sha256",
                        Files.isRegularFile(evidencePath)
                                ? sha256(evidencePath)
                                : "unavailable",
                        "schema", expected.get("schema"),
                        "definitionValid", definitionValid,
                        "definitionFailures",
                        definitionFailures),
                "matchedTestCount", matchedTestCount,
                "requiredSelectors", requiredSelectors,
                "examples", examples);
    }

    private static List<String> currentModeFailures(
            TestEvidence tests,
            Map<String, Object> operatorCoverage,
            Map<String, Object> counterCoverage,
            Map<String, Object> normativeVectorCoverage,
            List<Object> artifacts,
            Map<String, Object> releaseGates,
            Map<String, Object> dependencyResolution,
            Map<String, Object> specification,
            Map<String, Object> versionAutomation,
            Map<String, Object> namedEvidence,
            Map<String, Object> hostedLocalLimitCapability,
            Map<String, Object> cyclicProofUnavailabilityCapability,
            SourceState sourceState,
            Map<String, Object> hostLongTrace,
            Map<String, Object> hostedOutcomes,
            Map<String, Object> languageReleaseIdentity,
            Map<String, Object> representationMatrixResult) {
        List<String> failures = new ArrayList<String>();
        require(
                failures,
                "passed".equals(tests.overallStatus()),
                "ordinary-tests-not-passing-with-zero-skips");
        int passingBehavior = 0;
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            if ("passed".equals(tests.fixtureStatus(fixture.id()))) {
                passingBehavior++;
            }
        }
        require(
                failures,
                passingBehavior
                        == ConformancePackage.BEHAVIOR_FIXTURE_COUNT,
                "behavior-fixtures-not-105-of-105");
        require(
                failures,
                Boolean.TRUE.equals(
                        counterCoverage.get("allMicrofixturesPassing")),
                "gas-microfixtures-not-30-of-30");
        require(
                failures,
                Boolean.TRUE.equals(
                        operatorCoverage.get(
                                "allExecutedOperatorsPassing"))
                        && Integer.valueOf(86).equals(
                        asInteger(
                                operatorCoverage.get(
                                        "passingOperatorCount"))),
                "operator-coverage-not-86-of-86");
        require(
                failures,
                Boolean.TRUE.equals(
                        normativeVectorCoverage.get("allPassing")),
                "normative-vectors-not-all-executed-and-passing");
        require(
                failures,
                artifacts.size() == 4,
                "main-sources-javadoc-source-release-artifacts-not-all-present");
        require(
                failures,
                "passed".equals(dependencyResolution.get("status")),
                "dependency-resolution-evidence-not-passing");
        if ("standalone-published".equals(
                dependencyResolution.get("mode"))
                && Boolean.TRUE.equals(castMap(
                        dependencyResolution.get(
                                "cleanDependencyCacheAcceptance"))
                        .get("freshProofRequired"))) {
            require(
                    failures,
                    "passed".equals(castMap(
                            dependencyResolution.get(
                                    "cleanDependencyCacheAcceptance"))
                            .get("status")),
                    "blue-language-module-version-cache-acceptance-not-executed");
        }
        require(
                failures,
                gatePassed(releaseGates, "deterministicArchives"),
                "archive-packaging-determinism-gate-not-passing");
        require(
                failures,
                gatePassed(
                        releaseGates,
                        "independentCleanBuilds"),
                "independent-clean-build-reproducibility-gate-not-passing");
        require(
                failures,
                gatePassed(releaseGates, "binaryApi"),
                "binary-api-gate-not-passing");
        require(
                failures,
                gatePassed(releaseGates, "benchmarkCompilation"),
                "benchmark-compilation-gate-not-passing");
        require(
                failures,
                gatePassed(releaseGates, "java8Bytecode"),
                "packaged-java8-bytecode-gate-not-passing");
        require(
                failures,
                Boolean.TRUE.equals(specification.get("matchesBaseline")),
                "specification-identity-differs-from-baseline");
        require(
                failures,
                Boolean.TRUE.equals(
                        versionAutomation.get(
                                "matchesProjectVersion")),
                "cz-toml-version-differs-from-project-version");
        require(
                failures,
                "1.8".equals(System.getProperty(
                        "java.specification.version")),
                "report-not-running-on-java-8");
        require(
                failures,
                "passed".equals(
                        hostedLocalLimitCapability.get("status")),
                "current-host-shared-local-limit-capability-unavailable");
        require(
                failures,
                "passed".equals(
                        cyclicProofUnavailabilityCapability.get("status")),
                "current-host-cyclic-proof-unavailability-capability-unavailable");
        require(
                failures,
                sourceState.uncommittedReleasePaths.isEmpty(),
                "bex-release-inputs-not-represented-by-reported-commit");
        require(
                failures,
                sourceState.completeWorkspace(),
                "bex-source-workspace-incomplete");
        require(
                failures,
                "passed".equals(hostLongTrace.get("status")),
                "host-long-trace-not-over-256");
        require(
                failures,
                "passed".equals(hostedOutcomes.get("status")),
                "hosted-outcome-matrix-not-passing");
        require(
                failures,
                Boolean.TRUE.equals(
                        languageReleaseIdentity.get(
                                "exactFinalArtifactProven")),
                "exact-final-language-artifact-not-proven");
        require(
                failures,
                "passed".equals(
                        representationMatrixResult.get("status")),
                "representation-matrix-not-passing");
        for (Map.Entry<String, Object> entry
                : namedEvidence.entrySet()) {
            Map<String, Object> group = castMap(entry.getValue());
            require(
                    failures,
                    "passed".equals(group.get("status")),
                    entry.getKey() + "-not-passing");
        }
        return failures;
    }

    private static Map<String, Object> hostLongTraceEvidence(
            Path buildDir) throws IOException {
        Path evidencePath = buildDir.resolve("reports")
                .resolve("bex-release")
                .resolve("host-long-trace.properties");
        Map<String, String> evidence =
                readEvidence(evidencePath);
        if (evidence.isEmpty()) {
            return map(
                    "status", "not-executed",
                    "evidencePath", evidencePath.toString(),
                    "maximumObservedOrderedTraceEntries", 0L,
                    "failures",
                    Collections.singletonList(
                            "host-long-trace-probe-not-executed"));
        }
        List<String> failures = new ArrayList<String>();
        require(
                failures,
                "blue-bex-host-long-trace-evidence/1.0"
                        .equals(evidence.get("schema")),
                "unknown-host-long-trace-evidence-schema");
        long required = parseLong(evidence.get(
                "requiredMinimumOrderedTraceEntries"));
        long observed = parseLong(evidence.get(
                "maximumObservedOrderedTraceEntries"));
        require(
                failures,
                "passed".equals(evidence.get("status")),
                "host-rejected-long-ordered-trace");
        require(
                failures,
                Boolean.parseBoolean(
                        evidence.get("orderPreserved")),
                "host-long-trace-order-not-preserved");
        require(
                failures,
                required > 256L && observed >= required,
                "host-long-trace-did-not-exceed-256");
        return map(
                "status",
                failures.isEmpty()
                        ? "passed"
                        : "blocking",
                "evidencePath", evidencePath.toString(),
                "requestedItems",
                parseLong(evidence.get("requestedItems")),
                "requiredMinimumOrderedTraceEntries",
                required,
                "maximumObservedOrderedTraceEntries",
                observed,
                "observedDistinctCounterKinds",
                parseLong(evidence.get(
                        "observedDistinctCounterKinds")),
                "orderPreserved",
                Boolean.parseBoolean(
                        evidence.get("orderPreserved")),
                "observedFailureClass",
                evidence.get("failure.class"),
                "portableLimit", map(
                        "name",
                        evidence.get("portableLimit.name"),
                        "observed",
                        evidence.get("portableLimit.observed"),
                        "limit",
                        evidence.get("portableLimit.limit")),
                "failures", failures);
    }

    private static Map<String, Object> hostedOutcomeEvidence(
            TestEvidence tests) {
        Map<String, Object> success =
                tests.namedEvidence(
                        "successfulRuntimeSubmitsEverySeparatedLedgerExactlyOnce");
        Map<String, Object> deterministicFailure =
                tests.namedEvidence(
                        "deterministicFailureDiscardsBufferedOutputsAndLeavesPrefixForOwner");
        Map<String, Object> unavailable =
                tests.namedEvidence(
                        "providerUnavailableUsesHostedSuspensionAndRestoresParentBudget");
        Map<String, Object> exhaustion =
                tests.namedEvidence(
                        "hostedGasExhaustionRetainsExactBexPrefixAndNoOutput");
        Map<String, Object> failedOutputAdmission =
                tests.namedEvidence(
                        "failedAdmissionMutatesNeitherPatchNorEventBuffers");
        boolean passed =
                "passed".equals(success.get("status"))
                        && "passed".equals(
                        deterministicFailure.get("status"))
                        && "passed".equals(
                        unavailable.get("status"))
                        && "passed".equals(
                        exhaustion.get("status"))
                        && "passed".equals(
                        failedOutputAdmission.get("status"));
        return map(
                "status", passed ? "passed" : "not-passing",
                "success", success,
                "deterministicFailure",
                deterministicFailure,
                "transientEvidenceUnavailability",
                unavailable,
                "gasExhaustion", exhaustion,
                "failedSemanticAdmissionRollback",
                failedOutputAdmission);
    }

    private static Map<String, Object> languageReleaseIdentity(
            Map<String, Object> dependencyResolution,
            Path compositePath,
            Map<String, String> publishedApiInspection,
            String declaredDependency) throws Exception {
        String publishedCommit =
                publishedApiInspection.get("source.commit");
        boolean commitIdentified = publishedCommit != null
                && publishedCommit.matches("[0-9a-fA-F]{40}");
        String publishedHash =
                publishedApiInspection.get("artifact.sha256");
        boolean hashIdentified = publishedHash != null
                && publishedHash.matches("[0-9a-fA-F]{64}");
        boolean compatible =
                "compatible-with-final-hosted-adapter".equals(
                        publishedApiInspection.get("status"));
        boolean coordinateMatches =
                declaredDependency.equals(
                        publishedApiInspection.get("coordinate"));

        Map<String, Object> localSource =
                Collections.emptyMap();
        boolean localMatchesPublished = compositePath == null;
        if (compositePath != null
                && Files.isDirectory(compositePath)) {
            SourceState state = sourceState(compositePath);
            List<String> tagsAtHead =
                    gitTagsAtHead(compositePath);
            localMatchesPublished =
                    !state.worktreeDirty
                            && state.completeWorkspace()
                            && publishedSourceIdentityMatches(
                            declaredDependency,
                            publishedApiInspection,
                            state.commit,
                            tagsAtHead);
            localSource = new LinkedHashMap<String, Object>(
                    state.report());
            localSource.put("tagsAtHead", tagsAtHead);
            localSource.put(
                    "matchesPublishedIdentity",
                    localMatchesPublished);
        }
        Map<String, Object> resolvedArtifact =
                castMap(dependencyResolution.get("artifact"));
        boolean dependencyResolved =
                "passed".equals(
                        dependencyResolution.get("status"));
        boolean standalone =
                "standalone-published".equals(
                        dependencyResolution.get("mode"));
        boolean resolvedArtifactHashMatchesPublished =
                standalone
                        && publishedHash != null
                        && publishedHash.equals(
                        resolvedArtifact.get("sha256"));
        boolean resolvedArtifactIdentitySatisfied =
                !standalone
                        || resolvedArtifactHashMatchesPublished;
        boolean exactFinalArtifactProven =
                commitIdentified
                        && hashIdentified
                        && compatible
                        && coordinateMatches
                        && localMatchesPublished
                        && dependencyResolved
                        && resolvedArtifactIdentitySatisfied;
        return map(
                "schema",
                "blue-bex-language-release-identity/1.1",
                "exactFinalArtifactProven",
                exactFinalArtifactProven,
                "currentDependencyExactFinalArtifactProven",
                exactFinalArtifactProven,
                "declaredCoordinate", declaredDependency,
                "publishedCoordinate",
                publishedApiInspection.get("coordinate"),
                "publishedArtifactSha256", publishedHash,
                "publishedSourceCommit",
                commitIdentified
                        ? publishedCommit.toLowerCase()
                        : "unavailable",
                "publishedApiStatus",
                publishedApiInspection.get("status"),
                "dependencyResolutionPassed",
                dependencyResolved,
                "resolvedArtifactHashComparisonApplicable",
                standalone,
                "resolvedArtifactMatchesPublishedHash",
                resolvedArtifactHashMatchesPublished,
                "resolvedArtifactIdentitySatisfied",
                resolvedArtifactIdentitySatisfied,
                "resolvedArtifact", resolvedArtifact,
                "localCompositeSource", localSource,
                "localCompositeMatchesPublishedCommit",
                localMatchesPublished,
                "localCompositeMatchesPublishedIdentity",
                localMatchesPublished,
                "failures",
                exactFinalArtifactProven
                        ? Collections.emptyList()
                        : java.util.Arrays.asList(
                        commitIdentified
                                ? null
                                : "published-source-commit-unavailable",
                        hashIdentified
                                ? null
                                : "published-artifact-hash-unavailable",
                        compatible
                                ? null
                                : "published-api-not-final-compatible",
                        coordinateMatches
                                ? null
                                : "published-coordinate-mismatch",
                        dependencyResolved
                                ? null
                                : "dependency-resolution-not-passing",
                        resolvedArtifactIdentitySatisfied
                                ? null
                                : "resolved-artifact-hash-mismatch",
                        localMatchesPublished
                                ? null
                                : "local-composite-not-clean-exact-published-commit-and-version-tag")
                        .stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
    }

    private static Map<String, Object> representationMatrixResult(
            List<Object> matrix,
            Map<String, Object> namedEvidence) {
        List<String> failures = new ArrayList<String>();
        for (Object value : matrix) {
            Map<String, Object> entry = castMap(value);
            if (!"passed".equals(entry.get("status"))) {
                failures.add(
                        String.valueOf(
                                entry.get("fixtureId")));
            }
        }
        Map<String, Object> differential =
                castMap(namedEvidence.get(
                        "representationInvarianceEvidence"));
        if (!"passed".equals(differential.get("status"))) {
            failures.add(
                    "representation-invariance-differential");
        }
        return map(
                "status",
                failures.isEmpty()
                        ? "passed"
                        : "not-passing",
                "matrixEntryCount", matrix.size(),
                "differentialEvidence", differential,
                "failures", failures);
    }

    private static void require(
            List<String> failures,
            boolean condition,
            String failure) {
        if (!condition) {
            failures.add(failure);
        }
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number
                ? ((Number) value).intValue()
                : null;
    }

    private static boolean gatePassed(
            Map<String, Object> releaseGates,
            String name) {
        Map<String, Object> gate =
                castMap(releaseGates.get(name));
        return "passed".equals(gate.get("status"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map)) {
            return Collections.emptyMap();
        }
        return (Map<String, Object>) value;
    }

    private static void persistModeEvidence(
            Path root,
            String mode,
            String declaredDependency,
            String projectVersion,
            SourceState sourceState,
            Path compositePath,
            Map<String, Object> dependencyResolution,
            TestEvidence tests,
            Map<String, Object> normativeVectorCoverage,
            List<Object> artifacts,
            Path projectDir,
            Map<String, Object> specification,
            Map<String, Object> namedEvidence,
            Map<String, String> publishedApiInspection)
            throws Exception {
        Path modeRoot = root.resolve("modes").resolve(mode);
        Path artifactRoot = modeRoot.resolve("artifacts");
        Files.createDirectories(artifactRoot);
        Map<String, String> values =
                new LinkedHashMap<String, String>();
        values.put(
                "schema",
                "blue-bex-build-mode-evidence/2.2");
        values.put("status", "passed");
        values.put("mode", mode);
        values.put("declared.coordinate", declaredDependency);
        values.put("project.version", projectVersion);
        values.put("bex.commit", sourceState.commit);
        values.put(
                "bex.workspaceSha256",
                sourceState.fingerprint.sha256);
        values.put(
                "bex.releaseSourceSha256",
                sourceState.releaseFingerprint.sha256);
        values.put(
                "specification.sha256",
                String.valueOf(specification.get("sha256")));
        values.put(
                "tests.executed",
                String.valueOf(tests.cases.size()));
        values.put(
                "tests.passed",
                String.valueOf(tests.count("passed")));
        values.put("tests.failed", "0");
        values.put("tests.skipped", "0");
        values.put(
                "behaviorFixtures",
                String.valueOf(
                        ConformancePackage.BEHAVIOR_FIXTURE_COUNT));
        values.put(
                "gasMicrofixtures",
                String.valueOf(
                        ConformancePackage.GAS_FIXTURE_COUNT));
        values.put(
                "normativeVectors",
                String.valueOf(
                        normativeVectorCoverage.get(
                                "passingVectorCount")));
        values.put(
                "operators",
                String.valueOf(
                        ConformancePackage.OPERATOR_COUNT));
        values.put(
                "namedEvidence.status",
                allNamedEvidencePassed(namedEvidence)
                        ? "passed"
                        : "failed");
        values.put(
                "dependency.effectiveCoordinate",
                String.valueOf(
                        dependencyResolution.get(
                                "effectiveCoordinate")));
        Map<String, Object> dependencyProvenance =
                castMap(dependencyResolution.get("provenance"));
        values.put(
                "dependency.provenance.status",
                String.valueOf(
                        dependencyProvenance.get("status")));
        values.put(
                "dependency.provenance.repositoryPolicy",
                String.valueOf(
                        dependencyProvenance.get(
                                "repositoryPolicy")));
        values.put(
                "dependency.provenance.recordedRepository",
                String.valueOf(
                        dependencyProvenance.get(
                                "recordedRepository")));
        values.put(
                "dependency.provenance.recordedSha256",
                String.valueOf(
                        dependencyProvenance.get(
                                "recordedSha256")));
        values.put(
                "dependency.cache.acceptance",
                String.valueOf(castMap(
                        dependencyResolution.get(
                                "cleanDependencyCacheAcceptance"))
                        .get("status")));
        values.put(
                "dependency.cache.freshProofRequired",
                String.valueOf(castMap(
                        dependencyResolution.get(
                                "cleanDependencyCacheAcceptance"))
                        .get("freshProofRequired")));
        values.put(
                "dependency.cache.acceptanceScope",
                String.valueOf(castMap(
                        dependencyResolution.get(
                                "cleanDependencyCacheAcceptance"))
                        .get("scope")));
        values.put(
                "dependency.cache.blueLanguageModuleVersionPath",
                String.valueOf(castMap(
                        dependencyResolution.get(
                                "cleanDependencyCacheAcceptance"))
                        .get("moduleVersionPath")));
        values.put(
                "dependency.cache.blueLanguageModuleVersionInitiallyAbsent",
                String.valueOf(castMap(
                        dependencyResolution.get(
                                "cleanDependencyCacheAcceptance"))
                        .get(
                                "moduleVersionInitiallyAbsentAtProjectConfiguration")));

        Map<String, Object> resolvedArtifact =
                castMap(dependencyResolution.get("artifact"));
        Path dependencyArtifact = pathOrNull(
                String.valueOf(resolvedArtifact.get("path")));
        if (dependencyArtifact == null
                || !Files.isRegularFile(dependencyArtifact)) {
            throw new IllegalStateException(
                    "Resolved dependency artifact disappeared");
        }
        Path dependencyCopy =
                artifactRoot.resolve("blue-language-java.jar");
        Files.copy(
                dependencyArtifact,
                dependencyCopy,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        values.put(
                "dependency.artifact.path",
                dependencyCopy.toAbsolutePath().normalize().toString());
        values.put(
                "dependency.artifact.sha256",
                sha256(dependencyCopy));

        for (Object artifactValue : artifacts) {
            Map<String, Object> artifact = castMap(artifactValue);
            String relativePath =
                    String.valueOf(artifact.get("path"));
            Path source = projectDir.resolve(relativePath)
                    .toAbsolutePath().normalize();
            String key = artifactKey(relativePath);
            Path copy = artifactRoot.resolve(
                    source.getFileName().toString());
            Files.copy(
                    source,
                    copy,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            values.put(
                    "artifact." + key + ".path",
                    copy.toString());
            values.put(
                    "artifact." + key + ".sha256",
                    sha256(copy));
        }

        if ("local-composite".equals(mode)) {
            if (compositePath == null
                    || !Files.isDirectory(compositePath)) {
                throw new IllegalStateException(
                        "Local composite mode has no source directory");
            }
            SourceState dependencySource =
                    sourceState(compositePath);
            List<String> tagsAtHead =
                    gitTagsAtHead(compositePath);
            values.put(
                    "composite.path",
                    compositePath.toString());
            values.put(
                    "composite.commit",
                    dependencySource.commit);
            values.put(
                    "composite.workspaceSha256",
                    dependencySource.fingerprint.sha256);
            values.put(
                    "composite.releaseSourceSha256",
                    dependencySource
                            .releaseFingerprint.sha256);
            values.put(
                    "composite.releaseInputsCommitted",
                    String.valueOf(
                            !dependencySource.worktreeDirty
                                    && dependencySource
                                    .completeWorkspace()));
            values.put(
                    "composite.publishedSourceCommit",
                    String.valueOf(
                            publishedApiInspection.get(
                                    "source.commit")));
            values.put(
                    "composite.publishedSourceTag",
                    String.valueOf(
                            publishedApiInspection.get(
                                    "source.tag")));
            values.put(
                    "composite.matchesPublishedIdentity",
                    String.valueOf(
                            publishedSourceIdentityMatches(
                                    declaredDependency,
                                    publishedApiInspection,
                                    dependencySource.commit,
                                    tagsAtHead)));
        }
        writeEvidence(modeRoot.resolve("mode.properties"), values);
    }

    private static boolean allNamedEvidencePassed(
            Map<String, Object> namedEvidence) {
        for (Object value : namedEvidence.values()) {
            if (!"passed".equals(
                    castMap(value).get("status"))) {
                return false;
            }
        }
        return true;
    }

    private static String artifactKey(String path) {
        if (path.endsWith("-source-release.zip")) {
            return "sourceRelease";
        }
        if (path.endsWith("-sources.jar")) {
            return "sources";
        }
        if (path.endsWith("-javadoc.jar")) {
            return "javadoc";
        }
        return "main";
    }

    private static Map<String, Object> buildModeMatrix(
            Path root,
            String declaredDependency,
            String projectVersion,
            SourceState sourceState,
            Path activeCompositePath,
            Map<String, String> publishedApiInspection)
            throws Exception {
        Map<String, Object> standalone = validateModeEvidence(
                root,
                "standalone-published",
                declaredDependency,
                projectVersion,
                sourceState,
                activeCompositePath,
                publishedApiInspection);
        Map<String, Object> local = validateModeEvidence(
                root,
                "local-composite",
                declaredDependency,
                projectVersion,
                sourceState,
                activeCompositePath,
                publishedApiInspection);
        boolean bothPassed =
                "passed".equals(standalone.get("status"))
                        && "passed".equals(local.get("status"));
        boolean artifactsEquivalent = bothPassed
                && artifactHashes(standalone).equals(
                artifactHashes(local));
        boolean allRequired =
                bothPassed && artifactsEquivalent;
        return map(
                "standalonePublished", standalone,
                "localComposite", local,
                "artifactsBehaviorallyEquivalent",
                artifactsEquivalent,
                "allRequiredModesPassed", allRequired,
                "standaloneBlocker",
                "passed".equals(standalone.get("status"))
                        ? Collections.emptyList()
                        : missingPublishedHostApis(
                                publishedApiInspection));
    }

    private static Map<String, Object> validateModeEvidence(
            Path root,
            String expectedMode,
            String declaredDependency,
            String projectVersion,
            SourceState sourceState,
            Path activeCompositePath,
            Map<String, String> publishedApiInspection)
            throws Exception {
        Path evidencePath = root.resolve("modes")
                .resolve(expectedMode)
                .resolve("mode.properties");
        Map<String, String> evidence =
                readEvidence(evidencePath);
        if (evidence.isEmpty()) {
            return map(
                    "mode", expectedMode,
                    "status", "not-executed",
                    "evidencePresent", false,
                    "evidencePath", evidencePath.toString(),
                    "cleanDependencyCacheAcceptance", map(
                            "status", "not-executed",
                            "scope",
                            "standalone-published-blue-language-module-version-cache",
                            "reason",
                            "no-validated-standalone-mode-evidence"));
        }
        List<String> failures = new ArrayList<String>();
        require(
                failures,
                "blue-bex-build-mode-evidence/2.2".equals(
                        evidence.get("schema")),
                "unknown-evidence-schema");
        require(
                failures,
                "passed".equals(evidence.get("status")),
                "mode-run-did-not-pass");
        require(
                failures,
                expectedMode.equals(evidence.get("mode")),
                "mode-mismatch");
        require(
                failures,
                declaredDependency.equals(
                        evidence.get("declared.coordinate")),
                "declared-dependency-mismatch");
        require(
                failures,
                projectVersion.equals(
                        evidence.get("project.version")),
                "project-version-mismatch");
        require(
                failures,
                sourceState.commit.equals(
                        evidence.get("bex.commit")),
                "bex-commit-changed-since-mode-run");
        require(
                failures,
                sourceState.releaseFingerprint.sha256.equals(
                        evidence.get(
                                "bex.releaseSourceSha256")),
                "bex-source-state-changed-since-mode-run");
        require(
                failures,
                "0".equals(evidence.get("tests.failed"))
                        && "0".equals(
                        evidence.get("tests.skipped")),
                "test-failure-or-skip-recorded");
        require(
                failures,
                String.valueOf(
                        ConformancePackage.BEHAVIOR_FIXTURE_COUNT)
                        .equals(
                        evidence.get("behaviorFixtures"))
                        && String.valueOf(
                        ConformancePackage.GAS_FIXTURE_COUNT)
                        .equals(
                        evidence.get("gasMicrofixtures"))
                        && String.valueOf(
                        ConformancePackage.VECTOR_COUNT)
                        .equals(
                        evidence.get("normativeVectors"))
                        && String.valueOf(
                        ConformancePackage.OPERATOR_COUNT)
                        .equals(
                        evidence.get("operators")),
                "conformance-total-mismatch");
        require(
                failures,
                "passed".equals(
                        evidence.get("namedEvidence.status")),
                "named-hosted-evidence-not-passing");
        if ("standalone-published".equals(expectedMode)) {
            require(
                    failures,
                    declaredDependency.equals(
                            evidence.get(
                                    "dependency.effectiveCoordinate")),
                    "standalone-did-not-resolve-declared-coordinate");
            require(
                    failures,
                    "verified-against-recorded-maven-central-hash"
                            .equals(evidence.get(
                                    "dependency.provenance.status")),
                    "standalone-maven-central-provenance-not-verified");
            require(
                    failures,
                    "maven-central-only".equals(evidence.get(
                            "dependency.provenance.repositoryPolicy")),
                    "standalone-repository-policy-not-maven-central-only");
            require(
                    failures,
                    publishedApiInspection.get("repository").equals(
                            evidence.get(
                                    "dependency.provenance.recordedRepository"))
                            && publishedApiInspection.get(
                            "artifact.sha256").equals(
                            evidence.get(
                                    "dependency.provenance.recordedSha256")),
                    "standalone-recorded-provenance-mismatch");
            require(
                    failures,
                    "passed".equals(evidence.get(
                            "dependency.cache.acceptance")),
                    "standalone-blue-language-module-version-cache-not-accepted");
            require(
                    failures,
                    Boolean.parseBoolean(evidence.get(
                            "dependency.cache.freshProofRequired")),
                    "standalone-fresh-module-cache-proof-was-not-required");
            require(
                    failures,
                    "standalone-published-blue-language-module-version-cache"
                            .equals(evidence.get(
                                    "dependency.cache.acceptanceScope")),
                    "standalone-module-version-cache-scope-mismatch");
            require(
                    failures,
                    Boolean.parseBoolean(evidence.get(
                            "dependency.cache.blueLanguageModuleVersionInitiallyAbsent")),
                    "standalone-module-version-cache-was-not-initially-absent");
        }

        Map<String, Object> artifactReports =
                new LinkedHashMap<String, Object>();
        for (String kind
                : new String[] {
                        "main", "sources", "javadoc", "sourceRelease"
                }) {
            Map<String, Object> checked =
                    checkAbsoluteFile(
                            evidence.get(
                                    "artifact." + kind + ".path"),
                            evidence.get(
                                    "artifact." + kind + ".sha256"));
            artifactReports.put(kind, checked);
            require(
                    failures,
                    Boolean.TRUE.equals(
                            checked.get("matchesRecordedEvidence")),
                    kind + "-artifact-evidence-stale");
        }
        Map<String, Object> dependencyArtifact =
                checkAbsoluteFile(
                        evidence.get("dependency.artifact.path"),
                        evidence.get(
                                "dependency.artifact.sha256"));
        require(
                failures,
                Boolean.TRUE.equals(
                        dependencyArtifact.get(
                                "matchesRecordedEvidence")),
                "dependency-artifact-evidence-stale");
        if ("standalone-published".equals(expectedMode)) {
            require(
                    failures,
                    publishedApiInspection.get("artifact.sha256")
                            .equals(dependencyArtifact.get("sha256")),
                    "standalone-dependency-artifact-hash-not-maven-central");
        }

        Map<String, Object> compositeSource =
                Collections.emptyMap();
        if ("local-composite".equals(expectedMode)) {
            Path recordedComposite =
                    pathOrNull(evidence.get("composite.path"));
            Path composite = activeCompositePath != null
                    ? activeCompositePath
                    : recordedComposite;
            boolean samePath = composite != null
                    && recordedComposite != null
                    && composite.equals(recordedComposite);
            require(
                    failures,
                    samePath && Files.isDirectory(composite),
                    "local-composite-source-unavailable");
            if (samePath && Files.isDirectory(composite)) {
                SourceState dependencySource =
                        sourceState(composite);
                List<String> tagsAtHead =
                        gitTagsAtHead(composite);
                boolean sourceMatches =
                        dependencySource.fingerprint.sha256.equals(
                                evidence.get(
                                        "composite.workspaceSha256"))
                                && dependencySource.commit.equals(
                                evidence.get(
                                        "composite.commit"))
                                && !dependencySource.worktreeDirty
                                && dependencySource
                                .completeWorkspace()
                                && Boolean.parseBoolean(
                                evidence.get(
                                        "composite.releaseInputsCommitted"));
                boolean publishedIdentityMatches =
                        publishedSourceIdentityMatches(
                                declaredDependency,
                                publishedApiInspection,
                                dependencySource.commit,
                                tagsAtHead)
                                && Objects.equals(
                                publishedApiInspection.get(
                                        "source.commit"),
                                evidence.get(
                                        "composite.publishedSourceCommit"))
                                && Objects.equals(
                                publishedApiInspection.get(
                                        "source.tag"),
                                evidence.get(
                                        "composite.publishedSourceTag"))
                                && Boolean.parseBoolean(
                                evidence.get(
                                        "composite.matchesPublishedIdentity"));
                require(
                        failures,
                        sourceMatches,
                        "local-composite-source-state-changed");
                require(
                        failures,
                        publishedIdentityMatches,
                        "local-composite-not-bound-to-published-language-identity");
                compositeSource = map(
                        "path", composite.toString(),
                        "recordedCommit",
                        evidence.get("composite.commit"),
                        "recordedWorkspaceSha256",
                        evidence.get(
                                "composite.workspaceSha256"),
                        "recordedReleaseSourceSha256",
                        evidence.get(
                                "composite.releaseSourceSha256"),
                        "recordedPublishedSourceCommit",
                        evidence.get(
                                "composite.publishedSourceCommit"),
                        "recordedPublishedSourceTag",
                        evidence.get(
                                "composite.publishedSourceTag"),
                        "tagsAtHead", tagsAtHead,
                        "matchesPublishedIdentity",
                        publishedIdentityMatches,
                        "current", dependencySource.report(),
                        "matchesRecordedEvidence", sourceMatches);
            }
        }
        return map(
                "mode", expectedMode,
                "status",
                failures.isEmpty()
                        ? "passed"
                        : "stale-or-failed",
                "evidencePresent", true,
                "evidencePath", evidencePath.toString(),
                "declaredCoordinate",
                evidence.get("declared.coordinate"),
                "effectiveCoordinate",
                evidence.get(
                        "dependency.effectiveCoordinate"),
                "tests", map(
                        "executed",
                        parseLong(evidence.get("tests.executed")),
                        "passed",
                        parseLong(evidence.get("tests.passed")),
                        "failed", 0,
                        "skipped", 0),
                "artifacts", artifactReports,
                "dependencyArtifact", dependencyArtifact,
                "provenance", map(
                        "status",
                        evidence.get(
                                "dependency.provenance.status"),
                        "repositoryPolicy",
                        evidence.get(
                                "dependency.provenance.repositoryPolicy"),
                        "recordedRepository",
                        evidence.get(
                                "dependency.provenance.recordedRepository"),
                        "recordedSha256",
                        evidence.get(
                                "dependency.provenance.recordedSha256")),
                "cleanDependencyCacheAcceptance", map(
                        "status",
                        evidence.get(
                                "dependency.cache.acceptance"),
                        "freshProofRequired",
                        Boolean.parseBoolean(evidence.get(
                                "dependency.cache.freshProofRequired")),
                        "scope",
                        evidence.get(
                                "dependency.cache.acceptanceScope"),
                        "moduleVersionPath",
                        evidence.get(
                                "dependency.cache.blueLanguageModuleVersionPath"),
                        "moduleVersionInitiallyAbsentAtProjectConfiguration",
                        Boolean.parseBoolean(evidence.get(
                                "dependency.cache.blueLanguageModuleVersionInitiallyAbsent")),
                        "reason",
                        "passed".equals(evidence.get(
                                "dependency.cache.acceptance"))
                                ? "validated-mode-run-recorded-exact-module-version-cache-absence"
                                : "validated-exact-module-version-cache-absence-not-recorded"),
                "compositeSource", compositeSource,
                "failures", failures);
    }

    static boolean publishedSourceIdentityMatches(
            String declaredDependency,
            Map<String, String> publishedApiInspection,
            String sourceCommit,
            Collection<String> tagsAtHead) {
        String publishedCoordinate =
                publishedApiInspection.get("coordinate");
        String publishedCommit =
                publishedApiInspection.get("source.commit");
        String publishedTag =
                publishedApiInspection.get("source.tag");
        int firstSeparator = declaredDependency.indexOf(':');
        int lastSeparator = declaredDependency.lastIndexOf(':');
        boolean coordinateShapeValid =
                firstSeparator > 0
                        && lastSeparator > firstSeparator + 1
                        && lastSeparator
                        == declaredDependency.indexOf(
                        ':', firstSeparator + 1)
                        && lastSeparator + 1
                        < declaredDependency.length();
        String version = coordinateShapeValid
                && lastSeparator + 1 < declaredDependency.length()
                ? declaredDependency.substring(lastSeparator + 1)
                : "";
        return coordinateShapeValid
                && declaredDependency.equals(publishedCoordinate)
                && publishedCommit != null
                && publishedCommit.matches("[0-9a-fA-F]{40}")
                && publishedCommit.equalsIgnoreCase(sourceCommit)
                && publishedTag != null
                && publishedTag.equals("v" + version)
                && tagsAtHead != null
                && tagsAtHead.contains(publishedTag);
    }

    private static boolean localModeMatchesPublishedIdentity(
            Map<String, Object> buildModes) {
        Map<String, Object> local =
                castMap(buildModes.get("localComposite"));
        Map<String, Object> compositeSource =
                castMap(local.get("compositeSource"));
        return "passed".equals(local.get("status"))
                && Boolean.TRUE.equals(
                compositeSource.get(
                        "matchesPublishedIdentity"));
    }

    static boolean bindLanguageReleaseIdentityToModes(
            Map<String, Object> identity,
            Map<String, Object> buildModes) {
        boolean localMatches =
                localModeMatchesPublishedIdentity(buildModes);
        boolean currentDependencyExact =
                Boolean.TRUE.equals(
                        identity.get(
                                "currentDependencyExactFinalArtifactProven"));
        boolean exact = currentDependencyExact && localMatches;
        identity.put(
                "localCompositeMatchesPublishedCommit",
                localMatches);
        identity.put(
                "localCompositeMatchesPublishedIdentity",
                localMatches);
        identity.put(
                "validatedLocalCompositeModeMatchesPublishedIdentity",
                localMatches);
        identity.put(
                "validatedLocalCompositeModeFailure",
                localMatches
                        ? null
                        : "validated-local-composite-mode-not-bound-to-published-language-identity");
        Set<String> failures = new LinkedHashSet<String>();
        Object existingFailures = identity.get("failures");
        if (existingFailures instanceof Collection<?>) {
            for (Object failure
                    : (Collection<?>) existingFailures) {
                if (failure != null) {
                    failures.add(String.valueOf(failure));
                }
            }
        }
        if (!localMatches) {
            failures.add(
                    "validated-local-composite-mode-not-bound-to-published-language-identity");
        }
        identity.put(
                "failures",
                new ArrayList<String>(failures));
        identity.put("exactFinalArtifactProven", exact);
        return exact;
    }

    private static List<String> gitTagsAtHead(Path repository)
            throws Exception {
        String output = git(
                repository,
                "tag",
                "--points-at",
                "HEAD");
        List<String> tags = new ArrayList<String>();
        for (String line : output.split("\\R")) {
            String tag = line.trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        Collections.sort(tags);
        return Collections.unmodifiableList(tags);
    }

    private static Map<String, Object> cleanDependencyCacheAcceptance(
            Map<String, Object> buildModes) {
        Map<String, Object> standalone = castMap(
                buildModes.get("standalonePublished"));
        Map<String, Object> acceptance = castMap(
                standalone.get(
                        "cleanDependencyCacheAcceptance"));
        if (acceptance.isEmpty()) {
            return map(
                    "status", "not-executed",
                    "scope",
                    "standalone-published-blue-language-module-version-cache",
                    "reason",
                    "no-blue-language-module-version-cache-evidence");
        }
        return acceptance;
    }

    private static Map<String, String> artifactHashes(
            Map<String, Object> mode) {
        Map<String, Object> artifacts =
                castMap(mode.get("artifacts"));
        Map<String, String> result =
                new LinkedHashMap<String, String>();
        for (String kind
                : new String[] {
                        "main", "sources", "javadoc", "sourceRelease"
                }) {
            result.put(
                    kind,
                    String.valueOf(
                            castMap(artifacts.get(kind))
                                    .get("sha256")));
        }
        return result;
    }

    private static Map<String, Object> checkAbsoluteFile(
            String rawPath,
            String expectedHash) throws IOException {
        Path path = pathOrNull(rawPath);
        boolean present =
                path != null && Files.isRegularFile(path);
        String actual = present
                ? sha256(path)
                : "unavailable";
        return map(
                "path", rawPath,
                "present", present,
                "bytes", present ? Files.size(path) : 0L,
                "sha256", actual,
                "matchesRecordedEvidence",
                present && actual.equals(expectedHash));
    }

    private static Path pathOrNull(String rawPath) {
        if (rawPath == null
                || rawPath.isEmpty()
                || "null".equals(rawPath)) {
            return null;
        }
        try {
            return Paths.get(rawPath)
                    .toAbsolutePath()
                    .normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException invalid) {
            return -1L;
        }
    }

    private static List<String> missingPublishedHostApis(
            Map<String, String> inspection) {
        List<String> missing = new ArrayList<String>();
        for (Map.Entry<String, String> entry
                : inspection.entrySet()) {
            if (("class".equals(prefix(entry.getKey()))
                    || "method".equals(prefix(entry.getKey())))
                    && "false".equals(entry.getValue())) {
                missing.add(entry.getKey());
            }
        }
        Collections.sort(missing);
        return missing;
    }

    private static String prefix(String value) {
        int separator = value.indexOf('.');
        return separator < 0
                ? value
                : value.substring(0, separator);
    }

    private static Map<String, Object> hostedLocalLimitCapability(
            TestEvidence tests) {
        List<String> observedOpenLedgerSignatures =
                new ArrayList<String>();
        List<String> observedOpenSharedBudgetSignatures =
                new ArrayList<String>();
        boolean openLedgerAcceptsSharedBudget = false;
        for (Method method : RuntimeWorkSession.class.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"openLedger".equals(method.getName())
                    && !"openSharedBudget".equals(
                    method.getName())) {
                continue;
            }
            StringBuilder signature = new StringBuilder(
                    method.getName()).append('(');
            for (int index = 0; index < parameters.length; index++) {
                if (index > 0) {
                    signature.append(',');
                }
                signature.append(parameters[index].getName());
            }
            signature.append(')');
            if ("openLedger".equals(method.getName())) {
                observedOpenLedgerSignatures.add(
                        signature.toString());
            } else {
                observedOpenSharedBudgetSignatures.add(
                        signature.toString());
            }
            if ("openLedger".equals(method.getName())
                    && parameters.length == 3
                    && String.class.equals(parameters[0])
                    && Map.class.isAssignableFrom(parameters[1])
                    && RuntimeWorkBudget.class.equals(
                    parameters[2])) {
                openLedgerAcceptsSharedBudget = true;
            }
        }
        Collections.sort(observedOpenLedgerSignatures);
        Collections.sort(observedOpenSharedBudgetSignatures);

        boolean invocationOwnedSharedCappedBudgetScope = false;
        for (Method method : RuntimeWorkSession.class.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("openSharedBudget".equals(method.getName())
                    && parameters.length == 1
                    && Long.TYPE.equals(parameters[0])
                    && RuntimeWorkBudget.class.equals(
                    method.getReturnType())) {
                invocationOwnedSharedCappedBudgetScope = true;
            }
        }
        Map<String, Object> sharedBudgetEvidence =
                tests.namedEvidence(
                        "hostedLocalLimitIsSharedAcrossBexAndIntrinsicLedgers");
        boolean sharedBudgetTestPassed =
                "passed".equals(
                        sharedBudgetEvidence.get("status"));
        boolean capabilityAvailable =
                openLedgerAcceptsSharedBudget
                        && invocationOwnedSharedCappedBudgetScope
                        && sharedBudgetTestPassed;
        return map(
                "schema",
                "blue-bex-hosted-local-limit-capability/1.0",
                "status",
                capabilityAvailable ? "passed" : "blocking",
                "affectedRequirement",
                "workstream-2-property-1",
                "observedRuntimeWorkSessionOpenLedgerSignatures",
                observedOpenLedgerSignatures,
                "observedRuntimeWorkSessionOpenSharedBudgetSignatures",
                observedOpenSharedBudgetSignatures,
                "runtimeWorkSessionOpenLedgerAcceptsMaximumBudget",
                false,
                "runtimeWorkSessionOpenLedgerAcceptsSharedBudget",
                openLedgerAcceptsSharedBudget,
                "invocationOwnedSharedCappedBudgetScope",
                invocationOwnedSharedCappedBudgetScope,
                "sharedHostBudgetActive",
                capabilityAvailable,
                "bexWrapperPrecheckOccursBeforeWork",
                false,
                "canonicalHostPrecheckOccursBeforeWork",
                sharedBudgetTestPassed,
                "bexPhysicalLedgerAndIntrinsicLedgerShareLocalCap",
                sharedBudgetTestPassed,
                "canonicalSessionRecordedLocalRejection",
                sharedBudgetTestPassed,
                "sharedBudgetHostedExecutionEvidence",
                sharedBudgetEvidence,
                "assessment",
                capabilityAvailable
                        ? "RuntimeWorkSession supplies an invocation-owned "
                        + "shared budget, BEX attaches both its portable and "
                        + "intrinsic physical ledgers, and the focused hosted "
                        + "execution proves that rejection is session-recorded "
                        + "before intrinsic work."
                        : "The shared-budget API shape or its focused hosted "
                        + "execution evidence is incomplete.");
    }

    private static Map<String, Object>
    cyclicProofUnavailabilityCapability(TestEvidence tests) {
        String returnType = "missing";
        boolean typedOutcome = false;
        for (Method method : CyclicAwareNodeProvider.class.getMethods()) {
            if (!"cyclicSetProofFor".equals(method.getName())
                    || method.getParameterTypes().length != 1
                    || !String.class.equals(
                    method.getParameterTypes()[0])) {
                continue;
            }
            returnType = method.getReturnType().getName();
            typedOutcome =
                    CyclicSetProofResult.class.equals(
                            method.getReturnType());
        }

        Map<String, Object> contentFetchEvidence =
                tests.namedEvidence(
                        "cyclicMemberContentFetchUnavailabilityStopsBeforeProofQuery");
        Map<String, Object> nullProofEvidence =
                tests.namedEvidence(
                        "nullCyclicProofAfterFoundContentIsInvalidNotUnavailable");
        Map<String, Object> proofUnavailableEvidence =
                tests.namedEvidence(
                        "cyclicProofUnavailabilityAfterFoundContentRemainsTransient");
        Map<String, Object> hostedUnavailableEvidence =
                tests.namedEvidence(
                        "hostedCyclicProofUnavailabilityUsesSessionDiscardLifecycle");
        boolean proofLayerTransientUnavailableExpressible =
                typedOutcome
                        && "passed".equals(
                        proofUnavailableEvidence.get("status"));
        boolean hostedUnavailableLifecyclePassed =
                "passed".equals(
                        hostedUnavailableEvidence.get("status"));
        boolean capabilityAvailable = typedOutcome
                && proofLayerTransientUnavailableExpressible
                && hostedUnavailableLifecyclePassed;

        return map(
                "schema",
                "blue-bex-cyclic-proof-unavailability-capability/1.0",
                "status",
                capabilityAvailable ? "passed" : "blocking",
                "affectedRequirement",
                "transient-cyclic-set-proof-unavailability",
                "cyclicSetProofForReturnType",
                returnType,
                "typedProofOutcome",
                typedOutcome,
                "proofLayerTransientUnavailableExpressible",
                proofLayerTransientUnavailableExpressible,
                "nullProofAfterFoundClassification",
                "invalid-evidence",
                "contentFetchUnavailableBeforeProofQueryEvidence",
                contentFetchEvidence,
                "nullProofAfterFoundEvidence",
                nullProofEvidence,
                "proofLayerUnavailableEvidence",
                proofUnavailableEvidence,
                "contentFetchEvidenceIsProofLayerEvidence",
                false,
                "directInvalidProofCoverage",
                "passed".equals(nullProofEvidence.get("status"))
                        ? "passed"
                        : nullProofEvidence.get("status"),
                "hostedUnavailableProofLifecyclePathAvailable",
                hostedUnavailableLifecyclePassed,
                "hostedUnavailableProofStructuralReadCoverage",
                hostedUnavailableEvidence.get("status"),
                "hostedUnavailableProofStructuralReadEvidence",
                hostedUnavailableEvidence,
                "assessment",
                capabilityAvailable
                        ? "CyclicSetProofResult distinguishes FOUND, "
                        + "NOT_FOUND, UNAVAILABLE, and INVALID_EVIDENCE. "
                        + "Focused structural-read evidence proves that "
                        + "proof-layer UNAVAILABLE remains transient and "
                        + "uses the hosted discard lifecycle."
                        : "The typed cyclic-proof outcome or focused "
                        + "UNAVAILABLE lifecycle evidence is incomplete.");
    }

    private static List<Object> knownLimitations(
            Map<String, Object> buildModes,
            Map<String, String> publishedApiInspection,
            Map<String, Object> hostedLocalLimitCapability,
            Map<String, Object> cyclicProofUnavailabilityCapability,
            Map<String, Object> hostLongTrace,
            Map<String, Object> languageReleaseIdentity,
            SourceState sourceState) {
        List<Object> limitations = new ArrayList<Object>();
        if (!"passed".equals(
                hostedLocalLimitCapability.get("status"))) {
            limitations.add(map(
                    "id",
                    "current-host-shared-local-limit-capability",
                    "scope",
                    "hosted-bex-local-limit",
                    "status",
                    "blocking",
                    "affectedRequirement",
                    hostedLocalLimitCapability.get(
                            "affectedRequirement"),
                    "capability",
                    hostedLocalLimitCapability,
                    "resolution",
                    "Add an invocation-owned capped shared budget scope "
                            + "or an openLedger maximum-budget capability "
                            + "to RuntimeWorkSession so BEX and intrinsic "
                            + "physical ledgers share the BEX-local cap and "
                            + "local rejection is session-recorded."));
        }
        if (!"passed".equals(
                cyclicProofUnavailabilityCapability.get("status"))) {
            limitations.add(map(
                    "id",
                    "current-host-cyclic-proof-unavailability-capability",
                    "scope",
                    "cyclic-set-proof-evidence",
                    "status",
                    "blocking",
                    "affectedRequirement",
                    cyclicProofUnavailabilityCapability.get(
                            "affectedRequirement"),
                    "capability",
                    cyclicProofUnavailabilityCapability,
                    "resolution",
                    "Replace the proof-or-null contract with a typed cyclic "
                            + "proof outcome that distinguishes FOUND, "
                            + "NOT_FOUND, UNAVAILABLE, and INVALID_EVIDENCE, "
                            + "and preserve UNAVAILABLE through provider "
                            + "verification."));
        }
        if (!"passed".equals(hostLongTrace.get("status"))) {
            limitations.add(map(
                    "id", "runtime-work-session-long-trace",
                    "scope", "hosted-ordered-gas-trace",
                    "status", "blocking",
                    "requiredMinimumOrderedTraceEntries",
                    hostLongTrace.get(
                            "requiredMinimumOrderedTraceEntries"),
                    "maximumObservedOrderedTraceEntries",
                    hostLongTrace.get(
                            "maximumObservedOrderedTraceEntries"),
                    "observedPortableLimit",
                    hostLongTrace.get("portableLimit"),
                    "resolution",
                    "Correct RuntimeWorkSession so the counter-kind "
                            + "catalog limit does not cap ordered "
                            + "counter occurrences, then rerun the "
                            + "hosted BEX probe."));
        }
        if (!Boolean.TRUE.equals(
                languageReleaseIdentity.get(
                        "exactFinalArtifactProven"))) {
            limitations.add(map(
                    "id", "exact-final-language-artifact",
                    "scope", "published-host-dependency",
                    "status", "blocking",
                    "identity", languageReleaseIdentity,
                    "resolution",
                    "Commit and publish the final Language kernel, "
                            + "record its exact coordinate, commit, and "
                            + "artifact hash, then rerun both modes."));
        }
        if (!sourceState.uncommittedReleasePaths.isEmpty()) {
            limitations.add(map(
                    "id", "uncommitted-bex-release-inputs",
                    "scope", "bex-source-identity",
                    "status", "blocking",
                    "paths",
                    sourceState.uncommittedReleasePaths,
                    "resolution",
                    "Commit every BEX release input and rerun all "
                            + "release gates from that exact commit."));
        }
        Map<String, Object> standalone = castMap(
                buildModes.get("standalonePublished"));
        if (!"passed".equals(standalone.get("status"))
                && "incompatible-with-current-hosted-adapter".equals(
                publishedApiInspection.get("status"))) {
            limitations.add(map(
                    "id", "upstream-published-host-api",
                    "scope", "standalone-published-build",
                    "status", "blocking",
                    "coordinate",
                    publishedApiInspection.get("coordinate"),
                    "artifactSha256",
                    publishedApiInspection.get(
                            "artifact.sha256"),
                    "missingSymbols",
                    missingPublishedHostApis(
                            publishedApiInspection),
                    "resolution",
                    "Publish the current generic runtime-work-session "
                            + "and semantic-output-boundary APIs from "
                            + "blue-language-java, then update the "
                            + "declared coordinate and rerun both modes."));
        }
        return limitations;
    }

    private static String releaseIdentity(
            String projectVersion,
            SourceState sourceState,
            String declaredDependency,
            Map<String, Object> dependencyResolution,
            Map<String, Object> specification,
            List<Object> artifacts) {
        StringBuilder exactTuple = new StringBuilder();
        exactTuple.append("blue.bex:blue-bex-java:")
                .append(projectVersion).append('\n');
        exactTuple.append(sourceState.commit).append('\n');
        exactTuple.append(
                sourceState.releaseFingerprint.sha256)
                .append('\n');
        exactTuple.append(declaredDependency).append('\n');
        exactTuple.append(
                dependencyResolution.get(
                        "effectiveCoordinate")).append('\n');
        exactTuple.append(
                castMap(dependencyResolution.get("artifact"))
                        .get("sha256")).append('\n');
        exactTuple.append(specification.get("sha256"))
                .append('\n');
        Map<String, Object> identity = identities();
        exactTuple.append(identity.get("bexRegistry"))
                .append('\n');
        exactTuple.append(identity.get("gasManifest"))
                .append('\n');
        exactTuple.append(identity.get("fixturePackage"))
                .append('\n');
        for (Object value : artifacts) {
            Map<String, Object> artifact = castMap(value);
            exactTuple.append(artifact.get("path"))
                    .append('=')
                    .append(artifact.get("sha256"))
                    .append('\n');
        }
        return "sha256:" + ConformancePackage.sha256(
                exactTuple.toString()
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String markdownReport(
            Map<String, Object> report,
            Map<String, String> baseline,
            TestEvidence tests,
            Map<String, Object> operatorCoverage,
            Map<String, Object> counterCoverage,
            Map<String, Object> buildModes,
            List<String> currentModeFailures,
            List<Object> artifacts,
            Map<String, Object> releaseGates,
            Map<String, String> publishedApiInspection) {
        StringBuilder output = new StringBuilder();
        output.append("# Blue BEX 2.0 Hosted Release Evidence\n\n");
        output.append("- Release ready: `")
                .append(report.get("releaseReady"))
                .append("`\n");
        output.append("- Commit: `")
                .append(report.get("commit"))
                .append("`\n");
        output.append("- Release coordinate: `")
                .append(report.get("releaseCoordinate"))
                .append("`\n");
        output.append("- Release identity: `")
                .append(report.get("releaseIdentity"))
                .append("`\n\n");

        output.append("## Baseline and final totals\n\n");
        output.append("| Evidence | Baseline | Final |\n");
        output.append("|---|---:|---:|\n");
        output.append("| Tests executed | ")
                .append(baseline.get("tests.executed"))
                .append(" | ").append(tests.cases.size())
                .append(" |\n");
        output.append("| Tests passed | ")
                .append(baseline.get("tests.passed"))
                .append(" | ")
                .append(tests.count("passed"))
                .append(" |\n");
        output.append("| Tests failed | ")
                .append(baseline.get("tests.failed"))
                .append(" | ")
                .append(tests.count("failed"))
                .append(" |\n");
        output.append("| Tests skipped | ")
                .append(baseline.get("tests.skipped"))
                .append(" | ")
                .append(tests.count("skipped"))
                .append(" |\n");
        output.append("| Behavior fixtures | ")
                .append(baseline.get("behaviorFixtures"))
                .append(" | ")
                .append(passingBehaviorFixtures(tests))
                .append(" |\n");
        output.append("| Gas microfixtures | ")
                .append(baseline.get("gasMicrofixtures"))
                .append(" | ")
                .append(counterCoverage.get(
                        "passingMicrofixtureCount"))
                .append(" |\n");
        Map<String, Object> finalTotals =
                castMap(report.get("finalTotals"));
        Map<String, Object> vectorTotals = castMap(
                finalTotals.get("normativeVectors"));
        output.append("| Normative vectors | ")
                .append(baseline.get("normativeVectors"))
                .append(" | ")
                .append(vectorTotals.get(
                        "executedAndPassing"))
                .append(" |\n");
        output.append("| Direct operator coverage | ")
                .append(baseline.get("operators"))
                .append(" | ")
                .append(operatorCoverage.get(
                        "passingOperatorCount"))
                .append(" |\n\n");

        output.append("## Dependency modes\n\n");
        output.append("| Mode | Status | Effective dependency |\n");
        output.append("|---|---|---|\n");
        appendModeRow(
                output,
                "Standalone published",
                castMap(buildModes.get(
                        "standalonePublished")));
        appendModeRow(
                output,
                "Local composite",
                castMap(buildModes.get("localComposite")));
        output.append("\nDeclared dependency: `")
                .append(castMap(report.get("dependency"))
                        .get("declaredCoordinate"))
                .append("`.\n\n");

        output.append("## Named hosted evidence\n\n");
        appendNamedEvidence(
                output,
                "Semantic boundary invocation counts",
                castMap(report.get(
                        "semanticBoundaryInvocationEvidence")));
        appendNamedEvidence(
                output,
                "Representation and provider invariance",
                castMap(report.get(
                        "representationInvarianceEvidence")));
        appendNamedEvidence(
                output,
                "Child-ledger lifecycle",
                castMap(report.get(
                        "ledgerLifecycleEvidence")));
        appendNamedEvidence(
                output,
                "Gas exhaustion and no work after rejection",
                castMap(report.get(
                        "gasExhaustionEvidence")));
        appendNamedEvidence(
                output,
                "Concrete gas-exhaustion trace examples",
                castMap(report.get(
                        "gasExhaustionTraceExamples")));
        appendNamedEvidence(
                output,
                "Cyclic identity, direct proof validation, and content fetch",
                castMap(report.get(
                        "cyclicProofEvidence")));
        appendNamedEvidence(
                output,
                "Registered intrinsics",
                castMap(report.get("intrinsicEvidence")));
        appendNamedEvidence(
                output,
                "Reference evidence failure classification",
                castMap(report.get(
                        "referenceEvidenceClassificationEvidence")));
        Map<String, Object> gasTraceExamples =
                castMap(report.get(
                        "gasExhaustionTraceExamples"));
        output.append(
                "\n### Concrete gas-exhaustion trace examples\n\n");
        output.append(
                "| Example | Status | Namespace | Rejected counter "
                        + "| Quantity | Weight | Admitted gas "
                        + "| Effective budget | Rejected charge present "
                        + "| No later work |\n");
        output.append(
                "|---|---|---|---|---:|---:|---:|---:|---|---|\n");
        for (Object value : ConformancePackage.list(
                gasTraceExamples.get("examples"),
                "gasExhaustionTraceExamples.examples")) {
            Map<String, Object> example =
                    castMap(value);
            output.append("| `")
                    .append(example.get("id"))
                    .append("` | ")
                    .append(example.get("status"))
                    .append(" | `")
                    .append(example.get("namespace"))
                    .append("` | `")
                    .append(example.get("counterName"))
                    .append("` | ")
                    .append(example.get("quantity"))
                    .append(" | ")
                    .append(example.get("weight"))
                    .append(" | ")
                    .append(example.get("admittedGas"))
                    .append(" | ")
                    .append(example.get("effectiveBudget"))
                    .append(" | ")
                    .append(example.get(
                            "rejectedChargePresent"))
                    .append(" | ")
                    .append(example.get("noLaterWork"))
                    .append(" |\n");
        }
        Map<String, Object> gasTraceSource =
                castMap(gasTraceExamples.get(
                        "sourceControlledEvidence"));
        output.append(
                "\nThe admitted prefix for every example is exactly one "
                        + "`bex/expressionEvaluated` charge with quantity "
                        + "`1`, weight `1`, and total gas `1`. Expected "
                        + "values are source-controlled in `")
                .append(gasTraceSource.get("path"))
                .append("` (`")
                .append(gasTraceSource.get("sha256"))
                .append("`) and are reported as passing only when the "
                        + "exact dynamic JUnit selector passed.\n");
        output.append("\nRecursion fixture `bex-c-09`: `")
                .append(castMap(
                        report.get("recursionEvidence"))
                        .get("status"))
                .append("`. Finite gas-exhaustion fixture "
                        + "`bex-g-15`: `")
                .append(castMap(
                        report.get("finiteLoopEvidence"))
                        .get("status"))
                .append("`.\n\n");

        output.append("## Release gates\n\n");
        output.append("| Gate | Status |\n");
        output.append("|---|---|\n");
        output.append(
                "| Archive byte determinism "
                        + "(same compiled/source inputs; fresh Javadoc) | ")
                .append(castMap(releaseGates.get(
                        "deterministicArchives"))
                        .get("status"))
                .append(" |\n");
        output.append(
                "| Two independent clean builds "
                        + "(all four release artifacts) | ")
                .append(castMap(releaseGates.get(
                        "independentCleanBuilds"))
                        .get("status"))
                .append(" |\n");
        output.append("| Binary API | ")
                .append(castMap(releaseGates.get(
                        "binaryApi")).get("status"))
                .append(" |\n");
        output.append("| Java 8 benchmark compilation | ")
                .append(castMap(releaseGates.get(
                        "benchmarkCompilation"))
                        .get("status"))
                .append(" |\n");
        output.append("| Packaged Java 8 bytecode | ")
                .append(castMap(releaseGates.get(
                        "java8Bytecode"))
                        .get("status"))
                .append(" |\n");
        output.append(
                "| Blue Language module-version cache acceptance | ")
                .append(castMap(releaseGates.get(
                        "cleanDependencyCacheAcceptance"))
                        .get("status"))
                .append(" |\n\n");

        output.append("## Artifact identities\n\n");
        for (Object value : artifacts) {
            Map<String, Object> artifact = castMap(value);
            output.append("- `")
                    .append(artifact.get("path"))
                    .append("`: `")
                    .append(artifact.get("sha256"))
                    .append("`\n");
        }
        output.append("\nSpecification SHA-256: `")
                .append(castMap(report.get("specification"))
                        .get("sha256"))
                .append("`. `.cz.toml` matches the project version: `")
                .append(castMap(report.get(
                        "versionAutomation")).get(
                                "matchesProjectVersion"))
                .append("`.\n\n");

        Map<String, Object> hostedLocalLimit =
                castMap(report.get(
                        "hostedLocalLimitCapability"));
        output.append("## Hosted local-limit capability\n\n");
        output.append("- Status: `")
                .append(hostedLocalLimit.get("status"))
                .append("`\n");
        output.append("- `RuntimeWorkSession.openLedger` accepts a "
                        + "shared budget: `")
                .append(hostedLocalLimit.get(
                        "runtimeWorkSessionOpenLedgerAcceptsSharedBudget"))
                .append("`\n");
        output.append("- Invocation-owned shared capped scope: `")
                .append(hostedLocalLimit.get(
                        "invocationOwnedSharedCappedBudgetScope"))
                .append("`\n");
        output.append("- Duplicate BEX wrapper precheck active: `")
                .append(hostedLocalLimit.get(
                        "bexWrapperPrecheckOccursBeforeWork"))
                .append("`\n");
        output.append("- Canonical host precheck before work: `")
                .append(hostedLocalLimit.get(
                        "canonicalHostPrecheckOccursBeforeWork"))
                .append("`\n");
        output.append("- BEX and intrinsic physical ledgers share the "
                        + "BEX-local cap: `")
                .append(hostedLocalLimit.get(
                        "bexPhysicalLedgerAndIntrinsicLedgerShareLocalCap"))
                .append("`\n");
        output.append("- Local rejection is session-recorded: `")
                .append(hostedLocalLimit.get(
                        "canonicalSessionRecordedLocalRejection"))
                .append("`\n\n");

        Map<String, Object> cyclicProofUnavailability =
                castMap(report.get(
                        "cyclicProofUnavailabilityCapability"));
        output.append(
                "## Cyclic proof-unavailability capability\n\n");
        output.append("- Status: `")
                .append(cyclicProofUnavailability.get("status"))
                .append("`\n");
        output.append("- `cyclicSetProofFor` return type: `")
                .append(cyclicProofUnavailability.get(
                        "cyclicSetProofForReturnType"))
                .append("`\n");
        output.append("- Typed proof outcome: `")
                .append(cyclicProofUnavailability.get(
                        "typedProofOutcome"))
                .append("`\n");
        output.append(
                "- Proof-layer transient unavailable is expressible: `")
                .append(cyclicProofUnavailability.get(
                        "proofLayerTransientUnavailableExpressible"))
                .append("`\n");
        output.append("- Null proof after found content is classified as: `")
                .append(cyclicProofUnavailability.get(
                        "nullProofAfterFoundClassification"))
                .append("`\n");
        output.append("- Direct invalid-proof coverage: `")
                .append(cyclicProofUnavailability.get(
                        "directInvalidProofCoverage"))
                .append("`\n");
        output.append("- Hosted unavailable-proof lifecycle path: `")
                .append(cyclicProofUnavailability.get(
                        "hostedUnavailableProofLifecyclePathAvailable"))
                .append("`\n");
        output.append(
                "- Hosted unavailable-proof structural-read coverage: `")
                .append(cyclicProofUnavailability.get(
                        "hostedUnavailableProofStructuralReadCoverage"))
                .append("`\n");
        output.append(
                "- Content-fetch unavailable before proof query: `")
                .append(castMap(cyclicProofUnavailability.get(
                        "contentFetchUnavailableBeforeProofQueryEvidence"))
                        .get("status"))
                .append("` (this is not proof-layer unavailable "
                        + "coverage).\n\n");

        output.append("## Known limitations\n\n");
        boolean localLimitBlocked =
                !"passed".equals(
                        hostedLocalLimit.get("status"));
        boolean cyclicProofUnavailableBlocked =
                !"passed".equals(
                        cyclicProofUnavailability.get("status"));
        Map<String, Object> standalonePublished =
                castMap(buildModes.get("standalonePublished"));
        boolean publishedApiBlocked =
                !"passed".equals(
                        standalonePublished.get("status"))
                        && "incompatible-with-current-hosted-adapter".equals(
                        publishedApiInspection.get("status"));
        if (!localLimitBlocked
                && !cyclicProofUnavailableBlocked
                && !publishedApiBlocked) {
            output.append("None recorded.\n");
        }
        if (localLimitBlocked) {
            output.append(
                    String.valueOf(
                            hostedLocalLimit.get("assessment")))
                    .append("\n");
        }
        if (cyclicProofUnavailableBlocked) {
            output.append("\n")
                    .append(String.valueOf(
                            cyclicProofUnavailability.get(
                                    "assessment")))
                    .append("\n");
        }
        if (publishedApiBlocked) {
            output.append(
                    "\nThe published Blue Language artifact `")
                    .append(publishedApiInspection.get(
                            "coordinate"))
                    .append("` (`")
                    .append(publishedApiInspection.get(
                            "artifact.sha256"))
                    .append("`) does not contain the current generic "
                            + "hosted runtime APIs. Missing JAR symbols:\n\n");
            for (String symbol : missingPublishedHostApis(
                    publishedApiInspection)) {
                output.append("- `")
                        .append(symbol)
                        .append("`\n");
            }
            output.append(
                    "\nThis is an independent upstream publication "
                            + "blocker to standalone release evidence. "
                            + "The final release task remains fail-closed.\n");
        }
        if (!currentModeFailures.isEmpty()) {
            output.append(
                    "\nCurrent-mode evidence failures:\n\n");
            for (String failure : currentModeFailures) {
                output.append("- `")
                        .append(failure)
                        .append("`\n");
            }
        }
        return output.toString();
    }

    private static int passingBehaviorFixtures(
            TestEvidence tests) {
        int result = 0;
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            if ("passed".equals(
                    tests.fixtureStatus(fixture.id()))) {
                result++;
            }
        }
        return result;
    }

    private static void appendModeRow(
            StringBuilder output,
            String label,
            Map<String, Object> mode) {
        output.append("| ").append(label)
                .append(" | ")
                .append(mode.get("status"))
                .append(" | ")
                .append(mode.get("effectiveCoordinate"))
                .append(" |\n");
    }

    private static void appendNamedEvidence(
            StringBuilder output,
            String label,
            Map<String, Object> evidence) {
        output.append("- ").append(label)
                .append(": `")
                .append(evidence.get("status"))
                .append("` (")
                .append(evidence.get("matchedTestCount"))
                .append(" named tests)\n");
    }

    private static String readinessReason(
            List<String> currentModeFailures,
            boolean bothModesPassed) {
        List<String> reasons =
                new ArrayList<String>(currentModeFailures);
        if (!bothModesPassed) {
            reasons.add(
                    "standalone-and-local-composite-matrix-incomplete");
        }
        return String.join(";", reasons);
    }

    private static Map<String, Object> evidenceMap(
            Map<String, String> source) {
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        result.putAll(source);
        return result;
    }

    private static Map<String, String> stringMap(
            String... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "stringMap needs key/value pairs");
        }
        Map<String, String> result =
                new LinkedHashMap<String, String>();
        for (int index = 0;
                index < values.length;
                index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static void writeEvidence(
            Path path,
            Map<String, String> values) throws IOException {
        Files.createDirectories(path.getParent());
        List<String> keys =
                new ArrayList<String>(values.keySet());
        Collections.sort(keys);
        StringBuilder output = new StringBuilder();
        for (String key : keys) {
            output.append(key)
                    .append('=')
                    .append(values.get(key))
                    .append('\n');
        }
        Files.write(
                path,
                output.toString().getBytes(
                        StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static Map<String, Object> operatorCoverage(
            TestEvidence tests) {
        Map<String, Object> source = ConformancePackage.loadMap(
                ConformancePackage.FIXTURE_ROOT + "operator-coverage.yaml");
        List<?> declarations = ConformancePackage.list(
                source.get("operators"), "operator-coverage.operators");
        Map<String, String> idsByPath = behaviorIdsByPath();
        List<Object> matrix = new ArrayList<Object>();
        int executed = 0;
        int passed = 0;
        boolean directCoverageComplete = true;
        for (Object value : declarations) {
            Map<String, Object> declaration = ConformancePackage.map(
                    value, "operator coverage entry");
            String operator = String.valueOf(declaration.get("operator"));
            List<?> paths = ConformancePackage.list(
                    declaration.get("fixtures"), operator + ".fixtures");
            directCoverageComplete &= !paths.isEmpty();
            List<Object> fixtures = new ArrayList<Object>();
            boolean anyExecuted = false;
            boolean anyPassed = false;
            for (Object pathValue : paths) {
                String path = String.valueOf(pathValue);
                String fixtureId = idsByPath.get(path);
                String status = fixtureId != null
                        ? tests.fixtureStatus(fixtureId)
                        : "invalid-reference";
                anyExecuted |= !"not-executed".equals(status);
                anyPassed |= "passed".equals(status);
                fixtures.add(map(
                        "path", path,
                        "fixtureId", fixtureId,
                        "status", status));
            }
            if (anyExecuted) {
                executed++;
            }
            if (anyPassed) {
                passed++;
            }
            matrix.add(map(
                    "operator", operator,
                    "fixtures", fixtures));
        }
        return map(
                "declaredOperatorCount", source.get("operatorCount"),
                "matrixEntryCount", declarations.size(),
                "directCoverageComplete", directCoverageComplete,
                "executedOperatorCount", executed,
                "passingOperatorCount", passed,
                "allExecutedOperatorsPassing",
                executed == declarations.size() && passed == declarations.size(),
                "matrix", matrix);
    }

    private static Map<String, Object> counterCoverage(
            TestEvidence tests) {
        Map<String, Object> manifestCounters = ConformancePackage.map(
                ConformancePackage.gasManifest().get("counters"),
                "gas-manifest.counters");
        List<Object> matrix = new ArrayList<Object>();
        Set<String> fixtureCounters = new LinkedHashSet<String>();
        int executed = 0;
        int passed = 0;
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.gasFixtures()) {
            Map<String, Object> direct = ConformancePackage.map(
                    fixture.context().get("directCounterFixture"),
                    fixture.path + ".directCounterFixture");
            String counter = String.valueOf(direct.get("counter"));
            fixtureCounters.add(counter);
            String status = tests.fixtureStatus(fixture.id());
            if (!"not-executed".equals(status)) {
                executed++;
            }
            if ("passed".equals(status)) {
                passed++;
            }
            matrix.add(map(
                    "counter", counter,
                    "weight", manifestCounters.get(counter),
                    "fixtureId", fixture.id(),
                    "path", fixture.path,
                    "status", status));
        }
        return map(
                "declaredCounterCount",
                ConformancePackage.gasManifest().get("counterCount"),
                "microfixtureCount", matrix.size(),
                "vocabularyComplete",
                fixtureCounters.equals(manifestCounters.keySet()),
                "executedMicrofixtureCount", executed,
                "passingMicrofixtureCount", passed,
                "allMicrofixturesPassing",
                passed == ConformancePackage.GAS_FIXTURE_COUNT,
                "matrix", matrix);
    }

    private static List<Object> representationMatrix(
            TestEvidence tests) {
        Set<String> selected = new LinkedHashSet<String>();
        Collections.addAll(
                selected,
                "bex-r-01",
                "bex-r-02",
                "bex-r-08",
                "bex-e-10",
                "bex-h-05",
                "bex-h-06");
        List<Object> matrix = new ArrayList<Object>();
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            if (!selected.contains(fixture.id())) {
                continue;
            }
            Object declared = fixture.expected().get("variants");
            if (declared == null) {
                continue;
            }
            for (Object value : ConformancePackage.list(
                    declared, fixture.path + ".variants")) {
                Map<String, Object> variant =
                        ConformancePackage.map(value, "variant");
                String fixtureStatus =
                        tests.fixtureStatus(fixture.id());
                matrix.add(map(
                        "fixtureId", fixture.id(),
                        "path", fixture.path,
                        "variant", variant,
                        "status",
                        aggregateVariantStatus(fixtureStatus),
                        "evidenceScope", "aggregate-fixture-test",
                        "fixtureStatus", fixtureStatus));
            }
        }
        return matrix;
    }

    private static List<Object> cacheMatrix(TestEvidence tests) {
        List<Object> matrix = new ArrayList<Object>();
        ConformancePackage.Fixture r08 = fixture("bex-r-08");
        for (Object value : ConformancePackage.list(
                r08.expected().get("variants"), r08.path + ".variants")) {
            Map<String, Object> variant =
                    ConformancePackage.map(value, "cache variant");
            String fixtureStatus =
                    tests.fixtureStatus(r08.id());
            matrix.add(map(
                    "kind", "provider-cache",
                    "fixtureId", r08.id(),
                    "cache", variant.get("cache"),
                    "batching", variant.get("batching"),
                    "status",
                    aggregateVariantStatus(fixtureStatus),
                    "evidenceScope", "aggregate-fixture-test",
                    "fixtureStatus", fixtureStatus));
        }
        matrix.add(map(
                "kind", "compiled-program-cache-differential",
                "testClass",
                "blue.bex.conformance.BexConformancePropertyTest",
                "test", "compileCacheHitAndMissHaveIdenticalResultAndGas",
                "status",
                tests.namedStatus(
                        "compileCacheHitAndMissHaveIdenticalResultAndGas")));
        matrix.add(map(
                "kind",
                "fixture-provider-preparation-differential",
                "testClass",
                "blue.bex.conformance.BexConformancePropertyTest",
                "test",
                "fixtureAdapterActuallyExecutesDeclaredBatchingPreparation",
                "status",
                tests.namedStatus(
                        "fixtureAdapterActuallyExecutesDeclaredBatchingPreparation")));
        return matrix;
    }

    /**
     * A passing aggregate fixture proves that its runner reached and checked
     * every declared variant. A failed aggregate fixture does not identify
     * which variant failed or whether later variants ran, so reporting every
     * variant as failed would overclaim the available JUnit evidence.
     */
    static String aggregateVariantStatus(String fixtureStatus) {
        if ("passed".equals(fixtureStatus)) {
            return "passed";
        }
        if ("failed".equals(fixtureStatus)) {
            return "indeterminate-after-fixture-failure";
        }
        return "not-executed";
    }

    private static Map<String, Object> recursionEvidence(
            TestEvidence tests) {
        ConformancePackage.Fixture fixture = fixture("bex-c-09");
        return map(
                "fixtureId", fixture.id(),
                "status", tests.fixtureStatus(fixture.id()),
                "expectedCompileStatus",
                fixture.expected().get("compileStatus"),
                "expectedErrorClass",
                fixture.expected().get("errorClass"),
                "expectedReason", fixture.expected().get("reason"),
                "runtimeMustStart", false);
    }

    private static Map<String, Object> finiteLoopEvidence(
            TestEvidence tests) {
        ConformancePackage.Fixture fixture = fixture("bex-g-15");
        return map(
                "fixtureId", fixture.id(),
                "status", tests.fixtureStatus(fixture.id()),
                "gasLimit", fixture.context().get("gasLimit"),
                "parentRemainingGas",
                fixture.context().get("parentRemainingGas"),
                "expectedErrorClass",
                fixture.expected().get("errorClass"),
                "bufferedEffectsMustCommit", false);
    }

    private static List<Object> artifactEvidence(
            Path projectDir,
            Path buildDir,
            String projectVersion) throws IOException {
        Path libs = buildDir.resolve("libs");
        final String artifactPrefix =
                "blue-bex-java-" + projectVersion;
        List<Path> paths = new ArrayList<Path>();
        if (Files.isDirectory(libs)) {
            try (Stream<Path> stream = Files.list(libs)) {
                paths.addAll(stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name =
                                    path.getFileName().toString();
                            return name.equals(
                                    artifactPrefix + ".jar")
                                    || name.equals(
                                    artifactPrefix
                                            + "-sources.jar")
                                    || name.equals(
                                    artifactPrefix
                                            + "-javadoc.jar");
                        })
                        .collect(Collectors.toList()));
            }
        }
        Path distributions =
                buildDir.resolve("distributions");
        if (Files.isDirectory(distributions)) {
            try (Stream<Path> stream =
                         Files.list(distributions)) {
                paths.addAll(stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                .toString()
                                .equals(
                                        artifactPrefix
                                                + "-source-release.zip"))
                        .collect(Collectors.toList()));
            }
        }
        Collections.sort(
                paths,
                Comparator.comparing(
                        path -> path.getFileName().toString()));
        List<Object> artifacts = new ArrayList<Object>();
        for (Path path : paths) {
            artifacts.add(map(
                    "path", unix(projectDir.relativize(path)),
                    "bytes", Files.size(path),
                    "sha256", ConformancePackage.sha256(
                            Files.readAllBytes(path))));
        }
        return artifacts;
    }

    private static Map<String, Object> releaseGateEvidence(
            Path projectDir,
            Path buildDir,
            Path persistentEvidenceRoot,
            String projectVersion,
            String sourceCommit,
            String dependencyMode,
            String declaredDependency,
            Map<String, Object> dependencyResolution,
            Path compositePath) throws Exception {
        Path releaseRoot = buildDir.resolve("reports")
                .resolve("bex-release");
        return map(
                "deterministicArchives",
                deterministicArchiveEvidence(
                        projectDir,
                        releaseRoot.resolve(
                                "deterministic-archives.properties")),
                "independentCleanBuilds",
                independentCleanBuildEvidence(
                        projectDir,
                        buildDir,
                        persistentEvidenceRoot.resolve(
                                "independent-clean-builds-"
                                        + dependencyMode
                                        + ".properties"),
                        projectVersion,
                        sourceCommit,
                        dependencyMode,
                        declaredDependency,
                        dependencyResolution,
                        compositePath),
                "binaryApi",
                binaryApiEvidence(
                        projectDir,
                        buildDir,
                        releaseRoot.resolve(
                                "binary-api.properties")),
                "benchmarkCompilation",
                benchmarkCompilationEvidence(
                        projectDir,
                        releaseRoot.resolve(
                                "benchmark-compilation.properties")),
                "java8Bytecode",
                java8BytecodeEvidence(
                        projectDir,
                        releaseRoot.resolve(
                                "java8-bytecode.properties")));
    }

    private static Map<String, Object>
            independentCleanBuildEvidence(
                    Path projectDir,
                    Path buildDir,
                    Path evidencePath,
                    String projectVersion,
                    String sourceCommit,
                    String dependencyMode,
                    String declaredDependency,
                    Map<String, Object> dependencyResolution,
                    Path compositePath) throws Exception {
        Map<String, String> evidence =
                readEvidence(evidencePath);
        if (evidence.isEmpty()) {
            return map(
                    "status", "not-executed",
                    "evidencePresent", false,
                    "evidencePath", evidencePath.toString());
        }
        String artifactPrefix =
                "blue-bex-java-" + projectVersion;
        Map<String, String> artifactPaths =
                stringMap(
                        "main",
                        buildDir.resolve("libs")
                                .resolve(artifactPrefix + ".jar")
                                .toString(),
                        "sources",
                        buildDir.resolve("libs")
                                .resolve(
                                        artifactPrefix
                                                + "-sources.jar")
                                .toString(),
                        "javadoc",
                        buildDir.resolve("libs")
                                .resolve(
                                        artifactPrefix
                                                + "-javadoc.jar")
                                .toString(),
                        "sourceRelease",
                        buildDir.resolve("distributions")
                                .resolve(
                                        artifactPrefix
                                                + "-source-release.zip")
                                .toString());
        Map<String, Object> artifacts =
                new LinkedHashMap<String, Object>();
        boolean artifactChecksPassed = true;
        for (Map.Entry<String, String> entry
                : artifactPaths.entrySet()) {
            String name = entry.getKey();
            Path artifactPath =
                    Paths.get(entry.getValue())
                            .toAbsolutePath()
                            .normalize();
            String relativePath =
                    projectDir.relativize(artifactPath)
                            .toString();
            FileCheck artifact = checkFile(
                    projectDir,
                    relativePath,
                    evidence.get(
                            "artifact." + name + ".sha256"));
            boolean byteIdentical =
                    Boolean.parseBoolean(evidence.get(
                            "artifact." + name
                                    + ".byteIdentical"));
            artifactChecksPassed &=
                    artifact.valid && byteIdentical;
            artifacts.put(
                    name,
                    map(
                            "current", artifact.report(),
                            "byteIdenticalAcrossCleanBuilds",
                            byteIdentical));
        }
        Path firstReceipt =
                pathOrNull(evidence.get(
                        "first.evidence.path"));
        Path secondReceipt =
                pathOrNull(evidence.get(
                        "second.evidence.path"));
        String firstReceiptHash =
                evidence.get("first.evidence.sha256");
        String secondReceiptHash =
                evidence.get("second.evidence.sha256");
        boolean receiptsValid =
                firstReceipt != null
                        && secondReceipt != null
                        && !firstReceipt.equals(secondReceipt)
                        && Files.isRegularFile(firstReceipt)
                        && Files.isRegularFile(secondReceipt)
                        && firstReceiptHash != null
                        && firstReceiptHash.matches(
                        "[0-9a-f]{64}")
                        && secondReceiptHash != null
                        && secondReceiptHash.matches(
                        "[0-9a-f]{64}")
                        && !firstReceiptHash.equals(
                        secondReceiptHash)
                        && firstReceiptHash.equals(
                        sha256(firstReceipt))
                        && secondReceiptHash.equals(
                        sha256(secondReceipt));
        Map<String, String> firstReceiptValues =
                firstReceipt != null
                        && Files.isRegularFile(firstReceipt)
                        ? readEvidence(firstReceipt)
                        : Collections.<String, String>emptyMap();
        Map<String, String> secondReceiptValues =
                secondReceipt != null
                        && Files.isRegularFile(secondReceipt)
                        ? readEvidence(secondReceipt)
                        : Collections.<String, String>emptyMap();
        boolean receiptContentsMatch =
                receiptsValid
                        && cleanBuildReceiptMatchesAggregate(
                        evidence,
                        "first",
                        firstReceiptValues,
                        artifactPaths.keySet())
                        && cleanBuildReceiptMatchesAggregate(
                        evidence,
                        "second",
                        secondReceiptValues,
                        artifactPaths.keySet());
        Path firstRoot =
                pathOrNull(evidence.get(
                        "first.checkout.root"));
        Path secondRoot =
                pathOrNull(evidence.get(
                        "second.checkout.root"));
        Path firstGitDirectory =
                pathOrNull(evidence.get(
                        "first.checkout.gitDirectory"));
        Path secondGitDirectory =
                pathOrNull(evidence.get(
                        "second.checkout.gitDirectory"));
        boolean distinctCheckouts = false;
        boolean liveCheckoutStateMatches = false;
        boolean receiptArtifactsMatch = false;
        if (firstRoot != null
                && secondRoot != null
                && firstGitDirectory != null
                && secondGitDirectory != null) {
            try {
                Path firstRealRoot = firstRoot.toRealPath();
                Path secondRealRoot = secondRoot.toRealPath();
                Path firstRealGit =
                        firstGitDirectory.toRealPath();
                Path secondRealGit =
                        secondGitDirectory.toRealPath();
                distinctCheckouts =
                        !firstRealRoot.equals(secondRealRoot)
                                && !firstRealGit.equals(
                                secondRealGit);
                liveCheckoutStateMatches =
                        distinctCheckouts
                                && liveCleanCheckoutMatches(
                                firstRealRoot,
                                firstRealGit,
                                "first",
                                evidence,
                                sourceCommit)
                                && liveCleanCheckoutMatches(
                                secondRealRoot,
                                secondRealGit,
                                "second",
                                evidence,
                                sourceCommit);
                receiptArtifactsMatch =
                        cleanBuildReceiptArtifactsMatch(
                                firstRealRoot,
                                firstReceiptValues,
                                artifactPaths.keySet(),
                                projectVersion)
                                && cleanBuildReceiptArtifactsMatch(
                                secondRealRoot,
                                secondReceiptValues,
                                artifactPaths.keySet(),
                                projectVersion)
                                && cleanBuildReceiptDependencyMatches(
                                firstRealRoot,
                                firstReceiptValues)
                                && cleanBuildReceiptDependencyMatches(
                                secondRealRoot,
                                secondReceiptValues);
            } catch (Exception invalid) {
                distinctCheckouts = false;
                liveCheckoutStateMatches = false;
                receiptArtifactsMatch = false;
            }
        }
        Map<String, Object> resolvedArtifact =
                castMap(dependencyResolution.get(
                        "artifact"));
        String resolvedDependencyHash =
                String.valueOf(
                        resolvedArtifact.get("sha256"));
        String recordedDependencyHash =
                evidence.get(
                        "dependency.artifact.sha256");
        boolean dependencyInputMatches =
                "passed".equals(
                        dependencyResolution.get("status"))
                        && dependencyMode.equals(
                        evidence.get("dependency.mode"))
                        && declaredDependency.equals(
                        evidence.get(
                                "dependency.coordinate"))
                        && Objects.equals(
                        String.valueOf(
                                dependencyResolution.get(
                                        "effectiveCoordinate")),
                        evidence.get(
                                "dependency.effectiveCoordinate"))
                        && recordedDependencyHash != null
                        && recordedDependencyHash.matches(
                        "[0-9a-f]{64}")
                        && recordedDependencyHash.equals(
                        resolvedDependencyHash);
        boolean compositeInputMatches;
        Map<String, Object> currentComposite =
                Collections.emptyMap();
        if ("local-composite".equals(dependencyMode)) {
            if (compositePath == null
                    || !Files.isDirectory(compositePath)) {
                compositeInputMatches = false;
            } else {
                SourceState compositeState =
                        sourceState(compositePath);
                Path recordedCompositePath =
                        pathOrNull(evidence.get(
                                "composite.path"));
                compositeInputMatches =
                        recordedCompositePath != null
                                && compositePath.equals(
                                recordedCompositePath)
                                && !compositeState.worktreeDirty
                                && compositeState.completeWorkspace()
                                && compositeState.commit.equals(
                                evidence.get(
                                        "composite.commit"))
                                && "false".equals(evidence.get(
                                "composite.dirty"))
                                && String.valueOf(
                                compositeState.worktreeDirty)
                                .equals(evidence.get(
                                        "composite.dirty"))
                                && compositeState.statusSha256
                                .equals(evidence.get(
                                        "composite.gitStatusSha256"))
                                && compositeState.fingerprint
                                .sha256.equals(evidence.get(
                                        "composite.workspaceSha256"))
                                && compositeState.fingerprint
                                .pathCount == parseLong(
                                evidence.get(
                                        "composite.pathCount"));
                currentComposite =
                        compositeState.report();
            }
        } else {
            compositeInputMatches =
                    "".equals(evidence.get("composite.path"))
                            && "".equals(evidence.get(
                            "composite.commit"))
                            && "false".equals(evidence.get(
                            "composite.dirty"))
                            && "".equals(evidence.get(
                            "composite.gitStatusSha256"))
                            && "".equals(evidence.get(
                            "composite.workspaceSha256"))
                            && "0".equals(evidence.get(
                            "composite.pathCount"));
        }
        boolean passed =
                "blue-bex-independent-clean-builds/1.2"
                        .equals(evidence.get("schema"))
                        && "passed".equals(
                        evidence.get("status"))
                        && sourceCommit.equals(
                        evidence.get("commit"))
                        && projectVersion.equals(
                        evidence.get("project.version"))
                        && Boolean.parseBoolean(evidence.get(
                        "first.checkout.clean"))
                        && Boolean.parseBoolean(evidence.get(
                        "second.checkout.clean"))
                        && receiptsValid
                        && receiptContentsMatch
                        && receiptArtifactsMatch
                        && distinctCheckouts
                        && liveCheckoutStateMatches
                        && dependencyInputMatches
                        && compositeInputMatches
                        && artifactChecksPassed;
        return map(
                "status",
                passed ? "passed" : "stale-or-failed",
                "evidencePresent", true,
                "evidencePath", evidencePath.toString(),
                "commit", evidence.get("commit"),
                "firstCheckoutClean",
                Boolean.parseBoolean(evidence.get(
                        "first.checkout.clean")),
                "secondCheckoutClean",
                Boolean.parseBoolean(evidence.get(
                        "second.checkout.clean")),
                "firstEvidenceSha256",
                firstReceiptHash,
                "secondEvidenceSha256",
                secondReceiptHash,
                "receiptsValid", receiptsValid,
                "receiptContentsMatchAggregate",
                receiptContentsMatch,
                "receiptArtifactsMatch",
                receiptArtifactsMatch,
                "distinctCheckouts", distinctCheckouts,
                "liveCheckoutStateMatches",
                liveCheckoutStateMatches,
                "dependencyMode",
                evidence.get("dependency.mode"),
                "dependencyCoordinate",
                evidence.get("dependency.coordinate"),
                "dependencyArtifactSha256",
                recordedDependencyHash,
                "dependencyInputMatches",
                dependencyInputMatches,
                "compositeInputMatches",
                compositeInputMatches,
                "currentCompositeSource",
                currentComposite,
                "artifacts", artifacts);
    }

    static boolean cleanBuildReceiptMatchesAggregate(
            Map<String, String> aggregate,
            String prefix,
            Map<String, String> receipt,
            Collection<String> artifactNames) {
        if (!"blue-bex-clean-build-artifacts/1.1".equals(
                receipt.get("schema"))
                || !"passed".equals(receipt.get("status"))
                || !"true".equals(receipt.get("checkout.clean"))
                || !"true".equals(aggregate.get(
                prefix + ".checkout.clean"))) {
            return false;
        }
        String[][] prefixedMappings = {
                {"checkout.root", "checkout.root"},
                {"checkout.gitDirectory", "checkout.gitDirectory"},
                {"checkout.gitStatusSha256",
                        "checkout.gitStatusSha256"},
                {"checkout.workspaceSha256",
                        "checkout.workspaceSha256"},
                {"checkout.pathCount", "checkout.pathCount"}
        };
        for (String[] mapping : prefixedMappings) {
            if (!Objects.equals(
                    receipt.get(mapping[0]),
                    aggregate.get(prefix + "." + mapping[1]))) {
                return false;
            }
        }
        String[] commonKeys = {
                "commit",
                "project.version",
                "dependency.mode",
                "dependency.coordinate",
                "dependency.effectiveCoordinate",
                "dependency.artifact.path",
                "dependency.artifact.bytes",
                "dependency.artifact.sha256",
                "composite.path",
                "composite.commit",
                "composite.dirty",
                "composite.gitStatusSha256",
                "composite.workspaceSha256",
                "composite.pathCount"
        };
        for (String key : commonKeys) {
            if (!Objects.equals(
                    receipt.get(key),
                    aggregate.get(key))) {
                return false;
            }
        }
        for (String field
                : new String[] {"path", "bytes", "sha256"}) {
            String receiptKey =
                    "dependency.artifact." + field;
            if (!Objects.equals(
                    receipt.get(receiptKey),
                    aggregate.get(
                            prefix + "." + receiptKey))) {
                return false;
            }
        }
        for (String artifactName : artifactNames) {
            String artifactPrefix =
                    "artifact." + artifactName + ".";
            for (String field
                    : new String[] {"path", "bytes", "sha256"}) {
                String receiptKey = artifactPrefix + field;
                String aggregateKey =
                        prefix + "." + receiptKey;
                if (!Objects.equals(
                        receipt.get(receiptKey),
                        aggregate.get(aggregateKey))) {
                    return false;
                }
            }
            if (!Objects.equals(
                    receipt.get(artifactPrefix + "sha256"),
                    aggregate.get(
                            artifactPrefix + "sha256"))
                    || !"true".equals(aggregate.get(
                    "artifact." + artifactName
                            + ".byteIdentical"))) {
                return false;
            }
        }
        return true;
    }

    static boolean cleanBuildReceiptArtifactsMatch(
            Path checkoutRoot,
            Map<String, String> receipt,
            Collection<String> artifactNames,
            String projectVersion) {
        try {
            Path realRoot = checkoutRoot.toRealPath();
            String artifactPrefix =
                    "blue-bex-java-" + projectVersion;
            Map<String, String> expectedPaths =
                    stringMap(
                            "main",
                            "build/libs/" + artifactPrefix + ".jar",
                            "sources",
                            "build/libs/" + artifactPrefix
                                    + "-sources.jar",
                            "javadoc",
                            "build/libs/" + artifactPrefix
                                    + "-javadoc.jar",
                            "sourceRelease",
                            "build/distributions/" + artifactPrefix
                                    + "-source-release.zip");
            for (String artifactName : artifactNames) {
                String expected = expectedPaths.get(artifactName);
                String recorded =
                        receipt.get(
                                "artifact." + artifactName + ".path");
                if (expected == null
                        || recorded == null
                        || Paths.get(recorded).isAbsolute()
                        || !expected.equals(recorded)) {
                    return false;
                }
                Path artifact =
                        realRoot.resolve(recorded).normalize();
                if (!artifact.startsWith(realRoot)
                        || !Files.isRegularFile(artifact)) {
                    return false;
                }
                Path realArtifact = artifact.toRealPath();
                if (!realArtifact.startsWith(realRoot)) {
                    return false;
                }
                long recordedBytes = parseLong(receipt.get(
                        "artifact." + artifactName + ".bytes"));
                String recordedHash = receipt.get(
                        "artifact." + artifactName + ".sha256");
                if (recordedBytes < 0
                        || Files.size(realArtifact)
                        != recordedBytes
                        || recordedHash == null
                        || !recordedHash.matches("[0-9a-f]{64}")
                        || !recordedHash.equals(
                        sha256(realArtifact))) {
                    return false;
                }
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    static boolean cleanBuildReceiptDependencyMatches(
            Path checkoutRoot,
            Map<String, String> receipt) {
        try {
            Path realRoot = checkoutRoot.toRealPath();
            String expected =
                    "build/reports/bex-release/clean-build-inputs/"
                            + "blue-language-java.jar";
            String recorded =
                    receipt.get("dependency.artifact.path");
            if (!expected.equals(recorded)
                    || Paths.get(recorded).isAbsolute()) {
                return false;
            }
            Path artifact =
                    realRoot.resolve(recorded).normalize();
            if (!artifact.startsWith(realRoot)
                    || !Files.isRegularFile(artifact)) {
                return false;
            }
            Path realArtifact = artifact.toRealPath();
            if (!realArtifact.startsWith(realRoot)) {
                return false;
            }
            long recordedBytes = parseLong(
                    receipt.get("dependency.artifact.bytes"));
            String recordedHash =
                    receipt.get("dependency.artifact.sha256");
            return recordedBytes >= 0
                    && Files.size(realArtifact) == recordedBytes
                    && recordedHash != null
                    && recordedHash.matches("[0-9a-f]{64}")
                    && recordedHash.equals(sha256(realArtifact));
        } catch (Exception invalid) {
            return false;
        }
    }

    static boolean liveCleanCheckoutMatches(
            Path checkoutRoot,
            Path recordedGitDirectory,
            String prefix,
            Map<String, String> aggregate,
            String sourceCommit) {
        try {
            Path actualRoot =
                    Paths.get(git(
                            checkoutRoot,
                            "rev-parse",
                            "--show-toplevel").trim())
                            .toRealPath();
            Path actualGitDirectory =
                    Paths.get(git(
                            checkoutRoot,
                            "rev-parse",
                            "--absolute-git-dir").trim())
                            .toRealPath();
            if (!checkoutRoot.equals(actualRoot)
                    || !recordedGitDirectory.equals(
                    actualGitDirectory)) {
                return false;
            }
            SourceState state = sourceState(checkoutRoot);
            return !state.worktreeDirty
                    && state.completeWorkspace()
                    && sourceCommit.equals(state.commit)
                    && Objects.equals(
                    aggregate.get(
                            prefix
                                    + ".checkout.gitStatusSha256"),
                    state.statusSha256)
                    && Objects.equals(
                    aggregate.get(
                            prefix
                                    + ".checkout.workspaceSha256"),
                    state.fingerprint.sha256)
                    && Objects.equals(
                    aggregate.get(
                            prefix + ".checkout.pathCount"),
                    String.valueOf(
                            state.fingerprint.pathCount));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static Map<String, Object> deterministicArchiveEvidence(
            Path projectDir,
            Path evidencePath) throws Exception {
        Map<String, String> evidence = readEvidence(evidencePath);
        if (evidence.isEmpty()) {
            return map(
                    "status", "not-executed",
                    "evidencePresent", false);
        }
        FileCheck mainOriginal = checkFile(
                projectDir,
                evidence.get("main.original.path"),
                evidence.get("main.original.sha256"));
        FileCheck mainRebuild = checkFile(
                projectDir,
                evidence.get("main.rebuild.path"),
                evidence.get("main.rebuild.sha256"));
        FileCheck sourcesOriginal = checkFile(
                projectDir,
                evidence.get("sources.original.path"),
                evidence.get("sources.original.sha256"));
        FileCheck sourcesRebuild = checkFile(
                projectDir,
                evidence.get("sources.rebuild.path"),
                evidence.get("sources.rebuild.sha256"));
        FileCheck javadocOriginal = checkFile(
                projectDir,
                evidence.get("javadoc.original.path"),
                evidence.get("javadoc.original.sha256"));
        FileCheck javadocRebuild = checkFile(
                projectDir,
                evidence.get("javadoc.rebuild.path"),
                evidence.get("javadoc.rebuild.sha256"));
        FileCheck sourceReleaseOriginal = checkFile(
                projectDir,
                evidence.get("sourceRelease.original.path"),
                evidence.get("sourceRelease.original.sha256"));
        FileCheck sourceReleaseReplica = checkFile(
                projectDir,
                evidence.get("sourceRelease.replica.path"),
                evidence.get("sourceRelease.replica.sha256"));
        boolean mainIdentical = mainOriginal.valid
                && mainRebuild.valid
                && mainOriginal.sha256.equals(mainRebuild.sha256);
        boolean sourcesIdentical = sourcesOriginal.valid
                && sourcesRebuild.valid
                && sourcesOriginal.sha256.equals(
                        sourcesRebuild.sha256);
        boolean javadocIdentical = javadocOriginal.valid
                && javadocRebuild.valid
                && javadocOriginal.sha256.equals(
                        javadocRebuild.sha256)
                && Boolean.parseBoolean(evidence.get(
                        "javadoc.freshlyRegenerated"));
        boolean sourceReleaseIdentical =
                sourceReleaseOriginal.valid
                        && sourceReleaseReplica.valid
                        && sourceReleaseOriginal.sha256.equals(
                        sourceReleaseReplica.sha256)
                        && Boolean.parseBoolean(evidence.get(
                        "sourceRelease.byteIdentity"))
                        && Boolean.parseBoolean(evidence.get(
                        "sourceRelease.hashIdentity"))
                        && Boolean.parseBoolean(evidence.get(
                        "sourceRelease.independentAssembly"));
        boolean archiveScope =
                "jar-packaging-determinism-and-source-release-reassembly-from-the-same-working-tree"
                        .equals(
                        evidence.get("scope"));
        boolean independentCleanCompilation =
                Boolean.parseBoolean(evidence.get(
                        "independentCleanCompilation"));
        boolean passed = "passed".equals(evidence.get("status"))
                && mainIdentical
                && sourcesIdentical
                && javadocIdentical
                && sourceReleaseIdentical
                && archiveScope
                && !independentCleanCompilation;
        return map(
                "status", passed ? "passed" : "stale-or-failed",
                "evidencePresent", true,
                "scope", evidence.get("scope"),
                "independentCleanCompilation",
                independentCleanCompilation,
                "assessment",
                "This gate compares archive packaging from the same "
                        + "compiled/source inputs and independently "
                        + "reassembles the source ZIP. It does not prove "
                        + "a second clean checkout compilation.",
                "mainJar", map(
                        "original", mainOriginal.report(),
                        "rebuild", mainRebuild.report(),
                        "byteIdentical", mainIdentical),
                "sourcesJar", map(
                        "original", sourcesOriginal.report(),
                        "rebuild", sourcesRebuild.report(),
                        "byteIdentical", sourcesIdentical),
                "javadocJar", map(
                        "original", javadocOriginal.report(),
                        "freshRebuild", javadocRebuild.report(),
                        "freshlyRegenerated",
                        Boolean.parseBoolean(evidence.get(
                                "javadoc.freshlyRegenerated")),
                        "byteIdentical", javadocIdentical),
                "sourceRelease", map(
                        "original",
                        sourceReleaseOriginal.report(),
                        "replica",
                        sourceReleaseReplica.report(),
                        "independentAssembly",
                        Boolean.parseBoolean(evidence.get(
                                "sourceRelease.independentAssembly")),
                        "independentCleanCheckout",
                        Boolean.parseBoolean(evidence.get(
                                "sourceRelease.independentCleanCheckout")),
                        "byteIdentical",
                        sourceReleaseIdentical));
    }

    private static Map<String, Object> binaryApiEvidence(
            Path projectDir,
            Path buildDir,
            Path evidencePath) throws Exception {
        Map<String, String> evidence = readEvidence(evidencePath);
        TestEvidence apiTests = readTests(
                buildDir.resolve("test-results")
                        .resolve("binaryApiCheck"));
        if (evidence.isEmpty() && !apiTests.present) {
            return map(
                    "status", "not-executed",
                    "evidencePresent", false,
                    "tests", apiTests.report());
        }
        FileCheck artifact = checkFile(
                projectDir,
                evidence.get("artifact.path"),
                evidence.get("artifact.sha256"));
        FileCheck manifest = checkFile(
                projectDir,
                evidence.get("manifest.path"),
                evidence.get("manifest.sha256"));
        FileCheck requiredApi = checkFile(
                projectDir,
                evidence.get("required.path"),
                evidence.get("required.sha256"));
        Path manifestPath = evidence.get("manifest.path") == null
                ? null
                : projectDir.resolve(evidence.get("manifest.path"))
                .toAbsolutePath().normalize();
        List<String> manifestLines =
                manifestPath != null
                        && manifestPath.startsWith(projectDir)
                        && Files.isRegularFile(manifestPath)
                        ? Files.readAllLines(
                                manifestPath,
                                StandardCharsets.UTF_8)
                        : Collections.<String>emptyList();
        boolean manifestSchemaValid =
                "blue-bex-binary-api-manifest/1.0".equals(
                        evidence.get("manifest.schema"))
                        && !manifestLines.isEmpty()
                        && "schema=blue-bex-binary-api-manifest/1.0"
                        .equals(manifestLines.get(0));
        Path requiredPath = evidence.get("required.path") == null
                ? null
                : projectDir.resolve(evidence.get("required.path"))
                .toAbsolutePath().normalize();
        List<String> requiredLines =
                requiredPath != null
                        && requiredPath.startsWith(projectDir)
                        && Files.isRegularFile(requiredPath)
                        ? Files.readAllLines(
                                requiredPath,
                                StandardCharsets.UTF_8)
                        : Collections.<String>emptyList();
        List<String> actualSignatures =
                new ArrayList<String>();
        for (String line : manifestLines) {
            actualSignatures.add(trimTrailingWhitespace(line));
        }
        List<String> requiredSignatures =
                new ArrayList<String>();
        List<String> missingSignatures =
                new ArrayList<String>();
        List<String> unexpectedSignatures =
                new ArrayList<String>();
        for (String line : requiredLines) {
            String signature = trimTrailingWhitespace(line);
            requiredSignatures.add(signature);
            if (!actualSignatures.contains(signature)) {
                missingSignatures.add(signature);
            }
        }
        for (String signature : actualSignatures) {
            if (!requiredSignatures.contains(signature)) {
                unexpectedSignatures.add(signature);
            }
        }
        boolean requiredComparisonValid =
                requiredApi.valid
                        && !requiredSignatures.isEmpty()
                        && actualSignatures.equals(
                        requiredSignatures)
                        && missingSignatures.isEmpty()
                        && unexpectedSignatures.isEmpty()
                        && "exact-match".equals(
                        evidence.get("required.comparison"))
                        && parseLong(evidence.get(
                        "required.signatureCount"))
                        == requiredSignatures.size()
                        && parseLong(evidence.get(
                        "required.missingCount")) == 0L
                        && parseLong(evidence.get(
                        "required.unexpectedCount")) == 0L;
        String testStatus = apiTests.overallStatus();
        boolean passed = "passed".equals(evidence.get("status"))
                && artifact.valid
                && manifest.valid
                && manifestSchemaValid
                && requiredComparisonValid
                && "passed".equals(testStatus);
        return map(
                "status", passed ? "passed" : "stale-or-failed",
                "evidencePresent", !evidence.isEmpty(),
                "testStatus", testStatus,
                "testClass", evidence.get("testClass"),
                "artifact", artifact.report(),
                "publicApiManifest", map(
                        "file", manifest.report(),
                        "schemaValid", manifestSchemaValid,
                        "scope",
                        "public-and-protected-descriptor-level-api"),
                "requiredApiSignatures", map(
                        "file", requiredApi.report(),
                        "requiredCount",
                        requiredSignatures.size(),
                        "missingCount",
                        missingSignatures.size(),
                        "missing", missingSignatures,
                        "unexpectedCount",
                        unexpectedSignatures.size(),
                        "unexpected", unexpectedSignatures,
                        "comparison",
                        requiredComparisonValid
                                ? "exact-match"
                                : "stale-or-failed"),
                "tests", apiTests.report());
    }

    private static String trimTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0
                && Character.isWhitespace(
                        value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static Map<String, Object> benchmarkCompilationEvidence(
            Path projectDir,
            Path evidencePath) throws Exception {
        Map<String, String> evidence = readEvidence(evidencePath);
        if (evidence.isEmpty()) {
            return map(
                    "status", "not-executed",
                    "evidencePresent", false,
                    "timingExecuted", false);
        }
        FileCheck source = checkFile(
                projectDir,
                evidence.get("source.path"),
                evidence.get("source.sha256"));
        FileCheck compiledClass = checkFile(
                projectDir,
                evidence.get("class.path"),
                evidence.get("class.sha256"));
        boolean timingExecuted =
                Boolean.parseBoolean(evidence.get("timingExecuted"));
        boolean passed = "passed".equals(evidence.get("status"))
                && source.valid
                && compiledClass.valid;
        return map(
                "status", passed ? "passed" : "stale-or-failed",
                "evidencePresent", true,
                "timingExecuted", timingExecuted,
                "source", source.report(),
                "compiledClass", compiledClass.report());
    }

    private static Map<String, Object> java8BytecodeEvidence(
            Path projectDir,
            Path evidencePath) throws Exception {
        Map<String, String> evidence = readEvidence(evidencePath);
        if (evidence.isEmpty()) {
            return map(
                    "status", "not-executed",
                    "evidencePresent", false);
        }
        FileCheck artifact = checkFile(
                projectDir,
                evidence.get("artifact.path"),
                evidence.get("artifact.sha256"));
        long classCount = parseLong(evidence.get("classCount"));
        long expectedMajor = parseLong(evidence.get("expected.major"));
        long observedMajor = parseLong(evidence.get("observed.major"));
        boolean passed =
                "blue-bex-java8-bytecode-evidence/1.0".equals(
                        evidence.get("schema"))
                        && "passed".equals(evidence.get("status"))
                        && artifact.valid
                        && classCount > 0L
                        && expectedMajor == 52L
                        && observedMajor == expectedMajor
                        && "CAFEBABE".equals(
                        evidence.get("expected.magic"))
                        && Objects.equals(
                        evidence.get("expected.magic"),
                        evidence.get("observed.magic"));
        return map(
                "status", passed ? "passed" : "stale-or-failed",
                "evidencePresent", true,
                "artifact", artifact.report(),
                "classCount", classCount,
                "expectedMagic", evidence.get("expected.magic"),
                "observedMagic", evidence.get("observed.magic"),
                "expectedMajor", expectedMajor,
                "observedMajor", observedMajor);
    }

    private static Map<String, String> readEvidence(Path path)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            return Collections.emptyMap();
        }
        Map<String, String> result =
                new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(
                path, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            result.put(
                    line.substring(0, separator),
                    line.substring(separator + 1));
        }
        return result;
    }

    private static FileCheck checkFile(
            Path projectDir,
            String relativePath,
            String recordedSha256) throws IOException {
        if (relativePath == null || recordedSha256 == null) {
            return FileCheck.missing(relativePath);
        }
        Path path = projectDir.resolve(relativePath)
                .toAbsolutePath().normalize();
        if (!path.startsWith(projectDir) || !Files.isRegularFile(path)) {
            return FileCheck.missing(relativePath);
        }
        String actualSha256 = sha256(path);
        return new FileCheck(
                relativePath,
                Files.size(path),
                actualSha256,
                actualSha256.equals(recordedSha256));
    }

    private static SourceState sourceState(Path projectDir)
            throws Exception {
        String rawCommit =
                git(projectDir, "rev-parse", "HEAD").trim();
        String commit = rawCommit.matches("[0-9a-fA-F]{40}")
                ? rawCommit.toLowerCase()
                : "unavailable";
        String status = git(
                projectDir,
                "status",
                "--porcelain",
                "-z",
                "--untracked-files=all");
        String listedFiles = git(
                projectDir,
                "ls-files",
                "-z",
                "--cached",
                "--others",
                "--exclude-standard");
        String ignoredReleaseFiles = git(
                projectDir,
                "ls-files",
                "-z",
                "--others",
                "--ignored",
                "--exclude-standard",
                "--",
                ".github",
                "docs",
                "gradle",
                "specifications",
                "src");
        List<String> paths = splitNul(listedFiles);
        List<String> releasePaths = paths.stream()
                .filter(BexConformanceReportMain::isReleaseSourcePath)
                .collect(Collectors.toList());
        List<String> uncommittedReleasePaths =
                statusPaths(status).stream()
                        .filter(
                                BexConformanceReportMain
                                        ::isReleaseSourcePath)
                        .sorted()
                        .collect(Collectors.toList());
        WorkspaceFingerprint fingerprint =
                workspaceFingerprint(projectDir, paths);
        WorkspaceFingerprint releaseFingerprint =
                workspaceFingerprint(
                        projectDir, releasePaths);
        return new SourceState(
                commit,
                !status.isEmpty(),
                splitNul(status).size(),
                status.isEmpty()
                        ? ConformancePackage.sha256(new byte[0])
                        : ConformancePackage.sha256(
                                status.getBytes(StandardCharsets.UTF_8)),
                fingerprint,
                releaseFingerprint,
                uncommittedReleasePaths,
                splitNul(ignoredReleaseFiles));
    }

    private static List<String> statusPaths(String status) {
        List<String> records = splitNul(status);
        List<String> paths = new ArrayList<String>();
        for (int index = 0; index < records.size(); index++) {
            String record = records.get(index);
            if (record.length() < 4
                    || record.charAt(2) != ' ') {
                continue;
            }
            paths.add(record.substring(3));
            char indexStatus = record.charAt(0);
            char worktreeStatus = record.charAt(1);
            boolean rename =
                    indexStatus == 'R'
                            || worktreeStatus == 'R';
            boolean copy =
                    indexStatus == 'C'
                            || worktreeStatus == 'C';
            if (index + 1 < records.size()
                    && (rename || copy)) {
                if (rename) {
                    paths.add(records.get(index + 1));
                }
                index++;
            }
        }
        return paths;
    }

    private static boolean isReleaseSourcePath(String path) {
        return ".cz.toml".equals(path)
                || ".gitattributes".equals(path)
                || ".gitignore".equals(path)
                || ".gitmodules".equals(path)
                || "LICENSE".equals(path)
                || "README.md".equals(path)
                || "build.gradle.kts".equals(path)
                || "gradle.properties".equals(path)
                || "gradlew".equals(path)
                || "gradlew.bat".equals(path)
                || "settings.gradle.kts".equals(path)
                || path.startsWith(".github/")
                || path.startsWith("docs/")
                || path.startsWith("build-logic/")
                || path.startsWith("blue-bex-core/")
                || path.startsWith("blue-bex-contracts/")
                || path.startsWith("blue-bex-conformance/")
                || path.startsWith("blue-bex-java/")
                || path.startsWith("examples/")
                || path.startsWith("gradle/")
                || path.startsWith("specifications/")
                || path.startsWith("src/");
    }

    private static Map<String, Object> compositeDependencyEvidence(
            Path blueLanguage) throws Exception {
        if (blueLanguage == null) {
            return map(
                    "status", "not-selected");
        }
        if (!Files.isDirectory(blueLanguage)) {
            return map(
                    "path", blueLanguage.toString(),
                    "status", "unavailable");
        }
        SourceState dependencyState = sourceState(blueLanguage);
        return map(
                "path", blueLanguage.toString(),
                "status", "identified",
                "sourceState", dependencyState.report());
    }

    private static WorkspaceFingerprint workspaceFingerprint(
            Path projectDir,
            List<String> listedPaths) throws Exception {
        if (listedPaths.isEmpty()) {
            return new WorkspaceFingerprint(
                    "unavailable", 0, 0, 0, 0);
        }
        List<String> paths =
                new ArrayList<String>(listedPaths);
        Collections.sort(paths);
        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");
        int count = 0;
        int missingCount = 0;
        int unsupportedTypeCount = 0;
        int symlinkCount = 0;
        byte[] buffer = new byte[8192];
        for (String relativePath : paths) {
            Path path = projectDir.resolve(relativePath)
                    .toAbsolutePath().normalize();
            if (!path.startsWith(projectDir)) {
                throw new IllegalStateException(
                        "Source path escapes project: " + relativePath);
            }
            byte[] pathBytes =
                    relativePath.getBytes(
                            StandardCharsets.UTF_8);
            updateLength(digest, pathBytes.length);
            digest.update(pathBytes);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                digest.update((byte) 0);
                updateLength(digest, 0L);
                missingCount++;
            } else if (Files.isSymbolicLink(path)) {
                digest.update((byte) 2);
                symlinkCount++;
                byte[] targetBytes = Files.readSymbolicLink(
                        path).toString().getBytes(
                        StandardCharsets.UTF_8);
                updateLength(digest, targetBytes.length);
                digest.update(targetBytes);
            } else if (Files.isRegularFile(
                    path, LinkOption.NOFOLLOW_LINKS)) {
                digest.update((byte) 1);
                updateLength(digest, Files.size(path));
                try (InputStream input = Files.newInputStream(path)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            } else {
                digest.update((byte) 3);
                updateLength(digest, 0L);
                unsupportedTypeCount++;
            }
            count++;
        }
        return new WorkspaceFingerprint(
                hex(digest.digest()),
                count,
                missingCount,
                unsupportedTypeCount,
                symlinkCount);
    }

    private static void updateLength(
            MessageDigest digest,
            long value) {
        digest.update(ByteBuffer.allocate(8)
                .putLong(value)
                .array());
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result =
                new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(
                    java.util.Locale.ROOT,
                    "%02x",
                    value & 0xff));
        }
        return result.toString();
    }

    private static List<String> splitNul(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        int start = 0;
        for (int index = 0; index <= value.length(); index++) {
            if (index == value.length()
                    || value.charAt(index) == '\0') {
                if (index > start) {
                    result.add(value.substring(start, index));
                }
                start = index + 1;
            }
        }
        return result;
    }

    private static TestEvidence readTests(Path resultRoot)
            throws Exception {
        if (!Files.isDirectory(resultRoot)) {
            return new TestEvidence(false,
                    Collections.<TestCase>emptyList());
        }
        List<Path> xml;
        try (Stream<Path> paths = Files.walk(resultRoot)) {
            xml = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString().endsWith(".xml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
        List<TestCase> tests = new ArrayList<TestCase>();
        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        for (Path path : xml) {
            Document document =
                    factory.newDocumentBuilder().parse(path.toFile());
            NodeList cases = document.getElementsByTagName("testcase");
            for (int index = 0; index < cases.getLength(); index++) {
                Element element = (Element) cases.item(index);
                String status = childCount(element, "failure") > 0
                        || childCount(element, "error") > 0
                        ? "failed"
                        : childCount(element, "skipped") > 0
                        ? "skipped"
                        : "passed";
                tests.add(new TestCase(
                        element.getAttribute("classname"),
                        element.getAttribute("name"),
                        status));
            }
        }
        return new TestEvidence(!xml.isEmpty(), tests);
    }

    private static int childCount(Element element, String name) {
        return element.getElementsByTagName(name).getLength();
    }

    private static String git(
            Path projectDir,
            String... arguments) throws Exception {
        Process process = null;
        try {
            List<String> command = new ArrayList<String>();
            command.add("git");
            Collections.addAll(command, arguments);
            process = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = readUtf8(process.getInputStream());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "Git inspection failed ("
                                + String.join(" ", command)
                                + "): "
                                + output.trim());
            }
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readUtf8(InputStream input)
            throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(
                output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> behaviorIdsByPath() {
        Map<String, String> ids =
                new LinkedHashMap<String, String>();
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            ids.put(fixture.path, fixture.id());
        }
        return ids;
    }

    private static ConformancePackage.Fixture fixture(String id) {
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.behaviorFixtures()) {
            if (id.equals(fixture.id())) {
                return fixture;
            }
        }
        throw new IllegalArgumentException(
                "Unknown fixture " + id);
    }

    private static String unix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "map needs key/value pairs");
        }
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(
                    String.valueOf(values[index]),
                    values[index + 1]);
        }
        return result;
    }

    private static final class TestCase {
        final String className;
        final String name;
        final String status;

        TestCase(String className, String name, String status) {
            this.className = className;
            this.name = name;
            this.status = status;
        }
    }

    private static final class FileCheck {
        final String path;
        final long bytes;
        final String sha256;
        final boolean valid;

        FileCheck(
                String path,
                long bytes,
                String sha256,
                boolean valid) {
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.valid = valid;
        }

        static FileCheck missing(String path) {
            return new FileCheck(
                    path,
                    0L,
                    "unavailable",
                    false);
        }

        Map<String, Object> report() {
            return map(
                    "path", path,
                    "present", valid
                            || !"unavailable".equals(sha256),
                    "bytes", bytes,
                    "sha256", sha256,
                    "matchesRecordedEvidence", valid);
        }
    }

    private static final class WorkspaceFingerprint {
        final String sha256;
        final int pathCount;
        final int missingPathCount;
        final int unsupportedTypeCount;
        final int symlinkCount;

        WorkspaceFingerprint(
                String sha256,
                int pathCount,
                int missingPathCount,
                int unsupportedTypeCount,
                int symlinkCount) {
            this.sha256 = sha256;
            this.pathCount = pathCount;
            this.missingPathCount = missingPathCount;
            this.unsupportedTypeCount =
                    unsupportedTypeCount;
            this.symlinkCount = symlinkCount;
        }
    }

    private static final class SourceState {
        final String commit;
        final boolean worktreeDirty;
        final int dirtyEntryCount;
        final String statusSha256;
        final WorkspaceFingerprint fingerprint;
        final WorkspaceFingerprint releaseFingerprint;
        final List<String> uncommittedReleasePaths;
        final List<String> ignoredReleasePaths;

        SourceState(
                String commit,
                boolean worktreeDirty,
                int dirtyEntryCount,
                String statusSha256,
                WorkspaceFingerprint fingerprint,
                WorkspaceFingerprint releaseFingerprint,
                List<String> uncommittedReleasePaths,
                List<String> ignoredReleasePaths) {
            this.commit = commit;
            this.worktreeDirty = worktreeDirty;
            this.dirtyEntryCount = dirtyEntryCount;
            this.statusSha256 = statusSha256;
            this.fingerprint = fingerprint;
            this.releaseFingerprint = releaseFingerprint;
            this.uncommittedReleasePaths =
                    Collections.unmodifiableList(
                            new ArrayList<String>(
                                    uncommittedReleasePaths));
            this.ignoredReleasePaths =
                    Collections.unmodifiableList(
                            new ArrayList<String>(
                                    ignoredReleasePaths));
        }

        Map<String, Object> report() {
            return map(
                    "commit", commit,
                    "worktreeDirty", worktreeDirty,
                    "dirtyEntryCount", dirtyEntryCount,
                    "gitStatusSha256", statusSha256,
                    "workspaceSha256", fingerprint.sha256,
                    "pathCount", fingerprint.pathCount,
                    "missingPathCount",
                    fingerprint.missingPathCount,
                    "unsupportedPathTypeCount",
                    fingerprint.unsupportedTypeCount,
                    "symlinkPathCount",
                    fingerprint.symlinkCount,
                    "releaseInputsCommitted",
                    uncommittedReleasePaths.isEmpty()
                            && ignoredReleasePaths.isEmpty()
                            && releaseFingerprint
                            .missingPathCount == 0
                            && releaseFingerprint
                            .unsupportedTypeCount == 0
                            && releaseFingerprint.symlinkCount == 0,
                    "uncommittedReleaseInputCount",
                    uncommittedReleasePaths.size(),
                    "uncommittedReleaseInputs",
                    uncommittedReleasePaths,
                    "ignoredReleaseInputCount",
                    ignoredReleasePaths.size(),
                    "ignoredReleaseInputs",
                    ignoredReleasePaths,
                    "releaseSourceSha256",
                    releaseFingerprint.sha256,
                    "releaseSourcePathCount",
                    releaseFingerprint.pathCount,
                    "releaseSourceMissingPathCount",
                    releaseFingerprint.missingPathCount,
                    "releaseSourceUnsupportedPathTypeCount",
                    releaseFingerprint.unsupportedTypeCount,
                    "releaseSourceSymlinkPathCount",
                    releaseFingerprint.symlinkCount,
                    "algorithm",
                    "sha256(path-length,path,type,byte-length,"
                            + "working-tree-bytes)",
                    "scope",
                    "git tracked plus non-ignored untracked files");
        }

        boolean completeWorkspace() {
            return commit.matches("[0-9a-f]{40}")
                    && fingerprint.sha256.matches(
                    "[0-9a-f]{64}")
                    && fingerprint.pathCount > 0
                    && fingerprint.missingPathCount == 0
                    && fingerprint.unsupportedTypeCount == 0
                    && fingerprint.symlinkCount == 0
                    && releaseFingerprint.sha256.matches(
                    "[0-9a-f]{64}")
                    && releaseFingerprint.pathCount > 0
                    && releaseFingerprint.missingPathCount == 0
                    && releaseFingerprint.unsupportedTypeCount == 0
                    && releaseFingerprint.symlinkCount == 0
                    && ignoredReleasePaths.isEmpty();
        }
    }

    private static final class TestEvidence {
        final boolean present;
        final List<TestCase> cases;

        TestEvidence(boolean present, List<TestCase> cases) {
            this.present = present;
            this.cases = cases;
        }

        Map<String, Object> report() {
            int passed = count("passed");
            int failed = count("failed");
            int skipped = count("skipped");
            List<Object> failures = new ArrayList<Object>();
            List<Object> skips = new ArrayList<Object>();
            for (TestCase testcase : cases) {
                if ("failed".equals(testcase.status)) {
                    failures.add(map(
                            "className", testcase.className,
                            "name", testcase.name));
                } else if ("skipped".equals(testcase.status)) {
                    skips.add(map(
                            "className", testcase.className,
                            "name", testcase.name));
                }
            }
            return map(
                    "junitXmlPresent", present,
                    "executed", cases.size(),
                    "passed", passed,
                    "failed", failed,
                    "skipped", skipped,
                    "zeroFailures", present && failed == 0,
                    "zeroSkips", present && skipped == 0,
                    "failures", failures,
                    "skips", skips);
        }

        String fixtureStatus(String fixtureId) {
            List<TestCase> matches =
                    new ArrayList<TestCase>();
            for (TestCase testcase : cases) {
                if (testcase.name.equals(fixtureId)
                        || testcase.name.startsWith(fixtureId + " ")
                        || testcase.name.startsWith(fixtureId + " ::")) {
                    matches.add(testcase);
                }
            }
            return combinedStatus(matches);
        }

        String namedStatus(String name) {
            List<TestCase> matches =
                    new ArrayList<TestCase>();
            for (TestCase testcase : cases) {
                if (testcase.name.equals(name)
                        || testcase.name.startsWith(name + "(")) {
                    matches.add(testcase);
                }
            }
            return combinedStatus(matches);
        }

        Map<String, Object> exactEvidence(
                String className,
                String name) {
            List<TestCase> matches =
                    new ArrayList<TestCase>();
            for (TestCase testcase : cases) {
                if (testcase.className.equals(className)
                        && testcase.name.equals(name)) {
                    matches.add(testcase);
                }
            }
            return map(
                    "className", className,
                    "name", name,
                    "status", combinedStatus(matches),
                    "matchedTestCount", matches.size(),
                    "tests", testCaseReports(matches));
        }

        Map<String, Object> classEvidence(
                String... requiredClassFragments) {
            return classAndNamedEvidence(
                    requiredClassFragments,
                    new String[0]);
        }

        Map<String, Object> classAndNamedEvidence(
                String[] requiredClassFragments,
                String... requiredNames) {
            List<Object> selectors =
                    new ArrayList<Object>();
            List<TestCase> allMatches =
                    new ArrayList<TestCase>();
            boolean allPassed = true;
            for (String fragment : requiredClassFragments) {
                List<TestCase> matches =
                        new ArrayList<TestCase>();
                for (TestCase testcase : cases) {
                    if (testcase.className.contains(fragment)) {
                        matches.add(testcase);
                        if (!allMatches.contains(testcase)) {
                            allMatches.add(testcase);
                        }
                    }
                }
                String status = combinedStatus(matches);
                allPassed &= "passed".equals(status);
                selectors.add(map(
                        "classNameContains", fragment,
                        "status", status,
                        "matchedTestCount", matches.size()));
            }
            for (String requiredSelector : requiredNames) {
                int separator =
                        requiredSelector.indexOf('#');
                String requiredClassName =
                        separator < 0
                                ? null
                                : requiredSelector.substring(
                                0, separator);
                String requiredName =
                        separator < 0
                                ? requiredSelector
                                : requiredSelector.substring(
                                separator + 1);
                List<TestCase> matches =
                        new ArrayList<TestCase>();
                for (TestCase testcase : cases) {
                    if ((requiredClassName == null
                            || testcase.className.equals(
                            requiredClassName))
                            && (testcase.name.equals(requiredName)
                            || testcase.name.startsWith(
                            requiredName + "("))) {
                        matches.add(testcase);
                        if (!allMatches.contains(testcase)) {
                            allMatches.add(testcase);
                        }
                    }
                }
                String status = combinedStatus(matches);
                allPassed &= "passed".equals(status);
                selectors.add(map(
                        "className", requiredClassName,
                        "testName", requiredName,
                        "status", status,
                        "matchedTestCount", matches.size()));
            }
            return map(
                    "status",
                    allPassed ? "passed" : "not-passing",
                    "requiredSelectors", selectors,
                    "matchedTestCount", allMatches.size(),
                    "tests", testCaseReports(allMatches));
        }

        Map<String, Object> namedEvidence(
                String... requiredNames) {
            List<Object> selectors =
                    new ArrayList<Object>();
            List<TestCase> allMatches =
                    new ArrayList<TestCase>();
            boolean allPassed = true;
            for (String requiredSelector : requiredNames) {
                int separator =
                        requiredSelector.indexOf('#');
                String requiredClassName =
                        separator < 0
                                ? null
                                : requiredSelector.substring(
                                0, separator);
                String requiredName =
                        separator < 0
                                ? requiredSelector
                                : requiredSelector.substring(
                                separator + 1);
                List<TestCase> matches =
                        new ArrayList<TestCase>();
                for (TestCase testcase : cases) {
                    if ((requiredClassName == null
                            || testcase.className.equals(
                            requiredClassName))
                            && (testcase.name.equals(requiredName)
                            || testcase.name.startsWith(
                            requiredName + "("))) {
                        matches.add(testcase);
                        if (!allMatches.contains(testcase)) {
                            allMatches.add(testcase);
                        }
                    }
                }
                String status = combinedStatus(matches);
                allPassed &= "passed".equals(status);
                selectors.add(map(
                        "className", requiredClassName,
                        "testName", requiredName,
                        "status", status,
                        "matchedTestCount", matches.size()));
            }
            return map(
                    "status",
                    allPassed ? "passed" : "not-passing",
                    "requiredSelectors", selectors,
                    "matchedTestCount", allMatches.size(),
                    "tests", testCaseReports(allMatches));
        }

        private List<Object> testCaseReports(
                List<TestCase> matches) {
            List<Object> result =
                    new ArrayList<Object>();
            for (TestCase testcase : matches) {
                result.add(map(
                        "className", testcase.className,
                        "name", testcase.name,
                        "status", testcase.status));
            }
            return result;
        }

        String overallStatus() {
            if (!present || cases.isEmpty()) {
                return "not-executed";
            }
            if (count("failed") > 0) {
                return "failed";
            }
            if (count("skipped") > 0) {
                return "skipped";
            }
            return "passed";
        }

        private String combinedStatus(List<TestCase> matches) {
            if (matches.isEmpty()) {
                return "not-executed";
            }
            for (TestCase testcase : matches) {
                if ("failed".equals(testcase.status)) {
                    return "failed";
                }
            }
            for (TestCase testcase : matches) {
                if ("skipped".equals(testcase.status)) {
                    return "skipped";
                }
            }
            return "passed";
        }

        private int count(String status) {
            int count = 0;
            for (TestCase testcase : cases) {
                if (status.equals(testcase.status)) {
                    count++;
                }
            }
            return count;
        }
    }
}
