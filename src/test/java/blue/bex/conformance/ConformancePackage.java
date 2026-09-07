package blue.bex.conformance;

import blue.bex.test.TestBlue;
import blue.language.model.Node;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resource and canonicalization support shared by the BEX 2.0 conformance
 * tests.
 *
 * <p>This class deliberately contains no engine calls. Keeping package
 * integrity independent from the evaluator makes corrupt, incomplete, or
 * silently skipped fixture packages fail before behavioral execution.</p>
 */
final class ConformancePackage {
    static final String ROOT = "conformance/bex/";
    static final String FIXTURE_ROOT = ROOT + "fixtures/";
    static final String FIXTURE_MANIFEST = FIXTURE_ROOT + "manifest.yaml";
    static final String GAS_MANIFEST = ROOT + "gas-manifest.yaml";
    static final String REGISTRY_ROOT = ROOT + "registry/";
    static final String REGISTRY_MANIFEST = REGISTRY_ROOT + "manifest.yaml";

    static final int FILE_COUNT = 162;
    static final int VECTOR_COUNT = 75;
    static final int BEHAVIOR_FIXTURE_COUNT = 120;
    static final int GAS_FIXTURE_COUNT = 30;
    static final int OPERATOR_COUNT = 86;

    static final Pattern FIXTURE_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");
    static final Pattern VECTOR_ID =
            Pattern.compile("^BEX-[A-Z]+-[0-9]{2}$");
    static final Pattern SHA_256 =
            Pattern.compile("^[0-9a-f]{64}$");
    static final Pattern PACKAGE_IDENTITY =
            Pattern.compile("^sha256:[0-9a-f]{64}$");

    private static final Yaml YAML = new Yaml();

    private ConformancePackage() {
    }

    static Map<String, Object> fixtureManifest() {
        return loadMap(FIXTURE_MANIFEST);
    }

    static Map<String, Object> gasManifest() {
        return loadMap(GAS_MANIFEST);
    }

    static Map<String, Object> registryManifest() {
        return loadMap(REGISTRY_MANIFEST);
    }

    static Map<String, Object> loadMap(String resource) {
        Object loaded = load(resource);
        if (!(loaded instanceof Map)) {
            throw new IllegalStateException("Expected a YAML object at " + resource);
        }
        return stringKeyMap((Map<?, ?>) loaded, resource);
    }

    static Object load(String resource) {
        try (InputStream input = resourceStream(resource)) {
            Object loaded = YAML.load(input);
            if (loaded == null) {
                throw new IllegalStateException("Empty YAML resource: " + resource);
            }
            return loaded;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read " + resource, ex);
        }
    }

    static List<ManifestFile> manifestFiles() {
        List<?> files = list(fixtureManifest().get("files"), "manifest.files");
        List<ManifestFile> entries = new ArrayList<ManifestFile>(files.size());
        for (Object value : files) {
            Map<String, Object> entry = map(value, "manifest.files[]");
            entries.add(new ManifestFile(
                    text(entry.get("path"), "manifest.files[].path"),
                    text(entry.get("role"), "manifest.files[].role"),
                    text(entry.get("sha256"), "manifest.files[].sha256"),
                    integer(entry.get("bytes"), "manifest.files[].bytes").longValue()));
        }
        return Collections.unmodifiableList(entries);
    }

