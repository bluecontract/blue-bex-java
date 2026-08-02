package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.VerifyJava8BytecodeTask;
import blue.bex.buildlogic.tasks.VerifyLegacyLanguageImportsTask;
import java.nio.charset.StandardCharsets;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;

/** Shared Java 8, testing, and documentation policy for every BEX module. */
public final class Java8LibraryConventionsPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        JavaPluginExtension java =
                project.getExtensions().getByType(JavaPluginExtension.class);
        JavaToolchainService toolchains =
                project.getExtensions().getByType(JavaToolchainService.class);
        java.setSourceCompatibility(JavaVersion.VERSION_1_8);
        java.setTargetCompatibility(JavaVersion.VERSION_1_8);
        java.withSourcesJar();
        java.withJavadocJar();

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding(StandardCharsets.UTF_8.name());
            task.getOptions().getRelease().set(8);
        });
        project.getTasks().withType(Javadoc.class).configureEach(task -> {
            task.getJavadocTool().set(toolchains.javadocToolFor(spec ->
                    spec.getLanguageVersion().set(JavaLanguageVersion.of(8))));
            StandardJavadocDocletOptions options =
                    (StandardJavadocDocletOptions) task.getOptions();
            options.setEncoding(StandardCharsets.UTF_8.name());
            options.setCharSet(StandardCharsets.UTF_8.name());
            options.addBooleanOption("notimestamp", true);
        });
        project.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            task.getTestLogging().events("failed", "skipped");
        });

        project.getDependencies().add(
                "testImplementation",
                project.getDependencies().platform(
                        "org.junit:junit-bom:5.10.2"));
        project.getDependencies().add(
                "testImplementation", "org.junit.jupiter:junit-jupiter");
        project.getDependencies().add(
                "testRuntimeOnly",
                "org.junit.platform:junit-platform-launcher");

        TaskProvider<VerifyJava8BytecodeTask> bytecode =
                project.getTasks().register(
                        "java8BytecodeCheck",
                        VerifyJava8BytecodeTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Verifies all production classes use Java 8 bytecode.");
                            task.getAllowEmpty().convention(false);
                            task.dependsOn("classes");
                            task.getClassDirectories().from(
                                    java.getSourceSets().getByName("main")
                                            .getOutput().getClassesDirs());
                        });
        TaskProvider<VerifyLegacyLanguageImportsTask> imports =
                project.getTasks().register(
                        "verifyLegacyLanguageImports",
                        VerifyLegacyLanguageImportsTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Rejects imports removed by modular Blue Language.");
                            task.getSourceDirectories().from(
                                    java.getSourceSets().getByName("main")
                                            .getAllJava().getSourceDirectories());
                        });
        project.getTasks().named("check", task -> task.dependsOn(
                bytecode, imports));
    }
}
