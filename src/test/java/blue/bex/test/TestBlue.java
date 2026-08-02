package blue.bex.test;

import blue.language.codec.BlueFormat;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-only convenience facade over the focused modular Language services.
 *
 * <p>This preserves the terse fixture setup formerly supplied by the
 * aggregate {@code Blue} facade without making BEX production or test code
 * depend on the aggregate Language artifact.</p>
 */
public final class TestBlue implements AutoCloseable {
    private static final Node BEX_PROGRAM_TYPE =
            new Node().name("Blue/BEX Program");
    private static final String BEX_PROGRAM_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(BEX_PROGRAM_TYPE);
    private static final Node BEX_DEFINITION_TYPE =
            new Node().name("Blue/BEX Definition");
    private static final String BEX_DEFINITION_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(BEX_DEFINITION_TYPE);
    private static final String BEX_EMPTY_OPERATOR = "$empty";
    private static final String SHIELDED_BEX_EMPTY_OPERATOR =
            "__blue_bex_preprocess_shielded_empty_operator__";
    private final BlueLanguage language;
    private final NodeProvider provider;
    private DocumentProcessor documentProcessor;

    public TestBlue() {
        this(blueId -> null);
    }

    public TestBlue(NodeProvider provider) {
        ContractProcessorRegistry contracts =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .build()
                        .snapshot();
        this.provider = new SequentialNodeProvider(Arrays.asList(
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider(),
                contracts.exactTypeProvider(),
                TestBlue::bexTypeByBlueId,
                provider));
        Map<String, String> bexAliases = new LinkedHashMap<>();
        bexAliases.put(BEX_PROGRAM_TYPE.getName(),
                BEX_PROGRAM_TYPE_BLUE_ID);
        bexAliases.put(BEX_DEFINITION_TYPE.getName(),
                BEX_DEFINITION_TYPE_BLUE_ID);
        Map<String, String> environmentImports =
                new LinkedHashMap<>(RuntimeTypeAliases.NAME_TO_BLUE_ID);
        environmentImports.putAll(bexAliases);
        this.language = BlueLanguage.builder()
                .nodeProvider(this.provider)
                .preprocessingAliases(bexAliases)
                .environmentImports(environmentImports)
                .build();
    }

    public BlueLanguage runtime() {
        return language;
    }

    public Node yamlToNode(String yaml) {
        return language.preprocessing().preprocess(
                language.codec().parseSource(yaml, BlueFormat.YAML));
    }