    static List<ManifestFile> manifestFiles(String role) {
        List<ManifestFile> matches = new ArrayList<ManifestFile>();
        for (ManifestFile entry : manifestFiles()) {
            if (role.equals(entry.role)) {
                matches.add(entry);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    static List<Fixture> behaviorFixtures() {
        return fixtures("behavior-fixture");
    }

    static List<Fixture> gasFixtures() {
        return fixtures("gas-fixture");
    }

    private static List<Fixture> fixtures(String role) {
        List<Fixture> fixtures = new ArrayList<Fixture>();
        for (ManifestFile entry : manifestFiles(role)) {
            fixtures.add(new Fixture(entry.path, loadMap(FIXTURE_ROOT + entry.path)));
        }
        return Collections.unmodifiableList(fixtures);
    }

    static byte[] bytes(String resource) {
        try (InputStream input = resourceStream(resource)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read " + resource, ex);
        }
    }

    static byte[] lfNormalizedBytes(String resource) {
        String text = new String(bytes(resource), StandardCharsets.UTF_8);
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String packageIdentity(Map<String, Object> source, String... nullFields) {
        Map<String, Object> normalized = deepStringKeyMap(source, "package identity");
        for (String field : nullFields) {
            normalized.put(field, null);
        }
        return "sha256:" + sha256(json(normalized, true).getBytes(StandardCharsets.UTF_8));
    }

    static String json(Object value, boolean sortKeys) {
        StringBuilder result = new StringBuilder();
        appendJson(result, value, sortKeys);
        return result.toString();
    }

    static Node node(TestBlue blue, Object value) {
        return blue.jsonToNode(json(value, false));
    }

    /**
     * Builds an authored BEX syntax tree without letting Blue's source mapper
     * reinterpret reserved output-field names or infer exact scalar types.
     * Program maps are syntax: fields such as {@code blueId}, {@code type},
     * and {@code value} must remain ordinary expression fields until the BEX
     * output boundary.
     */
    static Node syntaxNode(Object value) {
        if (value == null) {
            /*
             * BEX syntax owns its null literal independently of Blue Source
             * preprocessing. Normalize YAML null to the registered $null
             * expression before freezing: a Blue source-null marker is
             * invalid after preprocessing, while a Java-null graph child is
             * not a valid immutable Language snapshot.
             */
            Map<String, Node> properties =
                    new LinkedHashMap<String, Node>();
            properties.put("$null", new Node().value(Boolean.TRUE));
            return new Node().properties(properties);
        }
        if (value instanceof Map) {
            Map<String, Node> properties =
                    new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Object> entry
                    : stringKeyMap((Map<?, ?>) value, "BEX syntax").entrySet()) {
                properties.put(entry.getKey(), syntaxNode(entry.getValue()));
            }
            return new Node().properties(properties);
        }
        if (value instanceof List) {
            List<Node> items = new ArrayList<Node>();
            for (Object child : (List<?>) value) {
                items.add(syntaxNode(child));
            }
            return new Node().items(items);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return new Node().value(
                    BigInteger.valueOf(((Number) value).longValue()));
        }
        if (value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Float || value instanceof Double
                || value instanceof Boolean || value instanceof String) {
            return new Node().value(value);
        }
        throw new IllegalArgumentException(
                "Unsupported BEX syntax scalar: "
                        + value.getClass().getName());
    }

    /**
     * Converts fixture context data to a semantic Blue object. Ordinary map
     * keys remain object properties even when their spelling is also a Blue
     * serialization field (for example a domain field named {@code items}).
     * Exact pure references and explicitly typed nodes retain their Blue
     * meaning.
     */
    static Node semanticNode(TestBlue blue, Object value) {
        if (value == null) {
            return new Node();
        }
        if (value instanceof Map) {
            Map<String, Object> map =
                    stringKeyMap((Map<?, ?>) value, "fixture context");
            if (map.size() == 1 && map.containsKey("blueId")) {
                return new Node().blueId(
                        text(map.get("blueId"), "fixture context blueId"));
            }
            if (isExplicitTypedNode(map)) {
                return blue.parseSourceJson(json(map, false));
            }
            Map<String, Node> properties =
                    new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                properties.put(
                        entry.getKey(),
                        semanticNode(blue, entry.getValue()));
            }
            return new Node().properties(properties);
        }
        if (value instanceof List) {
            List<Node> items = new ArrayList<Node>();
            for (Object child : (List<?>) value) {
                items.add(semanticNode(blue, child));
            }
            return new Node().items(items);
        }
        return syntaxNode(value);
    }

    private static boolean isExplicitTypedNode(
            Map<String, Object> map) {
        return map.containsKey("type")
                && (map.containsKey("value")
                || map.containsKey("items"));
    }

    static Path resourcePath(String resource) {
        URL url = ConformancePackage.class.getClassLoader().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing resource: " + resource);
        }
        if (!"file".equals(url.getProtocol())) {
            throw new IllegalStateException(
                    "Conformance resources must be exploded files for inventory validation: " + url);
        }
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Invalid resource URI: " + url, ex);
        }
    }

    static Set<String> regularResourcePaths(String root) {
        Path rootPath = resourcePath(root);
        try (Stream<Path> paths = Files.walk(rootPath)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(rootPath::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inventory " + root, ex);
        }
    }

    static Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return stringKeyMap((Map<?, ?>) value, path);
    }

