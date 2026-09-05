package blue.bex.conformance;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexIntrinsicInvocation;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasCharge;
import blue.bex.output.BexEstablishedIdentity;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexPatchEntry;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.test.TestBlue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepDo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential proof that physical Blue representation and provider delivery
 * are outside portable BEX semantics.
 */
class BexRepresentationInvarianceTest {
    private static final String SORT_INTRINSIC =
            "3x6byASNDdnEf9o2EgzAVqewP1zuqyNmzccAmyiYfQsw";
    private static final String REPRESENTATION_REGISTRY =
            "sha256:858952520d947dcffa773f7479434d86367b22f1a4ddabde90b8c19826710a62";

    @Test
    void programDocumentAndEventAreInvariantAcrossPhysicalRepresentations() {
        LogicalInputs inputs = LogicalInputs.create();
        List<Variant> variants = Arrays.asList(
                new Variant("fully-inline-exact",
                        RootForm.INLINE, ProviderForm.ONE_NODE,
                        CacheForm.COLD, ExactForm.CANONICAL_AND_RESOLVED),
                new Variant("pure-references-exact",
                        RootForm.REFERENCE, ProviderForm.ONE_NODE,
                        CacheForm.COLD, ExactForm.CANONICAL_AND_RESOLVED),
                new Variant("partially-materialized-exact",
                        RootForm.PARTIAL, ProviderForm.ONE_NODE,
                        CacheForm.COLD, ExactForm.CANONICAL_AND_RESOLVED),
                new Variant("cold-batched-references-exact",
                        RootForm.REFERENCE, ProviderForm.BATCHED,
                        CacheForm.COLD, ExactForm.CANONICAL_AND_RESOLVED),
                new Variant("warm-batched-references-exact",
                        RootForm.REFERENCE, ProviderForm.BATCHED,
                        CacheForm.WARM, ExactForm.CANONICAL_AND_RESOLVED),
                new Variant("cold-batched-partial-materialized",
                        RootForm.PARTIAL, ProviderForm.BATCHED,
                        CacheForm.COLD, ExactForm.MATERIALIZED),
                new Variant("warm-one-node-reference-materialized",
                        RootForm.REFERENCE, ProviderForm.ONE_NODE,
                        CacheForm.WARM, ExactForm.MATERIALIZED));

        List<Observation> observations = new ArrayList<Observation>();
        for (Variant variant : variants) {
            observations.add(execute(inputs, variant));
        }

        Observation expected = observations.get(0);
        assertEquals("success", expected.diagnosticCategory);
        assertNotNull(expected.compiledProgramBlueId);
        assertNotNull(expected.outputBlueId);
        assertEquals(1, expected.semanticBoundaryCalls);
        assertEquals(Arrays.asList("/state", "/marker"),
                expected.changePaths);
        assertEquals(2, ((List<?>) expected.events).size());
        assertRequiredObservations(expected.semanticResult);
        assertTrue(expected.portableTrace.stream()
                        .anyMatch(charge ->
                                "sortComparison".equals(
                                        charge.counterName())),
                "representation differential must execute stable sort work");

        for (Observation actual : observations) {
            assertEquals("success", actual.diagnosticCategory,
                    actual.name + " diagnostic category");
            assertEquals(expected.compiledProgramBlueId,
                    actual.compiledProgramBlueId,
                    actual.name + " compiled program identity");
            assertEquals(expected.semanticResult, actual.semanticResult,
                    actual.name + " semantic result");
            assertEquals(expected.outputBlueId, actual.outputBlueId,
                    actual.name + " output BlueId");
            assertEquals(expected.changes, actual.changes,
                    actual.name + " ordered changes");
            assertEquals(expected.changePaths, actual.changePaths,
                    actual.name + " change path order");
            assertEquals(expected.events, actual.events,
                    actual.name + " ordered events");
            assertEquals(expected.portableTrace, actual.portableTrace,
                    actual.name + " full portable named trace");
            assertEquals(expected.totalGas, actual.totalGas,
                    actual.name + " total gas");
            assertEquals(1, actual.semanticBoundaryCalls,
                    actual.name + " semantic boundary invocation count");
        }

        Observation coldBatch = find(
                observations, "cold-batched-references-exact");
        Observation warmBatch = find(
                observations, "warm-batched-references-exact");
        Observation coldOneNode = find(
                observations, "pure-references-exact");
        assertEquals(1, coldBatch.physicalBatchLoads,
                "cold batched delivery performs one physical catalog load");
        assertEquals(0, warmBatch.physicalBatchLoads,
                "warm batched delivery reuses the preloaded catalog");
        assertTrue(coldOneNode.physicalNodeLoads > 1,
                "one-node delivery obtains fragments independently");
        assertTrue(coldOneNode.providerDemands
                        >= warmBatch.providerDemands,
                "provider demand counts are allowed to collapse when warm");
    }

