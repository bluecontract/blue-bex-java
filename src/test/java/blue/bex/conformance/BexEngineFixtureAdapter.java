package blue.bex.conformance;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexIntrinsicInvocation;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.api.BexProgramSource;
import blue.bex.api.BexStepResults;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasCharge;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.output.BexEstablishedIdentity;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.test.TestBlue;
import blue.bex.test.TestGasLedgerCapability;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sole adapter between fixture semantics and public engine APIs.
 *
 * <p>Fixture parsing, variant preparation, host-ledger recording, registry
 * fixture intrinsics, and phase diagnostics live here so the assertion runner
 * remains stable when a public integration surface evolves.</p>
 */
final class BexEngineFixtureAdapter {
    static final String FIXTURE_INTRINSIC =
            "5Zbnaiu1hzRNEpuQmKNHuSmkiq5VqdZ5ros49gwGB674";
    static final String SORT_FIXTURE_INTRINSIC =
            "2R1WaEk8LVwFRMEGnsZ8HTj15QTz3tQEj9LDYYjGFJJG";
    private static final String FIXTURE_REGISTRY_IDENTITY =
            "sha256:23d282ec1c0bb016263922b1b49c369fdd537efdcf23e005eceeb888d7763fe1";

    private static final Pattern OPERATOR_IN_MESSAGE =
            Pattern.compile("(\\$[A-Za-z][A-Za-z0-9]*)");

    BexFixtureRun execute(ConformancePackage.Fixture fixture,
                          Map<String, Object> program,
                          Map<String, Object> context,
                          Map<String, Object> variant,
                          String runName) {
        Map<String, Object> providerData = optionalMap(
                context.get("provider"), fixture.path + ".context.provider");
        RecordingNodeProvider provider =
                new RecordingNodeProvider(
                        parseProviderNodes(providerData),
                        batching(variant));

        try (TestBlue blue = new TestBlue(provider)) {
            Map<String, Object> effectiveRoot =
                    effectiveRoot(context, variant, providerData);
            Node root = rootNode(blue, effectiveRoot, variant);
            ResolvedSnapshot rootSnapshot =
                    resolveDocumentSnapshot(
                            fixture, blue, root, variant, provider);

            long parentBudget = context.containsKey("parentRemainingGas")
                    ? longValue(context.get("parentRemainingGas"))
                    : GasSchedule.contracts10().maxProcessGas();
            long localLimit = context.containsKey("gasLimit")
                    ? longValue(context.get("gasLimit"))
                    : -1L;
            RecordingGasHost gasHost = new RecordingGasHost(parentBudget);
            RecordingIdentityBoundary identityBoundary =
                    new RecordingIdentityBoundary();

            BexExecutionContext executionContext = executionContext(
                    blue,
                    rootSnapshot,
                    context,
                    gasHost,
                    identityBoundary,
                    parentBudget,
                    localLimit);
            BexEngine engine = BexEngine.builder()
                    .language(blue.runtime())
                    .intrinsics(fixtureIntrinsics())
                    .build();

            BexExecutionResult result = null;
            Throwable failure = null;
            boolean runtimeStarted = false;
            try {
                Node programNode = ConformancePackage.syntaxNode(program);
                BexCompiledProgram compiled = engine.compile(
                        BexProgramSource.inline(
                                FrozenNode.fromResolvedNode(programNode)));
                runtimeStarted = true;
                result = engine.execute(compiled, executionContext);
            } catch (RuntimeException ex) {
                failure = ex;
            } catch (Error error) {
                failure = error;
            }

            List<Map<String, Object>> trace =
                    result != null
                            ? localTrace(result.gasTrace())
                            : hostTrace(gasHost.parent.trace());
            long gasTotal = result != null
                    ? result.gasUsed()
                    : gasHost.parent.totalGas();
            long effectiveBudget = localLimit < 0L
                    ? parentBudget
                    : Math.min(parentBudget, localLimit);
            Throwable diagnosticFailure = unwrap(failure);
            String errorClass = classifyError(
                    diagnosticFailure, runtimeStarted, context);
            BexSourcePath sourcePath = sourcePath(diagnosticFailure);
            String operator = sourcePath != null
                    ? sourcePath.operator()
                    : operatorFromMessage(diagnosticFailure);
            BexGasLimitExceededException gasFailure =
                    findCause(diagnosticFailure,
                            BexGasLimitExceededException.class);
            boolean failedChargePresent = gasFailure != null
                    && gasHost.parent.totalGas() != gasFailure.admittedGas();

            Object resultValue = result != null
                    ? result.value().toSimple()
                    : null;
            Object changes = result != null
                    ? result.changeset().asValue().toSimple()
                    : Collections.emptyList();
            Object events = result != null
                    ? result.events().asValue().toSimple()
                    : Collections.emptyList();

            return new BexFixtureRun(
                    runName,
                    runtimeStarted,
                    result,
                    diagnosticFailure,
                    errorClass,
                    sourcePath != null ? sourcePath.toString() : null,
                    operator,
                    provider.demands,
                    provider.batching,
                    provider.warmupNodeLoads,
                    provider.warmupBatchLoads,
                    provider.runtimeNodeLoads,
                    provider.runtimeBatchLoads,
                    provider.runtimeCacheHits,
                    trace,
                    gasTotal,
                    effectiveBudget,
                    gasHost.parentBudgetBefore,
                    gasHost.parent.remainingGas(),
                    gasHost.openCount,
                    gasHost.mergeCount,
                    gasHost.liveBounded,
                    failedChargePresent,
                    identityBoundary.complexIdentityCalls,
                    result != null,
                    resultValue,
                    changes,
                    events);
        }
    }

