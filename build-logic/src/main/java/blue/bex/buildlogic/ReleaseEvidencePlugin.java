package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.GenerateSourceFingerprintTask;
import blue.bex.buildlogic.tasks.VerifyPublishedLanguageTask;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/** Typed source and published-dependency evidence used by release gates. */
public final class ReleaseEvidencePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        TaskProvider<GenerateSourceFingerprintTask> source =
                project.getTasks().register(
                        "generateBexSourceFingerprint",
                        GenerateSourceFingerprintTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.getRepositoryDirectory().set(
                                    project.getRootProject().getLayout()
                                            .getProjectDirectory());
                            task.getOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/source.json"));
                            task.getOutputs().upToDateWhen(ignored -> false);
                        });
        TaskProvider<VerifyPublishedLanguageTask> published =
                project.getTasks().register(
                        "bexPublishedLanguageVerification",
                        VerifyPublishedLanguageTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.getRequired().convention(false);
                            task.getCoordinate().convention(
                                    project.getProviders().gradleProperty(
                                            "bexPublishedLanguageCoordinate"));
                            task.getArtifactSha256().convention(
                                    project.getProviders().gradleProperty(
                                            "bexPublishedLanguageSha256"));
                            task.getInspectionFile().set(
                                    project.getLayout().getProjectDirectory().file(
                                            "src/test/resources/hosted-release/"
                                                    + "published-api-inspection."
                                                    + "properties"));
                            task.getArtifacts().from(project.getProviders()
                                    .gradleProperty(
                                            "bexPublishedLanguageArtifacts")
                                    .orElse("")
                                    .map(value -> Arrays.asList(value.split(
                                            Pattern.quote(File.pathSeparator))))
                                    .map(values -> values.size() == 1
                                            && values.get(0).trim().isEmpty()
                                            ? Collections.emptyList() : values));
                            task.getArtifacts().from(project.fileTree(
                                    project.getLayout().getBuildDirectory().dir(
                                            "reports/bex-release/inputs/"
                                                    + "published-artifacts"),
                                    spec -> spec.include("*.jar")));
                            Object repeatability = project.findProperty(
                                    "bexPublishedRepeatability");
                            if (repeatability != null
                                    && !repeatability.toString().trim().isEmpty()) {
                                task.getRepeatabilityReport().fileValue(
                                        project.file(repeatability.toString()));
                            } else {
                                File retained = project.getLayout()
                                        .getBuildDirectory().file(
                                                "reports/bex-release/inputs/"
                                                        + "published-"
                                                        + "repeatability.json")
                                        .get().getAsFile();
                                if (retained.isFile()) {
                                    task.getRepeatabilityReport().fileValue(retained);
                                }
                            }
                            task.getOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-modernization/"
                                                    + "published-language.json"));
                        });
        project.getTasks().register("bexReleaseEvidence", task -> {
            task.setGroup("verification");
            task.dependsOn(source, published);
        });
    }
}