    @Test
    void coldBatchedRepresentationsUseIndependentRuntimeCaches() {
        LogicalInputs inputs = LogicalInputs.create();
        Variant coldBatched = new Variant(
                "runtime-isolated-cold-batched",
                RootForm.REFERENCE,
                ProviderForm.BATCHED,
                CacheForm.COLD,
                ExactForm.CANONICAL_AND_RESOLVED);

        Observation first = execute(inputs, coldBatched);
        Observation second = execute(inputs, coldBatched);

        assertEquals("success", first.diagnosticCategory);
        assertEquals("success", second.diagnosticCategory);
        assertEquals(1, first.physicalBatchLoads,
                "first cold runtime must load its own provider batch");
        assertEquals(1, second.physicalBatchLoads,
                "second cold runtime must not inherit the first cache");
        assertEquals(first.providerDemands, second.providerDemands);
        assertEquals(first.compiledProgramBlueId,
                second.compiledProgramBlueId);
        assertEquals(first.semanticResult, second.semanticResult);
        assertEquals(first.outputBlueId, second.outputBlueId);
        assertEquals(first.changes, second.changes);
        assertEquals(first.changePaths, second.changePaths);
        assertEquals(first.events, second.events);
        assertEquals(first.portableTrace, second.portableTrace);
        assertEquals(first.totalGas, second.totalGas);
        assertEquals(first.semanticBoundaryCalls,
                second.semanticBoundaryCalls);
    }

