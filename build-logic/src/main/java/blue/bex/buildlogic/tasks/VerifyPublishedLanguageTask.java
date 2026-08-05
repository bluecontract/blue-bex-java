package blue.bex.buildlogic.tasks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Authenticates published Language evidence against a reviewed inspection.
 *
 * <p>Caller-supplied coordinates and digests are assertions, not evidence.
 * This task only passes when they agree with the source-controlled inspection,
 * the resolved artifact bytes, and a same-source local/published differential
 * report. Missing publication inputs remain explicitly {@code not-executed}.
 */
public abstract class VerifyPublishedLanguageTask extends DefaultTask {
    private static final String COMPATIBLE_STATUS =
            "compatible-with-final-hosted-adapter";

    @Input
    public abstract Property<Boolean> getRequired();

    @Input
    @Optional
    public abstract Property<String> getCoordinate();

    @Input
    @Optional
    public abstract Property<String> getArtifactSha256();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getInspectionFile();

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getArtifacts();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getDifferentialReport();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void verify() {
        try {
            File inspectionFile = getInspectionFile().get().getAsFile();
            Properties inspection = new Properties();
            try (Reader reader = Files.newBufferedReader(
                    inspectionFile.toPath(), StandardCharsets.UTF_8)) {
                inspection.load(reader);
            }

            String reviewedStatus = property(inspection, "status");
            String reviewedCoordinate = property(inspection, "coordinate");
            String reviewedSha = property(inspection, "artifact.sha256");
            String sourceCommit = property(inspection, "source.commit");
            String sourceTag = property(inspection, "source.tag");
            String assertedCoordinate = getCoordinate().getOrElse("").trim();
            String assertedSha = getArtifactSha256().getOrElse("").trim();
            String configuredCoordinate = assertedCoordinate.isEmpty()
                    ? reviewedCoordinate : assertedCoordinate;
            String configuredSha = assertedSha.isEmpty()
                    ? reviewedSha : assertedSha;

            List<File> artifacts = new ArrayList<>(getArtifacts().getFiles());
            artifacts.removeIf(file -> !file.isFile());
            artifacts.sort(Comparator.comparing(File::getName)
                    .thenComparing(File::getAbsolutePath));
            List<ArtifactEvidence> artifactEvidence = new ArrayList<>();
            for (File artifact : artifacts) {
                artifactEvidence.add(new ArtifactEvidence(
                        artifact, sha256(artifact)));
            }

            List<String> reasons = new ArrayList<>();
            boolean reviewedCompatible = COMPATIBLE_STATUS.equals(reviewedStatus);
            if (!reviewedCompatible) {
                reasons.add("reviewed published API status is " + reviewedStatus);
            }
            boolean reviewedIdentityComplete = reviewedCoordinate.matches(
                    "[^:]+:[^:]+:[^:]+")
                    && reviewedSha.matches("[0-9a-f]{64}")
                    && sourceCommit.matches("[0-9a-f]{40}")
                    && sourceTag.matches("v?[A-Za-z0-9][A-Za-z0-9._-]*");
            if (!reviewedIdentityComplete) {
                reasons.add("reviewed coordinate, artifact hash, or source identity "
                        + "is incomplete");
            }

            boolean configured = !configuredCoordinate.isEmpty()
                    && !configuredSha.isEmpty();
            boolean assertionsMatch = configured
                    && reviewedCoordinate.equals(configuredCoordinate)
                    && reviewedSha.equals(configuredSha);
            if (configured && !assertionsMatch) {
                reasons.add("caller assertions differ from reviewed publication "
                        + "identity");
            }

            boolean artifactHashMatches = artifactEvidence.stream()
                    .anyMatch(item -> reviewedSha.equals(item.sha256));
            if (!artifacts.isEmpty() && !artifactHashMatches) {
                reasons.add("no resolved artifact matches the reviewed SHA-256");
            }
            boolean reviewedApiClaimsPass = reviewedApiClaimsPass(
                    inspection, artifacts, reasons);

            String differential = getDifferentialReport().isPresent()
                    && getDifferentialReport().get().getAsFile().isFile()
                    ? read(getDifferentialReport().get().getAsFile()) : "";
            Map<String, Object> differentialEvidence =
                    ReleaseEvidenceJson.parseOrEmpty(differential);
            boolean differentialPassed =
                    ReleaseEvidenceJson.differentialPassed(
                            differentialEvidence, null);
            if (!differential.isEmpty() && !differentialPassed) {
                reasons.add("local/published semantic and exact-gas differential "
                        + "did not pass");
            }

            boolean inputsPresent = configured && !artifacts.isEmpty()
                    && !differential.isEmpty();
            boolean passed = reviewedCompatible && reviewedIdentityComplete
                    && assertionsMatch && artifactHashMatches
                    && reviewedApiClaimsPass && differentialPassed;
            String status;
            if (!reviewedCompatible) {
                status = "incompatible";
            } else if (!inputsPresent) {
                status = "not-executed";
                reasons.add("resolved artifacts and same-run differential evidence "
                        + "are required");
            } else {
                status = passed ? "passed" : "failed";
            }

            String json = "{\n"
                    + "  \"schema\": \"blue-bex-published-language/2.0\",\n"
                    + "  \"status\": " + quote(status) + ",\n"
                    + "  \"coordinate\": " + quote(reviewedCoordinate) + ",\n"
                    + "  \"artifactSha256\": " + quote(reviewedSha) + ",\n"
                    + "  \"sourceCommit\": " + quote(sourceCommit) + ",\n"
                    + "  \"sourceTag\": " + quote(sourceTag) + ",\n"
                    + "  \"inspection\": {\"path\":"
                    + quote(unix(inspectionFile)) + ",\"sha256\":"
                    + quote(sha256(inspectionFile)) + ",\"reviewedStatus\":"
                    + quote(reviewedStatus) + "},\n"
                    + "  \"configuredAssertionsMatch\": "
                    + assertionsMatch + ",\n"
                    + "  \"resolvedArtifacts\": "
                    + artifactsJson(artifactEvidence) + ",\n"
                    + "  \"apiInspectionPassed\": "
                    + reviewedApiClaimsPass + ",\n"
                    + "  \"differentialStatus\": "
                    + quote(differentialPassed ? "passed" : "not-executed")
                    + ",\n"
                    + "  \"blockers\": " + jsonStrings(reasons) + "\n"
                    + "}\n";
            File output = getOutputFile().get().getAsFile();
            output.getParentFile().mkdirs();
            Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
            if (getRequired().get() && !passed) {
                throw new GradleException(
                        "Published Blue Language evidence is not release-ready; "
                                + "see " + output);
            }
        } catch (GradleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GradleException(
                    "Cannot verify published Blue Language evidence", exception);
        }
    }

