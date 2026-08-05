package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.VerifyBexArchitectureTask;
import java.util.Arrays;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Registers the fail-closed architecture and cohesion gate. */
public final class ArchitectureVerificationPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getTasks().register(
                "verifyBexArchitecture",
                VerifyBexArchitectureTask.class,
                task -> {
                    task.setGroup("verification");
                    task.getRepositoryDirectory().set(
                            project.getRootProject().getLayout()
                                    .getProjectDirectory());
                    task.getArchitectureInputs().from(
                            project.getRootProject().fileTree(
                                    project.getRootProject().getProjectDir(), spec -> {
                                        spec.include(
                                                "blue-bex-core/src/main/java/**/*.java",
                                                "blue-bex-contracts/src/main/java/**/*.java",
                                                "blue-bex-conformance/src/main/java/**/*.java",
                                                "blue-bex-java/src/main/java/**/*.java",
                                                "examples/src/main/java/**/*.java",
                                                "blue-bex-core/build.gradle.kts",
                                                "blue-bex-contracts/build.gradle.kts",
                                                "blue-bex-conformance/build.gradle.kts",
                                                "blue-bex-java/build.gradle.kts",
                                                "examples/build.gradle.kts",
                                                "build.gradle.kts");
                                    }));
                    task.getModuleNames().set(Arrays.asList(
                            "blue-bex-core",
                            "blue-bex-contracts",
                            "blue-bex-conformance",
                            "blue-bex-java",
                            "examples"));
                    task.getModuleEdges().set(Arrays.asList(
                            "blue-bex-contracts->blue-bex-core",
                            "blue-bex-conformance->blue-bex-core",
                            "blue-bex-conformance->blue-bex-contracts",
                            "blue-bex-conformance->blue-bex-java",
                            "blue-bex-java->blue-bex-core",
                            "blue-bex-java->blue-bex-contracts",
                            "examples->blue-bex-java"));
                    task.getOutputFile().set(
                            project.getLayout().getBuildDirectory().file(
                                    "reports/bex-modernization/architecture.json"));
                });
    }
}
