package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasCharge;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexEstablishedIdentity;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.runtime.BexRuntime;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static blue.bex.test.BexTestFixtures.frozen;
import static blue.bex.test.BexTestFixtures.largeObject;
import static blue.bex.test.BexTestFixtures.list;
import static blue.bex.test.BexTestFixtures.obj;
import static blue.bex.test.BexTestFixtures.op;
import static blue.bex.test.BexTestFixtures.stepDo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composite evidence that a rejected charge is the last observable action.
 *
 * <p>Each case first executes without a local sub-limit and selects an exact
 * charge from the resulting canonical trace. It then executes the same
 * compiled program with a live host ledger and a local limit equal to the gas
 * immediately before that charge. The host must retain exactly that prefix;
 * neither the rejected charge nor a later sentinel event may be observed.</p>
 */
class BexCompositeExhaustionEvidenceTest {
    private static final String SORT_INTRINSIC =
            "TestCompositeExhaustionSort";
    private static final String NAMED_INTRINSIC =
            "TestCompositeExhaustionNamed";
    private static final String REGISTRY_IDENTITY =
            "test-composite-exhaustion/1";

    @Test
    void largeFiniteForEachStopsBeforeRejectedIterationAndIsColdWarmStable() {
        BexProgramSource source = source(stepDo(list(
                op("$forEach", obj(
                        "in", integerList(48),
                        "item", "item",
                        "index", "index",
                        "do", list())),
                sentinelEvent(),
                op("$return", true))));

        List<BexMetrics> compileMetrics = new ArrayList<>();
        BexEngine warmEngine = BexEngine.builder()
                .metrics(metrics -> compileMetrics.add(metrics.copy()))
                .build();
        BexCompiledProgram firstCompilation = warmEngine.compile(source);
        BexCompiledProgram cachedCompilation = warmEngine.compile(source);

        assertSame(firstCompilation, cachedCompilation);
        assertEquals(1L, compileMetrics.get(0).compileCacheMisses());
        assertEquals(0L, compileMetrics.get(0).compileCacheHits());
        assertEquals(0L, compileMetrics.get(1).compileCacheMisses());
        assertEquals(1L, compileMetrics.get(1).compileCacheHits());

        TargetEvidence target = deriveTarget(
                warmEngine,
                cachedCompilation,
                trace -> nthCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter.COLLECTION_ITEM_VISITED
                                && "$forEach".equals(charge.operator()),
                        23));
        LimitedEvidence warm = assertRejectedAtPrefix(
                warmEngine, cachedCompilation, target);

        BexEngine coldEngine = BexEngine.builder().build();
        BexCompiledProgram coldCompilation = coldEngine.compile(source);
        LimitedEvidence cold = assertRejectedAtPrefix(
                coldEngine, coldCompilation, target);

