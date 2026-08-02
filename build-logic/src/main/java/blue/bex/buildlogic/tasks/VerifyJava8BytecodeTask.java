package blue.bex.buildlogic.tasks;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Verifies that every emitted production class is valid Java 8 bytecode. */
public abstract class VerifyJava8BytecodeTask extends DefaultTask {
    @Input
    public abstract Property<Boolean> getAllowEmpty();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassDirectories();

    @TaskAction
    public void verify() throws IOException {
        List<String> failures = new ArrayList<>();
        int[] count = {0};
        for (File root : getClassDirectories().getFiles()) {
            if (!root.isDirectory()) {
                continue;
            }
            java.nio.file.Files.walk(root.toPath())
                    .filter(path -> path.toString().endsWith(".class"))
                    .sorted()
                    .forEach(path -> {
                        count[0]++;
                        try (DataInputStream input = new DataInputStream(
                                new FileInputStream(path.toFile()))) {
                            int magic = input.readInt();
                            int minor = input.readUnsignedShort();
                            int major = input.readUnsignedShort();
                            if (magic != 0xCAFEBABE || major > 52) {
                                failures.add(root.toPath().relativize(path)
                                        + ":major=" + major + ":minor=" + minor);
                            }
                        } catch (IOException exception) {
                            throw new BytecodeReadException(exception);
                        }
                    });
        }
        if (count[0] == 0 && !getAllowEmpty().get()) {
            throw new GradleException("No production class files were verified");
        }
        if (!failures.isEmpty()) {
            throw new GradleException(
                    "Non-Java-8 BEX bytecode: " + String.join("; ", failures));
        }
    }

    private static final class BytecodeReadException extends RuntimeException {
        private BytecodeReadException(IOException cause) {
            super(cause);
        }
    }
}
