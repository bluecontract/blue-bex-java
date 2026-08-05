package blue.bex.buildlogic.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Verifies module/package ownership, acyclicity, and cohesion constraints. */
public abstract class VerifyBexArchitectureTask extends DefaultTask {
    private static final Pattern PACKAGE =
            Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern IMPORT =
            Pattern.compile("(?m)^import\\s+(?:static\\s+)?([a-zA-Z0-9_.*]+)\\s*;");
    private static final Pattern PROJECT_DEPENDENCY =
            Pattern.compile("project\\(\\s*\"(:[a-zA-Z0-9_-]+)\"\\s*\\)");

    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getArchitectureInputs();

    @Input
    public abstract ListProperty<String> getModuleNames();

    @Input
    public abstract ListProperty<String> getModuleEdges();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void verify() throws IOException {
        File root = getRepositoryDirectory().get().getAsFile();
        List<String> failures = new ArrayList<>();
        Map<String, Set<String>> packageOwners = new LinkedHashMap<>();
        Map<String, Set<String>> packageEdges = new LinkedHashMap<>();
        List<Source> sources = new ArrayList<>();

        for (String module : getModuleNames().get()) {
            File sourceRoot = new File(root, module + "/src/main/java");
            if (!sourceRoot.isDirectory()) {
                failures.add("missing-main-source-root:" + module);
                continue;
            }
            try (Stream<java.nio.file.Path> paths = Files.walk(sourceRoot.toPath())) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .forEach(path -> {
                        try {
                            String text = new String(
                                    Files.readAllBytes(path), StandardCharsets.UTF_8);
                            Matcher packageMatcher = PACKAGE.matcher(text);
                            if (!packageMatcher.find()) {
                                failures.add("missing-package:" + root.toPath()
                                        .relativize(path));
                                return;
                            }
                            String packageName = packageMatcher.group(1);
                            packageOwners.computeIfAbsent(
                                    packageName, ignored -> new LinkedHashSet<>())
                                    .add(module);
                            sources.add(new Source(module, path.toFile(),
                                    packageName, text));
                            packageEdges.computeIfAbsent(
                                    packageName, ignored -> new LinkedHashSet<>());
                        } catch (IOException exception) {
                            throw new ArchitectureReadException(exception);
                        }
                        });
            }
        }

        for (Map.Entry<String, Set<String>> entry : packageOwners.entrySet()) {
            if (entry.getValue().size() > 1) {
                failures.add("split-package:" + entry.getKey() + ":"
                        + String.join(",", entry.getValue()));
            }
        }

        Set<String> packages = packageOwners.keySet();
        for (Source source : sources) {
            if (source.text.contains("import blue.language.utils.")) {
                failures.add("legacy-language-utils:" + relative(root, source.file));
            }
            if (source.module.equals("blue-bex-core")
                    && source.text.contains("import blue.language.processor.")) {
                failures.add("core-processor-import:" + relative(root, source.file));
            }
            Matcher imports = IMPORT.matcher(source.text);
            while (imports.find()) {
                String imported = imports.group(1).replace(".*", "");
                String target = longestPackagePrefix(imported, packages);
                if (target != null && !target.equals(source.packageName)) {
                    packageEdges.get(source.packageName).add(target);
                }
            }
            int lines = source.text.split("\\R").length;
            String name = source.file.getName();
            if (name.equals("BexCompiler.java") && lines > 450) {
                failures.add("BexCompiler-lines:" + lines);
            }
            if (name.equals("BexExpressions.java")) {
                failures.add("BexExpressions-still-present");
            }
            if (name.equals("BexBlueTypeMatcher.java") && lines > 450) {
                failures.add("BexBlueTypeMatcher-lines:" + lines);
            }
            if (name.equals("BexGasMeter.java") && lines > 500) {
                failures.add("BexGasMeter-lines:" + lines);
            }
            if (lines > 800) {
                failures.add("production-file-over-800-lines:"
                        + relative(root, source.file) + ":" + lines);
            }
        }

        List<Set<String>> packageComponents = stronglyConnected(packageEdges);
        long packageCycles = packageComponents.stream()
                .filter(component -> component.size() > 1)
                .count();
        if (packageCycles != 0) {
            failures.add("package-sccs-larger-than-one:" + packageCycles);
        }