        assertHostTracesEqual(warm.hostTrace, cold.hostTrace);
        assertSameFailure(warm.failure, cold.failure);
        assertEquals(0, warm.boundary.sentinelAdmissions.get());
        assertEquals(0, cold.boundary.sentinelAdmissions.get());
    }

    @Test
    void exhaustionIsStableAcrossInlineColdReferenceAndWarmReferenceDocuments() {
        Node document = obj("items", integerList(48));
        String documentBlueId =
                BlueIdCalculator.calculateBlueId(document);
        FrozenNode inlineDocument =
                FrozenNode.fromResolvedNode(document);
        FrozenNode referenceDocument = FrozenNode.fromNode(
                new Node().blueId(documentBlueId));
        FrozenBexDocumentView inlineView =
                new FrozenBexDocumentView(inlineDocument);
        FrozenBexDocumentView referenceView =
                new FrozenBexDocumentView(
                        referenceDocument,
                        referenceDocument,
                        "/");
        BexProgramSource source = source(stepDo(list(
                op("$forEach", obj(
                        "in", op("$document", "/items"),
                        "item", "item",
                        "index", "index",
                        "do", list())),
                sentinelEvent(),
                op("$return", true))));

        ExactDocumentProvider coldProvider =
                new ExactDocumentProvider(
                        documentBlueId, document);
        ExactDocumentProvider warmProvider =
                new ExactDocumentProvider(
                        documentBlueId, document);
        try (Blue inlineBlue = new Blue();
             Blue coldBlue = new Blue(coldProvider);
             Blue warmBlue = new Blue(warmProvider)) {
            BexEngine inlineEngine = BexEngine.builder()
                    .blue(inlineBlue)
                    .build();
            BexCompiledProgram inlineProgram =
                    inlineEngine.compile(source);
            BexExecutionResult unlimited = inlineEngine.execute(
                    inlineProgram,
                    context(
                            null,
                            new RecordingIdentityBoundary(),
                            -1L,
                            inlineView));
            List<BexGasCharge> trace = unlimited.gasTrace();
            int rejectedIndex = nthCharge(
                    trace,
                    charge -> charge.counter()
                            == BexGasCounter.COLLECTION_ITEM_VISITED
                            && "$forEach".equals(charge.operator()),
                    23);
            List<BexGasCharge> prefix =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    trace.subList(0, rejectedIndex)));
            TargetEvidence target = new TargetEvidence(
                    prefix,
                    trace.get(rejectedIndex),
                    gas(prefix));

            LimitedEvidence inline = assertRejectedAtPrefix(
                    inlineEngine,
                    inlineProgram,
                    target,
                    inlineView);

            BexEngine coldEngine = BexEngine.builder()
                    .blue(coldBlue)
                    .build();
            LimitedEvidence coldReference =
                    assertRejectedAtPrefix(
                            coldEngine,
                            coldEngine.compile(source),
                            target,
                            referenceView);
            assertTrue(coldProvider.demands > 0,
                    "the cold reference execution must demand provider content");

            warmBlue.expand(
                    new Node().blueId(documentBlueId));
            int warmupDemands = warmProvider.demands;
            BexEngine warmEngine = BexEngine.builder()
                    .blue(warmBlue)
                    .build();
            LimitedEvidence warmReference =
                    assertRejectedAtPrefix(
                            warmEngine,
                            warmEngine.compile(source),
                            target,
                            referenceView);
            assertTrue(warmupDemands > 0,
                    "the warm variant must establish provider content first");

            assertHostTracesEqual(
                    inline.hostTrace,
                    coldReference.hostTrace);
            assertHostTracesEqual(
                    inline.hostTrace,
                    warmReference.hostTrace);
            assertSameFailure(
                    inline.failure,
                    coldReference.failure);
            assertSameFailure(
                    inline.failure,
                    warmReference.failure);
            assertEquals(0,
                    coldReference.boundary
                            .sentinelAdmissions.get());
            assertEquals(0,
                    warmReference.boundary
                            .sentinelAdmissions.get());
        }
    }

    @Test
    void largeTextConstructionStopsBeforeResultAndLaterEvent() {
        String block = repeatCodePoint('x', 257);
        Node expression = op("$concat", list(
                block + "0",
                block + "1",
                block + "2",
                block + "3",
                block + "4",
                block + "5",
                block + "6",
                block + "7"));
        Scenario scenario = scenario(BexEngine.builder().build(),
                expressionProgram(expression));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> lastCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter.TEXT_BLOCK_CONSTRUCTED
                                && "$concat".equals(charge.operator())));
        assertTrue(target.rejected.quantity() > 1L);

        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);
        assertEquals(0, limited.boundary.sentinelAdmissions.get());
    }

    @Test
    void numericTextStopsBeforeUnadmittedLaterMagnitudeExtraction() {
        TrackingBigInteger magnitude = new TrackingBigInteger(
                repeatCodePoint('9', 257));
        Scenario scenario = scenario(
                BexEngine.builder().build(),
                obj(
                        "type", "Blue/BEX Program",
                        "expr", op("$text", magnitude)));

        BexExecutionResult complete = scenario.engine.execute(
                scenario.program,
                context(
                        null,
                        new RecordingIdentityBoundary(),
                        -1L));
        assertEquals(magnitude.toString(),
                complete.value().toSimple());
        assertEquals(5L, complete.gasLedger().quantity(
                BexGasCounter.TEXT_BLOCK_EXAMINED));
        assertEquals(5L, complete.gasLedger().quantity(
                BexGasCounter.TEXT_BLOCK_CONSTRUCTED));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> nthCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter.TEXT_BLOCK_EXAMINED
                                && "$text".equals(charge.operator()),
                        1));
        magnitude.resetObservations();

        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);

        assertEquals(1, magnitude.quotientExtractions(),
                "only the first admitted numeric text block may extract "
                        + "a magnitude quotient");
        assertEquals(0, magnitude.eagerRemainderExtractions(),
                "an admitted leading block must not materialize the "
                        + "unadmitted lower decimal remainder");
        assertEquals(0, limited.boundary.totalAdmissions.get(),
                "the rejected second block must precede result admission");
    }

    @Test
    void concatConstructionQuantityHandlesSurrogatePairAcrossOperands() {
        String highSurrogateSuffix =
                repeatCodePoint('a', 63) + "\uD83D";
        String lowSurrogatePrefix = "\uDE00";
        Scenario scenario = scenario(
                BexEngine.builder().build(),
                expressionProgram(op(
                        "$concat",
                        list(highSurrogateSuffix, lowSurrogatePrefix))));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> lastCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter.TEXT_BLOCK_CONSTRUCTED
                                && "$concat".equals(charge.operator())));

        assertEquals(1L, target.rejected.quantity(),
                "the cross-operand surrogate pair forms one code point");
        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);
        assertEquals(0, limited.boundary.totalAdmissions.get(),
                "aggregate construction rejection must precede result admission");
    }

    @Test
    void surrogatePrefixStopsBeforeRejectedLaterComparisonBlock() {
        String emoji = new String(
                Character.toChars(0x1F600));
        String prefix = repeatText(emoji, 65);
        Scenario scenario = scenario(
                BexEngine.builder().build(),
                expressionProgram(op(
                        "$sliceAfter",
                        list(prefix + "suffix", prefix))));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> nthCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter.TEXT_BLOCK_EXAMINED
                                && "$sliceAfter".equals(
                                charge.operator()),
                        1));

        assertEquals(2L, target.rejected.quantity(),
                "one admitted pair covers 64 Unicode code points, "
                        + "not 64 UTF-16 code units");
        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);
        assertEquals(0, limited.boundary.totalAdmissions.get(),
                "the suffix and result boundary must not run after "
                        + "the second comparison block is rejected");
    }

    @Test
    void largeIntegerArithmeticStopsBeforeResultAndLaterEvent() {
        BigInteger left = BigInteger.ONE.shiftLeft(2048)
                .subtract(BigInteger.ONE);
        BigInteger right = BigInteger.ONE.shiftLeft(2016)
                .add(BigInteger.valueOf(17L));
        Node expression = op("$multiply", list(left, right));
        Scenario scenario = scenario(BexEngine.builder().build(),
                expressionProgram(expression));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> lastCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter.INTEGER_LIMB_OPERATION
                                && "$multiply".equals(charge.operator())));
        assertTrue(target.rejected.quantity() > 1L);

        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);
        assertEquals(0, limited.boundary.sentinelAdmissions.get());
    }

    @Test
    void deterministicSortIntrinsicStopsBeforeRejectedComparison() {
        AtomicInteger completedComparisons = new AtomicInteger();
        BexEngine engine = sortEngine(completedComparisons);
        Node expression = intrinsicExpression(
                SORT_INTRINSIC,
                "values",
                list(9, 1, 8, 2, 7, 3, 6, 4, 5));
        Scenario scenario = scenario(engine, expressionProgram(expression));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> nthCharge(
                        trace,
                        charge -> ("intrinsic-" + SORT_INTRINSIC)
                                .equals(charge.namespace())
                                && "sortComparison".equals(
                                charge.counterName()),
                        6));
        long admittedComparisons = quantity(
                target.prefix,
                "intrinsic-" + SORT_INTRINSIC,
                "sortComparison");
        completedComparisons.set(0);

        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);

        assertEquals(admittedComparisons,
                completedComparisons.get(),
                "the comparison following the rejected charge must not run");
        assertEquals(0, limited.boundary.sentinelAdmissions.get());
    }

    @Test
    void transientAggregateConstructionStopsBeforeRejectedMember() {
        Scenario scenario = scenario(
                BexEngine.builder().build(),
                expressionProgram(largeObject(40)));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> nthCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter
                                .TRANSIENT_OBJECT_MEMBER_PRODUCED,
                        24));

        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);
        assertEquals(0, limited.boundary.sentinelAdmissions.get());
    }

    @Test
    void rejectedPatchAppendDoesNotMutateChangesetOrOverlay() {
        Node programNode = stepDo(list(
                op("$appendChange", obj(
                        "op", "add",
                        "path", "/mutated",
                        "val", "must-not-appear")),
                sentinelEvent(),
                op("$return", true)));

        try (Blue blue = new Blue()) {
            BexEngine engine = BexEngine.builder()
                    .blue(blue)
                    .build();
            BexCompiledProgram program =
                    engine.compile(source(programNode));
            TargetEvidence target = deriveTarget(
                    engine,
                    program,
                    trace -> lastCharge(
                            trace,
                            charge -> charge.counter()
                                    == BexGasCounter.PATCH_APPENDED));
            RecordingGasHost host =
                    new RecordingGasHost();
            RecordingIdentityBoundary boundary =
                    new RecordingIdentityBoundary();
            BexRuntime runtime = new BexRuntime(
                    program,
                    context(
                            host,
                            boundary,
                            target.prefixGas),
                    blue,
                    BexGasSchedule.defaults(),
                    new BexMetrics(),
                    new BexPointerCache(),
                    BexIntrinsicRegistry.empty());

            RuntimeException topLevel = assertThrows(
                    RuntimeException.class,
                    runtime::execute);
            BexGasLimitExceededException failure = findCause(
                    topLevel,
                    BexGasLimitExceededException.class);

            assertNotNull(failure);
            assertEquals(BexGasCounter.PATCH_APPENDED,
                    failure.counter());
            assertEquals(target.rejected.counterName(),
                    failure.counterName());
            assertEquals(target.rejected.quantity(),
                    failure.quantity());
            assertEquals(target.rejected.weight(),
                    failure.weight());
            assertEquals(target.prefixGas,
                    failure.admittedGas());
            assertEquals(target.prefixGas,
                    failure.effectiveBudget());
            assertTrue(runtime.accumulator()
                            .changeset()
                            .entries()
                            .isEmpty(),
                    "the rejected patch charge must precede changeset mutation");
            assertTrue(runtime.accumulator()
                            .overlay()
                            .rootValue()
                            .get("mutated")
                            .isUndefined(),
                    "the rejected patch charge must precede overlay mutation");
            assertEquals(0,
                    boundary.totalAdmissions.get(),
                    "patch-value and later-event admission must not begin");
            assertHostTraceEqualsPrefix(
                    target.prefix,
                    host.parent.trace());
        }
    }

    @Test
    void transientNodeBlueIdStopsBeforeSemanticIdentityBoundary() {
        Node aggregate = largeObject(18);
        Scenario scenario = scenario(
                BexEngine.builder().build(),
                expressionProgram(op("$nodeBlueId", aggregate)));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> lastCharge(
                        trace,
                        charge -> charge.counter()
                                == BexGasCounter.NODE_IDENTITY_REQUESTED));

        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);

        assertEquals(0, limited.boundary.totalAdmissions.get(),
                "semantic identity establishment must not begin after "
                        + "nodeIdentityRequested is rejected");
        assertEquals(0, limited.boundary.sentinelAdmissions.get());
    }

    @Test
    void registeredNamedIntrinsicStopsBeforeRejectedProcessorWork() {
        AtomicInteger completedIntrinsicWork = new AtomicInteger();
        Map<String, Long> weights = Collections.singletonMap(
                "namedWork", 7L);
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        NAMED_INTRINSIC,
                        REGISTRY_IDENTITY,
                        weights,
                        invocation -> {
                            invocation.charge(
                                    "namedWork",
                                    4L,
                                    "registered-named-work");
                            completedIntrinsicWork.incrementAndGet();
                            return BexValues.scalar(true);
                        })
                .build();
        Scenario scenario = scenario(
                engine,
                expressionProgram(intrinsicExpression(
                        NAMED_INTRINSIC,
                        "payload",
                        "value")));

        TargetEvidence target = deriveTarget(
                scenario.engine,
                scenario.program,
                trace -> lastCharge(
                        trace,
                        charge -> ("intrinsic-" + NAMED_INTRINSIC)
                                .equals(charge.namespace())
                                && "namedWork".equals(
                                charge.counterName())));
        completedIntrinsicWork.set(0);

        LimitedEvidence limited = assertRejectedAtPrefix(
                scenario.engine, scenario.program, target);

        assertEquals(0, completedIntrinsicWork.get(),
                "intrinsic work following its named charge must not run");
        assertEquals(0, limited.boundary.sentinelAdmissions.get());
    }

    private static BexEngine sortEngine(
            AtomicInteger completedComparisons) {
        return BexEngine.builder()
                .intrinsic(
                        SORT_INTRINSIC,
                        REGISTRY_IDENTITY,
                        Collections.singletonMap(
                                "sortComparison", 3L),
                        invocation -> {
                            BexValue input = invocation.field("values");
                            if (!input.isList()) {
                                throw new BexException(
                                        "sort values must be a list");
                            }
                            List<BexValue> values = new ArrayList<>();
                            for (int index = 0;
                                 index < input.size();
                                 index++) {
                                values.add(input.get(
                                        String.valueOf(index)));
                            }
                            for (int left = 0;
                                 left < values.size();
                                 left++) {
                                int least = left;
                                for (int right = left + 1;
                                     right < values.size();
                                     right++) {
                                    invocation.charge(
                                            "sortComparison",
                                            1L,
                                            "deterministic-selection-sort");
                                    completedComparisons.incrementAndGet();
                                    if (values.get(right)
                                            .asInteger()
                                            .compareTo(values.get(least)
                                                    .asInteger()) < 0) {
                                        least = right;
                                    }
                                }
                                BexValue swap = values.get(left);
                                values.set(left, values.get(least));
                                values.set(least, swap);
                            }
                            return BexValues.list(values);
                        })
                .build();
    }

    private static Scenario scenario(
            BexEngine engine,
            Node programNode) {
        BexProgramSource source = source(programNode);
        return new Scenario(
                engine,
                engine.compile(source));
    }

    private static Node expressionProgram(Node expression) {
        return stepDo(list(
                op("$let", obj(
                        "name", "ignored",
                        "expr", expression)),
                sentinelEvent(),
                op("$return", true)));
    }

    private static Node sentinelEvent() {
        return op("$appendEvent", obj(
                "afterExhaustion", true));
    }

    private static Node intrinsicExpression(
            String blueId,
            String field,
            Object value) {
        return op("$intrinsic", obj(
                "type", obj("blueId", blueId),
                field, value));
    }

    private static Node integerList(int size) {
        Object[] values = new Object[size];
        for (int index = 0; index < size; index++) {
            values[index] = index;
        }
        return list(values);
    }

    private static BexProgramSource source(Node program) {
        return BexProgramSource.inline(frozen(program));
    }

    private static TargetEvidence deriveTarget(
            BexEngine engine,
            BexCompiledProgram program,
            ChargeSelector selector) {
        RecordingIdentityBoundary boundary =
                new RecordingIdentityBoundary();
        BexExecutionResult unlimited = engine.execute(
                program,
                context(null, boundary, -1L));
        List<BexGasCharge> trace = unlimited.gasTrace();
        int rejectedIndex = selector.select(trace);
        assertTrue(rejectedIndex >= 0);
        assertTrue(rejectedIndex < trace.size());

        List<BexGasCharge> prefix = Collections.unmodifiableList(
                new ArrayList<>(trace.subList(0, rejectedIndex)));
        long prefixGas = gas(prefix);
        assertTrue(prefixGas > 0L,
                "composite case must have a non-empty admitted prefix");
        return new TargetEvidence(
                prefix,
                trace.get(rejectedIndex),
                prefixGas);
    }

    private static LimitedEvidence assertRejectedAtPrefix(
            BexEngine engine,
            BexCompiledProgram program,
            TargetEvidence target) {
        FrozenNode empty = FrozenNode.fromResolvedNode(obj());
        return assertRejectedAtPrefix(
                engine,
                program,
                target,
                new FrozenBexDocumentView(
                        empty,
                        empty,
                        "/"));
    }

    private static LimitedEvidence assertRejectedAtPrefix(
            BexEngine engine,
            BexCompiledProgram program,
            TargetEvidence target,
            FrozenBexDocumentView document) {
        RecordingGasHost host = new RecordingGasHost();
        RecordingIdentityBoundary boundary =
                new RecordingIdentityBoundary();
        BexExecutionContext context = context(
                host,
                boundary,
                target.prefixGas,
                document);

        RuntimeException topLevel = assertThrows(
                RuntimeException.class,
                () -> engine.execute(program, context));
        BexGasLimitExceededException failure = findCause(
                topLevel, BexGasLimitExceededException.class);
        assertNotNull(failure,
                "execution must retain the exact BEX exhaustion");

        assertEquals(target.rejected.namespace(),
                failure.namespace());
        assertEquals(target.rejected.counter(),
                failure.counter());
        assertEquals(target.rejected.counterName(),
                failure.counterName());
        assertEquals(target.rejected.quantity(),
                failure.quantity());
        assertEquals(target.rejected.weight(),
                failure.weight());
        assertEquals(target.prefixGas,
                failure.admittedGas());
        assertEquals(target.prefixGas,
                failure.effectiveBudget());
        assertNull(failure.hostGasLimitExceeded(),
                "the stricter local sub-limit must reject before "
                        + "touching the host ledger");

        assertEquals(host.openedLedgers.size(),
                host.deterministicFailures);
        assertEquals(0, host.successfulSubmissions);
        assertEquals(0, host.unavailableFinalizations);
        assertEquals(0, host.exhaustionPropagations);
        assertEquals(target.prefixGas, host.parent.totalGas());
        assertHostTraceEqualsPrefix(
                target.prefix, host.parent.trace());
        assertFalse(host.parent.trace().stream().anyMatch(
                        charge -> BexGasCounter.EVENT_APPENDED
                                .canonicalName()
                                .equals(charge.counter())),
                "the later sentinel event must remain uncharged");
        assertEquals(0, boundary.sentinelAdmissions.get(),
                "the later sentinel event must remain unadmitted");

        return new LimitedEvidence(
                failure,
                host.parent.trace(),
                boundary);
    }

    private static void assertHostTraceEqualsPrefix(
            List<BexGasCharge> expected,
            List<GasTraceEntry> actual) {
        assertEquals(expected.size(), actual.size(),
                "rejected charge must be absent from the host trace");
        for (int index = 0; index < expected.size(); index++) {
            BexGasCharge local = expected.get(index);
            GasTraceEntry host = actual.get(index);
            assertEquals(index, local.sequence());
            assertEquals(index, host.sequence());
            assertEquals(local.namespace(), host.namespace());
            assertEquals(local.counterName(), host.counter());
            assertEquals(local.quantity(), host.quantity());
            assertEquals(local.weight(), host.weight());
            assertEquals(local.gas(), host.subtotal());
            assertEquals(local.sourcePath(), host.scopePath());
            assertEquals(local.operator(), host.logicalPath());
            assertEquals(local.reason(), host.reason());
        }
    }

    private static void assertSameFailure(
            BexGasLimitExceededException left,
            BexGasLimitExceededException right) {
        assertEquals(left.namespace(), right.namespace());
        assertEquals(left.counter(), right.counter());
        assertEquals(left.counterName(), right.counterName());
        assertEquals(left.quantity(), right.quantity());
        assertEquals(left.weight(), right.weight());
        assertEquals(left.admittedGas(), right.admittedGas());
        assertEquals(left.effectiveBudget(), right.effectiveBudget());
    }

    private static void assertHostTracesEqual(
            List<GasTraceEntry> left,
            List<GasTraceEntry> right) {
        assertEquals(left.size(), right.size());
        for (int index = 0; index < left.size(); index++) {
            GasTraceEntry first = left.get(index);
            GasTraceEntry second = right.get(index);
            assertEquals(first.sequence(), second.sequence());
            assertEquals(first.namespace(), second.namespace());
            assertEquals(first.counter(), second.counter());
            assertEquals(first.quantity(), second.quantity());
            assertEquals(first.weight(), second.weight());
            assertEquals(first.subtotal(), second.subtotal());
            assertEquals(first.scopePath(), second.scopePath());
            assertEquals(first.logicalPath(), second.logicalPath());
            assertEquals(first.reason(), second.reason());
        }
    }

    private static BexExecutionContext context(
            RecordingGasHost host,
            RecordingIdentityBoundary boundary,
            long localLimit) {
        FrozenNode empty = FrozenNode.fromResolvedNode(obj());
        return context(
                host,
                boundary,
                localLimit,
                new FrozenBexDocumentView(
                        empty,
                        empty,
                        "/"));
    }

    private static BexExecutionContext context(
            RecordingGasHost host,
            RecordingIdentityBoundary boundary,
            long localLimit,
            FrozenBexDocumentView document) {
        BexExecutionContext.Builder builder =
                BexExecutionContext.builder()
                        .document(document)
                        .semanticIdentityBoundary(boundary);
        if (host != null) {
            builder.gasLedgerHost(host);
        }
        if (localLimit >= 0L) {
            builder.gasLimit(localLimit);
        }
        return builder.build();
    }

    private static final class ExactDocumentProvider
            implements NodeProvider {
        private final String blueId;
        private final Node document;
        private int demands;

        private ExactDocumentProvider(
                String blueId,
                Node document) {
            this.blueId = blueId;
            this.document = document.clone();
        }

        @Override
        public List<Node> fetchByBlueId(
                String requestedBlueId) {
            demands++;
            return blueId.equals(requestedBlueId)
                    ? Collections.singletonList(
                            document.clone())
                    : Collections.<Node>emptyList();
        }
    }

    private static int nthCharge(
            List<BexGasCharge> trace,
            Predicate<BexGasCharge> predicate,
            int occurrence) {
        int seen = 0;
        for (int index = 0; index < trace.size(); index++) {
            if (predicate.test(trace.get(index))) {
                if (seen == occurrence) {
                    return index;
                }
                seen++;
            }
        }
        throw new AssertionError(
                "Expected charge occurrence " + occurrence
                        + " but found " + seen);
    }

    private static int lastCharge(
            List<BexGasCharge> trace,
            Predicate<BexGasCharge> predicate) {
        for (int index = trace.size() - 1; index >= 0; index--) {
            if (predicate.test(trace.get(index))) {
                return index;
            }
        }
        throw new AssertionError("Expected matching charge");
    }

    private static long gas(List<BexGasCharge> charges) {
        long total = 0L;
        for (BexGasCharge charge : charges) {
            total += charge.gas();
        }
        return total;
    }

    private static long quantity(
            List<BexGasCharge> charges,
            String namespace,
            String counter) {
        long total = 0L;
        for (BexGasCharge charge : charges) {
            if (namespace.equals(charge.namespace())
                    && counter.equals(charge.counterName())) {
                total += charge.quantity();
            }
        }
        return total;
    }

    private static String repeatCodePoint(
            char value,
            int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
    }

    private static String repeatText(
            String value,
            int count) {
        StringBuilder text = new StringBuilder(
                value.length() * count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
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

    private interface ChargeSelector {
        int select(List<BexGasCharge> trace);
    }

    private static final class TrackingBigInteger
            extends BigInteger {
        private static final long serialVersionUID = 1L;

        private int quotientExtractions;
        private int eagerRemainderExtractions;

        private TrackingBigInteger(String value) {
            super(value);
        }

        @Override
        public BigInteger divide(BigInteger divisor) {
            quotientExtractions++;
            return super.divide(divisor);
        }

        @Override
        public BigInteger[] divideAndRemainder(
                BigInteger divisor) {
            eagerRemainderExtractions++;
            return super.divideAndRemainder(divisor);
        }

        private void resetObservations() {
            quotientExtractions = 0;
            eagerRemainderExtractions = 0;
        }

        private int quotientExtractions() {
            return quotientExtractions;
        }

        private int eagerRemainderExtractions() {
            return eagerRemainderExtractions;
        }
    }

    private static final class Scenario {
        private final BexEngine engine;
        private final BexCompiledProgram program;

        private Scenario(
                BexEngine engine,
                BexCompiledProgram program) {
            this.engine = engine;
            this.program = program;
        }
    }

    private static final class TargetEvidence {
        private final List<BexGasCharge> prefix;
        private final BexGasCharge rejected;
        private final long prefixGas;

        private TargetEvidence(
                List<BexGasCharge> prefix,
                BexGasCharge rejected,
                long prefixGas) {
            this.prefix = prefix;
            this.rejected = rejected;
            this.prefixGas = prefixGas;
        }
    }

    private static final class LimitedEvidence {
        private final BexGasLimitExceededException failure;
        private final List<GasTraceEntry> hostTrace;
        private final RecordingIdentityBoundary boundary;

        private LimitedEvidence(
                BexGasLimitExceededException failure,
                List<GasTraceEntry> hostTrace,
                RecordingIdentityBoundary boundary) {
            this.failure = failure;
            this.hostTrace = hostTrace;
            this.boundary = boundary;
        }
    }

    private static final class RecordingIdentityBoundary
            implements BexSemanticIdentityBoundary {
        private final AtomicInteger totalAdmissions =
                new AtomicInteger();
        private final AtomicInteger sentinelAdmissions =
                new AtomicInteger();

        @Override
        public BexEstablishedIdentity establishIdentity(Node node) {
            totalAdmissions.incrementAndGet();
            Map<String, Node> properties = node.getProperties();
            if (properties != null) {
                Node marker = properties.get("afterExhaustion");
                if (marker != null
                        && Boolean.TRUE.equals(marker.getValue())) {
                    sentinelAdmissions.incrementAndGet();
                }
            }
            return BexSemanticIdentityBoundary.STANDALONE
                    .establishIdentity(node);
        }
    }

    private static final class RecordingGasHost
            implements BexGasLedgerHost {
        private final GasMeter parent =
                new GasMeter(GasSchedule.contracts10());
        private final Map<GasMeter.ChildGasLedger, Boolean>
                openedLedgers = new IdentityHashMap<>();
        private int successfulSubmissions;
        private int deterministicFailures;
        private int unavailableFinalizations;
        private int exhaustionPropagations;

        @Override
        public GasMeter.ChildGasLedger open(
                String namespace,
                Map<String, Long> counterWeights) {
            GasMeter.ChildGasLedger ledger =
                    parent.childLedger(namespace, counterWeights);
            openedLedgers.put(ledger, Boolean.TRUE);
            return ledger;
        }

        @Override
        public boolean separatesRuntimeNamespaces() {
            return true;
        }

        @Override
        public void submit(GasMeter.ChildGasLedger ledger) {
            successfulSubmissions++;
            mergeOwned(ledger);
        }

        @Override
        public void failedDeterministically(
                GasMeter.ChildGasLedger ledger) {
            deterministicFailures++;
            mergeOwned(ledger);
        }

        @Override
        public void evidenceUnavailable(
                GasMeter.ChildGasLedger ledger) {
            unavailableFinalizations++;
            requireOwned(ledger);
        }

        @Override
        public void propagateGasExhaustion(
                GasMeter.ChildGasLedger ledger,
                GasLimitExceededException exhaustion) {
            exhaustionPropagations++;
            requireOwned(ledger);
            throw exhaustion;
        }

        private void mergeOwned(
                GasMeter.ChildGasLedger ledger) {
            requireOwned(ledger);
            parent.merge(ledger);
        }

        private void requireOwned(
                GasMeter.ChildGasLedger ledger) {
            assertTrue(openedLedgers.containsKey(ledger),
                    "host may finalize only a ledger it opened");
        }
    }
}
