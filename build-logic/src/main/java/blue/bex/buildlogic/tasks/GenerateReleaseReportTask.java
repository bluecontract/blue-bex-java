package blue.bex.buildlogic.tasks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Writes and enforces the single strict, fail-closed BEX release decision. */
public abstract class GenerateReleaseReportTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getModernizationReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getPublishedLanguageReport();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getIndependentCleanBuildReport();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRepeatabilityReport();

    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    @Input
    public abstract Property<String> getExpectedReleaseTag();

    @OutputFile
    public abstract RegularFileProperty getJsonOutputFile();

    @OutputFile
    public abstract RegularFileProperty getMarkdownOutputFile();

    @TaskAction
    public void generate() {
        try {
            String modernization = read(
                    getModernizationReport().get().getAsFile());
            String published = read(
                    getPublishedLanguageReport().get().getAsFile());
            String independent = optionalText(getIndependentCleanBuildReport());
            String repeatability = optionalText(getRepeatabilityReport());
            Map<String, Object> modernizationEvidence =
                    ReleaseEvidenceJson.parseOrEmpty(modernization);
            Map<String, Object> publishedEvidence =
                    ReleaseEvidenceJson.parseOrEmpty(published);
            Map<String, Object> independentEvidence =
                    ReleaseEvidenceJson.parseOrEmpty(independent);
            Map<String, Object> repeatabilityEvidence =
                    ReleaseEvidenceJson.parseOrEmpty(repeatability);
            File repository = getRepositoryDirectory().get().getAsFile();
            String commit = gitText(repository, "rev-parse", "HEAD").trim();
            boolean clean = gitBytes(repository, "status", "--porcelain", "-z")
                    .length == 0;
            List<String> tags = lines(gitText(
                    repository, "tag", "--points-at", "HEAD"));
            boolean exactTag = tags.contains(getExpectedReleaseTag().get());

            boolean modernizationReady =
                    ReleaseEvidenceJson.modernizationPassed(
                            modernizationEvidence);
            boolean conformanceReleaseReady =
                    ReleaseEvidenceJson.conformanceReleasePassed(
                            modernizationEvidence);
            boolean publishedReady =
                    ReleaseEvidenceJson.publishedLanguagePassed(
                            publishedEvidence);
            boolean independentReady =
                    ReleaseEvidenceJson.independentBuildsPassed(
                            independentEvidence, commit);
            boolean repeatabilityReady =
                    ReleaseEvidenceJson.publishedRepeatabilityPassed(
                            repeatabilityEvidence, commit);

            List<String> blockers = new ArrayList<>();
            addBlocker(blockers, modernizationReady,
                    "modernization evidence is not ready");
            addBlocker(blockers, conformanceReleaseReady,
                    "detailed conformance report is not release-ready");
            addBlocker(blockers, publishedReady,
                    "matching published Language artifacts are not authenticated");
            addBlocker(blockers, independentReady,
                    "two isolated clean-build pairs are absent or do not match");
            addBlocker(blockers, repeatabilityReady,
                    "published semantic and exact-gas repeatability is absent");
            addBlocker(blockers, clean,
                    "BEX source checkout is dirty");
            addBlocker(blockers, exactTag,
                    "HEAD is not tagged exactly " + getExpectedReleaseTag().get());
            boolean releaseReady = blockers.isEmpty();

            String json = "{\n"
                    + "  \"schema\": \"blue-bex-strict-release/2.0\",\n"
                    + "  \"dependencyPolicy\": \"published-only\",\n"
                    + "  \"bexCommit\": " + quote(commit) + ",\n"
                    + "  \"sourceState\": {\"clean\":" + clean
                    + ",\"expectedTag\":"
                    + quote(getExpectedReleaseTag().get())
                    + ",\"tagsAtHead\":" + jsonStrings(tags)
                    + ",\"exactReleaseTag\":" + exactTag + "},\n"
                    + "  \"modernizationStatus\": "
                    + quote(modernizationReady ? "passed" : "failed") + ",\n"
                    + "  \"conformanceReleaseStatus\": "
                    + quote(conformanceReleaseReady ? "passed" : "failed")
                    + ",\n"
                    + "  \"publishedLanguageStatus\": "
                    + quote(ReleaseEvidenceJson.stringOrEmpty(
                    publishedEvidence, "status")) + ",\n"
                    + "  \"independentCleanBuildStatus\": "
                    + quote(independentReady ? "passed" : "not-executed")
                    + ",\n"
                    + "  \"publishedRepeatabilityStatus\": "
                    + quote(repeatabilityReady ? "passed" : "not-executed")
                    + ",\n"
                    + "  \"blockers\": " + jsonStrings(blockers) + ",\n"
                    + "  \"releaseReady\": " + releaseReady + "\n"
                    + "}\n";
            File jsonFile = getJsonOutputFile().get().getAsFile();
            jsonFile.getParentFile().mkdirs();
            Files.write(jsonFile.toPath(), json.getBytes(StandardCharsets.UTF_8));

            String markdown = "# BEX strict release evidence\n\n"
                    + "- Commit: `" + commit + "`\n"
                    + "- Modernization: " + pass(modernizationReady) + "\n"
                    + "- Published Language: " + pass(publishedReady) + "\n"
                    + "- Independent clean builds: " + pass(independentReady)
                    + "\n"
                    + "- Published semantic/gas repeatability: "
                    + pass(repeatabilityReady) + "\n"
                    + "- Clean exact tagged source: "
                    + pass(clean && exactTag) + "\n"
                    + "- `releaseReady`: `" + releaseReady + "`\n\n"
                    + (blockers.isEmpty() ? "No blockers."
                    : "Blockers:\n\n" + blockers.stream()
                            .map(value -> "- " + value)
                            .collect(Collectors.joining("\n"))) + "\n";
            File markdownFile = getMarkdownOutputFile().get().getAsFile();
            markdownFile.getParentFile().mkdirs();
            Files.write(markdownFile.toPath(),
                    markdown.getBytes(StandardCharsets.UTF_8));

            if (!releaseReady) {
                throw new GradleException(
                        "BEX public release remains fail-closed; see " + jsonFile);
            }
        } catch (GradleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GradleException("Cannot generate strict release report",
                    exception);
        }
    }

    private static String optionalText(RegularFileProperty property)
            throws IOException {
        return property.isPresent() && property.get().getAsFile().isFile()
                ? read(property.get().getAsFile()) : "";
    }

    private static void addBlocker(List<String> blockers, boolean passed,
            String blocker) {
        if (!passed) {
            blockers.add(blocker);
        }
    }

    private static String pass(boolean value) {
        return value ? "passed" : "not passed";
    }

    private static List<String> lines(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? new ArrayList<>()
                : Arrays.asList(trimmed.split("\\R"));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
    }

    private static String gitText(File directory, String... arguments)
            throws IOException, InterruptedException {
        return new String(gitBytes(directory, arguments), StandardCharsets.UTF_8);
    }

    private static byte[] gitBytes(File directory, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
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

    private static String jsonStrings(List<String> values) {
        return values.stream().map(GenerateReleaseReportTask::quote)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String quote(String value) {
        return StrictJson.quote(value);
    }
}