    private static Observation execute(
            LogicalInputs inputs,
            Variant variant) {
        /*
         * BEX syntax scalar nodes intentionally retain compiler-only inferred
         * metadata, so cut the program only at its statement-list boundary.
         * Ordinary document/event data can use fully shallow fragmentation.
         */
        ExactNodeGraphFragments programGraph =
                ExactNodeGraphFragments.split(
                        inputs.program,
                        Collections.singletonList("/do"));
        ExactNodeGraphFragments documentGraph =
                new ExactNodeGraphFragments(inputs.document);
        ExactNodeGraphFragments eventGraph =
                new ExactNodeGraphFragments(inputs.event);
        Map<String, Node> catalog =
                new LinkedHashMap<String, Node>();
        catalog.putAll(programGraph.fragments());
        catalog.putAll(documentGraph.fragments());
        catalog.putAll(eventGraph.fragments());
        catalog.put(SORT_INTRINSIC, inputs.intrinsicType.clone());
        VariantNodeProvider provider = new VariantNodeProvider(
                catalog, variant.providerForm);
        Node programRoot = present(
                programGraph.roots().get(0), variant.rootForm);
        Node documentRoot = present(
                documentGraph.roots().get(0), variant.rootForm);
        Node eventRoot = present(
                eventGraph.roots().get(0), variant.rootForm);

        try (TestBlue blue = new TestBlue(provider)) {
            if (variant.cacheForm == CacheForm.WARM) {
                materialize(blue, programRoot, variant.exactForm);
                materialize(blue, documentRoot, variant.exactForm);
                materialize(blue, eventRoot, variant.exactForm);
            }

            ExactPair program =
                    materialize(blue, programRoot, variant.exactForm);
            ExactPair document = runtimePair(
                    blue, documentRoot, variant.exactForm);
            ExactPair event = runtimePair(
                    blue, eventRoot, variant.exactForm);
            CountingIdentityBoundary boundary =
                    new CountingIdentityBoundary();
            BexEngine engine = BexEngine.builder()
                    .language(blue.runtime())
                    .intrinsics(representationIntrinsics())
                    .build();
            BexCompiledProgram compiled = engine.compile(
                    BexProgramSource.inline(FrozenNode.fromResolvedNode(
                            prepareProgramTypes(blue, program.resolved.toNode()))));
            FrozenBexDocumentView documentView =
                    new FrozenBexDocumentView(
                            document.canonical, document.resolved, "/");
            BexValue eventValue = BexValues.exact(
                    event.canonical, event.resolved);
            BexExecutionContext context = BexExecutionContext.builder()
                    .document(documentView)
                    .event(eventValue)
                    .semanticIdentityBoundary(boundary)
                    .gasLimit(1_000_000L)
                    .build();

            /*
             * Program selection/compilation and any deliberately materialized
             * exact inputs are host preparation. Reset the provider's physical
             * diagnostics immediately before BEX runtime so reference,
             * partial, warm/cold, and batched/one-node observations below are
             * genuine execution-time demands.
             */
            provider.beginRuntime(
                    variant.cacheForm == CacheForm.WARM);
            try {
                BexExecutionResult result =
                        engine.execute(compiled, context);
                List<String> changePaths = new ArrayList<String>();
                for (BexPatchEntry entry
                        : result.changeset().entries()) {
                    changePaths.add(entry.absolutePath());
                }
                return new Observation(
                        variant.name,
                        "success",
                        compiled.programBlueId(),
                        result.value().toSimple(),
                        result.output().nodeBlueId(),
                        result.changeset().asValue().toSimple(),
                        changePaths,
                        result.events().asValue().toSimple(),
                        result.gasTrace(),
                        result.gasUsed(),
                        boundary.calls,
                        provider.demands.size(),
                        provider.physicalNodeLoads,
                        provider.physicalBatchLoads);
            } catch (RuntimeException failure) {
                return new Observation(
                        variant.name,
                        diagnosticCategory(failure),
                        compiled.programBlueId(),
                        null,
                        null,
                        Collections.emptyList(),
                        Collections.<String>emptyList(),
                        Collections.emptyList(),
                        Collections.<BexGasCharge>emptyList(),
                        0L,
                        boundary.calls,
                        provider.demands.size(),
                        provider.physicalNodeLoads,
                        provider.physicalBatchLoads);
            }
        }
    }

    private static ExactPair runtimePair(
            TestBlue blue,
            Node presented,
            ExactForm exactForm) {
        if (exactForm == ExactForm.MATERIALIZED) {
            return materialize(blue, presented, exactForm);
        }
        /*
         * Preserve the presented physical form in both lanes. Pure references
         * and nested references are materialized lazily by BEX through the
         * verified Blue provider boundary only when execution reads them.
         */
        return new ExactPair(
                FrozenNode.fromNode(presented),
                FrozenNode.fromResolvedNode(presented));
    }

    private static ExactPair materialize(
            TestBlue blue,
            Node presented,
            ExactForm exactForm) {
        blue.language.merge.ResolvedSnapshot snapshot = blue.resolveToSnapshot(presented);
        return new ExactPair(snapshot.frozenCanonicalRoot(),
                FrozenNode.fromResolvedNode(blue.expand(presented)));
    }

    /** Host preparation preserves exact type references while opening program bodies. */
    private static Node prepareProgramTypes(TestBlue blue, Node expanded) {
        Node prepared = expanded.clone();
        if (prepared.getType() != null)
            prepared.type(blue.runtime().graph().collapse(prepared.getType()));
        if (prepared.getItemType() != null)
            prepared.itemType(blue.runtime().graph().collapse(prepared.getItemType()));
        if (prepared.getKeyType() != null)
            prepared.keyType(blue.runtime().graph().collapse(prepared.getKeyType()));
        if (prepared.getValueType() != null)
            prepared.valueType(blue.runtime().graph().collapse(prepared.getValueType()));
        if (prepared.getProperties() != null)
            prepared.getProperties().replaceAll((key, value) -> prepareProgramTypes(blue, value));
        if (prepared.getItems() != null)
            prepared.getItems().replaceAll(value -> prepareProgramTypes(blue, value));
        return prepared;
    }

    private static Node present(
            ExactNodeGraphFragments.RootRepresentation root,
            RootForm form) {
        switch (form) {
            case INLINE:
                return root.original();
            case PARTIAL:
                return root.directFragment();
            case REFERENCE:
                return root.pureReference();
            default:
                throw new AssertionError(form);
        }
    }