    static List<?> list(Object value, String path) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        return (List<?>) value;
    }

    static String text(Object value, String path) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(path + " must be non-empty text");
        }
        return (String) value;
    }

    static BigInteger integer(Object value, String path) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        throw new IllegalArgumentException(path + " must be an integer");
    }

    static Set<String> stringSet(String... values) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        Collections.addAll(set, values);
        return Collections.unmodifiableSet(set);
    }

    static Set<String> operatorOccurrences(Object value) {
        LinkedHashSet<String> operators = new LinkedHashSet<String>();
        collectOperators(value, operators);
        return Collections.unmodifiableSet(operators);
    }

    static Object normalizedSemanticValue(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry
                    : stringKeyMap((Map<?, ?>) value, "semantic value").entrySet()) {
                result.put(entry.getKey(), normalizedSemanticValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object child : (List<?>) value) {
                result.add(normalizedSemanticValue(child));
            }
            return result;
        }
        return value;
    }

    private static InputStream resourceStream(String resource) {
        InputStream input = ConformancePackage.class.getClassLoader()
                .getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("Missing resource: " + resource);
        }
        return input;
    }

    private static void collectOperators(Object value, Set<String> result) {
        if (value instanceof Map) {
            for (Map.Entry<String, Object> entry
                    : stringKeyMap((Map<?, ?>) value, "program").entrySet()) {
                if (entry.getKey().startsWith("$")) {
                    result.add(entry.getKey());
                }
                collectOperators(entry.getValue(), result);
            }
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) {
                collectOperators(child, result);
            }
        }
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source, String path) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(path + " contains a non-text key");
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> deepStringKeyMap(
            Map<String, Object> source,
            String path) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), deepCopy(entry.getValue(), path + "." + entry.getKey()));
        }
        return result;
    }

    private static Object deepCopy(Object value, String path) {
        if (value instanceof Map) {
            return deepStringKeyMap(stringKeyMap((Map<?, ?>) value, path), path);
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object child : (List<?>) value) {
                result.add(deepCopy(child, path + "[]"));
            }
            return result;
        }
        return value;
    }

    private static void appendJson(StringBuilder target, Object value, boolean sortKeys) {
        if (value == null) {
            target.append("null");
            return;
        }
        if (value instanceof String || value instanceof Character) {
            appendJsonString(target, String.valueOf(value));
            return;
        }
        if (value instanceof Boolean) {
            target.append(value);
            return;
        }
        if (value instanceof Number) {
            appendJsonNumber(target, (Number) value);
            return;
        }
        if (value instanceof Map) {
            Map<String, Object> map = stringKeyMap((Map<?, ?>) value, "JSON value");
            List<Map.Entry<String, Object>> entries =
                    new ArrayList<Map.Entry<String, Object>>(map.entrySet());
            if (sortKeys) {
                Collections.sort(entries,
                        Comparator.comparing(Map.Entry<String, Object>::getKey));
            }
            target.append('{');
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) {
                    target.append(',');
                }
                Map.Entry<String, Object> entry = entries.get(index);
                appendJsonString(target, entry.getKey());
                target.append(':');
                appendJson(target, entry.getValue(), sortKeys);
            }
            target.append('}');
            return;
        }
        if (value instanceof Iterable) {
            target.append('[');
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                if (index++ > 0) {
                    target.append(',');
                }
                appendJson(target, item, sortKeys);
            }
            target.append(']');
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported JSON value type: " + value.getClass().getName());
    }

    private static void appendJsonNumber(StringBuilder target, Number value) {
        if (value instanceof Double) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
        } else if (value instanceof Float) {
            float number = value.floatValue();
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
        }
        target.append(value.toString());
    }

    private static void appendJsonString(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    target.append("\\\"");
                    break;
                case '\\':
                    target.append("\\\\");
                    break;
                case '\b':
                    target.append("\\b");
                    break;
                case '\f':
                    target.append("\\f");
                    break;
                case '\n':
                    target.append("\\n");
                    break;
                case '\r':
                    target.append("\\r");
                    break;
                case '\t':
                    target.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        target.append(String.format("\\u%04x", (int) character));
                    } else {
                        target.append(character);
                    }
            }
        }
        target.append('"');
    }

    static final class ManifestFile {
        final String path;
        final String role;
        final String sha256;
        final long bytes;

        ManifestFile(String path, String role, String sha256, long bytes) {
            this.path = path;
            this.role = role;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }

    static final class Fixture {
        final String path;
        final Map<String, Object> data;

        Fixture(String path, Map<String, Object> data) {
            this.path = path;
            this.data = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>(data));
        }

        String id() {
            return text(data.get("id"), path + ".id");
        }

        String category() {
            return text(data.get("category"), path + ".category");
        }

        Map<String, Object> program() {
            return map(data.get("program"), path + ".program");
        }

        Map<String, Object> context() {
            return map(data.get("context"), path + ".context");
        }

        Map<String, Object> expected() {
            return map(data.get("expected"), path + ".expected");
        }
    }
}
