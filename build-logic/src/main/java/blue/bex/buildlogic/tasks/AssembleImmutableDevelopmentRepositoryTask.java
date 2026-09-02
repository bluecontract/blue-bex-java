package blue.bex.buildlogic.tasks;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
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
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Seals exact BEX development artifacts for a downstream repository consumer. */
public abstract class AssembleImmutableDevelopmentRepositoryTask
        extends DefaultTask {
    private static final String MANIFEST_FILE = "artifact-manifest.json";
    private static final Pattern DEVELOPMENT_VERSION = Pattern.compile(
            "[0-9]+\\.[0-9]+\\.[0-9]+-dev\\.([0-9a-f]{40})");
    private static final Pattern PACKAGE_IDENTITY = Pattern.compile(
            "(?m)^packageIdentity:\\s*(sha256:[0-9a-f]{64})\\s*$");
    private static final List<String> ARTIFACTS = Collections.unmodifiableList(
            Arrays.asList(
                    "blue-bex-contracts",
                    "blue-bex-core",
                    "blue-bex-java"));
    private static final List<ArtifactKind> KINDS = Collections.unmodifiableList(
            Arrays.asList(
                    new ArtifactKind("javadoc", "-javadoc.jar"),
                    new ArtifactKind("pom", ".pom"),
                    new ArtifactKind("runtime", ".jar"),
                    new ArtifactKind("sources", "-sources.jar")));

    @Input
    public abstract Property<String> getVersion();

    @Input
    public abstract Property<String> getLanguageVersion();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getMutableRepository();

    @Input
    public abstract Property<String> getImmutableRepositoryPath();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLanguageArtifactManifest();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSpecification();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getFixtureManifest();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getRegistryManifest();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getGasManifest();

    @Internal
    public abstract DirectoryProperty getCheckout();

    @TaskAction
    public void assemble() {
        Path source = getMutableRepository().get().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path target = getProject().file(getImmutableRepositoryPath().get())
                .toPath().toAbsolutePath().normalize();
        Path checkout = getCheckout().get().getAsFile().toPath()
                .toAbsolutePath().normalize();
        if (source.equals(target)
                || source.startsWith(target)
                || target.startsWith(source)) {
            throw new GradleException(
                    "Mutable and immutable BEX repositories must be disjoint");
        }
        String version = getVersion().get();
        Matcher versionMatcher = DEVELOPMENT_VERSION.matcher(version);
        if (!versionMatcher.matches()) {
            throw new GradleException(
                    "Immutable BEX development repository requires a "
                            + "commit-bound dev version");
        }
        String sourceCommit = git(checkout, "rev-parse", "HEAD").trim();
        if (!sourceCommit.equals(versionMatcher.group(1))) {
            throw new GradleException(
                    "BEX development version does not match source HEAD");
        }
        if (gitBytes(checkout, "status", "--porcelain", "-z").length != 0) {
            throw new GradleException(
                    "BEX development repository requires a clean source checkout");
        }

        LanguageBinding language = languageBinding(
                getLanguageArtifactManifest().get().getAsFile().toPath(),
                getLanguageVersion().get());
        Path parent = target.getParent();
        if (parent == null) {
            throw new GradleException(
                    "Immutable BEX repository has no parent: " + target);
        }
        Path temporary = parent.resolve(target.getFileName()
                + ".assembling-" + UUID.randomUUID());
        try {
            Files.createDirectories(parent);
            Files.createDirectory(temporary);
            List<Map<String, Object>> artifacts = copyArtifacts(
                    source, temporary, version);
            Map<String, Object> manifest = new TreeMap<>();
            manifest.put("artifacts", artifacts);
            manifest.put("bexFixturePackageIdentity", packageIdentity(
                    getFixtureManifest().get().getAsFile().toPath()));
            manifest.put("bexGasPackageIdentity", packageIdentity(
                    getGasManifest().get().getAsFile().toPath()));
            manifest.put("bexRegistryPackageIdentity", packageIdentity(
                    getRegistryManifest().get().getAsFile().toPath()));
            manifest.put("bexSpecificationIdentity", "sha256:" + sha256(
                    getSpecification().get().getAsFile().toPath()));
            manifest.put("groupId", "blue.bex");
            manifest.put("languageRepositoryManifestIdentity",
                    language.manifestIdentity);
            manifest.put("languageSourceCommit", language.sourceCommit);
            manifest.put("languageVersion", language.version);
            manifest.put("schema", "blue-bex-development-repository/1.0");
            manifest.put("sourceCommit", sourceCommit);
            manifest.put("version", version);
            String json = JsonOutput.prettyPrint(JsonOutput.toJson(manifest))
                    + "\n";
            write(temporary.resolve(MANIFEST_FILE), json);
            writeChecksum(temporary.resolve(MANIFEST_FILE));
            verifyClosedRepository(temporary, artifacts);
            if (Files.exists(target)) {
                if (!sameTree(temporary, target)) {
                    throw new GradleException(
                            "Immutable BEX repository already exists with "
                                    + "different bytes: " + target);
                }
                deleteTree(temporary);
                return;
            }
            moveDirectory(temporary, target);
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteTreeQuietly(temporary);
            throw new GradleException(
                    "Cannot assemble immutable BEX repository " + target,
                    exception);
        } catch (RuntimeException exception) {
            deleteTreeQuietly(temporary);
            throw exception;
        }
    }

    private static LanguageBinding languageBinding(
            Path manifestPath,
            String expectedVersion) {
        try {
            if (!Files.isRegularFile(manifestPath)
                    || Files.isSymbolicLink(manifestPath)) {
                throw new GradleException(
                        "Language artifact manifest is missing or symbolic");
            }
            Path checksum = manifestPath.resolveSibling(
                    manifestPath.getFileName() + ".sha256");
            if (!Files.isRegularFile(checksum)
                    || Files.isSymbolicLink(checksum)) {
                throw new GradleException(
                        "Language artifact manifest checksum is missing or symbolic");
            }
            String expectedChecksum = sha256(manifestPath) + "  "
                    + manifestPath.getFileName() + "\n";
            if (!expectedChecksum.equals(Files.readString(
                    checksum, StandardCharsets.UTF_8))) {
                throw new GradleException(
                        "Language artifact manifest checksum does not match");
            }
            Object parsed = new JsonSlurper().parse(manifestPath.toFile());
            if (!(parsed instanceof Map<?, ?>)) {
                throw new GradleException(
                        "Language artifact manifest is not a JSON object");
            }
            Map<?, ?> manifest = (Map<?, ?>) parsed;
            String schema = text(manifest.get("schema"));
            String version = text(manifest.get("version"));
            String sourceCommit = text(manifest.get("sourceCommit"));
            if (!"blue-staged-dependency-repository/1.0".equals(schema)) {
                throw new GradleException(
                        "Unsupported Language artifact manifest schema");
            }
            if (!expectedVersion.equals(version)) {
                throw new GradleException(
                        "Language artifact manifest version does not match "
                                + "blueLanguageVersion");
            }
            Matcher versionMatcher = DEVELOPMENT_VERSION.matcher(version);
            if (!versionMatcher.matches()
                    || !sourceCommit.equals(versionMatcher.group(1))) {
                throw new GradleException(
                        "Language artifact manifest is not commit-bound");
            }
            return new LanguageBinding(
                    version,
                    sourceCommit,
                    "sha256:" + sha256(manifestPath));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new GradleException(
                    "Cannot verify Language artifact manifest", exception);
        }
    }

    private static List<Map<String, Object>> copyArtifacts(
            Path source,
            Path target,
            String version) throws IOException, NoSuchAlgorithmException {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String artifact : ARTIFACTS) {
            String base = "blue/bex/" + artifact + "/" + version + "/"
                    + artifact + "-" + version;
            for (ArtifactKind kind : KINDS) {
                String relative = base + kind.suffix;
                Path input = source.resolve(relative);
                if (!Files.isRegularFile(input)
                        || Files.isSymbolicLink(input)) {
                    throw new GradleException(
                            "Required staged BEX artifact is missing: " + input);
                }
                Path output = target.resolve(relative);
                Files.createDirectories(output.getParent());
                Files.copy(input, output, StandardCopyOption.COPY_ATTRIBUTES);
                writeChecksum(output);

                Map<String, Object> record = new TreeMap<>();
                record.put("bytes", Files.size(output));
                record.put("checksumPath", relative + ".sha256");
                record.put("coordinate", "blue.bex:" + artifact + ":" + version);
                record.put("kind", kind.name);
                record.put("path", relative);
                record.put("sha256", "sha256:" + sha256(output));
                records.add(record);
            }
        }
        records.sort(Comparator
                .comparing((Map<String, Object> record) ->
                        String.valueOf(record.get("coordinate")))
                .thenComparing(record -> String.valueOf(record.get("kind")))
                .thenComparing(record -> String.valueOf(record.get("path"))));
        return records;
    }

    private static void verifyClosedRepository(
            Path repository,
            List<Map<String, Object>> artifacts) throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        for (Map<String, Object> artifact : artifacts) {
            String path = String.valueOf(artifact.get("path"));
            expected.add(path);
            expected.add(path + ".sha256");
        }
        expected.add(MANIFEST_FILE);
        expected.add(MANIFEST_FILE + ".sha256");
        Set<String> actual = regularFiles(repository);
        if (!expected.equals(actual)) {
            throw new GradleException(
                    "Immutable BEX repository file closure mismatch; expected="
                            + expected + ", actual=" + actual);
        }
    }

    private static String packageIdentity(Path manifest) throws IOException {
        String content = Files.readString(manifest, StandardCharsets.UTF_8);
        Matcher matcher = PACKAGE_IDENTITY.matcher(content);
        if (!matcher.find()) {
            throw new GradleException(
                    "Manifest has no exact packageIdentity: " + manifest);
        }
        return matcher.group(1);
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static void writeChecksum(Path input)
            throws IOException, NoSuchAlgorithmException {
        write(input.resolveSibling(input.getFileName() + ".sha256"),
                sha256(input) + "  " + input.getFileName() + "\n");
    }

    private static void write(Path output, String content) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private static String sha256(Path file)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }

    private static String git(Path checkout, String... arguments) {
        return new String(gitBytes(checkout, arguments), StandardCharsets.UTF_8);
    }

    private static byte[] gitBytes(Path checkout, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(checkout.toFile())
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = process.getInputStream().read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            if (process.waitFor() != 0) {
                throw new GradleException(
                        "Git command failed: "
                                + new String(output.toByteArray(),
                                StandardCharsets.UTF_8));
            }
            return output.toByteArray();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Git command interrupted", exception);
        } catch (IOException exception) {
            throw new GradleException("Cannot execute Git", exception);
        }
    }

    private static Set<String> regularFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> !path.equals(root))
                    .peek(path -> {
                        if (Files.isSymbolicLink(path)) {
                            throw new GradleException(
                                    "Symbolic link in immutable BEX repository: "
                                            + root.relativize(path));
                        }
                    })
                    .filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString()
                            .replace(File.separatorChar, '/'))
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static boolean sameTree(Path left, Path right) throws IOException {
        Set<String> leftFiles = regularFiles(left);
        Set<String> rightFiles = regularFiles(right);
        if (!leftFiles.equals(rightFiles)) {
            return false;
        }
        for (String path : leftFiles) {
            if (Files.mismatch(left.resolve(path), right.resolve(path)) != -1L) {
                return false;
            }
        }
        return true;
    }

    private static void moveDirectory(Path source, Path target)
            throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // Preserve the original staging failure.
        }
    }

    private static final class ArtifactKind {
        private final String name;
        private final String suffix;

        private ArtifactKind(String name, String suffix) {
            this.name = name;
            this.suffix = suffix;
        }
    }

    private static final class LanguageBinding {
        private final String version;
        private final String sourceCommit;
        private final String manifestIdentity;

        private LanguageBinding(
                String version,
                String sourceCommit,
                String manifestIdentity) {
            this.version = version;
            this.sourceCommit = sourceCommit;
            this.manifestIdentity = manifestIdentity;
        }
    }
}
