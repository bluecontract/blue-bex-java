package blue.bex.buildlogic.tasks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;

/** Aggregates same-run modernization evidence without inventing pass counts. */
public abstract class GenerateModernizationReportTask extends DefaultTask {
    @InputDirectory
    public abstract DirectoryProperty getTestResultsDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getEvidenceFiles();

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getArtifacts();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Input
    public abstract Property<Boolean> getFailOnIncomplete();

    @OutputFile
    public abstract RegularFileProperty getJsonOutputFile();

    @OutputFile
    public abstract RegularFileProperty getMarkdownOutputFile();

    @TaskAction
    public void generate() {
        try {
            TestTotals tests = readTests(getTestResultsDirectory().get().getAsFile());
            List<FileEvidence> evidence = describe(getEvidenceFiles());
            List<FileEvidence> artifacts = describe(getArtifacts());
            String conformance = textFor(
                    evidence, "/bex-conformance/report.json");
            ConformanceTotals totals = conformanceTotals(conformance);
            String architecture = textFor(evidence, "architecture");
            String published = textFor(evidence, "published-language");
            String jmh = textFor(evidence, "jmh/results");
            String jmhEnvironment = textFor(evidence, "jmh/environment");
            String working = textFor(evidence, "latest-language-migration/final");
            boolean architecturePassed = containsStatus(architecture, "passed")
                    && integerField(architecture, "moduleCycles") == 0
                    && integerField(architecture,
                    "packageSccsLargerThanOne") == 0
                    && integerField(architecture, "splitPackageCount") == 0
                    && integerField(architecture, "undeclaredModuleEdges") == 0;
            boolean conformancePassed = totals.complete();
            boolean dependencyEvidencePassed = sectionPassed(
                    conformance, "resolution");
            boolean conformanceReleaseReady = booleanField(
                    conformance, "releaseReady");
            boolean semanticEvidencePassed = sectionPassed(
                    conformance, "representationMatrixResult")
                    && sectionPassed(conformance, "hostedLocalLimitCapability")
                    && sectionPassed(conformance,
                    "cyclicProofUnavailabilityCapability")
                    && sectionPassed(conformance,
                    "semanticBoundaryInvocationEvidence")
                    && sectionPassed(conformance, "ledgerLifecycleEvidence")
                    && sectionPassed(conformance, "gasExhaustionEvidence")
                    && sectionPassed(conformance, "cyclicProofEvidence")
                    && sectionPassed(conformance, "intrinsicEvidence")
                    && sectionPassed(conformance,
                    "referenceEvidenceClassificationEvidence");
            boolean benchmarkPresent = seriousJmhEvidence(jmh)
                    && jmhEnvironment.contains(
                    "\"schema\": \"blue-bex-jmh-environment/1.0\"")
                    && jmhEnvironment.contains("\"profilers\":[\"gc\"]");
            boolean apiPresent = evidence.stream().anyMatch(item ->
                    item.path.contains("public-api-classification"))
                    && evidence.stream().anyMatch(item ->
                    item.path.contains("latest-language-api-migration"))
                    && working.contains("\"apiEvidence\":")
                    && working.matches("(?s).*\"apiEvidence\"\\s*:\\s*\\{"
                    + ".*?\"status\"\\s*:\\s*\"passed\".*");
            boolean workingPassed = working.contains(
                    "\"workingReady\": true");
            boolean documentationPresent = documentationCount(evidence) == 14;
            SourceMetrics sourceMetrics = sourceMetrics(getSourceFiles());
            boolean modernizationReady = tests.executed > 0
                    && tests.failed == 0 && tests.skipped == 0
                    && tests.unclassified == 0
                    && architecturePassed && conformancePassed
                    && dependencyEvidencePassed
                    && semanticEvidencePassed
                    && tests.concurrencyPassed && tests.propertiesPassed
                    && benchmarkPresent && apiPresent && workingPassed
                    && documentationPresent && sourceMetrics.fileCount > 0
                    && !artifacts.isEmpty();
            String publishedStatus = stringField(published, "status");
            boolean releaseReady = modernizationReady
                    && "passed".equals(publishedStatus)
                    && conformanceReleaseReady;

            String json = "{\n"
                    + "  \"schema\": \"blue-bex-modernization-report/1.0\",\n"
                    + "  \"tests\": {\"executed\":" + tests.executed
                    + ",\"failed\":" + tests.failed
                    + ",\"skipped\":" + tests.skipped
                    + ",\"unclassified\":" + tests.unclassified + "},\n"
                    + "  \"conformance\": " + totals.json() + ",\n"
                    + "  \"dependencyEvidenceStatus\": "
                    + quote(dependencyEvidencePassed ? "passed" : "failed")
                    + ",\n"
                    + "  \"conformanceReleaseReady\": "
                    + conformanceReleaseReady + ",\n"
                    + "  \"semanticEvidenceStatus\": "
                    + quote(semanticEvidencePassed ? "passed" : "failed") + ",\n"
                    + "  \"architectureStatus\": "
                    + quote(architecturePassed ? "passed" : "failed") + ",\n"
                    + "  \"architecture\": " + jsonOrEmpty(architecture) + ",\n"
                    + "  \"benchmarkEvidence\": " + benchmarkPresent + ",\n"
                    + "  \"benchmarkEnvironment\": "
                    + jsonOrEmpty(jmhEnvironment) + ",\n"
                    + "  \"apiEvidence\": " + apiPresent + ",\n"
                    + "  \"workingEvidence\": " + workingPassed + ",\n"
                    + "  \"documentationGuideCount\": "
                    + documentationCount(evidence) + ",\n"
                    + "  \"sourceMetrics\": " + sourceMetrics.json() + ",\n"
                    + "  \"concurrencyProperties\": {\"concurrency\":"
                    + tests.concurrencyPassed + ",\"properties\":"
                    + tests.propertiesPassed + "},\n"
                    + "  \"evidence\": " + fileEvidenceJson(evidence) + ",\n"
                    + "  \"artifacts\": " + fileEvidenceJson(artifacts) + ",\n"
                    + "  \"modernizationReady\": " + modernizationReady + ",\n"
                    + "  \"publishedModeStatus\": "
                    + quote(publishedStatus.isEmpty()
                            ? "not-executed" : publishedStatus) + ",\n"
                    + "  \"releaseReady\": " + releaseReady + "\n"
                    + "}\n";
            File jsonFile = getJsonOutputFile().get().getAsFile();
            jsonFile.getParentFile().mkdirs();
            Files.write(jsonFile.toPath(), json.getBytes(StandardCharsets.UTF_8));

            String markdown = "# BEX modernization evidence\n\n"
                    + "- Tests: " + tests.executed + " executed, "
                    + tests.failed + " failed, " + tests.skipped + " skipped\n"
                    + "- Normative vectors: " + totals.vectors + "/"
                    + totals.requiredVectors + "\n"
                    + "- Behavior fixtures: " + totals.behavior + "/"
                    + totals.requiredBehavior + "\n"
                    + "- Gas microfixtures: " + totals.gas + "/"
                    + totals.requiredGas + "\n"
                    + "- Operators: " + totals.operators + "/"
                    + totals.requiredOperators + "\n"
                    + "- Architecture: "
                    + (architecturePassed ? "passed" : "failed") + "\n"
                    + "- JMH benchmark evidence: "
                    + (benchmarkPresent ? "serious campaign present"
                    : "missing or incomplete") + "\n"
                    + "- Concurrency/property gates: "
                    + tests.concurrencyPassed + "/" + tests.propertiesPassed + "\n"
                    + "- Developer guides: " + documentationCount(evidence)
                    + "/14\n"
                    + "- Modernization ready: " + modernizationReady + "\n"
                    + "- Public release ready: " + releaseReady + "\n\n"
                    + (releaseReady ? "Published dependency evidence is complete."
                    : "Published release remains fail-closed until matching "
                            + "Language artifacts and published repeatability "
                            + "evidence exist.")
                    + "\n";
            File markdownFile = getMarkdownOutputFile().get().getAsFile();
            markdownFile.getParentFile().mkdirs();
            Files.write(markdownFile.toPath(),
                    markdown.getBytes(StandardCharsets.UTF_8));

            if (getFailOnIncomplete().get() && !modernizationReady) {
                throw new GradleException(
                        "BEX modernization evidence is incomplete; see " + jsonFile);
            }
        } catch (GradleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GradleException("Cannot generate modernization report", exception);
        }
    }

