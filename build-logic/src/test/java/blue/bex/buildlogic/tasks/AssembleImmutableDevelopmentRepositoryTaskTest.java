package blue.bex.buildlogic.tasks;

import groovy.json.JsonSlurper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
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

    @TempDir
    Path temporary;

    @Test
    void sealsCommitBoundRepositoryAndNeverOverwritesIt() throws Exception {
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
        String bexVersion = "1.1.0-dev." + bexCommit;

        Path mutable = temporary.resolve("mutable");
        for (String artifact : Arrays.asList(
                "blue-bex-contracts", "blue-bex-core", "blue-bex-java")) {
            String base = "blue/bex/" + artifact + "/" + bexVersion + "/"
                    + artifact + "-" + bexVersion;
            write(mutable.resolve(base + ".jar"), artifact + " runtime\n");
            write(mutable.resolve(base + "-sources.jar"), artifact + " sources\n");
            write(mutable.resolve(base + "-javadoc.jar"), artifact + " javadoc\n");
            write(mutable.resolve(base + ".pom"), artifact + " pom\n");
        }

        String languageVersion = "3.1.0-dev." + LANGUAGE_COMMIT;
        Path languageManifest = temporary.resolve("language/artifact-manifest.json");
        write(languageManifest, "{\"schema\":"
                + "\"blue-staged-dependency-repository/1.0\","
                + "\"sourceCommit\":\"" + LANGUAGE_COMMIT + "\","
                + "\"version\":\"" + languageVersion + "\"}\n");
        write(languageManifest.resolveSibling("artifact-manifest.json.sha256"),
                sha256(languageManifest) + "  artifact-manifest.json\n");

        Project project = ProjectBuilder.builder()
                .withProjectDir(temporary.resolve("gradle-project").toFile())
                .build();
        AssembleImmutableDevelopmentRepositoryTask task =
                project.getTasks().create(
                        "seal",
                        AssembleImmutableDevelopmentRepositoryTask.class);
        Path immutable = temporary.resolve("immutable");
        task.getVersion().set(bexVersion);
        task.getLanguageVersion().set(languageVersion);
        task.getMutableRepository().set(mutable.toFile());
        task.getImmutableRepositoryPath().set(immutable.toString());
        task.getLanguageArtifactManifest().set(languageManifest.toFile());
        task.getSpecification().set(checkout.resolve("specification.md").toFile());
        task.getFixtureManifest().set(checkout.resolve("fixtures.yaml").toFile());
        task.getRegistryManifest().set(checkout.resolve("registry.yaml").toFile());
        task.getGasManifest().set(checkout.resolve("gas.yaml").toFile());
        task.getCheckout().set(checkout.toFile());

        task.assemble();
        Path manifest = immutable.resolve("artifact-manifest.json");
        assertTrue(Files.isRegularFile(manifest));
        try (Stream<Path> paths = Files.walk(immutable)) {
            assertEquals(26L, paths.filter(Files::isRegularFile).count());
        }
        Map<?, ?> receipt = (Map<?, ?>) new JsonSlurper().parse(
                manifest.toFile());
        assertEquals(bexVersion, receipt.get("version"));
        assertEquals(bexCommit, receipt.get("sourceCommit"));
        assertEquals(languageVersion, receipt.get("languageVersion"));
        assertEquals("sha256:" + sha256(languageManifest),
                receipt.get("languageRepositoryManifestIdentity"));

        task.assemble();
        Path changedArtifact = mutable.resolve(
                "blue/bex/blue-bex-core/" + bexVersion
                        + "/blue-bex-core-" + bexVersion + ".jar");
        write(changedArtifact, "changed\n");
        assertThrows(GradleException.class, task::assemble);
        assertEquals("blue-bex-core runtime\n",
                Files.readString(immutable.resolve(
                        "blue/bex/blue-bex-core/" + bexVersion
                                + "/blue-bex-core-" + bexVersion + ".jar")));
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
}
