package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.GenerateApiClassificationTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/** Provides stable lifecycle names for API descriptors and migration ledgers. */
public final class ApiEvidencePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        TaskProvider<GenerateApiClassificationTask> classification =
                project.getTasks().register(
                        "generateApiClassification",
                        GenerateApiClassificationTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Classifies every same-run public BEX descriptor.");
                            task.getManifestFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-release/public-api.txt"));
                            task.getOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/bex-release/"
                                                    + "public-api-classification.json"));
                        });
        TaskProvider<org.gradle.api.Task> evidence =
                project.getTasks().register("bexApiEvidence", task -> {
            task.setGroup("verification");
            task.setDescription(
                    "Generates and verifies the BEX API descriptor, "
                            + "classification, and migration ledger.");
            task.dependsOn(classification);
        });
        project.afterEvaluate(ignored -> {
            if (project.getTasks().findByName("binaryApiCheck") != null) {
                evidence.configure(owner -> owner.dependsOn("binaryApiCheck"));
            }
            if (project.getTasks().findByName("generateBinaryApiManifest")
                    != null) {
                evidence.configure(owner ->
                        owner.dependsOn("generateBinaryApiManifest"));
                classification.configure(owner ->
                        owner.dependsOn("generateBinaryApiManifest"));
            }
        });
    }
}