    private static boolean seriousJmhEvidence(String json) {
        if (!json.trim().startsWith("[")) {
            return false;
        }
        int benchmarks = matchCount(json,
                Pattern.compile("\\\"benchmark\\\"\\s*:"));
        if (benchmarks == 0
                || matchCount(json, Pattern.compile(
                "\\\"forks\\\"\\s*:\\s*2(?:\\s*[,}])")) != benchmarks
                || matchCount(json, Pattern.compile(
                "\\\"warmupIterations\\\"\\s*:\\s*3(?:\\s*[,}])"))
                != benchmarks
                || matchCount(json, Pattern.compile(
                "\\\"measurementIterations\\\"\\s*:\\s*5(?:\\s*[,}])"))
                != benchmarks
                || matchCount(json, Pattern.compile(
                "\\\"warmupTime\\\"\\s*:\\s*\\\"250 ms\\\""))
                != benchmarks
                || matchCount(json, Pattern.compile(
                "\\\"measurementTime\\\"\\s*:\\s*\\\"250 ms\\\""))
                != benchmarks) {
            return false;
        }
        String number = "-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"
                + "(?:[eE][+-]?[0-9]+)?";
        Pattern finitePrimary = Pattern.compile(
                "\\\"primaryMetric\\\"\\s*:\\s*\\{[^}]*?"
                        + "\\\"score\\\"\\s*:\\s*" + number + "[^}]*?"
                        + "\\\"scoreConfidence\\\"\\s*:\\s*\\[\\s*"
                        + number + "\\s*,\\s*" + number + "\\s*\\]",
                Pattern.DOTALL);
        Pattern finiteAllocation = Pattern.compile(
                "\\\"gc\\.alloc\\.rate\\.norm\\\"\\s*:\\s*\\{[^}]*?"
                        + "\\\"score\\\"\\s*:\\s*" + number + "[^}]*?"
                        + "\\\"scoreConfidence\\\"\\s*:\\s*\\[\\s*"
                        + number + "\\s*,\\s*" + number + "\\s*\\]",
                Pattern.DOTALL);
        return matchCount(json, finitePrimary) == benchmarks
                && matchCount(json, Pattern.compile(
                "\\\"gc\\.alloc\\.rate\\.norm\\\"\\s*:")) == benchmarks
                && matchCount(json, finiteAllocation) == benchmarks;
    }

