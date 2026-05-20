package blue.bex;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class BexDependencyBoundaryTest {
    @Test
    void mainSourcesHaveNoContractQuickJsWasmOrProductSpecificReferences() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        List<String> forbidden = Arrays.asList(
                "blue-contract-java", "blue.contract", "quickjs", "QuickJS", "wasm", "WASM",
                "package-linked", "processCustomerPayNote", "reseller-weekend-package", "myos"
        );
        StringBuilder scanned = new StringBuilder();
        Files.walk(root.resolve("src/main/java"))
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        scanned.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).append('\n');
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
        scanned.append(new String(Files.readAllBytes(root.resolve("build.gradle.kts")), StandardCharsets.UTF_8));

        for (String value : forbidden) {
            assertFalse(scanned.toString().contains(value), "Forbidden dependency/reference found: " + value);
        }
    }
}
