package blue.bex.buildlogic.tasks;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Records the host and exact serious-campaign controls used by JMH. */
public abstract class GenerateBenchmarkEnvironmentTask extends DefaultTask {
    @Input
    public abstract Property<Integer> getForks();

    @Input
    public abstract Property<Integer> getWarmups();

    @Input
    public abstract Property<Integer> getMeasurements();

    @Input
    public abstract Property<String> getDuration();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void write() throws Exception {
        String json = "{\n"
                + "  \"schema\": \"blue-bex-jmh-environment/1.0\",\n"
                + "  \"os\": {\"name\":"
                + quote(System.getProperty("os.name")) + ",\"version\":"
                + quote(System.getProperty("os.version")) + ",\"arch\":"
                + quote(System.getProperty("os.arch")) + "},\n"
                + "  \"jvm\": {\"version\":"
                + quote(System.getProperty("java.version")) + ",\"vendor\":"
                + quote(System.getProperty("java.vendor")) + ",\"vmName\":"
                + quote(System.getProperty("java.vm.name")) + "},\n"
                + "  \"cpu\": {\"availableProcessors\":"
                + Runtime.getRuntime().availableProcessors() + "},\n"
                + "  \"campaign\": {\"forks\":" + getForks().get()
                + ",\"warmups\":" + getWarmups().get()
                + ",\"measurements\":" + getMeasurements().get()
                + ",\"iterationDuration\":" + quote(getDuration().get())
                + ",\"profilers\":[\"gc\"],"
                + "\"resultFormat\":\"json\"}\n"
                + "}\n";
        File output = getOutputFile().get().getAsFile();
        output.getParentFile().mkdirs();
        Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