    private static int matchCount(String text, Pattern pattern) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static TestTotals readTests(File directory) throws Exception {
        TestTotals totals = new TestTotals();
        if (!directory.isDirectory()) {
            return totals;
        }
        Files.walk(directory.toPath())
                .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                .filter(path -> path.toString().endsWith(".xml"))
                .sorted()
                .forEach(path -> {
                    try {
                        Element root = DocumentBuilderFactory.newInstance()
                                .newDocumentBuilder().parse(path.toFile())
                                .getDocumentElement();
                        int declared = integer(root, "tests");
                        int cases = root.getElementsByTagName("testcase")
                                .getLength();
                        totals.executed += cases;
                        totals.failed += integer(root, "failures")
                                + integer(root, "errors");
                        totals.skipped += integer(root, "skipped");
                        totals.unclassified += Math.max(0, declared - cases);
                        String name = path.getFileName().toString();
                        boolean passed = integer(root, "failures") == 0
                                && integer(root, "errors") == 0
                                && integer(root, "skipped") == 0
                                && cases > 0;
                        if (name.contains("BexConcurrentEngineIsolationTest")) {
                            totals.concurrencyPassed |= passed;
                        }
                        if (name.contains("BexModernizationPropertyTest")) {
                            totals.propertiesPassed |= passed;
                        }
                    } catch (Exception exception) {
                        throw new ReportReadException(exception);
                    }
                });
        return totals;
    }

