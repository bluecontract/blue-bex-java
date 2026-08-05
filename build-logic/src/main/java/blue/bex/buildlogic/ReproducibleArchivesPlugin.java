package blue.bex.buildlogic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.javadoc.Javadoc;

/** Applies deterministic timestamp and entry-order policy to BEX archives. */
public final class ReproducibleArchivesPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getTasks().withType(AbstractArchiveTask.class)
                .configureEach(task -> {
                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                });
        project.getPluginManager().withPlugin("java", ignored ->
                configureReplicaVerification(project));
    }

    private static void configureReplicaVerification(Project project) {
        JavaPluginExtension java =
                project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        TaskProvider<Jar> primary = project.getTasks().named("jar", Jar.class);
        TaskProvider<Jar> replica = project.getTasks().register(
                "replicaJar", Jar.class, task -> {
                    task.setGroup("verification");
                    task.from(main.getOutput());
                    task.getDestinationDirectory().set(
                            project.getLayout().getBuildDirectory().dir(
                                    "reproducibility/main"));
                    task.getArchiveFileName().set(
                            primary.flatMap(Jar::getArchiveFileName));
                    task.doFirst(unused -> task.getManifest().from(
                            primary.get().getManifest()));
                });
        TaskProvider<Jar> sources =
                project.getTasks().named("sourcesJar", Jar.class);
        TaskProvider<Jar> sourcesReplica = project.getTasks().register(
                "replicaSourcesJar", Jar.class, task -> {
                    task.setGroup("verification");
                    task.from(main.getAllSource());
                    task.getDestinationDirectory().set(
                            project.getLayout().getBuildDirectory().dir(
                                    "reproducibility/sources"));
                    task.getArchiveFileName().set(
                            sources.flatMap(Jar::getArchiveFileName));
                    task.doFirst(unused -> task.getManifest().from(
                            sources.get().getManifest()));
                });
        TaskProvider<Javadoc> javadoc =
                project.getTasks().named("javadoc", Javadoc.class);
        TaskProvider<Jar> javadocPrimary =
                project.getTasks().named("javadocJar", Jar.class);
        TaskProvider<Jar> javadocReplica = project.getTasks().register(
                "replicaJavadocJar", Jar.class, task -> {
                    task.setGroup("verification");
                    task.dependsOn(javadoc);
                    task.from(javadoc.map(Javadoc::getDestinationDir));
                    task.getDestinationDirectory().set(
                            project.getLayout().getBuildDirectory().dir(
                                    "reproducibility/javadoc"));
                    task.getArchiveFileName().set(
                            javadocPrimary.flatMap(Jar::getArchiveFileName));
                    task.doFirst(unused -> task.getManifest().from(
                            javadocPrimary.get().getManifest()));
                });
        project.getTasks().register("verifyReproducibleArchives", task -> {
            task.setGroup("verification");
            task.setDescription(
                    "Builds independent module, sources, and Javadoc JAR "
                            + "replicas and compares bytes.");
            task.dependsOn(primary, replica, sources, sourcesReplica,
                    javadocPrimary, javadocReplica);
            task.doLast(unused -> {
                compare(
                        primary.get().getArchiveFile().get().getAsFile(),
                        replica.get().getArchiveFile().get().getAsFile());
                compare(
                        sources.get().getArchiveFile().get().getAsFile(),
                        sourcesReplica.get().getArchiveFile().get().getAsFile());
                compare(
                        javadocPrimary.get().getArchiveFile().get().getAsFile(),
                        javadocReplica.get().getArchiveFile().get().getAsFile());
            });
        });
    }

    private static void compare(File first, File second) {
        try {
            if (Files.mismatch(first.toPath(), second.toPath()) != -1L) {
                throw new GradleException(
                        "Archive replica differs: " + first + " and " + second);
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot compare archive replicas", exception);
        }
    }
}
