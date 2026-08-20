package blue.bex.buildlogic.tasks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Emits exact, BEX-owned dependency provenance for one runtime module. */
public abstract class GenerateDependencyEvidenceTask extends DefaultTask {
    @Classpath
    public abstract ConfigurableFileCollection getArtifacts();

    @Input
    public abstract Property<String> getMode();

    @Input
    public abstract Property<String> getModuleName();

    @Input
    public abstract Property<String> getDeclaredLanguageVersion();

    @Input
    public abstract ListProperty<String> getResolvedComponents();

    @Input
    public abstract Property<Boolean> getExactVersionCacheInitiallyAbsent();

    @Input
    @Optional
    public abstract Property<String> getStagedRepositoryPath();

    @Internal
    public abstract DirectoryProperty getLanguageCheckout();

    @Internal
    public abstract DirectoryProperty getBexCheckout();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        try {
            List<File> artifacts = new ArrayList<>(getArtifacts().getFiles());
            artifacts.sort(Comparator.comparing(File::getName)
                    .thenComparing(File::getAbsolutePath));
            List<String> artifactJson = new ArrayList<>();
            for (File artifact : artifacts) {
                if (!artifact.isFile() || !artifact.getName().endsWith(".jar")) {
                    continue;
                }
                artifactJson.add("    {\"name\":" + quote(artifact.getName())
                        + ",\"path\":" + quote(unix(artifact))
                        + ",\"bytes\":" + artifact.length()
                        + ",\"sha256\":" + quote(sha256(artifact)) + "}");
            }
            String mode = getMode().get();
            boolean stagedRepositoryMode =
                    "staged-repository".equals(mode);
            String stagedRepository = getStagedRepositoryPath().isPresent()
                    ? getStagedRepositoryPath().get().trim() : "";
            List<String> stagedArtifactJson = new ArrayList<>();
            boolean stagedArtifactsMatch = stagedRepositoryMode;
            if (stagedRepositoryMode) {
                File repository = new File(stagedRepository).getCanonicalFile();
                String version = getDeclaredLanguageVersion().get();
                String[] focusedModules = {
                        "blue-language-model",
                        "blue-language-core",
                        "blue-language-mapping",
                        "blue-contracts-core",
                        "blue-language-java"
                };
                for (String module : focusedModules) {
                    File stagedArtifact = new File(
                            repository,
                            "blue/language/" + module + "/" + version + "/"
                                    + module + "-" + version + ".jar");
                    File resolvedArtifact = artifacts.stream()
                            .filter(candidate -> candidate.getName().equals(
                                    stagedArtifact.getName()))
                            .findFirst()
                            .orElse(null);
                    boolean matches = stagedArtifact.isFile()
                            && resolvedArtifact != null
                            && sha256(stagedArtifact).equals(
                                    sha256(resolvedArtifact));
                    stagedArtifactsMatch &= matches;
                    stagedArtifactJson.add(
                            "    {\"module\":" + quote(module)
                                    + ",\"path\":" + quote(unix(stagedArtifact))
                                    + ",\"sha256\":" + quote(
                                    stagedArtifact.isFile()
                                            ? sha256(stagedArtifact) : "")
                                    + ",\"matchesResolvedArtifact\":"
                                    + matches + "}");
                }
                stagedRepository = repository.getAbsolutePath();
            }
            String languageHead = "";
            String languageStatus = "not-applicable";
            if (getLanguageCheckout().isPresent()) {
                File checkout = getLanguageCheckout().get().getAsFile();
                languageHead = gitText(checkout, "rev-parse", "HEAD").trim();
                languageStatus = gitBytes(
                        checkout, "status", "--porcelain", "-z").length == 0
                        ? "clean" : "dirty";
            }
            File bex = getBexCheckout().get().getAsFile();
            String bexHead = gitText(bex, "rev-parse", "HEAD").trim();
            boolean resolved = !artifactJson.isEmpty()
                    && !getResolvedComponents().get().isEmpty()
                    && !"dirty".equals(languageStatus)
                    && (!stagedRepositoryMode || stagedArtifactsMatch);
            String json = "{\n"
                    + "  \"schema\": \"blue-bex-dependency-evidence/1.0\",\n"
                    + "  \"status\": "
                    + quote(resolved ? "passed" : "failed") + ",\n"
                    + "  \"module\": " + quote(getModuleName().get()) + ",\n"
                    + "  \"mode\": " + quote(mode) + ",\n"
                    + "  \"declaredLanguageVersion\": "
                    + quote(getDeclaredLanguageVersion().get()) + ",\n"
                    + "  \"bexCommit\": " + quote(bexHead) + ",\n"
                    + "  \"languageCommit\": " + quote(languageHead) + ",\n"
                    + "  \"languageCheckoutState\": "
                    + quote(languageStatus) + ",\n"
                    + "  \"exactVersionCacheInitiallyAbsent\": "
                    + getExactVersionCacheInitiallyAbsent().get() + ",\n"
                    + "  \"stagedRepositoryEvidenceApplicable\": "
                    + stagedRepositoryMode + ",\n"
                    + "  \"stagedRepositoryPath\": "
                    + quote(stagedRepository) + ",\n"
                    + "  \"stagedRepositoryArtifactsMatchResolved\": "
                    + stagedArtifactsMatch + ",\n"
                    + "  \"stagedRepositoryArtifacts\": [\n"
                    + String.join(",\n", stagedArtifactJson) + "\n  ],\n"
                    + "  \"resolvedComponents\": "
                    + jsonArray(getResolvedComponents().get()) + ",\n"
                    + "  \"artifacts\": [\n"
                    + String.join(",\n", artifactJson) + "\n  ]\n"
                    + "}\n";
            File output = getOutputFile().get().getAsFile();
            output.getParentFile().mkdirs();
            Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Cannot generate dependency evidence", exception);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new GradleException("Cannot generate dependency evidence", exception);
        }
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
        return hex(digest.digest());
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
        Process process = new ProcessBuilder(command)
                .directory(directory).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = process.getInputStream().read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        if (process.waitFor() != 0) {
            throw new IOException(new String(
                    output.toByteArray(), StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static String unix(File file) {
        return file.getAbsolutePath().replace(File.separatorChar, '/');
    }

    private static String jsonArray(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(quote(value));
        }
        return "[" + String.join(",", result) + "]";
    }
}