    private static int integer(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    private static List<FileEvidence> describe(ConfigurableFileCollection files)
            throws IOException, NoSuchAlgorithmException {
        List<File> ordered = new ArrayList<>(files.getFiles());
        ordered.removeIf(file -> !file.isFile());
        ordered.sort(Comparator.comparing(File::getName)
                .thenComparing(File::getAbsolutePath));
        List<FileEvidence> result = new ArrayList<>();
        for (File file : ordered) {
            result.add(new FileEvidence(
                    file.getPath().replace(File.separatorChar, '/'),
                    file.length(), sha256(file)));
        }
        return result;
    }

    private static String textFor(List<FileEvidence> files, String fragment)
            throws IOException {
        for (FileEvidence item : files) {
            if (item.path.contains(fragment)) {
                return new String(Files.readAllBytes(new File(item.path).toPath()),
                        StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static boolean containsStatus(String text, String status) {
        return text.matches("(?s).*\\\"status\\\"\\s*:\\s*\\\""
                + Pattern.quote(status) + "\\\".*");
    }

    private static int integerField(String text, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*(-?\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String stringField(String text, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean booleanField(String text, String field) {
        return text.matches("(?s).*\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*true.*");
    }

    private static boolean sectionPassed(String text, String name) {
        String section = objectSection(text, name);
        return containsStatus(section, "passed")
                && !containsStatus(section, "failed")
                && !containsStatus(section, "not-executed");
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

    private static int documentationCount(List<FileEvidence> evidence) {
        String[] names = {
            "start-here.md", "architecture.md", "program-model.md",
            "values-and-identity.md", "compiler-and-ir.md",
            "runtime-and-context.md", "blue-output-boundary.md",
            "gas-and-exhaustion.md", "intrinsics.md",
            "adding-an-operator.md", "contracts-hosting.md",
            "migrating-to-modular-blue-language.md", "conformance.md",
            "release.md"
        };
        int count = 0;
        for (String name : names) {
            if (evidence.stream().anyMatch(item ->
                    item.path.endsWith("/docs/" + name))) {
                count++;
            }
        }
        return count;
    }

    private static SourceMetrics sourceMetrics(ConfigurableFileCollection files)
            throws IOException {
        SourceMetrics metrics = new SourceMetrics();
        for (File file : files.getFiles()) {
            if (!file.isFile() || !file.getName().endsWith(".java")) {
                continue;
            }
            String text = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            metrics.fileCount++;
            metrics.lineCount += text.split("\\R").length;
            metrics.publicTypeCount += matches(text,
                    "(?m)^public\\s+(?:abstract\\s+|final\\s+)?"
                            + "(?:class|interface|enum)\\s+");
            metrics.publicMethodCount += matches(text,
                    "(?m)^\\s*public\\s+(?!class|interface|enum)"
                            + "[^=;{}]+\\([^;{}]*\\)\\s*(?:\\{|;)");
        }
        return metrics;
    }

    private static int matches(String text, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String jsonOrEmpty(String text) {
        return text.trim().isEmpty() ? "{}" : text.trim();
    }

    private static ConformanceTotals conformanceTotals(String text) {
        ConformanceTotals totals = new ConformanceTotals();
        totals.vectors = pair(text, "normativeVectors", "executedAndPassing");
        totals.requiredVectors = pair(text, "normativeVectors", "required");
        totals.behavior = pair(text, "behaviorFixtures", "executedAndPassing");
        totals.requiredBehavior = pair(text, "behaviorFixtures", "required");
        totals.gas = pair(text, "gasMicrofixtures", "executedAndPassing");
        totals.requiredGas = pair(text, "gasMicrofixtures", "required");
        totals.operators = pair(text, "operators", "executedAndPassing");
        totals.requiredOperators = pair(text, "operators", "required");
        return totals;
    }

    private static int pair(String text, String section, String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(section)
                + "\\\"\\s*:\\s*\\{([^{}]|\\{[^{}]*\\})*?\\\""
                + Pattern.quote(field) + "\\\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : -1;
    }

    private static String fileEvidenceJson(List<FileEvidence> files) {
        List<String> values = new ArrayList<>();
        for (FileEvidence item : files) {
            values.add("{\"path\":" + quote(item.path)
                    + ",\"bytes\":" + item.bytes
                    + ",\"sha256\":" + quote(item.sha256) + "}");
        }
        return "[" + String.join(",", values) + "]";
    }

    private static String sha256(File file)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static final class TestTotals {
        private int executed;
        private int failed;
        private int skipped;
        private int unclassified;
        private boolean concurrencyPassed;
        private boolean propertiesPassed;
    }

    private static final class SourceMetrics {
        private int fileCount;
        private int lineCount;
        private int publicTypeCount;
        private int publicMethodCount;

        private String json() {
            return "{\"productionJavaFiles\":" + fileCount
                    + ",\"productionJavaLines\":" + lineCount
                    + ",\"publicTopLevelTypes\":" + publicTypeCount
                    + ",\"publicMethods\":" + publicMethodCount + "}";
        }
    }

    private static final class FileEvidence {
        private final String path;
        private final long bytes;
        private final String sha256;

        private FileEvidence(String path, long bytes, String sha256) {
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class ConformanceTotals {
        private int vectors = -1;
        private int requiredVectors = -1;
        private int behavior = -1;
        private int requiredBehavior = -1;
        private int gas = -1;
        private int requiredGas = -1;
        private int operators = -1;
        private int requiredOperators = -1;

        private boolean complete() {
            return vectors == 60 && requiredVectors == 60
                    && behavior == 105 && requiredBehavior == 105
                    && gas == 30 && requiredGas == 30
                    && operators == 86 && requiredOperators == 86;
        }

        private String json() {
            return "{\"vectors\":{" + counts(vectors, requiredVectors)
                    + "},\"behaviorFixtures\":{" + counts(behavior, requiredBehavior)
                    + "},\"gasMicrofixtures\":{" + counts(gas, requiredGas)
                    + "},\"operators\":{" + counts(operators, requiredOperators)
                    + "}}";
        }

        private static String counts(int executed, int required) {
            return "\"executedAndPassing\":" + executed
                    + ",\"required\":" + required;
        }
    }

    private static final class ReportReadException extends RuntimeException {
        private ReportReadException(Exception cause) {
            super(cause);
        }
    }
}
