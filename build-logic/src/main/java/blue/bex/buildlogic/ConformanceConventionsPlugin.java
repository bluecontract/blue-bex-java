package blue.bex.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/** Owns the complete, non-skipping BEX conformance lifecycle. */
public final class ConformanceConventionsPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);
        TaskProvider<Test> test = project.getTasks().named("test", Test.class);
        test.configure(task -> {
            task.setFailFast(false);
            task.getOutputs().upToDateWhen(ignored -> false);
        });
        project.getTasks().register("bexConformance", task -> {
            task.setGroup("verification");
            task.setDescription(
                    "Runs the complete BEX semantic, fixture, gas, and hosted suite.");
            task.dependsOn(test);
        });
    }
}
