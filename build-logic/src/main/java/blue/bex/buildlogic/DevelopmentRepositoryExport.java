package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.AssembleImmutableDevelopmentRepositoryTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.bundling.Jar;

/** Local development export with exact existing manifest validation, independent of release sealing. */
final class DevelopmentRepositoryExport {
    private DevelopmentRepositoryExport() {}

    static void register(Project project) {
        var staging = project.getTasks().register("stageDevelopmentArtifacts", Sync.class, task -> {
            task.setGroup("build");
            task.setDescription("Copies compiled development publications into this build's private staging directory.");
            task.into(project.getLayout().getBuildDirectory().dir("development-publications"));
        });
        project.getTasks().register("exportDevelopmentRepository",
                AssembleImmutableDevelopmentRepositoryTask.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Exports immutable development bytes without claiming release verification.");
                    task.dependsOn(staging);
                    task.getVersion().set(project.provider(() -> project.getVersion().toString()));
                    task.getLanguageVersion().set(project.getProviders().gradleProperty("blueLanguageVersion"));
                    task.getMutableRepository().set(project.getLayout().getBuildDirectory().dir("development-publications"));
                    task.getImmutableRepositoryPath().set(project.getProviders().gradleProperty("bexDevelopmentRepository"));
                    task.getLanguageRepository().set(project.getLayout().dir(project.getProviders()
                            .gradleProperty("blueLanguageRepository").map(project::file)));
                    task.getSpecification().set(project.getLayout().getProjectDirectory()
                            .file("specifications/blue-bex-specification-2.0.md"));
                    task.getFixtureManifest().set(project.getLayout().getProjectDirectory()
                            .file("src/test/resources/conformance/bex/fixtures/manifest.yaml"));
                    task.getRegistryManifest().set(project.getLayout().getProjectDirectory()
                            .file("src/test/resources/conformance/bex/registry/manifest.yaml"));
                    task.getGasManifest().set(project.getLayout().getProjectDirectory()
                            .file("src/test/resources/conformance/bex/gas-manifest.yaml"));
                    task.getCheckout().set(project.getLayout().getProjectDirectory());
                });
        project.getGradle().projectsEvaluated(ignored -> {
            for (String name : new String[] {"blue-bex-core", "blue-bex-contracts", "blue-bex-java"}) {
                Project module = project.project(":" + name);
                String version = project.getVersion().toString();
                String directory = "blue/bex/" + name + "/" + version;
                staging.configure(task -> {
                    for (String archive : new String[] {"jar", "sourcesJar", "javadocJar"}) {
                        task.from(module.getTasks().named(archive, Jar.class).flatMap(Jar::getArchiveFile),
                                spec -> spec.into(directory));
                    }
                    task.dependsOn(module.getTasks().named("generatePomFileForMavenJavaPublication"));
                    task.from(module.getLayout().getBuildDirectory().file("publications/mavenJava/pom-default.xml"),
                            spec -> {
                                spec.into(directory);
                                spec.rename(ignoredName -> name + "-" + version + ".pom");
                            });
                });
            }
        });
    }
}