    private static BexExecutionContext executionContext(
            TestBlue blue,
            ResolvedSnapshot root,
            Map<String, Object> context,
            RecordingGasHost gasHost,
            RecordingIdentityBoundary identityBoundary,
            long parentBudget,
            long localLimit) {
        String scope = context.containsKey("documentScope")
                ? String.valueOf(context.get("documentScope"))
                : "/";
        BexExecutionContext.Builder builder = BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(
                        root.frozenCanonicalRoot(),
                        root.frozenResolvedRoot(),
                        scope))
                .event(exactValue(blue, valueOrEmpty(context.get("event"))))
                .processingEvent(exactValue(
                        blue, valueOrEmpty(context.get("processingEvent"))))
                .currentContract(exactValue(
                        blue, valueOrEmpty(context.get("currentContract"))))
                .steps(stepResults(blue, optionalMap(
                        context.get("steps"), "context.steps")))
                .parentRemainingGas(parentBudget)
                .gasLedgerHost(gasHost)
                .semanticIdentityBoundary(identityBoundary);
        if (localLimit >= 0L) {
            builder.gasLimit(localLimit);
        }

        for (Map.Entry<String, Object> binding : optionalMap(
                context.get("bindings"), "context.bindings").entrySet()) {
            builder.binding(binding.getKey(),
                    exactValue(blue, binding.getValue()));
        }
        return builder.build();
    }

    private static BexStepResults stepResults(
            TestBlue blue,
            Map<String, Object> steps) {
        BexStepResults.Builder builder = BexStepResults.builder();
        for (Map.Entry<String, Object> entry : steps.entrySet()) {
            builder.put(entry.getKey(), exactValue(blue, entry.getValue()));
        }
        return builder.build();
    }

    private static BexValue exactValue(TestBlue blue, Object value) {
        ResolvedSnapshot snapshot =
                blue.resolveToSnapshot(
                        ConformancePackage.semanticNode(blue, value));
        return BexValues.exact(
                snapshot.frozenCanonicalRoot(),
                snapshot.frozenResolvedRoot());
    }

    private static ResolvedSnapshot resolveDocumentSnapshot(
            ConformancePackage.Fixture fixture,
            TestBlue blue,
            Node root,
            Map<String, Object> variant,
            RecordingNodeProvider provider) {
        if ("warm".equals(variant.get("cache"))) {
            provider.prepareWarmBatch();
            blue.resolveToSnapshot(root);
            /*
             * Warmup is physical preparation, not a BEX semantic demand.
             * Retain the provider cache while starting fresh runtime
             * observations for the measured execution.
             */
            provider.finishWarmup();
        }
        try {
            return blue.resolveToSnapshot(root);
        } catch (RuntimeException unavailable) {
            /*
             * BEX-R-09 is deliberately an opaque final cyclic-set member with
             * no structural provider. Identity access must not demand it.
             */
            if (!"bex-r-09".equals(fixture.id())) {
                throw unavailable;
            }
            FrozenNode exact = FrozenNode.fromNode(root);
            return new ResolvedSnapshot(exact, exact);
        }
    }

    private static Node rootNode(
            TestBlue blue,
            Map<String, Object> root,
            Map<String, Object> variant) {
        Object rawJson = variant.get("rawRootDocumentJson");
        if (rawJson != null) {
            return blue.parseSourceJson(String.valueOf(rawJson));
        }
        return ConformancePackage.semanticNode(blue, root);
    }

    private static Map<String, Object> effectiveRoot(
            Map<String, Object> context,
            Map<String, Object> variant,
            Map<String, Object> provider) {
        Map<String, Object> root = optionalMap(
                context.get("rootDocument"), "context.rootDocument");
        String rootForm = String.valueOf(variant.get("rootForm"));
        if ("inline".equals(rootForm)
                || "eager".equals(rootForm)
                || "materialized".equals(rootForm)) {
            return ConformancePackage.map(
                    inlineReferences(root, provider), "inline root");
        }
        return ConformancePackage.map(
                deepCopy(root), "reference root");
    }

    private static Object inlineReferences(
            Object value,
            Map<String, Object> provider) {
        if (value instanceof Map) {
            Map<String, Object> map =
                    ConformancePackage.map(value, "reference value");
            if (map.size() == 1 && map.containsKey("blueId")) {
                Object replacement = provider.get(String.valueOf(map.get("blueId")));
                if (replacement != null) {
                    return inlineReferences(deepCopy(replacement), provider);
                }
            }
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(),
                        inlineReferences(entry.getValue(), provider));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object child : (List<?>) value) {
                result.add(inlineReferences(child, provider));
            }
            return result;
        }
        return value;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry
                    : ConformancePackage.map(value, "copy").entrySet()) {
                result.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object child : (List<?>) value) {
                result.add(deepCopy(child));
            }
            return result;
        }
        return value;
    }

    private static Map<String, Node> parseProviderNodes(
            Map<String, Object> provider) {
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        try (TestBlue parser = new TestBlue()) {
            for (Map.Entry<String, Object> entry : provider.entrySet()) {
                result.put(entry.getKey(),
                        ConformancePackage.semanticNode(
                                parser, entry.getValue()));
            }
        }
        return result;
    }

    private static BexIntrinsicRegistry fixtureIntrinsics() {
        return BexIntrinsicRegistry.builder()
                .register(
                        FIXTURE_INTRINSIC,
                        FIXTURE_REGISTRY_IDENTITY,
                        singletonWeight("payloadReturned", 1L),
                        invocation -> {
                    invocation.charge(
                            "payloadReturned", 1L, "fixture-payload-returned");
                    return invocation.field("x");
                })
                .register(
                        SORT_FIXTURE_INTRINSIC,
                        FIXTURE_REGISTRY_IDENTITY,
                        singletonWeight("sortComparison", 1L),
                        invocation -> {
                    BexValue values = invocation.field("values");
                    if (!values.isList()) {
                        throw new BexException(
                                "Sort fixture intrinsic values must be a list");
                    }
                    List<BexValue> sorted = new ArrayList<BexValue>();
                    for (int index = 0; index < values.size(); index++) {
                        sorted.add(values.get(String.valueOf(index)));
                    }
                    stableBottomUpMergeSort(sorted, invocation);
                    return BexValues.list(sorted);
                })
                .build();
    }

    private static Map<String, Long> singletonWeight(
            String counter,
            long weight) {
        Map<String, Long> weights = new LinkedHashMap<String, Long>();
        weights.put(counter, weight);
        return weights;
    }

    private static void stableBottomUpMergeSort(
            List<BexValue> values,
            BexIntrinsicInvocation invocation) {
        int size = values.size();
        List<BexValue> source = new ArrayList<BexValue>(values);
        List<BexValue> target = new ArrayList<BexValue>(
                Collections.nCopies(size, BexValues.undefined()));
        for (int width = 1; width < size; width *= 2) {
            for (int left = 0; left < size; left += 2 * width) {
                int middle = Math.min(left + width, size);
                int right = Math.min(left + 2 * width, size);
                int first = left;
                int second = middle;
                int output = left;
                while (first < middle && second < right) {
                    invocation.charge(
                            "sortComparison", 1L, "canonical-merge-sort");
                    BigDecimal firstValue = source.get(first).asNumber();
                    BigDecimal secondValue = source.get(second).asNumber();
                    if (firstValue.compareTo(secondValue) <= 0) {
                        target.set(output++, source.get(first++));
                    } else {
                        target.set(output++, source.get(second++));
                    }
                }
                while (first < middle) {
                    target.set(output++, source.get(first++));
                }
                while (second < right) {
                    target.set(output++, source.get(second++));
                }
            }
            List<BexValue> swap = source;
            source = target;
            target = swap;
        }
        values.clear();
        values.addAll(source);
    }

    private static List<Map<String, Object>> localTrace(
            List<BexGasCharge> charges) {
        List<Map<String, Object>> trace =
                new ArrayList<Map<String, Object>>(charges.size());
        for (BexGasCharge charge : charges) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("sequence", charge.sequence());
            entry.put("namespace", charge.namespace());
            entry.put("counter", charge.counterName());
            entry.put("quantity", charge.quantity());
            entry.put("weight", charge.weight());
            entry.put("gas", charge.gas());
            entry.put("sourcePath", charge.sourcePath());
            entry.put("operator", charge.operator());
            entry.put("reason", charge.reason());
            trace.add(entry);
        }
        return trace;
    }

    private static List<Map<String, Object>> hostTrace(
            List<GasTraceEntry> charges) {
        List<Map<String, Object>> trace =
                new ArrayList<Map<String, Object>>(charges.size());
        for (GasTraceEntry charge : charges) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("sequence", charge.sequence());
            entry.put("namespace", charge.namespace());
            entry.put("counter", charge.counter());
            entry.put("quantity", charge.quantity());
            entry.put("weight", charge.weight());
            entry.put("gas", charge.subtotal());
            entry.put("sourcePath", charge.scopePath());
            entry.put("operator", charge.logicalPath());
            entry.put("reason", charge.reason());
            trace.add(entry);
        }
        return trace;
    }

    private static String classifyError(
            Throwable failure,
            boolean runtimeStarted,
            Map<String, Object> context) {
        if (failure == null) {
            return null;
        }
        if (!runtimeStarted) {
            return "compile-error";
        }
        if (findCause(failure, BexGasLimitExceededException.class) != null) {
            return context.containsKey("parentRemainingGas")
                    ? "gas-limit-exceeded"
                    : "gas-exhaustion";
        }
        String message = allMessages(failure).toLowerCase();
        if (message.contains("blue output")
                || message.contains("output conversion")
                || message.contains("output identity")
                || message.contains("unsupported blue schema field")
                || message.contains("blueid reference node cannot contain")
                || message.contains("undefined cannot be emitted")
                || message.contains("undefined cannot appear in a blue list")
                || message.contains("list literal item cannot be undefined")
                || message.contains("payload kind")
                || message.contains("list-control")
                || message.contains("internal blue field")
                || message.contains("computed bex output")
                || message.contains("sparse overlay")) {
            return "output-conversion-error";
        }
        return "runtime-error";
    }

    private static BexSourcePath sourcePath(Throwable failure) {
        BexException exception = findCause(failure, BexException.class);
        return exception != null && exception.sourcePath().isPresent()
                ? exception.sourcePath().get()
                : null;
    }

    private static String operatorFromMessage(Throwable failure) {
        Matcher matcher = OPERATOR_IN_MESSAGE.matcher(allMessages(failure));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                if (messages.length() > 0) {
                    messages.append(" | ");
                }
                messages.append(current.getMessage());
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof InvocationTargetException
                && ((InvocationTargetException) failure).getCause() != null) {
            return unwrap(((InvocationTargetException) failure).getCause());
        }
        return failure;
    }

    private static <T extends Throwable> T findCause(
            Throwable failure,
            Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static Map<String, Object> optionalMap(
            Object value,
            String path) {
        return value == null
                ? Collections.<String, Object>emptyMap()
                : ConformancePackage.map(value, path);
    }

    private static Object valueOrEmpty(Object value) {
        return value != null
                ? value
                : Collections.<String, Object>emptyMap();
    }

    private static long longValue(Object value) {
        return ConformancePackage.integer(value, "gas limit").longValueExact();
    }

    private static String batching(Map<String, Object> variant) {
        return "batched".equals(variant.get("batching"))
                ? "batched"
                : "unbatched";
    }

    private static final class RecordingNodeProvider implements NodeProvider {
        private final Map<String, Node> nodes =
                new LinkedHashMap<String, Node>();
        private final Map<String, Node> preparedBatch =
                new LinkedHashMap<String, Node>();
        private final String batching;
        private final List<String> demands = new ArrayList<String>();
        private boolean batchPrepared;
        private int warmupNodeLoads;
        private int warmupBatchLoads;
        private int runtimeNodeLoads;
        private int runtimeBatchLoads;
        private int runtimeCacheHits;

        private RecordingNodeProvider(
                Map<String, Node> source,
                String batching) {
            this.batching = batching;
            for (Map.Entry<String, Node> entry : source.entrySet()) {
                nodes.put(entry.getKey(), entry.getValue().clone());
            }
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            demands.add(blueId);
            Node node;
            if ("batched".equals(batching)) {
                if (!batchPrepared) {
                    prepareBatch();
                } else {
                    runtimeCacheHits++;
                }
                node = preparedBatch.get(blueId);
            } else {
                runtimeNodeLoads++;
                node = nodes.get(blueId);
            }
            return node == null
                    ? null
                    : Collections.singletonList(node.clone());
        }

        private void prepareWarmBatch() {
            if ("batched".equals(batching)
                    && !batchPrepared) {
                prepareBatch();
            }
        }

        private void prepareBatch() {
            for (Map.Entry<String, Node> entry
                    : nodes.entrySet()) {
                preparedBatch.put(
                        entry.getKey(),
                        entry.getValue().clone());
            }
            batchPrepared = true;
            runtimeBatchLoads++;
        }

        private void finishWarmup() {
            warmupNodeLoads = runtimeNodeLoads;
            warmupBatchLoads = runtimeBatchLoads;
            demands.clear();
            runtimeNodeLoads = 0;
            runtimeBatchLoads = 0;
            runtimeCacheHits = 0;
        }
    }

    private static final class RecordingGasHost implements BexGasLedgerHost {
        private final GasMeter parent;
        private final long parentBudgetBefore;
        private final Map<BexGasLedgerCapability, Boolean> opened =
                new IdentityHashMap<>();
        private int openCount;
        private int mergeCount;
        private boolean liveBounded;

        private RecordingGasHost(long budget) {
            this.parent = new GasMeter(GasSchedule.contracts10(), budget);
            this.parentBudgetBefore = parent.remainingGas();
        }

        @Override
        public BexGasLedgerCapability open(
                String namespace,
                Map<String, Long> counterWeights) {
            openCount++;
            BexGasLedgerCapability ledger = TestGasLedgerCapability.wrap(
                    parent.childLedger(namespace, counterWeights));
            opened.put(ledger, Boolean.TRUE);
            liveBounded = openCount == 1
                    ? ledger.remainingGas() == parent.remainingGas()
                    : liveBounded
                    && ledger.remainingGas() == parent.remainingGas();
            return ledger;
        }

        @Override
        public void submit(BexGasLedgerCapability ledger) {
            mergeCount++;
            if (!opened.containsKey(ledger)) {
                throw new IllegalArgumentException(
                        "BEX submitted a different child ledger");
            }
            parent.merge(((TestGasLedgerCapability) ledger).delegate());
        }

        @Override
        public void failedDeterministically(
                BexGasLedgerCapability ledger) {
            submit(ledger);
        }

        @Override
        public void evidenceUnavailable(
                BexGasLedgerCapability ledger) {
            if (!opened.containsKey(ledger)) {
                throw new IllegalArgumentException(
                        "BEX finalized a different child ledger");
            }
        }
    }

    private static final class RecordingIdentityBoundary
            implements BexSemanticIdentityBoundary {
        private long complexIdentityCalls;

        @Override
        public BexEstablishedIdentity establishIdentity(Node node) {
            if (node.getProperties() != null || node.getItems() != null) {
                complexIdentityCalls++;
            }
            return new BexEstablishedIdentity(
                    DirectBlueIdCalculator.calculateBlueId(node),
                    FrozenNode.fromResolvedNode(node.clone()));
        }
    }

}
