package blue.bex.buildlogic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RootOrchestrationPluginTest {
    @TempDir
    Path projectDirectory;

    @Test
    void sourceReleaseArchiveExcludesNestedGradleState() throws IOException {
        write("settings.gradle.kts", """
                rootProject.name = "fixture"
                include(
                    "blue-bex-core",
                    "blue-bex-contracts",
                    "blue-bex-conformance",
                    "blue-bex-java",
                    "examples"
                )
                """);
        write("build.gradle.kts", """
                plugins {
                    id("blue.bex.root-orchestration")
                }

                version = "1.2.3"

                subprojects {
                    listOf(
                        "assemble",
                        "check",
                        "bexConformance",
                        "verifyLanguageDependencyMode",
                        "writeLanguageDependencyEvidence",
                        "verifyReproducibleArchives",
                        "bexApiEvidence",
                        "writeBexConformanceReport",
                        "jmh",
                        "jmhSmoke"
                    ).forEach { tasks.register(it) }
                }
                """);
        write("gradle/verification/latest-language-baseline.json", """
                {
                  "language": {
                    "exactHead": "1111111111111111111111111111111111111111",
                    "verifiedImplementationCommit": "1111111111111111111111111111111111111111",
                    "documentationOnlyDiffPaths": []
                  }
                }
                """);
        write("README.md", "release source\n");
        write("build-logic/src/main/java/Fixture.java", "final class Fixture {}\n");
        write("build-logic/.gradle/9.6.0/executionHistory/executionHistory.bin",
                "checkout-specific Gradle state\n");
        for (String subproject : new String[] {
                "blue-bex-core", "blue-bex-contracts", "blue-bex-conformance",
                "blue-bex-java", "examples"}) {
            Files.createDirectories(projectDirectory.resolve(subproject));
        }

        GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments("sourceReleaseArchive", "--stacktrace")
                .build();

        Path archive = projectDirectory.resolve(
                "build/distributions/blue-bex-java-1.2.3-source-release.zip");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry(
                    "blue-bex-java-1.2.3/build-logic/src/main/java/Fixture.java"));
            assertFalse(zip.stream().anyMatch(entry ->
                    entry.getName().contains("/.gradle/")));
        }
    }

    private void write(String relativePath, String contents) throws IOException {
        Path target = projectDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, contents);
    }
}