    /**
     * Parses a BEX-bearing Source Document without letting BEX's
     * {@code $empty} expression operator collide with Blue Language's
     * identically spelled list-placeholder control.
     *
     * <p>The operator key is replaced only across this explicit BEX source
     * boundary, the ordinary mandatory preprocessing pipeline is then run,
     * and the key is restored by rebuilding every property map in its
     * original insertion order. {@link #yamlToNode(String)} deliberately does
     * not use this boundary, so ordinary Blue documents retain strict
     * malformed-placeholder validation.</p>
     */
    public Node yamlToBexSource(String yaml) {
        Node source = language.codec().parseSource(yaml, BlueFormat.YAML);
        rewritePropertyKey(
                source,
                BEX_EMPTY_OPERATOR,
                SHIELDED_BEX_EMPTY_OPERATOR,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
        Node preprocessed = language.preprocessing().preprocess(source);
        rewritePropertyKey(
                preprocessed,
                SHIELDED_BEX_EMPTY_OPERATOR,
                BEX_EMPTY_OPERATOR,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
        return preprocessed;
    }

    public Node jsonToNode(String json) {
        return language.preprocessing().preprocess(
                parseSourceJson(json));
    }

    public Node parseSourceJson(String json) {
        return language.codec().parseSource(json, BlueFormat.JSON);
    }

    /**
     * Parses authored YAML without resolving environment-owned type aliases.
     * This is used when a fixture is an external event payload whose host
     * registry is intentionally outside BEX's test composition.
     */
    public Node parseSourceYaml(String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }

    public Node expand(Node node) {
        return language.graph().expand(node);
    }

    public ResolvedSnapshot resolveToSnapshot(Node node) {
        return language.snapshots().resolve(node);
    }

    public String calculateBlueId(Node node) {
        return language.identity().directBlueId(node);
    }

    public boolean nodeMatchesType(Node candidate, Node type) {
        return language.matching().matches(candidate, type);
    }

    /**
     * Legacy processor-construction support for the hosted adapter test.
     * New integration code should use the focused Contracts composition.
     */
    public DocumentProcessor getDocumentProcessor() {
        if (documentProcessor == null) {
            documentProcessor = DocumentProcessor.builder()
                    .nodeProvider(provider)
                    .matchingService(new ContractMatchingService(
                            language.processing().runtimeAccess()))
                    .build();
        }
        return documentProcessor;
    }

    private static List<Node> bexTypeByBlueId(String blueId) {
        if (BEX_PROGRAM_TYPE_BLUE_ID.equals(blueId)) {
            return Collections.singletonList(BEX_PROGRAM_TYPE.clone());
        }
        if (BEX_DEFINITION_TYPE_BLUE_ID.equals(blueId)) {
            return Collections.singletonList(BEX_DEFINITION_TYPE.clone());
        }
        return Collections.emptyList();
    }

    private static void rewritePropertyKey(
            Node node,
            String from,
            String to,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        rewritePropertyKey(node.getType(), from, to, visited);
        rewritePropertyKey(node.getItemType(), from, to, visited);
        rewritePropertyKey(node.getKeyType(), from, to, visited);
        rewritePropertyKey(node.getValueType(), from, to, visited);
        rewritePropertyKey(node.getContracts(), from, to, visited);
        rewritePropertyKey(node.getBlue(), from, to, visited);
        rewriteSchemaPropertyKeys(node.getSchema(), from, to, visited);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                rewritePropertyKey(item, from, to, visited);
            }
        }
        Map<String, Node> properties = node.getProperties();
        if (properties == null) {
            return;
        }
        if (properties.containsKey(from) && properties.containsKey(to)) {
            throw new IllegalArgumentException(
                    "BEX source contains the reserved preprocessing shield key: "
                            + to);
        }
        Map<String, Node> rewritten = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : properties.entrySet()) {
            String key = from.equals(entry.getKey()) ? to : entry.getKey();
            rewritten.put(key, entry.getValue());
            rewritePropertyKey(entry.getValue(), from, to, visited);
        }
        node.properties(rewritten);
    }

    private static void rewriteSchemaPropertyKeys(
            Schema schema,
            String from,
            String to,
            Set<Node> visited) {
        if (schema == null) {
            return;
        }
        rewritePropertyKey(schema.getRequired(), from, to, visited);
        rewritePropertyKey(schema.getMinLength(), from, to, visited);
        rewritePropertyKey(schema.getMaxLength(), from, to, visited);
        rewritePropertyKey(schema.getMinimum(), from, to, visited);
        rewritePropertyKey(schema.getMaximum(), from, to, visited);
        rewritePropertyKey(schema.getExclusiveMinimum(), from, to, visited);
        rewritePropertyKey(schema.getExclusiveMaximum(), from, to, visited);
        rewritePropertyKey(schema.getMultipleOf(), from, to, visited);
        rewritePropertyKey(schema.getMinItems(), from, to, visited);
        rewritePropertyKey(schema.getMaxItems(), from, to, visited);
        rewritePropertyKey(schema.getUniqueItems(), from, to, visited);
        rewritePropertyKey(schema.getMinFields(), from, to, visited);
        rewritePropertyKey(schema.getMaxFields(), from, to, visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                rewritePropertyKey(value, from, to, visited);
            }
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        if (documentProcessor != null) {
            try {
                documentProcessor.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
        try {
            language.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else if (failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