    private static String diagnosticCategory(RuntimeException failure) {
        return "runtime-error:" + failure.getClass().getName();
    }

    private static Observation find(
            List<Observation> observations,
            String name) {
        for (Observation observation : observations) {
            if (name.equals(observation.name)) {
                return observation;
            }
        }
        throw new AssertionError("Missing observation " + name);
    }

    private static Node document(String pointer) {
        return op("$document", pointer);
    }

    private static Node event(String pointer) {
        return op("$event", pointer);
    }

    private static BexIntrinsicRegistry representationIntrinsics() {
        Map<String, Long> weights =
                new LinkedHashMap<String, Long>();
        weights.put("sortComparison", 1L);
        return BexIntrinsicRegistry.builder()
                .register(
                        SORT_INTRINSIC,
                        REPRESENTATION_REGISTRY,
                        weights,
                        invocation -> {
                            BexValue values =
                                    invocation.field("values");
                            assertTrue(values.isList(),
                                    "sort values must be a list");
                            List<BexValue> sorted =
                                    new ArrayList<BexValue>();
                            for (int index = 0;
                                 index < values.size();
                                 index++) {
                                sorted.add(values.get(
                                        String.valueOf(index)));
                            }
                            stableSort(sorted, invocation);
                            return BexValues.list(sorted);
                        })
                .build();
    }

