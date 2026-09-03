package blue.bex.buildlogic.tasks;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AssembleImmutableDevelopmentRepositoryTaskTest {
    private static final String LANGUAGE_COMMIT =
            "1111111111111111111111111111111111111111";
    private static final String LANGUAGE_TREE =
            "2222222222222222222222222222222222222222";
    private static final String LANGUAGE_VERSION =
            "3.1.0-dev." + LANGUAGE_COMMIT;
    private static final List<String> LANGUAGE_ARTIFACTS = Arrays.asList(
            "blue-language-model",
            "blue-language-core",
            "blue-language-mapping",
            "blue-language-ipfs",
            "blue-contracts-core",
            "blue-language-java");

    @TempDir
    Path temporary;

    @Test
    void sealsCommitBoundRepositoryAndNeverOverwritesIt() throws Exception {
        Fixture fixture = fixture();

        fixture.task.assemble();

        Path manifest = fixture.immutable.resolve("artifact-manifest.json");
        assertTrue(Files.isRegularFile(manifest));
        try (Stream<Path> paths = Files.walk(fixture.immutable)) {
            assertEquals(26L, paths.filter(Files::isRegularFile).count());
        }
        try (Stream<Path> paths = Files.walk(fixture.languageRepository)) {
            assertEquals(26L, paths.filter(Files::isRegularFile).count());
        }
        Map<?, ?> receipt = (Map<?, ?>) new JsonSlurper().parse(
                manifest.toFile());
        assertEquals(17, receipt.size());
        assertTrue(receipt.keySet().containsAll(Arrays.asList(
                "artifacts",
                "bexFixturePackageIdentity",
                "bexGasPackageIdentity",
                "bexRegistryPackageIdentity",
                "bexSpecificationIdentity",
                "builtWithJava",
                "groupId",
                "languageRepositoryManifestIdentity",
                "languageSourceCommit",
                "languageVersion",
                "releaseReadinessClaimed",
                "schema",
                "sourceCommit",
                "sourceDirty",
                "sourceTree",
                "stagePurpose",
                "version")));
        assertEquals(fixture.bexVersion, receipt.get("version"));
        assertEquals(fixture.bexCommit, receipt.get("sourceCommit"));
        assertEquals(fixture.bexTree, receipt.get("sourceTree"));
        assertEquals(false, receipt.get("sourceDirty"));
        assertEquals(17, ((Number) receipt.get("builtWithJava")).intValue());
        assertEquals("DEVELOPMENT", receipt.get("stagePurpose"));
        assertEquals(false, receipt.get("releaseReadinessClaimed"));
        assertEquals(LANGUAGE_VERSION, receipt.get("languageVersion"));
        assertEquals("sha256:" + sha256(fixture.languageManifest),
                receipt.get("languageRepositoryManifestIdentity"));

        fixture.task.assemble();
        Path changedArtifact = fixture.mutable.resolve(
                "blue/bex/blue-bex-core/" + fixture.bexVersion
                        + "/blue-bex-core-" + fixture.bexVersion + ".jar");
        write(changedArtifact, "changed\n");
        assertThrows(GradleException.class, fixture.task::assemble);
        assertEquals("blue-bex-core runtime\n",
                Files.readString(fixture.immutable.resolve(
                        "blue/bex/blue-bex-core/" + fixture.bexVersion
                                + "/blue-bex-core-" + fixture.bexVersion
                                + ".jar")));
    }

    @Test
    void rejectsLegacyLanguageManifestSchema() throws Exception {
        Fixture fixture = fixture();
        rewriteManifest(fixture, manifest -> manifest.put(
                "schema", "blue-staged-dependency-repository/1.0"));

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains(
                "Unsupported Language artifact manifest schema"));
    }

    @Test
    void rejectsLanguageManifestThatClaimsReleaseReadiness() throws Exception {
        Fixture fixture = fixture();
        rewriteManifest(fixture, manifest -> manifest.put(
                "releaseReadinessClaimed", true));

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains(
                "must not claim release readiness"));
    }

    @Test
    void rejectsWrongLanguageBuildJdk() throws Exception {
        Fixture fixture = fixture();
        rewriteManifest(fixture, manifest -> manifest.put(
                "builtWithJava", 21));

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains("builtWithJava 17"));
    }

    @Test
    void rejectsDirtyLanguageSourceProvenance() throws Exception {
        Fixture fixture = fixture();
        rewriteManifest(fixture, manifest -> manifest.put("sourceDirty", true));

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains("must bind a clean source tree"));
    }

    @Test
    void rejectsInvalidLanguageSemanticIdentity() throws Exception {
        Fixture fixture = fixture();
        rewriteManifest(fixture, manifest -> manifest.put(
                "contractsReleaseIdentity", "sha256:invalid"));

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains(
                "contractsReleaseIdentity is not a SHA-256 identity"));
    }

    @Test
    void rejectsUnknownLanguageManifestField() throws Exception {
        Fixture fixture = fixture();
        rewriteManifest(fixture, manifest -> manifest.put("extra", "value"));

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains("unexpected=[extra]"));
    }

    @Test
    void rejectsTamperedLanguageArtifact() throws Exception {
        Fixture fixture = fixture();
        Map<?, ?> manifest = (Map<?, ?>) new JsonSlurper().parse(
                fixture.languageManifest.toFile());
        List<?> artifacts = (List<?>) manifest.get("artifacts");
        Map<?, ?> record = (Map<?, ?>) artifacts.get(0);
        write(fixture.languageRepository.resolve(
                String.valueOf(record.get("path"))), "tampered\n");

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains("artifact digest differs"));
    }

    @Test
    void rejectsOpenLanguageRepositoryClosure() throws Exception {
        Fixture fixture = fixture();
        write(fixture.languageRepository.resolve("unexpected.txt"), "extra\n");

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains("file closure mismatch"));
    }

    @Test
    void rejectsLanguageManifestSidecarMismatch() throws Exception {
        Fixture fixture = fixture();
        write(fixture.languageManifest.resolveSibling(
                "artifact-manifest.json.sha256"), "tampered\n");

        GradleException failure = assertThrows(
                GradleException.class, fixture.task::assemble);

        assertTrue(failure.getMessage().contains(
                "manifest checksum does not match"));
    }

    private Fixture fixture() throws Exception {
        Path checkout = temporary.resolve("checkout");
        Files.createDirectories(checkout);
        write(checkout.resolve("specification.md"), "BEX specification\n");
        write(checkout.resolve("fixtures.yaml"),
                "packageIdentity: sha256:" + repeat('a', 64) + "\n");
        write(checkout.resolve("registry.yaml"),
                "packageIdentity: sha256:" + repeat('b', 64) + "\n");
        write(checkout.resolve("gas.yaml"),
                "packageIdentity: sha256:" + repeat('c', 64) + "\n");
        git(checkout, "init");
        git(checkout, "config", "user.name", "BEX test");
        git(checkout, "config", "user.email", "bex-test@example.invalid");
        git(checkout, "add", ".");
        git(checkout, "commit", "-m", "fixture");
        String bexCommit = git(checkout, "rev-parse", "HEAD").trim();
        String bexTree = git(checkout, "rev-parse", "HEAD^{tree}").trim();
        String bexVersion = "1.1.0-dev." + bexCommit;

        Path mutable = temporary.resolve("mutable");
        for (String artifact : Arrays.asList(
                "blue-bex-contracts", "blue-bex-core", "blue-bex-java")) {
            String base = "blue/bex/" + artifact + "/" + bexVersion + "/"
                    + artifact + "-" + bexVersion;
            write(mutable.resolve(base + ".jar"), artifact + " runtime\n");
            write(mutable.resolve(base + "-sources.jar"),
                    artifact + " sources\n");
            write(mutable.resolve(base + "-javadoc.jar"),
                    artifact + " javadoc\n");
            write(mutable.resolve(base + ".pom"), artifact + " pom\n");
        }

        Path languageRepository = languageRepository();
        Path languageManifest = languageRepository.resolve(
                "artifact-manifest.json");
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporary.resolve("gradle-project").toFile())
                .build();
        AssembleImmutableDevelopmentRepositoryTask task =
                project.getTasks().create(
                        "seal",
                        AssembleImmutableDevelopmentRepositoryTask.class);
        Path immutable = temporary.resolve("immutable");
        task.getVersion().set(bexVersion);
        task.getLanguageVersion().set(LANGUAGE_VERSION);
        task.getMutableRepository().set(mutable.toFile());
        task.getImmutableRepositoryPath().set(immutable.toString());
        task.getLanguageRepository().set(languageRepository.toFile());
        task.getSpecification().set(checkout.resolve("specification.md").toFile());
        task.getFixtureManifest().set(checkout.resolve("fixtures.yaml").toFile());
        task.getRegistryManifest().set(checkout.resolve("registry.yaml").toFile());
        task.getGasManifest().set(checkout.resolve("gas.yaml").toFile());
        task.getCheckout().set(checkout.toFile());
        return new Fixture(
                task,
                mutable,
                immutable,
                languageRepository,
                languageManifest,
                bexVersion,
                bexCommit,
                bexTree);
    }

    private Path languageRepository() throws Exception {
        Path repository = temporary.resolve("language");
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> artifacts = new ArrayList<>(LANGUAGE_ARTIFACTS);
        artifacts.sort(String::compareTo);
        for (String artifact : artifacts) {
            String base = "blue/language/" + artifact + "/" + LANGUAGE_VERSION
                    + "/" + artifact + "-" + LANGUAGE_VERSION;
            addLanguageArtifact(
                    repository, records, artifact, base, "pom", ".pom");
            addLanguageArtifact(
                    repository, records, artifact, base, "runtime", ".jar");
        }
        records.sort(Comparator
                .comparing((Map<String, Object> record) ->
                        String.valueOf(record.get("coordinate")))
                .thenComparing(record -> String.valueOf(record.get("kind")))
                .thenComparing(record -> String.valueOf(record.get("path"))));

        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("artifacts", records);
        manifest.put("builtWithJava", 17);
        manifest.put("contractsFixturePackageIdentity",
                "sha256:" + repeat('d', 64));
        manifest.put("contractsReleaseIdentity",
                "sha256:" + repeat('e', 64));
        manifest.put("contractsSpecificationIdentity",
                "sha256:" + repeat('f', 64));
        manifest.put("groupId", "blue.language");
        manifest.put("releaseReadinessClaimed", false);
        manifest.put("schema", "blue-development-maven-repository/1.0");
        manifest.put("sourceCommit", LANGUAGE_COMMIT);
        manifest.put("sourceDirty", false);
        manifest.put("sourceTree", LANGUAGE_TREE);
        manifest.put("stagePurpose", "DEVELOPMENT");
        manifest.put("version", LANGUAGE_VERSION);
        Path manifestPath = repository.resolve("artifact-manifest.json");
        writeJson(manifestPath, manifest);
        writeChecksum(manifestPath);
        return repository;
    }

    private static void addLanguageArtifact(
            Path repository,
            List<Map<String, Object>> records,
            String artifact,
            String base,
            String kind,
            String suffix) throws Exception {
        String relative = base + suffix;
        Path payload = repository.resolve(relative);
        write(payload, artifact + " " + kind + "\n");
        writeChecksum(payload);
        Map<String, Object> record = new TreeMap<>();
        record.put("bytes", Files.size(payload));
        record.put("checksumPath", relative + ".sha256");
        record.put("coordinate",
                "blue.language:" + artifact + ":" + LANGUAGE_VERSION);
        record.put("kind", kind);
        record.put("path", relative);
        record.put("sha256", "sha256:" + sha256(payload));
        records.add(record);
    }

    @SuppressWarnings("unchecked")
    private static void rewriteManifest(
            Fixture fixture,
            Consumer<Map<String, Object>> mutation) throws Exception {
        Map<String, Object> manifest = (Map<String, Object>)
                new JsonSlurper().parse(fixture.languageManifest.toFile());
        mutation.accept(manifest);
        writeJson(fixture.languageManifest, manifest);
        writeChecksum(fixture.languageManifest);
    }

    private static void writeJson(Path path, Map<String, Object> value)
            throws IOException {
        write(path, JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n");
    }

    private static void writeChecksum(Path path) throws Exception {
        write(path.resolveSibling(path.getFileName() + ".sha256"),
                sha256(path) + "  " + path.getFileName() + "\n");
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private static String git(Path directory, String... arguments)
            throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError(output.toString(StandardCharsets.UTF_8));
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String sha256(Path path)
            throws IOException, NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path));
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static final class Fixture {
        private final AssembleImmutableDevelopmentRepositoryTask task;
        private final Path mutable;
        private final Path immutable;
        private final Path languageRepository;
        private final Path languageManifest;
        private final String bexVersion;
        private final String bexCommit;
        private final String bexTree;

        private Fixture(
                AssembleImmutableDevelopmentRepositoryTask task,
                Path mutable,
                Path immutable,
                Path languageRepository,
                Path languageManifest,
                String bexVersion,
                String bexCommit,
                String bexTree) {
            this.task = task;
            this.mutable = mutable;
            this.immutable = immutable;
            this.languageRepository = languageRepository;
            this.languageManifest = languageManifest;
            this.bexVersion = bexVersion;
            this.bexCommit = bexCommit;
            this.bexTree = bexTree;
        }
    }
}
