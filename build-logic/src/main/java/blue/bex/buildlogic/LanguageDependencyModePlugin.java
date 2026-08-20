package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.GenerateDependencyEvidenceTask;
import java.io.File;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

/** Establishes explicit local-composite versus published Language policy. */
public final class LanguageDependencyModePlugin implements Plugin<Project> {
    public static final String EXTENSION = "bexLanguage";

    @Override
    public void apply(Project project) {
        LanguageDependencyModeExtension extension =
                project.getExtensions().create(
                        EXTENSION,
                        LanguageDependencyModeExtension.class);
        String repositoryProperty =
                extension.getRepositoryPropertyName().get();
        Object configuredRepository = project.findProperty(
                repositoryProperty);
        if (configuredRepository != null
                && !configuredRepository.toString().trim().isEmpty()) {
            String repositoryPath = configuredRepository.toString().trim();
            project.getRepositories().maven(repository -> {
                repository.setName("stagedBlueLanguage");
                repository.setUrl(project.uri(repositoryPath));
                repository.content(content ->
                        content.includeGroup("blue.language"));
            });
        }
        project.getRepositories().mavenCentral();

        project.getPluginManager().withPlugin("java", ignored ->
                project.getTasks().register(
                        "writeLanguageDependencyEvidence",
                        GenerateDependencyEvidenceTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Records exact resolved Language/module JAR hashes.");
                            String property =
                                    extension.getCompositePropertyName().get();
                            String composite = (String) project.findProperty(property);
                            boolean local = composite != null
                                    && !composite.trim().isEmpty();
                            boolean staged = configuredRepository != null
                                    && !configuredRepository.toString()
                                    .trim().isEmpty();
                            task.getMode().set(local
                                    ? "local-composite"
                                    : staged
                                            ? "staged-repository"
                                            : "standalone-published");
                            if (staged) {
                                task.getStagedRepositoryPath().set(
                                        configuredRepository.toString().trim());
                            }
                            task.getModuleName().set(project.getName());
                            task.getDeclaredLanguageVersion().set(
                                    extension.getVersion());
                            File groupCache = new File(
                                    project.getGradle().getGradleUserHomeDir(),
                                    "caches/modules-2/files-2.1/blue.language");
                            String version = extension.getVersion().get();
                            String[] focusedModules = {
                                    "blue-language-model",
                                    "blue-language-core",
                                    "blue-language-mapping",
                                    "blue-contracts-core",
                                    "blue-language-java"
                            };
                            boolean initiallyAbsent = true;
                            for (String module : focusedModules) {
                                if (new File(new File(groupCache, module), version)
                                        .exists()) {
                                    initiallyAbsent = false;
                                }
                            }
                            task.getExactVersionCacheInitiallyAbsent().set(
                                    initiallyAbsent);
                            task.getBexCheckout().set(
                                    project.getRootProject().getLayout()
                                            .getProjectDirectory());
                            if (local) {
                                task.getLanguageCheckout().set(
                                        project.getLayout().dir(
                                                project.provider(() ->
                                                        project.file(composite.trim()))));
                            }
                            Configuration runtime = project.getConfigurations()
                                    .getByName("runtimeClasspath");
                            task.getArtifacts().from(runtime);
                            task.getResolvedComponents().set(project.provider(() ->
                                    runtime.getIncoming().getResolutionResult()
                                            .getAllComponents().stream()
                                            .map(component -> component.getId()
                                                    .getDisplayName())
                                            .sorted()
                                            .collect(java.util.stream.Collectors
                                                    .toList())));
                            task.getOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/dependencies/language.json"));
                            task.getOutputs().upToDateWhen(element -> false);
                        }));

        project.getTasks().register("verifyLanguageDependencyMode", task -> {
            task.setGroup("verification");
            task.setDescription(
                    "Validates the explicit local-composite or published "
                            + "Blue Language dependency mode.");
            task.doLast(ignored -> {
                String property = extension.getCompositePropertyName().get();
                String value = (String) project.findProperty(property);
                String repositoryName =
                        extension.getRepositoryPropertyName().get();
                String repositoryValue =
                        (String) project.findProperty(repositoryName);
                if (value != null && !value.trim().isEmpty()) {
                    File checkout = project.file(value.trim());
                    if (!checkout.isDirectory()) {
                        throw new GradleException(
                                property + " is not a directory: " + checkout);
                    }
                }
                if (value != null && !value.trim().isEmpty()
                        && repositoryValue != null
                        && !repositoryValue.trim().isEmpty()) {
                    throw new GradleException(
                            "Choose either " + property + " or "
                                    + repositoryName + ", not both");
                }
                if (repositoryValue != null
                        && !repositoryValue.trim().isEmpty()) {
                    File repository = project.file(repositoryValue.trim());
                    if (!repository.isDirectory()) {
                        throw new GradleException(
                                repositoryName + " is not a directory: "
                                        + repository);
                    }
                }
                if (project.getRepositories().stream().anyMatch(repository ->
                        repository.getName().equalsIgnoreCase("MavenLocal"))) {
                    throw new GradleException(
                            "mavenLocal() is forbidden for BEX Language resolution");
                }
            });
        });
    }
}
