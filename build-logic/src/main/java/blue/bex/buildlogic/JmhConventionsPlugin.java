package blue.bex.buildlogic;

import blue.bex.buildlogic.tasks.GenerateBenchmarkEnvironmentTask;
import java.util.Arrays;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

/**
 * Adds a dedicated JMH source set and both serious and bounded evidence runs.
 *
 * <p>The convention deliberately avoids reflection and an opaque third-party
 * Gradle plugin.  The benchmark compiler and runner therefore have the same
 * typed, reviewable dependency graph as the rest of the build.
 */
public final class JmhConventionsPlugin implements Plugin<Project> {
    private static final String JMH_VERSION = "1.37";
    private static final int SERIOUS_FORKS = 2;
    private static final int SERIOUS_WARMUPS = 3;
    private static final int SERIOUS_MEASUREMENTS = 5;
    private static final String SERIOUS_DURATION = "250ms";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);
        JavaPluginExtension java =
                project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = java.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);
        SourceSet jmh = java.getSourceSets().create("jmh", sourceSet -> {
            sourceSet.setCompileClasspath(
                    sourceSet.getCompileClasspath()
                            .plus(main.getOutput())
                            .plus(test.getOutput()));
            sourceSet.setRuntimeClasspath(
                    sourceSet.getRuntimeClasspath()
                            .plus(sourceSet.getOutput())
                            .plus(main.getOutput())
                            .plus(test.getOutput()));
        });

        project.getConfigurations().getByName(jmh.getImplementationConfigurationName())
                .extendsFrom(project.getConfigurations().getByName(
                        test.getImplementationConfigurationName()));
        project.getDependencies().add(
                jmh.getImplementationConfigurationName(),
                "org.openjdk.jmh:jmh-core:" + JMH_VERSION);
        project.getDependencies().add(
                jmh.getAnnotationProcessorConfigurationName(),
                "org.openjdk.jmh:jmh-generator-annprocess:" + JMH_VERSION);

        TaskProvider<GenerateBenchmarkEnvironmentTask> environment =
                project.getTasks().register(
                        "jmhEnvironment",
                        GenerateBenchmarkEnvironmentTask.class,
                        task -> {
                            task.setGroup("benchmark");
                            task.getForks().set(SERIOUS_FORKS);
                            task.getWarmups().set(SERIOUS_WARMUPS);
                            task.getMeasurements().set(SERIOUS_MEASUREMENTS);
                            task.getDuration().set(SERIOUS_DURATION);
                            task.getOutputFile().set(
                                    project.getLayout().getBuildDirectory().file(
                                            "reports/jmh/environment.json"));
                        });
        TaskProvider<JavaExec> campaign = project.getTasks().register(
                "jmh", JavaExec.class, task -> {
                    task.setGroup("benchmark");
                    task.setDescription(
                            "Runs the serious BEX JMH campaign with allocation "
                                    + "profiling and writes JSON evidence.");
                    task.dependsOn(jmh.getClassesTaskName(), environment);
                    task.setClasspath(jmh.getRuntimeClasspath());
                    task.getMainClass().set("org.openjdk.jmh.Main");
                    task.doFirst(ignored -> {
                        project.getLayout().getBuildDirectory().dir(
                                "reports/jmh").get().getAsFile().mkdirs();
                        task.setArgs(Arrays.asList(
                                "-f", String.valueOf(SERIOUS_FORKS),
                                "-wi", String.valueOf(SERIOUS_WARMUPS),
                                "-i", String.valueOf(SERIOUS_MEASUREMENTS),
                                "-w", SERIOUS_DURATION,
                                "-r", SERIOUS_DURATION,
                                "-prof", "gc", "-foe", "true",
                                "-rf", "json", "-rff",
                                project.getLayout().getBuildDirectory().file(
                                        "reports/jmh/results.json")
                                        .get().getAsFile().getAbsolutePath()));
                    });
                });

        project.getTasks().register("jmhSmoke", JavaExec.class, task -> {
            task.setGroup("verification");
            task.setDescription(
                    "Runs every BEX benchmark with bounded iterations as same-run "
                            + "correctness and gas-identity evidence.");
            task.dependsOn(jmh.getClassesTaskName());
            task.setClasspath(jmh.getRuntimeClasspath());
            task.getMainClass().set("org.openjdk.jmh.Main");
            task.doFirst(ignored -> {
                project.getLayout().getBuildDirectory().dir(
                        "reports/jmh").get().getAsFile().mkdirs();
                task.setArgs(Arrays.asList(
                        "-f", "1", "-wi", "1", "-i", "1",
                        "-w", "100ms", "-r", "100ms", "-foe", "true",
                        "-rf", "json", "-rff",
                        project.getLayout().getBuildDirectory().file(
                                "reports/jmh/smoke-results.json")
                                .get().getAsFile().getAbsolutePath()));
            });
        });
    }
}
