package blue.bex.buildlogic.tasks;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;

/** Writes the exact local-composite BEX working-readiness receipt. */
public abstract class GenerateWorkingReportTask extends DefaultTask {
    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
            "(?m)^import\\s+blue\\.language\\.(?:utils\\.|"
                    + "snapshot\\.ResolvedSnapshot|NodeProvider|"
                    + "BlueOperationLimits|BlueOperationOutcome|"
                    + "BlueOperationResult)");

    @InputDirectory
    public abstract DirectoryProperty getTestResultsDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getEvidenceFiles();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPrimaryArtifacts();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getReplicaArtifacts();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProductionSources();

    @Internal
    public abstract DirectoryProperty getBexRepository();

    @Input
    public abstract Property<String> getLanguageRepositoryPath();

    @Input
    public abstract Property<String> getExpectedLanguageCommit();

    @Input
    public abstract Property<String> getVerifiedImplementationCommit();

    @Input
    public abstract ListProperty<String> getAllowedLanguageDeltaPaths();

    @Input
    public abstract Property<String> getLocalCompositeCommand();

    @Input
    public abstract Property<String> getProjectVersion();

    @Input
    public abstract Property<Boolean> getFailOnIncomplete();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        try {
            File bex = getBexRepository().get().getAsFile();
            File language = new File(getLanguageRepositoryPath().get())
                    .getCanonicalFile();
            GitState bexState = gitState(bex);
            GitState languageState = gitState(language);

            List<File> evidence = regularFiles(getEvidenceFiles());
            String conformance = textFor(evidence, "/bex-conformance/report.json");
            String architecture = textFor(evidence, "/bex-modernization/architecture.json");
            String baseline = textFor(evidence, "latest-language-baseline.json");
            String generatedApiClassification = textFor(
                    evidence, "/blue-bex-conformance/build/reports/bex-release/"
                            + "public-api-classification.json");
            String reviewedApiClassification = textFor(
                    evidence, "/docs/public-api-classification.json");
            String apiMigration = textFor(
                    evidence, "latest-language-api-migration.json");
            String generatedApi = textFor(
                    evidence, "/bex-release/public-api.txt");
            String requiredApi = textFor(
                    evidence, "/hosted-release/required-public-api.txt");
            String jmh = textFor(evidence, "/jmh/smoke-results.json");
            String published = textFor(evidence, "published-language.json");

            TestTotals tests = readTests(
                    getTestResultsDirectory().get().getAsFile());
            ConformanceTotals totals = conformanceTotals(conformance);
            boolean architecturePassed = hasStatus(architecture, "passed");
            int descriptorCount = manifestDescriptorCount(generatedApi);
            int publicTypeCount = manifestTypeCount(generatedApi);
            String manifestSha = sha256(
                    generatedApi.getBytes(StandardCharsets.UTF_8));
            boolean apiPassed = !generatedApi.isEmpty()
                    && generatedApi.equals(requiredApi)
                    && generatedApiClassification.equals(
                            reviewedApiClassification)
                    && hasInventory(generatedApiClassification,
                            publicTypeCount, descriptorCount)
                    && apiMigration.contains(
                            "\"afterSha256\": \"" + manifestSha + "\"");
            boolean benchmarkPassed = jmh.trim().startsWith("[")
                    && jmh.contains("BexCoreBenchmark")
                    && jmh.contains("BexHostedGasBenchmark");

            List<File> dependencyFiles = evidence.stream()
                    .filter(file -> unix(file).contains(
                            "/reports/dependencies/language.json"))
                    .sorted(Comparator.comparing(GenerateWorkingReportTask::unix))
                    .collect(Collectors.toList());
            boolean dependenciesPassed = dependencyFiles.size() == 5;
            List<String> dependencyJson = new ArrayList<>();
            for (File file : dependencyFiles) {
                String text = read(file);
                dependencyJson.add(text.trim());
                dependenciesPassed &= text.contains(
                        "\"mode\": \"local-composite\"")
                        && text.contains("\"languageCommit\": \""
                                + getExpectedLanguageCommit().get() + "\"")
                        && text.contains(
                                "\"languageCheckoutState\": \"clean\"")
                        && text.contains("\"bexCommit\": \""
                                + bexState.head + "\"")
                        && shaCount(text) > 0;
            }

            Set<String> actualDelta = new LinkedHashSet<>(gitLines(
                    language, "diff", "--name-only",
                    getVerifiedImplementationCommit().get() + "..HEAD"));
            Set<String> allowedDelta = new LinkedHashSet<>(
                    getAllowedLanguageDeltaPaths().get());
            boolean languageCodeEquivalent = languageState.head.equals(
                    getExpectedLanguageCommit().get())
                    && !languageState.dirty
                    && actualDelta.equals(allowedDelta);

            String expectedBexCz = stringAfter(
                    baseline, "\"bex\"", "\"czTomlSha256\"");
            String baselineBexCommit = stringAfter(
                    baseline, "\"bex\"", "\"migrationBaselineCommit\"");
            String expectedLanguageCz = stringAfter(
                    baseline, "\"language\"", "\"czTomlSha256\"");
            File bexCzFile = new File(bex, ".cz.toml");
            String actualBexCzText = read(bexCzFile);
            String baselineBexCzText = gitText(
                    bex, "show", baselineBexCommit + ":.cz.toml");
            String actualBexCz = sha256(bexCzFile);
            String baselineBexCz = sha256(
                    baselineBexCzText.getBytes(StandardCharsets.UTF_8));
            String actualLanguageCz = sha256(new File(language, ".cz.toml"));
            CommitizenVersionCheck.Result bexVersion =
                    CommitizenVersionCheck.evaluate(
                            actualBexCzText,
                            baselineBexCzText,
                            getProjectVersion().get());
            boolean baselineBexCzVerified = baselineBexCz.equals(expectedBexCz);
            boolean versionAutomationValid = baselineBexCzVerified
                    && bexVersion.passed
                    && actualLanguageCz.equals(expectedLanguageCz);

            LegacyTotals legacy = legacyTotals(getProductionSources());
            int legacyBeforeLines = integerAfter(
                    baseline, "\"allForbiddenLegacyImports\"", "\"lines\"");
            int legacyBeforeFiles = integerAfter(
                    baseline, "\"allForbiddenLegacyImports\"", "\"files\"");
            boolean legacyPassed = legacy.lines == 0 && legacy.files == 0
                    && legacyBeforeLines >= 0 && legacyBeforeFiles >= 0;

            List<FileEvidence> primary = describe(getPrimaryArtifacts());
            List<FileEvidence> replicas = describe(getReplicaArtifacts());
            boolean artifactsComplete = requiredArtifacts(primary);
            boolean reproducible = replicasMatch(primary, replicas);
            BytecodeEvidence bytecode = bytecode(primary);
            boolean hostedPassed = hostedTestsPassed(
                    getTestResultsDirectory().get().getAsFile());
            boolean criticalSemanticEvidence = sectionPassed(
                    conformance, "representationMatrixResult")
                    && sectionPassed(conformance,
                    "hostedLocalLimitCapability")
                    && sectionPassed(conformance,
                    "cyclicProofUnavailabilityCapability")
                    && sectionPassed(conformance,
                    "semanticBoundaryInvocationEvidence")
                    && sectionPassed(conformance,
                    "ledgerLifecycleEvidence")
                    && sectionPassed(conformance,
                    "gasExhaustionEvidence")
                    && sectionPassed(conformance, "cyclicProofEvidence")
                    && sectionPassed(conformance, "intrinsicEvidence")
                    && sectionPassed(conformance,
                    "referenceEvidenceClassificationEvidence");
            boolean semanticAndGasParity = tests.failed == 0
                    && tests.skipped == 0 && tests.unclassified == 0
                    && totals.complete() && criticalSemanticEvidence;
            String publishedStatus = hasStatus(published, "passed")
                    ? "passed" : "not-executed";
            boolean workingReady = !bexState.dirty
                    && languageCodeEquivalent
                    && versionAutomationValid
                    && dependenciesPassed
                    && tests.executed > 0
                    && semanticAndGasParity
                    && hostedPassed
                    && architecturePassed
                    && legacyPassed
                    && apiPassed
                    && benchmarkPassed
                    && bytecode.passed
                    && artifactsComplete
                    && reproducible;

            String json = "{\n"
                    + "  \"schema\": \"blue-bex-latest-language-working/1.0\",\n"
                    + "  \"bex\": {\"commit\":" + quote(bexState.head)
                    + ",\"dirty\":" + bexState.dirty
                    + ",\"statusSha256\":" + quote(bexState.statusSha256)
                    + "},\n"
                    + "  \"language\": {\"exactCommit\":"
                    + quote(languageState.head) + ",\"dirty\":"
                    + languageState.dirty + ",\"verifiedImplementationCommit\":"
                    + quote(getVerifiedImplementationCommit().get())
                    + ",\"codeEquivalent\":" + languageCodeEquivalent
                    + ",\"deltaPaths\":" + jsonStrings(actualDelta) + "},\n"
                    + "  \"languageModuleBaseline\": "
                    + jsonOrEmpty(baseline) + ",\n"
                    + "  \"versionAutomation\": {\"status\":"
                    + quote(versionAutomationValid ? "passed" : "failed")
                    + ",\"bexCzTomlSha256\":" + quote(actualBexCz)
                    + ",\"bexHistoricalBaselineSha256\":"
                    + quote(expectedBexCz)
                    + ",\"bexBaselineVerified\":"
                    + baselineBexCzVerified
                    + ",\"bexConfiguredVersion\":"
                    + quote(bexVersion.configuredVersion)
                    + ",\"projectVersion\":"
                    + quote(getProjectVersion().get())
                    + ",\"bexMatchesProjectVersion\":"
                    + bexVersion.matchesProjectVersion
                    + ",\"bexNonVersionConfigMatchesBaseline\":"
                    + bexVersion.nonVersionConfigMatchesBaseline
                    + ",\"languageCzTomlSha256\":"
                    + quote(actualLanguageCz)
                    + ",\"languageMatchesBaseline\":"
                    + actualLanguageCz.equals(expectedLanguageCz) + "},\n"
                    + "  \"dependencyEvidence\": "
                    + jsonObjects(dependencyJson) + ",\n"
                    + "  \"legacyImports\": {\"before\":{\"lines\":"
                    + legacyBeforeLines + ",\"files\":" + legacyBeforeFiles
                    + "},\"after\":{\"lines\":" + legacy.lines
                    + ",\"files\":" + legacy.files + "}},\n"
                    + "  \"tests\": {\"executed\":" + tests.executed
                    + ",\"passed\":" + tests.passed + ",\"failed\":"
                    + tests.failed + ",\"skipped\":" + tests.skipped
                    + ",\"unclassified\":" + tests.unclassified + "},\n"
                    + "  \"conformance\": " + totals.json() + ",\n"
                    + "  \"semanticAndGasParity\": "
                    + quote(semanticAndGasParity ? "passed" : "failed") + ",\n"
                    + "  \"hostedBoundaryTests\": "
                    + quote(hostedPassed ? "passed" : "failed") + ",\n"
                    + "  \"java8Bytecode\": {\"status\":"
                    + quote(bytecode.passed ? "passed" : "failed")
                    + ",\"classCount\":" + bytecode.classCount
                    + ",\"maximumMajorVersion\":" + bytecode.maximumMajor + "},\n"
                    + "  \"architecture\": " + jsonOrEmpty(architecture) + ",\n"
                    + "  \"apiEvidence\": {\"status\":"
                    + quote(apiPassed ? "passed" : "failed")
                    + ",\"publicTypeCount\":" + publicTypeCount
                    + ",\"publicDescriptorCount\":" + descriptorCount
                    + ",\"manifestSha256\":" + quote(manifestSha)
                    + ",\"classificationSha256\":"
                    + quote(sha256(generatedApiClassification.getBytes(
                            StandardCharsets.UTF_8)))
                    + ",\"migrationLedgerSha256\":"
                    + quote(sha256(apiMigration.getBytes(StandardCharsets.UTF_8)))
                    + "},\n"
                    + "  \"benchmarkSmoke\": "
                    + quote(benchmarkPassed ? "passed" : "failed") + ",\n"
                    + "  \"artifacts\": " + fileEvidenceJson(primary) + ",\n"
                    + "  \"reproducibility\": {\"status\":"
                    + quote(reproducible ? "passed" : "failed")
                    + ",\"replicas\":" + fileEvidenceJson(replicas) + "},\n"
                    + "  \"localComposite\": {\"command\":"
                    + quote(getLocalCompositeCommand().get())
                    + ",\"outcome\":"
                    + quote(workingReady ? "passed" : "failed") + "},\n"
                    + "  \"workingReady\": " + workingReady + ",\n"
                    + "  \"publishedModeStatus\": "
                    + quote(publishedStatus) + "\n"
                    + "}\n";
            File output = getOutputFile().get().getAsFile();
            output.getParentFile().mkdirs();
            Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
            if (getFailOnIncomplete().get() && !workingReady) {
                throw new GradleException(
                        "BEX working evidence is incomplete; see " + output);
            }
        } catch (GradleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GradleException("Cannot generate BEX working report", exception);
        }
    }

    private static List<File> regularFiles(ConfigurableFileCollection files) {
        return files.getFiles().stream().filter(File::isFile)
                .sorted(Comparator.comparing(GenerateWorkingReportTask::unix))
                .collect(Collectors.toList());
    }

    private static List<FileEvidence> describe(ConfigurableFileCollection files)
            throws Exception {
        List<FileEvidence> result = new ArrayList<>();
        for (File file : regularFiles(files)) {
            result.add(new FileEvidence(file, file.length(), sha256(file)));
        }
        return result;
    }

    private static boolean requiredArtifacts(List<FileEvidence> files) {
        Set<String> names = files.stream().map(item -> item.file.getName())
                .collect(Collectors.toSet());
        return names.stream().anyMatch(name -> name.matches(
                "blue-bex-core-.+\\.jar"))
                && names.stream().anyMatch(name -> name.matches(
                "blue-bex-contracts-.+\\.jar"))
                && names.stream().anyMatch(name -> name.matches(
                "blue-bex-java-.+\\.jar"))
                && names.stream().anyMatch(name -> name.matches(
                "blue-bex-java-.+-sources\\.jar"))
                && names.stream().anyMatch(name -> name.matches(
                "blue-bex-java-.+-javadoc\\.jar"))
                && names.stream().anyMatch(name -> name.matches(
                "blue-bex-java-.+-source-release\\.zip"));
    }

    private static boolean replicasMatch(
            List<FileEvidence> primary, List<FileEvidence> replicas) {
        Map<String, FileEvidence> byName = new LinkedHashMap<>();
        for (FileEvidence replica : replicas) {
            byName.put(replica.file.getName(), replica);
        }
        return !primary.isEmpty() && primary.stream().allMatch(item ->
                byName.containsKey(item.file.getName())
                        && item.sha256.equals(byName.get(
                                item.file.getName()).sha256));
    }

    private static BytecodeEvidence bytecode(List<FileEvidence> artifacts)
            throws IOException {
        int classes = 0;
        int maximum = 0;
        boolean passed = true;
        for (FileEvidence artifact : artifacts) {
            String name = artifact.file.getName();
            if (!name.endsWith(".jar") || name.contains("-sources")
                    || name.contains("-javadoc")) {
                continue;
            }
            try (JarFile jar = new JarFile(artifact.file)) {
                for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    classes++;
                    try (DataInputStream input = new DataInputStream(
                            jar.getInputStream(entry))) {
                        if (input.readInt() != 0xCAFEBABE) {
                            passed = false;
                            continue;
                        }
                        input.readUnsignedShort();
                        int major = input.readUnsignedShort();
                        maximum = Math.max(maximum, major);
                        passed &= major <= 52;
                    }
                }
            }
        }
        return new BytecodeEvidence(passed && classes > 0, classes, maximum);
    }

    private static TestTotals readTests(File directory) throws Exception {
        TestTotals totals = new TestTotals();
        if (!directory.isDirectory()) {
            return totals;
        }
        for (java.nio.file.Path path : Files.walk(directory.toPath())
                .filter(item -> item.getFileName().toString().startsWith("TEST-"))
                .filter(item -> item.toString().endsWith(".xml"))
                .collect(Collectors.toList())) {
            Element root = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(path.toFile()).getDocumentElement();
            int declared = integer(root, "tests");
            int executed = root.getElementsByTagName("testcase").getLength();
            int failed = integer(root, "failures") + integer(root, "errors");
            int skipped = integer(root, "skipped");
            totals.executed += executed;
            totals.failed += failed;
            totals.skipped += skipped;
            totals.passed += executed - failed - skipped;
            totals.unclassified += Math.max(0, declared - executed);
        }
        return totals;
    }

    private static boolean hostedTestsPassed(File directory) throws Exception {
        TestTotals totals = new TestTotals();
        int files = 0;
        for (java.nio.file.Path path : Files.walk(directory.toPath())
                .filter(item -> item.getFileName().toString().matches(
                        "TEST-.*(?:HostedRuntimeWorkSession|"
                                + "SemanticIdentityIntegration|"
                                + "CompositeExhaustionEvidence).*\\.xml"))
                .collect(Collectors.toList())) {
            files++;
            Element root = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(path.toFile()).getDocumentElement();
            totals.executed += integer(root, "tests");
            totals.failed += integer(root, "failures") + integer(root, "errors");
            totals.skipped += integer(root, "skipped");
        }
        return files >= 3 && totals.executed > 0
                && totals.failed == 0 && totals.skipped == 0;
    }

    private static int integer(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    private static ConformanceTotals conformanceTotals(String text) {
        return new ConformanceTotals(
                pair(text, "normativeVectors", "executedAndPassing"),
                pair(text, "normativeVectors", "required"),
                pair(text, "behaviorFixtures", "executedAndPassing"),
                pair(text, "behaviorFixtures", "required"),
                pair(text, "gasMicrofixtures", "executedAndPassing"),
                pair(text, "gasMicrofixtures", "required"),
                pair(text, "operators", "executedAndPassing"),
                pair(text, "operators", "required"));
    }

    private static int pair(String text, String section, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(section)
                + "\\\"\\s*:\\s*\\{([^{}]|\\{[^{}]*\\})*?\\\""
                + Pattern.quote(field) + "\\\"\\s*:\\s*(\\d+)")
                .matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : -1;
    }

    private static boolean hasStatus(String text, String status) {
        return text.matches("(?s).*\\\"status\\\"\\s*:\\s*\\\""
                + Pattern.quote(status) + "\\\".*");
    }

    private static boolean sectionPassed(String text, String name) {
        String section = objectSection(text, name);
        return hasStatus(section, "passed")
                && !hasStatus(section, "failed")
                && !hasStatus(section, "not-executed");
    }

    private static String objectSection(String text, String name) {
        int key = text.indexOf("\"" + name + "\"");
        if (key < 0) {
            return "";
        }
        int start = text.indexOf('{', key);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    quoted = false;
                }
            } else if (value == '"') {
                quoted = true;
            } else if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        return "";
    }

    private static boolean hasInventory(String text, int types, int descriptors) {
        return text.matches("(?s).*\\\"publicTypeCount\\\"\\s*:\\s*"
                + types + ".*")
                && text.matches("(?s).*\\\"publicDescriptorCount\\\""
                + "\\s*:\\s*" + descriptors + ".*");
    }

    private static int manifestDescriptorCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return (int) Arrays.stream(text.split("\\R"))
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("schema="))
                .count();
    }

    private static int manifestTypeCount(String text) {
        return (int) Arrays.stream(text.split("\\R"))
                .filter(line -> line.startsWith("class "))
                .count();
    }

    private static int shaCount(String text) {
        Matcher matcher = Pattern.compile(
                "\\\"sha256\\\"\\s*:\\s*\\\"[0-9a-f]{64}\\\"")
                .matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static LegacyTotals legacyTotals(ConfigurableFileCollection sources)
            throws IOException {
        int lines = 0;
        int files = 0;
        for (File file : regularFiles(sources)) {
            String text = read(file);
            Matcher matcher = FORBIDDEN_IMPORT.matcher(text);
            int fileLines = 0;
            while (matcher.find()) {
                fileLines++;
            }
            if (fileLines > 0) {
                files++;
                lines += fileLines;
            }
        }
        return new LegacyTotals(lines, files);
    }

    private static int integerAfter(String text, String section, String field) {
        int start = text.indexOf(section);
        if (start < 0) {
            return -1;
        }
        Matcher matcher = Pattern.compile(Pattern.quote(field)
                + "\\s*:\\s*(\\d+)").matcher(text.substring(start));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String stringAfter(String text, String section, String field) {
        int start = text.indexOf(section);
        if (start < 0) {
            return "";
        }
        Matcher matcher = Pattern.compile(Pattern.quote(field)
                + "\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(text.substring(start));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static GitState gitState(File directory) throws Exception {
        String head = gitText(directory, "rev-parse", "HEAD").trim();
        byte[] status = git(directory, "status", "--porcelain", "-z");
        return new GitState(head, status.length != 0, sha256(status));
    }

    private static List<String> gitLines(File directory, String... args)
            throws Exception {
        String text = gitText(directory, args).trim();
        return text.isEmpty() ? new ArrayList<>()
                : Arrays.asList(text.split("\\R"));
    }

    private static String gitText(File directory, String... args)
            throws Exception {
        return new String(git(directory, args), StandardCharsets.UTF_8);
    }

    private static byte[] git(File directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(directory)
                .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = process.getInputStream().read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        if (process.waitFor() != 0) {
            throw new IOException(output.toString(StandardCharsets.UTF_8.name()));
        }
        return output.toByteArray();
    }

    private static String textFor(List<File> files, String fragment)
            throws IOException {
        for (File file : files) {
            if (unix(file).contains(fragment)) {
                return read(file);
            }
        }
        return "";
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String unix(File file) {
        return file.getAbsolutePath().replace(File.separatorChar, '/');
    }

    private static String fileEvidenceJson(List<FileEvidence> files) {
        return files.stream().map(item -> "{\"path\":" + quote(unix(item.file))
                + ",\"bytes\":" + item.bytes + ",\"sha256\":"
                + quote(item.sha256) + "}").collect(Collectors.joining(",", "[", "]"));
    }

    private static String jsonObjects(List<String> values) {
        return values.stream().filter(value -> !value.isEmpty())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String jsonStrings(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(quote(value));
        }
        return "[" + String.join(",", result) + "]";
    }

    private static String jsonOrEmpty(String value) {
        return value.trim().isEmpty() ? "{}" : value.trim();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static String sha256(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    private static final class TestTotals {
        private int executed;
        private int passed;
        private int failed;
        private int skipped;
        private int unclassified;
    }

    private static final class ConformanceTotals {
        private final int vectors;
        private final int requiredVectors;
        private final int behavior;
        private final int requiredBehavior;
        private final int gas;
        private final int requiredGas;
        private final int operators;
        private final int requiredOperators;

        private ConformanceTotals(int vectors, int requiredVectors,
                int behavior, int requiredBehavior, int gas, int requiredGas,
                int operators, int requiredOperators) {
            this.vectors = vectors;
            this.requiredVectors = requiredVectors;
            this.behavior = behavior;
            this.requiredBehavior = requiredBehavior;
            this.gas = gas;
            this.requiredGas = requiredGas;
            this.operators = operators;
            this.requiredOperators = requiredOperators;
        }

        private boolean complete() {
            return vectors == 60 && requiredVectors == 60
                    && behavior == 105 && requiredBehavior == 105
                    && gas == 30 && requiredGas == 30
                    && operators == 86 && requiredOperators == 86;
        }

        private String json() {
            return "{\"normativeVectors\":" + counts(vectors, requiredVectors)
                    + ",\"behaviorFixtures\":" + counts(behavior, requiredBehavior)
                    + ",\"gasMicrofixtures\":" + counts(gas, requiredGas)
                    + ",\"operators\":" + counts(operators, requiredOperators)
                    + "}";
        }

        private static String counts(int actual, int required) {
            return "{\"executedAndPassing\":" + actual
                    + ",\"required\":" + required + "}";
        }
    }

    private static final class GitState {
        private final String head;
        private final boolean dirty;
        private final String statusSha256;

        private GitState(String head, boolean dirty, String statusSha256) {
            this.head = head;
            this.dirty = dirty;
            this.statusSha256 = statusSha256;
        }
    }

    private static final class LegacyTotals {
        private final int lines;
        private final int files;

        private LegacyTotals(int lines, int files) {
            this.lines = lines;
            this.files = files;
        }
    }

    private static final class FileEvidence {
        private final File file;
        private final long bytes;
        private final String sha256;

        private FileEvidence(File file, long bytes, String sha256) {
            this.file = file;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class BytecodeEvidence {
        private final boolean passed;
        private final int classCount;
        private final int maximumMajor;

        private BytecodeEvidence(boolean passed, int classCount,
                int maximumMajor) {
            this.passed = passed;
            this.classCount = classCount;
            this.maximumMajor = maximumMajor;
        }
    }
}
