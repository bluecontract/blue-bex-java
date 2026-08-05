package blue.bex.buildlogic.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Fails when a removed pre-modular Language package returns to production. */
public abstract class VerifyLegacyLanguageImportsTask extends DefaultTask {
    private static final List<String> FORBIDDEN = Arrays.asList(
            "blue.language.utils.",
            "blue.language.snapshot.ResolvedSnapshot",
            "blue.language.NodeProvider",
            "blue.language.BlueOperationLimits",
            "blue.language.BlueOperationOutcome",
            "blue.language.BlueOperationResult");

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceDirectories();

    @TaskAction
    public void verify() throws IOException {
        List<String> failures = new ArrayList<>();
        for (File root : getSourceDirectories().getFiles()) {
            if (!root.isDirectory()) {
                continue;
            }
            Files.walk(root.toPath())
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String source = new String(
                                    Files.readAllBytes(path), StandardCharsets.UTF_8);
                            for (String forbidden : FORBIDDEN) {
                                if (source.contains("import " + forbidden)) {
                                    failures.add(root.toPath().relativize(path)
                                            + ":" + forbidden);
                                }
                            }
                        } catch (IOException exception) {
                            throw new SourceReadException(exception);
                        }
                    });
        }
        if (!failures.isEmpty()) {
            throw new GradleException(
                    "Removed Language imports in BEX production source: "
                            + String.join("; ", failures));
        }
    }

    private static final class SourceReadException extends RuntimeException {
        private SourceReadException(IOException cause) {
            super(cause);
        }
    }
}