        Map<String, Set<String>> moduleGraph = new LinkedHashMap<>();
        for (String module : getModuleNames().get()) {
            moduleGraph.put(module, new LinkedHashSet<>());
        }
        for (String edge : getModuleEdges().get()) {
            String[] parts = edge.split("->", -1);
            if (parts.length != 2 || !moduleGraph.containsKey(parts[0])
                    || !moduleGraph.containsKey(parts[1])) {
                failures.add("invalid-module-edge:" + edge);
            } else {
                moduleGraph.get(parts[0]).add(parts[1]);
            }
        }
        int undeclaredModuleEdges = 0;
        for (String module : getModuleNames().get()) {
            File build = new File(root, module + "/build.gradle.kts");
            if (!build.isFile()) {
                failures.add("missing-module-build:" + module);
                continue;
            }
            String text = new String(
                    Files.readAllBytes(build.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = PROJECT_DEPENDENCY.matcher(text);
            while (matcher.find()) {
                String target = matcher.group(1).substring(1);
                if (target.equals(module)) {
                    failures.add("self-module-edge:" + module);
                    undeclaredModuleEdges++;
                } else if (!moduleGraph.get(module).contains(target)) {
                    failures.add("undeclared-module-edge:" + module + "->" + target);
                    undeclaredModuleEdges++;
                }
            }
        }
        long moduleCycles = stronglyConnected(moduleGraph).stream()
                .filter(component -> component.size() > 1)
                .count();
        if (moduleCycles != 0) {
            failures.add("module-cycles:" + moduleCycles);
        }

        File rootBuild = new File(root, "build.gradle.kts");
        long rootBuildLines = -1;
        if (rootBuild.isFile()) {
            try (Stream<String> lines = Files.lines(rootBuild.toPath())) {
                rootBuildLines = lines.count();
            }
        }
        if (rootBuildLines < 0 || rootBuildLines > 300) {
            failures.add("root-build-lines:" + rootBuildLines);
        }

        String json = "{\n"
                + "  \"schema\": \"blue-bex-architecture/1.0\",\n"
                + "  \"status\": \"" + (failures.isEmpty() ? "passed" : "failed")
                + "\",\n"
                + "  \"moduleCount\": " + moduleGraph.size() + ",\n"
                + "  \"moduleCycles\": " + moduleCycles + ",\n"
                + "  \"moduleEdges\": "
                + jsonArray(sortedEdges(moduleGraph)) + ",\n"
                + "  \"undeclaredModuleEdges\": "
                + undeclaredModuleEdges + ",\n"
                + "  \"packageCount\": " + packageOwners.size() + ",\n"
                + "  \"packageSccsLargerThanOne\": " + packageCycles + ",\n"
                + "  \"packageEdges\": "
                + jsonArray(sortedEdges(packageEdges)) + ",\n"
                + "  \"splitPackageCount\": "
                + packageOwners.values().stream().filter(owners -> owners.size() > 1)
                        .count() + ",\n"
                + "  \"rootBuildLines\": " + rootBuildLines + ",\n"
                + "  \"failures\": " + jsonArray(failures) + "\n"
                + "}\n";
        File output = getOutputFile().get().getAsFile();
        output.getParentFile().mkdirs();
        Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
        if (!failures.isEmpty()) {
            throw new GradleException(
                    "BEX architecture verification failed: "
                            + String.join("; ", failures));
        }
    }

    private static String longestPackagePrefix(
            String imported, Collection<String> packages) {
        String result = null;
        for (String packageName : packages) {
            if ((imported.equals(packageName)
                    || imported.startsWith(packageName + "."))
                    && (result == null || packageName.length() > result.length())) {
                result = packageName;
            }
        }
        return result;
    }

    private static <T> List<Set<T>> stronglyConnected(
            Map<T, Set<T>> graph) {
        Tarjan<T> tarjan = new Tarjan<>(graph);
        return tarjan.components();
    }

    private static String relative(File root, File file) {
        return root.toPath().relativize(file.toPath()).toString();
    }

    private static String jsonArray(List<String> values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            escaped.add("\"" + value.replace("\\", "\\\\")
                    .replace("\"", "\\\"") + "\"");
        }
        return "[" + String.join(",", escaped) + "]";
    }

    private static List<String> sortedEdges(Map<String, Set<String>> graph) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            for (String target : entry.getValue()) {
                result.add(entry.getKey() + "->" + target);
            }
        }
        Collections.sort(result);
        return result;
    }

    private static final class Source {
        private final String module;
        private final File file;
        private final String packageName;
        private final String text;

        private Source(String module, File file, String packageName, String text) {
            this.module = module;
            this.file = file;
            this.packageName = packageName;
            this.text = text;
        }
    }

    private static final class ArchitectureReadException
            extends RuntimeException {
        private ArchitectureReadException(IOException cause) {
            super(cause);
        }
    }

    private static final class Tarjan<T> {
        private final Map<T, Set<T>> graph;
        private final Map<T, Integer> indexes = new HashMap<>();
        private final Map<T, Integer> lowLinks = new HashMap<>();
        private final Deque<T> stack = new ArrayDeque<>();
        private final Set<T> onStack = new HashSet<>();
        private final List<Set<T>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<T, Set<T>> graph) {
            this.graph = graph;
        }

        private List<Set<T>> components() {
            for (T node : graph.keySet()) {
                if (!indexes.containsKey(node)) {
                    visit(node);
                }
            }
            return components;
        }

        private void visit(T node) {
            indexes.put(node, nextIndex);
            lowLinks.put(node, nextIndex);
            nextIndex++;
            stack.push(node);
            onStack.add(node);
            for (T target : graph.getOrDefault(node, Collections.emptySet())) {
                if (!indexes.containsKey(target)) {
                    visit(target);
                    lowLinks.put(node, Math.min(
                            lowLinks.get(node), lowLinks.get(target)));
                } else if (onStack.contains(target)) {
                    lowLinks.put(node, Math.min(
                            lowLinks.get(node), indexes.get(target)));
                }
            }
            if (lowLinks.get(node).equals(indexes.get(node))) {
                Set<T> component = new LinkedHashSet<>();
                T item;
                do {
                    item = stack.pop();
                    onStack.remove(item);
                    component.add(item);
                } while (!item.equals(node));
                components.add(component);
            }
        }
    }
}
