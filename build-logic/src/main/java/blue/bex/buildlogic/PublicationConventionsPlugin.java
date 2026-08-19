package blue.bex.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;

/** Configures module Maven publications without introducing local repositories. */
public final class PublicationConventionsPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("maven-publish");
        project.getPluginManager().apply(ReproducibleArchivesPlugin.class);
        project.getPluginManager().withPlugin("java", ignored -> {
                    PublishingExtension publishing =
                            project.getExtensions().getByType(
                                    PublishingExtension.class);
                    if (publishing.getPublications().findByName("mavenJava")
                            == null) {
                        publishing.getPublications().create(
                                "mavenJava", MavenPublication.class,
                                publication -> {
                                    publication.from(
                                            project.getComponents().getByName("java"));
                                    publication.getPom().getName().set(
                                            "Blue BEX " + project.getName());
                                    publication.getPom().getDescription().set(
                                            project.provider(() ->
                                                    project.getDescription()));
                                    publication.getPom().getUrl().set(
                                            "https://timeline.blue");
                                    publication.getPom().licenses(licenses ->
                                            licenses.license(license -> {
                                                license.getName().set("MIT License");
                                                license.getUrl().set(
                                                        "https://github.com/"
                                                        + "bluecontract/blue-bex-java/"
                                                        + "blob/main/LICENSE");
                                            }));
                                    publication.getPom().developers(developers ->
                                            developers.developer(developer -> {
                                                developer.getName().set("Blue");
                                                developer.getEmail().set(
                                                        "devsupport@timeline.blue");
                                            }));
                                    publication.getPom().scm(scm -> {
                                        scm.getUrl().set("https://github.com/"
                                                + "bluecontract/blue-bex-java.git");
                                        scm.getConnection().set("scm:git:"
                                                + "git@github.com:bluecontract/"
                                                + "blue-bex-java.git");
                                        scm.getDeveloperConnection().set(
                                                "scm:git:ssh://git@github.com/"
                                                        + "bluecontract/"
                                                        + "blue-bex-java.git");
                                    });
                                });
                    }
                    publishing.getRepositories().maven(repository -> {
                        repository.setName("staging");
                        Object configured = project.getRootProject()
                                .findProperty("bexSdkStagingRepository");
                        if (configured == null
                                || configured.toString().trim().isEmpty()) {
                            repository.setUrl(project.getRootProject()
                                    .getLayout().getBuildDirectory()
                                    .dir("staging-deploy"));
                        } else {
                            repository.setUrl(project.uri(
                                    configured.toString().trim()));
                        }
                    });
                });
        project.getTasks().withType(AbstractPublishToMaven.class)
                .configureEach(task -> {
                    Object localStage = project.getRootProject()
                            .findProperty("bexSdkStagingRepository");
                    String gate = localStage != null
                            && !localStage.toString().trim().isEmpty()
                            ? "bexSdkStageVerify" : "bexReleaseVerify";
                    task.dependsOn(project.getRootProject().getTasks()
                            .named(gate));
                });
    }
}
