package blue.bex.buildlogic.tasks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Classifies every public BEX binary type from the same generated manifest. */
public abstract class GenerateApiClassificationTask extends DefaultTask {
    private static final Pattern TYPE = Pattern.compile(
            "^class .*?\\s(blue\\.bex\\.[^\\s]+)(?:\\s|$)");

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifestFile();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        try {
            File manifest = getManifestFile().get().getAsFile();
            List<String> lines = Files.readAllLines(
                    manifest.toPath(), StandardCharsets.UTF_8);
            Map<String, List<String>> categories = new LinkedHashMap<>();
            categories.put("stable API", new ArrayList<>());
            categories.put("host SPI", new ArrayList<>());
            categories.put("intrinsic SPI", new ArrayList<>());
            categories.put("internal implementation", new ArrayList<>());
            categories.put("conformance-only", new ArrayList<>());
            int descriptors = 0;
            for (String line : lines) {
                if (!line.startsWith("schema=")) {
                    descriptors++;
                }
                Matcher matcher = TYPE.matcher(line);
                if (matcher.find()) {
                    String type = matcher.group(1);
                    categories.get(classification(type)).add(type);
                }
            }
            int typeCount = 0;
            for (List<String> types : categories.values()) {
                Collections.sort(types);
                typeCount += types.size();
            }
            StringBuilder json = new StringBuilder();
            json.append("{\n")
                    .append("  \"schema\": \"blue-bex-public-api-classification/2.0\",\n")
                    .append("  \"inventory\": {\n")
                    .append("    \"path\": \"src/test/resources/hosted-release/required-public-api.txt\",\n")
                    .append("    \"sha256\": \"").append(sha256(manifest))
                    .append("\",\n")
                    .append("    \"manifestSchema\": \"blue-bex-binary-api-manifest/1.0\",\n")
                    .append("    \"publicTypeCount\": ").append(typeCount)
                    .append(",\n")
                    .append("    \"publicDescriptorCount\": ")
                    .append(descriptors).append("\n  },\n")
                    .append("  \"classifications\": {\n");
            int index = 0;
            for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
                json.append("    ").append(quote(entry.getKey())).append(": ")
                        .append(array(entry.getValue()));
                if (++index < categories.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("  },\n")
                    .append("  \"notes\": [\n")
                    .append("    \"Every public/protected binary type is classified exactly once from same-run manifest evidence.\",\n")
                    .append("    \"Public visibility does not promote an internal implementation type to stable API.\",\n")
                    .append("    \"Conformance-only types are absent from runtime module JARs.\"\n")
                    .append("  ]\n}\n");
            File output = getOutputFile().get().getAsFile();
            output.getParentFile().mkdirs();
            Files.write(output.toPath(),
                    json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new GradleException("Cannot classify BEX public API", exception);
        }
    }

    private static String classification(String type) {
        if (type.contains(".conformance.")) {
            return "conformance-only";
        }
        if (type.contains("BexIntrinsic")
                || type.endsWith("BexTypeBlueIdResolver")
                || type.endsWith("BexRuntimeIntrinsics")) {
            return "intrinsic SPI";
        }
        if (type.contains(".contracts.") || type.contains(".spi.")
                || type.contains("Boundary") || type.contains("Policy")
                || type.contains("DocumentView")
                || type.contains("GasLedgerHost")
                || type.contains("GasLedgerCapability")
                || type.contains("GasLedgerLifecycle")
                || type.contains("SharedGasBudget")
                || type.contains("HostGas")
                || type.endsWith("BexRuntimeContext")
                || type.endsWith("BexStepResultView")
                || type.startsWith("blue.bex.output.")) {
            return "host SPI";
        }
        if (type.startsWith("blue.bex.api.")
                || type.equals("blue.bex.BexException")
                || type.equals("blue.bex.BexSourcePath")
                || stableResult(type)
                || stableGas(type) || stableValue(type)
                || stableCompile(type)) {
            return "stable API";
        }
        return "internal implementation";
    }

    private static boolean stableGas(String type) {
        return type.matches("blue\\.bex\\.gas\\.BexGas(Charge|Counter|Ledger|LimitExceededException|Manifest|Meter|Schedule)(\\$.*)?");
    }

    private static boolean stableResult(String type) {
        return type.matches("blue\\.bex\\.result\\.Bex(Changeset|Events|ExecutionResult|Metrics|MetricsSnapshot|PatchEntry)(\\$.*)?");
    }

    private static boolean stableValue(String type) {
        return type.matches("blue\\.bex\\.value\\.Bex(Value|Values|ValueKind|UnicodeOrder)(\\$.*)?");
    }

    private static boolean stableCompile(String type) {
        return type.matches("blue\\.bex\\.compile\\.Bex(CompilationInput|CompiledProgram|CompiledProgramCache|CompiledProgramKey|Compiler)(\\$.*)?")
                || type.equals("blue.bex.compile.LruBexCompiledProgramCache");
    }

    private static String array(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder result = new StringBuilder("[\n");
        for (int index = 0; index < values.size(); index++) {
            result.append("      ").append(quote(values.get(index)));
            if (index + 1 < values.size()) {
                result.append(',');
            }
            result.append('\n');
        }
        return result.append("    ]").toString();
    }

    private static String sha256(File file)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