    private static void stableSort(
            List<BexValue> values,
            BexIntrinsicInvocation invocation) {
        for (int index = 1; index < values.size(); index++) {
            BexValue candidate = values.get(index);
            int insertion = index;
            while (insertion > 0) {
                invocation.charge(
                        "sortComparison",
                        1L,
                        "representation-stable-sort");
                BexValue previous = values.get(insertion - 1);
                if (previous.get("rank").asNumber().compareTo(
                        candidate.get("rank").asNumber()) <= 0) {
                    break;
                }
                values.set(insertion, previous);
                insertion--;
            }
            values.set(insertion, candidate);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertRequiredObservations(Object result) {
        assertTrue(result instanceof Map);
        Map<String, Object> observations =
                (Map<String, Object>) result;
        assertEquals("object", observations.get("kind"));
        assertEquals(true, observations.get("exists"));
        assertEquals(
                Arrays.asList("id", "state", "values"),
                observations.get("keys"));
        assertEquals(3,
                ((List<?>) observations.get("entries")).size());
        assertEquals(
                java.math.BigInteger.valueOf(3L),
                observations.get("size"));
        assertEquals("ready", observations.get("pointerRead"));
        assertTrue(observations.get("exactIdentity")
                instanceof String);
        assertEquals(true,
                observations.get("identityAwareEquality"));
        assertEquals(true, observations.get("deepEquality"));
        assertEquals(true, observations.get("matching"));
        assertEquals(true, observations.get("truthiness"));
        assertEquals(
                Arrays.asList(
                        java.math.BigInteger.ONE,
                        java.math.BigInteger.valueOf(2L),
                        java.math.BigInteger.valueOf(3L)),
                observations.get("iteration"));
        List<Object> sorted =
                (List<Object>) observations.get("stableSort");
        assertEquals(3, sorted.size());
        assertEquals("first",
                ((Map<String, Object>) sorted.get(0)).get("tag"));
        assertEquals("second",
                ((Map<String, Object>) sorted.get(1)).get("tag"));
        assertEquals("last",
                ((Map<String, Object>) sorted.get(2)).get("tag"));
    }

    private enum RootForm {
        INLINE,
        REFERENCE,
        PARTIAL
    }

    private enum ProviderForm {
        ONE_NODE,
        BATCHED
    }

    private enum CacheForm {
        COLD,
        WARM
    }

    private enum ExactForm {
        CANONICAL_AND_RESOLVED,
        MATERIALIZED
    }

    private static final class Variant {
        private final String name;
        private final RootForm rootForm;
        private final ProviderForm providerForm;
        private final CacheForm cacheForm;
        private final ExactForm exactForm;

        private Variant(String name,
                        RootForm rootForm,
                        ProviderForm providerForm,
                        CacheForm cacheForm,
                        ExactForm exactForm) {
            this.name = name;
            this.rootForm = rootForm;
            this.providerForm = providerForm;
            this.cacheForm = cacheForm;
            this.exactForm = exactForm;
        }
    }

    private static final class ExactPair {
        private final FrozenNode canonical;
        private final FrozenNode resolved;

        private ExactPair(FrozenNode canonical, FrozenNode resolved) {
            this.canonical = canonical;
            this.resolved = resolved;
        }
    }

    private static final class LogicalInputs {
        private final Node program;
        private final Node document;
        private final Node event;
        private final Node intrinsicType;

        private LogicalInputs(Node program,
                              Node document,
                              Node event,
                              Node intrinsicType) {
            this.program = program;
            this.document = document;
            this.event = event;
            this.intrinsicType = intrinsicType;
        }

        private static LogicalInputs create() {
            Node authoredDocument = obj(
                    "subject", obj(
                            "id", "D-1",
                            "state", "ready",
                            "values", list(1, 2, 3)),
                    "sortable", list(
                            obj("rank", 2, "tag", "last"),
                            obj("rank", 1, "tag", "first"),
                            obj("rank", 1, "tag", "second")),
                    "marker", "document-marker");
            Node authoredEvent = obj(
                    "payload", obj(
                            "id", "E-1",
                            "amount", 7),
                    "mirrorSubject", obj(
                            "id", "D-1",
                            "state", "ready",
                            "values", list(1, 2, 3)),
                    "followup", obj(
                            "kind", "Followup",
                            "ordinal", 2));
            /*
             * Physical references must address already admitted semantic
             * content. Normalize authored test data once before fragmenting it
             * so inline children and materialized children carry the same
             * exact scalar identities.
             */
            try (TestBlue blue = new TestBlue()) {
                Node textPattern = blue.yamlToNode("type: Text");
                Node program = stepDo(list(
                        op("$let", obj(
                                "name", "shared",
                                "expr", obj(
                                        "kind",
                                        op("$kind",
                                                document("/subject")),
                                        "exists",
                                        op("$exists",
                                                document(
                                                        "/subject/state")),
                                        "keys",
                                        op("$keys",
                                                document("/subject")),
                                        "entries",
                                        op("$entries",
                                                document("/subject")),
                                        "size",
                                        op("$size",
                                                document("/subject")),
                                        "pointerRead",
                                        op("$pointerGet", obj(
                                                "object",
                                                document("/subject"),
                                                "path", "/state")),
                                        "exactIdentity",
                                        op("$nodeBlueId",
                                                document("/subject")),
                                        "identityAwareEquality",
                                        op("$eq", list(
                                                document("/subject"),
                                                event(
                                                        "/mirrorSubject"))),
                                        "deepEquality",
                                        op("$eq", list(
                                                document("/subject"),
                                                obj(
                                                        "id", "D-1",
                                                        "state", "ready",
                                                        "values",
                                                        list(1, 2, 3)))),
                                        "matching",
                                        op("$is", obj(
                                                "node",
                                                document(
                                                        "/subject/state"),
                                                "pattern",
                                                textPattern)),
                                        "truthiness",
                                        op("$boolean",
                                                document("/subject")),
                                        "iteration",
                                        op("$map", obj(
                                                "in",
                                                document(
                                                        "/subject/values"),
                                                "item", "item",
                                                "expr",
                                                op("$var", "item"))),
                                        "stableSort",
                                        op("$intrinsic", obj(
                                                "type",
                                                new Node().blueId(
                                                        SORT_INTRINSIC),
                                                "values",
                                                document("/sortable")))))),
                        op("$appendChange", obj(
                                "op", "replace",
                                "path", "/state",
                                "val", op("$var", "shared"))),
                        op("$appendChange", obj(
                                "op", "add",
                                "path", "/marker",
                                "val", document("/marker"))),
                        op("$appendEvent", op("$var", "shared")),
                        op("$appendEvent", event("/followup")),
                        op("$return", op("$var", "shared"))));
                Node document = blue.resolveToSnapshot(authoredDocument)
                        .frozenResolvedRoot().toNode();
                Node event = blue.resolveToSnapshot(authoredEvent)
                        .frozenResolvedRoot().toNode();
                Node intrinsicType = blue.yamlToNode(
                        "name: BEX Sort Fixture Intrinsic 2.0\n"
                                + "description: Conformance-only deterministic "
                                + "intrinsic that sorts a supplied list using "
                                + "the canonical merge-sort schedule and "
                                + "reports named sortComparison counters.\n"
                                + "values:\n"
                                + "  type:\n"
                                + "    blueId: "
                                + "85ip88snCGrgUNdi1rUFqqAxcxwVGKV2g4LjsKoyKmXK");
                return new LogicalInputs(
                        program, document, event, intrinsicType);
            }
        }
    }

    private static final class VariantNodeProvider
            implements NodeProvider {
        private final Map<String, Node> catalog =
                new LinkedHashMap<String, Node>();
        private final Map<String, Node> batch =
                new LinkedHashMap<String, Node>();
        private final ProviderForm form;
        private final List<String> demands =
                new ArrayList<String>();
        private int physicalNodeLoads;
        private int physicalBatchLoads;

        private VariantNodeProvider(
                Map<String, Node> source,
                ProviderForm form) {
            this.form = form;
            for (Map.Entry<String, Node> entry : source.entrySet()) {
                catalog.put(entry.getKey(), entry.getValue().clone());
            }
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            demands.add(blueId);
            Node node;
            if (form == ProviderForm.BATCHED) {
                if (batch.isEmpty()) {
                    for (Map.Entry<String, Node> entry
                            : catalog.entrySet()) {
                        batch.put(entry.getKey(),
                                entry.getValue().clone());
                    }
                    physicalBatchLoads++;
                }
                node = batch.get(blueId);
            } else {
                physicalNodeLoads++;
                node = catalog.get(blueId);
            }
            return node == null
                    ? null
                    : Collections.singletonList(node.clone());
        }

        private void clearObservations() {
            demands.clear();
            physicalNodeLoads = 0;
            physicalBatchLoads = 0;
        }

        private void beginRuntime(boolean retainWarmBatch) {
            clearObservations();
            if (!retainWarmBatch) {
                batch.clear();
            }
        }
    }

    private static final class CountingIdentityBoundary
            implements BexSemanticIdentityBoundary {
        private int calls;

        @Override
        public BexEstablishedIdentity establishIdentity(Node node) {
            calls++;
            FrozenNode frozen =
                    FrozenNode.fromResolvedNode(node.clone());
            return new BexEstablishedIdentity(
                    DirectBlueIdCalculator.calculateBlueId(
                            frozen.toNode()),
                    frozen);
        }
    }

    private static final class Observation {
        private final String name;
        private final String diagnosticCategory;
        private final String compiledProgramBlueId;
        private final Object semanticResult;
        private final String outputBlueId;
        private final Object changes;
        private final List<String> changePaths;
        private final Object events;
        private final List<BexGasCharge> portableTrace;
        private final long totalGas;
        private final int semanticBoundaryCalls;
        private final int providerDemands;
        private final int physicalNodeLoads;
        private final int physicalBatchLoads;

        private Observation(String name,
                            String diagnosticCategory,
                            String compiledProgramBlueId,
                            Object semanticResult,
                            String outputBlueId,
                            Object changes,
                            List<String> changePaths,
                            Object events,
                            List<BexGasCharge> portableTrace,
                            long totalGas,
                            int semanticBoundaryCalls,
                            int providerDemands,
                            int physicalNodeLoads,
                            int physicalBatchLoads) {
            this.name = name;
            this.diagnosticCategory = diagnosticCategory;
            this.compiledProgramBlueId = compiledProgramBlueId;
            this.semanticResult = semanticResult;
            this.outputBlueId = outputBlueId;
            this.changes = changes;
            this.changePaths = Collections.unmodifiableList(
                    new ArrayList<String>(changePaths));
            this.events = events;
            this.portableTrace = Collections.unmodifiableList(
                    new ArrayList<BexGasCharge>(portableTrace));
            this.totalGas = totalGas;
            this.semanticBoundaryCalls = semanticBoundaryCalls;
            this.providerDemands = providerDemands;
            this.physicalNodeLoads = physicalNodeLoads;
            this.physicalBatchLoads = physicalBatchLoads;
        }
    }
}