    private static boolean reviewedApiClaimsPass(Properties inspection,
            List<File> artifacts, List<String> reasons) throws IOException {
        boolean passed = "passed".equals(property(
                inspection, "standaloneCompile"));
        if (!passed) {
            reasons.add("reviewed standalone compile did not pass");
        }
        for (String key : inspection.stringPropertyNames().stream()
                .sorted().collect(Collectors.toList())) {
            if ((key.startsWith("class.") || key.startsWith("method.")
                    || key.startsWith("visibility."))
                    && !"true".equals(inspection.getProperty(key))) {
                passed = false;
                reasons.add("reviewed API claim is false: " + key);
            }
            if (key.startsWith("class.")
                    && "true".equals(inspection.getProperty(key))) {
                String entry = key.substring("class.".length())
                        .replace('.', '/') + ".class";
                if (!containsJarEntry(artifacts, entry)) {
                    passed = false;
                    reasons.add("resolved artifacts do not contain " + entry);
                }
            }
        }
        return passed;
    }

    private static boolean containsJarEntry(List<File> files, String name)
            throws IOException {
        for (File file : files) {
            if (!file.getName().endsWith(".jar")) {
                continue;
            }
            try (JarFile jar = new JarFile(file)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    if (name.equals(entries.nextElement().getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String property(Properties properties, String key) {
        return properties.getProperty(key, "").trim();
    }

    private static String artifactsJson(List<ArtifactEvidence> artifacts) {
        return artifacts.stream().map(item -> "{\"path\":"
                + quote(unix(item.file)) + ",\"bytes\":" + item.file.length()
                + ",\"sha256\":" + quote(item.sha256) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String jsonStrings(List<String> values) {
        return values.stream().map(VerifyPublishedLanguageTask::quote)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
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

    private static String unix(File file) {
        return file.getAbsolutePath().replace(File.separatorChar, '/');
    }

    private static String quote(String value) {
        return StrictJson.quote(value);
    }

    private static final class ArtifactEvidence {
        private final File file;
        private final String sha256;

        private ArtifactEvidence(File file, String sha256) {
            this.file = file;
            this.sha256 = sha256;
        }
    }
}
