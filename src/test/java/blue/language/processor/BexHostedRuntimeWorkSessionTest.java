package blue.language.processor;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.api.ProcessorExecutionContextBexGasLedgerHost;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.runtime.BexRuntime;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.CircularBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static blue.bex.test.BexTestFixtures.frozen;
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

class BexHostedRuntimeWorkSessionTest {
    private static final String FAILURE_INTRINSIC =
            "TestHostedFailureIntrinsic";
    private static final String FAILURE_REGISTRY =
            "test-hosted-failure/1";

    @Test
    void hostedBexLongTraceCapabilityProbeRecordsObservedOutcome()
            throws Exception {
        final int requestedItems = 128;
        final int requiredTraceEntries = 516;
        writeLongTraceEvidence(
                false,
                requestedItems,
                requiredTraceEntries,
                0,
                0,
                false,
                null,
                null);
        GasMeter parent = parentMeter(100_000L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(
                        session, "bex:long-trace-probe");
        Node program = stepDo(list(
                op("$forEach", obj(
                        "in", integerList(requestedItems),
                        "item", "item",
                        "index", "index",
                        "do", list())),
                op("$return", true)));

        BexExecutionResult result = null;
        RuntimeException failure = null;
        try {
            result = BexEngine.builder()
                    .build()
                    .compileAndExecute(
                            BexProgramSource.inline(
                                    frozen(program)),
                            context(
                                    host,
                                    -1L,
                                    BexSemanticIdentityBoundary
                                            .STANDALONE));
        } catch (RuntimeException observed) {
            failure = observed;
        }

        List<GasTraceEntry> staged =
                session.stagedTrace();
        boolean orderPreserved = true;
        Set<String> counters =
                new LinkedHashSet<String>();
        for (int index = 0; index < staged.size(); index++) {
            GasTraceEntry entry = staged.get(index);
            orderPreserved &= entry.sequence() == index;
            counters.add(entry.counter());
        }
        PortableLimitExceededException portable =
                cause(
                        failure,
                        PortableLimitExceededException.class);
        writeLongTraceEvidence(
                false,
                requestedItems,
                requiredTraceEntries,
                staged.size(),
                counters.size(),
                orderPreserved,
                portable,
                failure);
        assertNull(
                failure,
                "the final host must admit an ordered BEX trace "
                        + "longer than 256 entries");
        assertNotNull(result);
        assertTrue(
                staged.size() >= requiredTraceEntries,
                "the hosted trace must exercise more than 256 "
                        + "ordered counter occurrences");
        assertTrue(orderPreserved);
        assertTrue(
                counters.size()
                        <= GasSchedule.contracts10().portableLimit(
                        GasScheduleConstants.PortableLimit
                                .RUNTIME_CHILD_LEDGER_COUNTER_KINDS),
                "the portable limit applies to distinct counter "
                        + "kinds, not trace occurrences");
        assertEquals(
                staged.size(),
                result.gasTrace().size());
        for (int index = 0;
             index < staged.size();
             index++) {
            GasTraceEntry hosted = staged.get(index);
            blue.bex.gas.BexGasCharge bex =
                    result.gasTrace().get(index);
            assertEquals(
                    bex.counterName(),
                    hosted.counter());
            assertEquals(
                    bex.quantity(),
                    hosted.quantity());
            assertEquals(
                    bex.weight(),
                    hosted.weight());
            assertEquals(
                    bex.gas(),
                    hosted.subtotal());
        }
        session.complete();

        assertEquals(staged.size(), parent.trace().size());
        assertTrue(orderPreserved);
        writeLongTraceEvidence(
                true,
                requestedItems,
                requiredTraceEntries,
                staged.size(),
                counters.size(),
                orderPreserved,
                null,
                null);
    }

    @Test
    void physicalNamespacesShareOneBudgetAndSubmittedLedgersAreFinal() {
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        ProcessorExecutionContextBexGasLedgerHost host =
                new ProcessorExecutionContextBexGasLedgerHost(
                        session, "bex:first");

        GasMeter.ChildGasLedger bex = host.open(
                "bex",
                Collections.singletonMap("expressionEvaluated", 1L));
        GasMeter.ChildGasLedger intrinsic = host.open(
                "intrinsic:test",
                Collections.singletonMap("work", 2L));

        assertEquals("bex:first", bex.namespace());
        assertEquals(
                "bex:first/intrinsic:test",
                intrinsic.namespace());
        assertEquals(100L, bex.effectiveBudget());
        assertEquals(100L, intrinsic.effectiveBudget());
        assertThrows(
                UnsupportedOperationException.class,
                () -> bex.counterWeights().put("other", 1L));
        ProcessorExecutionContextBexGasLedgerHost secondOwner =
                new ProcessorExecutionContextBexGasLedgerHost(
                        session, "bex:first");
        assertThrows(
                IllegalStateException.class,
                () -> secondOwner.open(
                        "bex",
                        Collections.singletonMap(
                                "expressionEvaluated", 1L)));

        bex.charge("expressionEvaluated", 1L);
        intrinsic.charge("work", 1L);
        assertEquals(0L, parent.totalGas());
        assertEquals(97L, parent.remainingGas());

        host.submit(bex);
        host.submit(intrinsic);
        assertThrows(
                IllegalStateException.class,
                () -> host.submit(bex));
        assertThrows(
                IllegalStateException.class,
                () -> bex.charge("expressionEvaluated", 1L));

        session.complete();
        assertEquals(3L, parent.totalGas());
        assertEquals(2, parent.trace().size());
        assertEquals("bex:first", parent.trace().get(0).namespace());
        assertEquals(
                "bex:first/intrinsic:test",
                parent.trace().get(1).namespace());
    }

    @Test
    void successfulRuntimeSubmitsEverySeparatedLedgerExactlyOnce() {
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(session, "bex:success");
        BexIntrinsicRegistry intrinsics = BexIntrinsicRegistry.builder()
                .register(
                        "intrinsic-type",
                        "test-registry/1",
                        "intrinsic:test",
                        Collections.singletonMap("work", 2L),
                        invocation -> {
                            invocation.charge(
                                    "work", 1L, "hosted-intrinsic");
                            return BexValues.scalar(true);
                        })
                .build();

        BexExecutionResult result = BexEngine.builder()
                .intrinsics(intrinsics)
                .build()
                .compileAndExecute(
                        BexProgramSource.expression(
                                frozen(op(
                                        "$intrinsic",
                                        obj(
                                                "type",
                                                obj(
                                                        "blueId",
                                                        "intrinsic-type"))))),
                        context(host, -1L,
                                BexSemanticIdentityBoundary.STANDALONE));

        assertNotNull(result);
        assertEquals(2, host.openLogicalNamespaces.size());
        assertEquals("bex", host.openLogicalNamespaces.get(0));
        assertEquals(
                "intrinsic:test",
                host.openLogicalNamespaces.get(1));
        assertEquals(2, host.submitCount);
        assertEquals(0, host.deterministicFailureCount);
        assertEquals(0, host.unavailableCount);

        session.complete();
        assertTrue(parent.totalGas() > 0L);
        assertTrue(parent.trace().stream().anyMatch(
                entry -> "bex:success/intrinsic:test".equals(
                        entry.namespace())
                        && "work".equals(entry.counter())));
    }

    @Test
    void twoCompleteProgramsShareOneParentReservationAndLifecycle() {
        long parentBudget = 1_000L;
        GasMeter parent = parentMeter(parentBudget);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost firstHost =
                new RecordingSessionHost(
                        session, "bex:first-program");
        RecordingSessionHost secondHost =
                new RecordingSessionHost(
                        session, "bex:second-program");
        BexEngine engine = BexEngine.builder().build();

        BexExecutionResult first = engine.compileAndExecute(
                BexProgramSource.expression(
                        FrozenNode.fromResolvedNode(
                                new Node().value(1L))),
                context(
                        firstHost,
                        -1L,
                        BexSemanticIdentityBoundary.STANDALONE));

        assertNotNull(first);
        assertEquals(1, firstHost.submitCount);
        assertEquals(1, firstHost.openedLedgers.size());
        assertEquals(
                parentBudget,
                firstHost.openedLedgers.get(0)
                        .effectiveBudget());
        long firstStaged = gasForNamespace(
                session.stagedTrace(),
                "bex:first-program");
        assertTrue(firstStaged > 0L);
        assertEquals(
                parentBudget - firstStaged,
                parent.remainingGas());
        assertEquals(0L, parent.totalGas());

        BexExecutionResult second = engine.compileAndExecute(
                BexProgramSource.expression(
                        FrozenNode.fromResolvedNode(
                                new Node().value(2L))),
                context(
                        secondHost,
                        -1L,
                        BexSemanticIdentityBoundary.STANDALONE));

        assertNotNull(second);
        assertEquals(1, secondHost.submitCount);
        assertEquals(1, secondHost.openedLedgers.size());
        assertEquals(
                parentBudget - firstStaged,
                secondHost.openedLedgers.get(0)
                        .effectiveBudget(),
                "the second execution must inherit the live shared "
                        + "reservation, not an independent parent budget");

        List<GasTraceEntry> staged = session.stagedTrace();
        long secondStaged = gasForNamespace(
                staged, "bex:second-program");
        long combinedStaged = firstStaged + secondStaged;
        assertTrue(secondStaged > 0L);
        assertTrue(staged.stream().allMatch(entry ->
                "bex:first-program".equals(entry.namespace())
                        || "bex:second-program".equals(
                        entry.namespace())));
        assertTrue(staged.stream().anyMatch(entry ->
                "bex:first-program".equals(entry.namespace())));
        assertTrue(staged.stream().anyMatch(entry ->
                "bex:second-program".equals(entry.namespace())));
        assertEquals(
                combinedStaged,
                parentBudget - parent.remainingGas());
        assertEquals(0L, parent.totalGas());
        assertEquals(0, firstHost.deterministicFailureCount);
        assertEquals(0, firstHost.unavailableCount);
        assertEquals(0, secondHost.deterministicFailureCount);
        assertEquals(0, secondHost.unavailableCount);

        session.complete();

        assertEquals(combinedStaged, parent.totalGas());
        assertEquals(
                parentBudget - combinedStaged,
                parent.remainingGas());
        assertEquals(
                combinedStaged,
                gasForNamespace(
                        parent.trace(),
                        "bex:first-program")
                        + gasForNamespace(
                        parent.trace(),
                        "bex:second-program"));
        assertThrows(
                IllegalStateException.class,
                session::complete);
        assertEquals(
                combinedStaged,
                parent.totalGas(),
                "the shared session lifecycle must merge exactly once");
    }

    @Test
    void deterministicFailureDiscardsBufferedOutputsAndLeavesPrefixForOwner() {
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(session, "bex:failure");
        AtomicInteger semanticAdmissions =
                new AtomicInteger();
        BexExecutionContext context =
                context(
                        host,
                        -1L,
                        node -> {
                            semanticAdmissions.incrementAndGet();
                            return BexSemanticIdentityBoundary
                                    .STANDALONE
                                    .establishIdentity(node);
                        });
        Node program = stepDo(list(
                op("$appendChange", obj(
                        "op", "replace",
                        "path", "/staged",
                        "val", "change")),
                op("$appendEvent", obj(
                        "kind", "staged-event")),
                op("$let", obj(
                        "name", "failure",
                        "expr", op(
                                "$integer",
                                "not-an-integer")))));

        BexProgramSource source =
                BexProgramSource.inline(frozen(program));
        BexCompiledProgram compiled =
                BexEngine.builder().build().compile(source);
        BexMetrics metrics = new BexMetrics();
        try (Blue blue = new Blue()) {
            BexRuntime runtime = new BexRuntime(
                    compiled,
                    context,
                    blue,
                    BexGasSchedule.defaults(),
                    metrics,
                    new BexPointerCache());

            assertThrows(BexException.class, runtime::execute);

            assertEquals(0, host.submitCount);
            assertEquals(1, host.deterministicFailureCount);
            assertEquals(
                    2,
                    semanticAdmissions.get(),
                    "each transient output is admitted once before "
                            + "the later deterministic failure");
            assertTrue(runtime.accumulator()
                    .changeset().entries().isEmpty());
            assertTrue(runtime.accumulator()
                    .events().events().isEmpty());
            assertTrue(runtime.accumulator()
                    .events().admittedEvents().isEmpty());
            assertTrue(runtime.accumulator()
                    .overlay()
                    .rootValue()
                    .get("staged")
                    .isUndefined());
        }
        assertFalse(session.stagedTrace().isEmpty());
        assertEquals(0L, parent.totalGas());

        session.failDeterministically();
        assertTrue(parent.totalGas() > 0L);
    }

    @Test
    void laterHostFailureMergesSuccessfulBexLedgerOnlyOnce() {
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(session, "bex:later-failure");

        BexEngine.builder()
                .build()
                .compileAndExecute(
                        literalExpression(),
                        context(
                                host,
                                -1L,
                                BexSemanticIdentityBoundary.STANDALONE));

        long stagedTotal = 0L;
        for (GasTraceEntry entry : session.stagedTrace()) {
            stagedTotal += entry.subtotal();
        }
        assertTrue(stagedTotal > 0L);
        assertEquals(1, host.submitCount);
        assertEquals(0L, parent.totalGas());

        session.failDeterministically();
        assertEquals(stagedTotal, parent.totalGas());
        assertThrows(
                IllegalStateException.class,
                session::failDeterministically);
        assertEquals(stagedTotal, parent.totalGas());
    }

    @Test
    void unavailableOutputLeavesLedgerForSessionSuspensionAndDiscard() {
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(session, "bex:unavailable");

        assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> BexEngine.builder()
                        .build()
                        .compileAndExecute(
                                literalExpression(),
                                context(
                                        host,
                                        -1L,
                                        node -> {
                                            throw new
                                                    ExecutionEvidenceUnavailableException(
                                                    "identity evidence unavailable");
                                        })));

        assertEquals(0, host.submitCount);
        assertEquals(0, host.deterministicFailureCount);
        assertEquals(1, host.unavailableCount);
        assertFalse(session.stagedTrace().isEmpty());

        session.suspend();
        assertEquals(0L, parent.totalGas());
        assertEquals(100L, parent.remainingGas());
    }

    @Test
    void providerUnavailableUsesHostedSuspensionAndRestoresParentBudget() {
        Node exactContent = obj(
                "value", "temporarily-offline");
        String exactBlueId =
                BlueIdCalculator.calculateBlueId(exactContent);
        AtomicInteger providerDemands =
                new AtomicInteger();
        NodeProvider provider = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(
                    String requestedBlueId) {
                NodeProviderResult result =
                        fetchResultByBlueId(
                                requestedBlueId);
                return result.outcome()
                        == NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : Collections.<Node>emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String requestedBlueId) {
                providerDemands.incrementAndGet();
                return NodeProviderResult.unavailable(
                        "document evidence temporarily unavailable");
            }
        };
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(
                        session,
                        "bex:provider-unavailable");
        FrozenNode document = FrozenNode.fromNode(
                obj("subject",
                        new Node().blueId(
                                exactBlueId)));
        BexExecutionContext context =
                BexExecutionContext.builder()
                        .document(
                                new FrozenBexDocumentView(
                                        document,
                                        document,
                                        "/"))
                        .gasLedgerHost(host)
                        .semanticIdentityBoundary(
                                BexSemanticIdentityBoundary
                                        .STANDALONE)
                        .build();

        try (Blue blue = new Blue(provider)) {
            ExecutionEvidenceUnavailableException failure =
                    assertThrows(
                            ExecutionEvidenceUnavailableException.class,
                            () -> BexEngine.builder()
                                    .blue(blue)
                                    .build()
                                    .compileAndExecute(
                                            BexProgramSource.expression(
                                                    frozen(op(
                                                            "$kind",
                                                            op(
                                                                    "$document",
                                                                    "/subject")))),
                                            context));

            assertEquals(
                    "document evidence temporarily unavailable",
                    failure.getMessage());
            assertEquals(
                    Collections.singletonList(
                            exactBlueId),
                    failure.requiredExactBlueIds());
            assertTrue(providerDemands.get() > 0);
            assertEquals(0, host.submitCount);
            assertEquals(
                    0,
                    host.deterministicFailureCount);
            assertEquals(1, host.unavailableCount);
            assertFalse(session.stagedTrace().isEmpty());
            assertTrue(parent.remainingGas() < 100L,
                    "the live session must hold the uncommitted reservation");
            assertEquals(0L, parent.totalGas());

            session.suspend();

            assertEquals(0L, parent.totalGas());
            assertEquals(100L,
                    parent.remainingGas());
            assertFalse(session.isOpen());
        }
    }

    @Test
    void hostRejectionPropagatesTheExactRecordedException() {
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(session, "bex:exhaustion");
        AtomicInteger identityCalls = new AtomicInteger();

        try (Blue blue = new Blue()) {
            BexEngine engine = BexEngine.builder()
                    .blue(blue)
                    .build();
            BexCompiledProgram compiled =
                    engine.compile(literalExpression());
            BexRuntime runtime = new BexRuntime(
                    compiled,
                    context(
                            host,
                            -1L,
                            node -> {
                                identityCalls.incrementAndGet();
                                return BexSemanticIdentityBoundary.STANDALONE
                                        .establishIdentity(node);
                            }),
                    blue,
                    BexGasSchedule.defaults(),
                    new BexMetrics(),
                    new BexPointerCache(),
                    BexIntrinsicRegistry.empty());

            GasMeter.ChildGasLedger competing =
                    session.openLedger(
                            "competing",
                            Collections.singletonMap("work", 1L));
            competing.charge("work", 100L);

            GasLimitExceededException exhausted = assertThrows(
                    GasLimitExceededException.class,
                    runtime::execute);

            assertSame(host.propagatedExhaustion, exhausted);
            assertEquals("bex:exhaustion", exhausted.namespace());
            assertEquals(
                    BexGasCounter.FUNCTION_CALLED.canonicalName(),
                    exhausted.counter());
            assertEquals(1L, exhausted.quantity());
            assertEquals(2L, exhausted.weight());
            assertEquals(0L, exhausted.admittedGas());
            assertEquals(100L, exhausted.effectiveBudget());
            assertEquals(0, identityCalls.get());
            assertEquals(0, host.submitCount);
            assertEquals(1, host.deterministicFailureCount);
            assertFalse(session.isOpen());
            assertEquals(100L, parent.totalGas());
            assertEquals(1, parent.trace().size());
            assertEquals(
                    "competing",
                    parent.trace().get(0).namespace());
        }
    }

    @Test
    void hostedGasExhaustionRetainsExactBexPrefixAndNoOutput() {
        BexEngine engine = BexEngine.builder().build();
        Node program = stepDo(list(
                op("$forEach", obj(
                        "in", integerList(4),
                        "item", "item",
                        "index", "index",
                        "do", list())),
                op("$return", true)));
        BexCompiledProgram compiled =
                engine.compile(BexProgramSource.inline(
                        frozen(program)));
        FrozenNode document = FrozenNode.fromResolvedNode(
                new Node().properties(
                        Collections.<String, Node>emptyMap()));
        BexExecutionResult baseline = engine.execute(
                compiled,
                BexExecutionContext.builder()
                        .document(
                                new FrozenBexDocumentView(
                                        document))
                        .semanticIdentityBoundary(
                                BexSemanticIdentityBoundary
                                        .STANDALONE)
                        .gasLimit(1_000_000L)
                        .build());
        int rejectedIndex = 3;
        assertTrue(
                baseline.gasTrace().size()
                        > rejectedIndex);
        long prefixGas = 0L;
        for (int index = 0;
             index < rejectedIndex;
             index++) {
            prefixGas += baseline.gasTrace()
                    .get(index).gas();
        }
        blue.bex.gas.BexGasCharge rejected =
                baseline.gasTrace().get(rejectedIndex);

        GasMeter parent = parentMeter(prefixGas);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(
                        session, "bex:prefix-exhaustion");

        GasLimitExceededException failure =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> engine.execute(
                                compiled,
                                context(
                                        host,
                                        -1L,
                                        BexSemanticIdentityBoundary
                                                .STANDALONE)));

        assertSame(host.propagatedExhaustion, failure);
        assertEquals(
                "bex:prefix-exhaustion",
                failure.namespace());
        assertEquals(
                rejected.counterName(),
                failure.counter());
        assertEquals(
                rejected.quantity(),
                failure.quantity());
        assertEquals(
                rejected.weight(),
                failure.weight());
        assertEquals(prefixGas,
                failure.admittedGas());
        assertEquals(prefixGas,
                failure.effectiveBudget());
        assertEquals(0, host.submitCount);
        assertEquals(1,
                host.deterministicFailureCount);
        assertFalse(session.isOpen());
        assertEquals(prefixGas,
                parent.totalGas());
        assertEquals(rejectedIndex,
                parent.trace().size());
        for (int index = 0;
             index < rejectedIndex;
             index++) {
            blue.bex.gas.BexGasCharge expected =
                    baseline.gasTrace().get(index);
            GasTraceEntry actual =
                    parent.trace().get(index);
            assertEquals(
                    expected.counterName(),
                    actual.counter());
            assertEquals(
                    expected.quantity(),
                    actual.quantity());
            assertEquals(
                    expected.weight(),
                    actual.weight());
            assertEquals(
                    expected.gas(),
                    actual.subtotal());
        }
        assertFalse(parent.trace().stream()
                .anyMatch(entry ->
                        rejected.counterName().equals(
                                entry.counter())
                                && entry.sequence()
                                >= rejectedIndex));
    }

    @Test
    void localLimitUsesCanonicalSessionRecordedHostRejection() {
        GasMeter parent = parentMeter(100L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(session, "bex:local-limit");
        AtomicInteger identityCalls = new AtomicInteger();

        GasLimitExceededException failure = assertThrows(
                GasLimitExceededException.class,
                () -> BexEngine.builder()
                        .build()
                        .compileAndExecute(
                                literalExpression(),
                                context(
                                        host,
                                        2L,
                                        node -> {
                                            identityCalls.incrementAndGet();
                                            return BexSemanticIdentityBoundary
                                                    .STANDALONE
                                                    .establishIdentity(node);
                                        })));

        assertSame(host.propagatedExhaustion, failure);
        assertEquals("bex:local-limit", failure.namespace());
        assertEquals(
                BexGasCounter.EXPRESSION_EVALUATED.canonicalName(),
                failure.counter());
        assertEquals(2L, failure.admittedGas());
        assertEquals(2L, failure.effectiveBudget());
        assertEquals(0, identityCalls.get());
        assertEquals(0, host.submitCount);
        assertEquals(1, host.deterministicFailureCount);
        assertEquals(1, host.openSharedBudgetCount);
        assertEquals(2L, host.sharedBudget.maximumGas());
        assertFalse(session.isOpen());
        assertEquals(2L, parent.totalGas());
    }

    @Test
    void hostedLocalLimitIsSharedAcrossBexAndIntrinsicLedgers() {
        final String intrinsicBlueId =
                "TestSharedLocalBudgetIntrinsic";
        final String intrinsicCounter = "work";
        final long intrinsicWeight = 3L;
        AtomicLong gasBeforeIntrinsicWork =
                new AtomicLong(-1L);
        AtomicInteger workAfterCharge =
                new AtomicInteger();
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        intrinsicBlueId,
                        "test-shared-local-budget/1",
                        Collections.singletonMap(
                                intrinsicCounter,
                                intrinsicWeight),
                        invocation -> {
                            gasBeforeIntrinsicWork.set(
                                    invocation.gasUsed());
                            invocation.charge(
                                    intrinsicCounter,
                                    1L,
                                    "shared-local-budget-probe");
                            workAfterCharge.incrementAndGet();
                            return BexValues.scalar(true);
                        })
                .build();
        BexProgramSource source =
                BexProgramSource.expression(
                        frozen(op(
                                "$intrinsic",
                                obj(
                                        "type",
                                        obj(
                                                "blueId",
                                                intrinsicBlueId)))));

        GasMeter baselineParent = parentMeter(1_000L);
        RuntimeWorkSession baselineSession =
                session(baselineParent);
        RecordingSessionHost baselineHost =
                new RecordingSessionHost(
                        baselineSession,
                        "bex:shared-local-baseline");
        engine.compileAndExecute(
                source,
                context(
                        baselineHost,
                        -1L,
                        BexSemanticIdentityBoundary.STANDALONE));
        baselineSession.complete();
        long sharedLimit = gasBeforeIntrinsicWork.get();
        assertTrue(sharedLimit > 0L);
        assertEquals(1, workAfterCharge.get());

        workAfterCharge.set(0);
        GasMeter parent = parentMeter(1_000L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(
                        session, "bex:shared-local");

        GasLimitExceededException failure = assertThrows(
                GasLimitExceededException.class,
                () -> engine.compileAndExecute(
                        source,
                        context(
                                host,
                                sharedLimit,
                                BexSemanticIdentityBoundary
                                        .STANDALONE)));

        assertSame(host.propagatedExhaustion, failure);
        assertEquals(
                "bex:shared-local/intrinsic-"
                        + intrinsicBlueId,
                failure.namespace());
        assertEquals(intrinsicCounter, failure.counter());
        assertEquals(1L, failure.quantity());
        assertEquals(intrinsicWeight, failure.weight());
        assertEquals(sharedLimit, failure.admittedGas());
        assertEquals(sharedLimit, failure.effectiveBudget());
        assertEquals(0, workAfterCharge.get(),
                "rejected intrinsic work must not run");
        assertEquals(1, host.openSharedBudgetCount);
        assertEquals(sharedLimit,
                host.sharedBudget.maximumGas());
        assertEquals(sharedLimit,
                host.sharedBudget.admittedGas());
        assertEquals(
                java.util.Arrays.asList(
                        BexGasCounter.NAMESPACE,
                        "intrinsic-" + intrinsicBlueId),
                host.openLogicalNamespaces);
        assertEquals(0, host.submitCount);
        assertEquals(2, host.deterministicFailureCount);
        assertEquals(0, host.unavailableCount);
        assertFalse(session.isOpen());
        assertEquals(sharedLimit, parent.totalGas());
    }

    @Test
    void runtimeNamespaceSeparatorIsReservedAndIntrinsicFlatteningFailsClosed() {
        RuntimeWorkSession session = session(parentMeter(100L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProcessorExecutionContextBexGasLedgerHost(
                        session, "bex/run"));

        NonSeparatingHost host = new NonSeparatingHost();
        BexIntrinsicRegistry intrinsics = BexIntrinsicRegistry.builder()
                .register(
                        "intrinsic-type",
                        "test-registry/1",
                        "intrinsic:test",
                        Collections.singletonMap("work", 1L),
                        invocation -> BexValues.scalar(true))
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> BexEngine.builder()
                        .intrinsics(intrinsics)
                        .build()
                        .compileAndExecute(
                                BexProgramSource.expression(
                                        frozen(op(
                                                "$intrinsic",
                                                obj(
                                                        "type",
                                                        obj(
                                                                "blueId",
                                                                "intrinsic-type"))))),
                                context(
                                        host,
                                        -1L,
                                        BexSemanticIdentityBoundary
                                                .STANDALONE)));
        assertEquals(0, host.openCount);
    }

    @Test
    void laterNamespaceOpenFailureFinalizesEveryLedgerAlreadyOpened() {
        FailingSecondOpenHost host =
                new FailingSecondOpenHost();
        BexIntrinsicRegistry intrinsics = BexIntrinsicRegistry.builder()
                .register(
                        "intrinsic-type",
                        "test-registry/1",
                        "intrinsic:test",
                        Collections.singletonMap("work", 1L),
                        invocation -> BexValues.scalar(true))
                .build();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> BexEngine.builder()
                        .intrinsics(intrinsics)
                        .build()
                        .compileAndExecute(
                                BexProgramSource.expression(
                                        frozen(op(
                                                "$intrinsic",
                                                obj(
                                                        "type",
                                                        obj(
                                                                "blueId",
                                                                "intrinsic-type"))))),
                                context(
                                        host,
                                        -1L,
                                        BexSemanticIdentityBoundary
                                                .STANDALONE)));

        assertEquals("second open rejected", failure.getMessage());
        assertEquals(2, host.openCount);
        assertEquals(1, host.deterministicFinalizations);
        assertEquals(0, host.unavailableFinalizations);
        assertEquals(0, host.successfulSubmissions);
    }

    @Test
    void constructionProcessorFailureWinsOverNestedUnavailability() {
        ExecutionEvidenceUnavailableException nested =
                new ExecutionEvidenceUnavailableException(
                        "nested open evidence detail");
        ProcessorFailureException expected =
                new ProcessorFailureException(
                        ProcessorErrorCategory
                                .RuntimeExecutionFailure,
                        "second namespace rejected deterministically",
                        nested);
        FailingSecondOpenHost host =
                new FailingSecondOpenHost(expected);
        BexIntrinsicRegistry intrinsics = BexIntrinsicRegistry.builder()
                .register(
                        "intrinsic-type",
                        "test-registry/1",
                        "intrinsic:test",
                        Collections.singletonMap("work", 1L),
                        invocation -> BexValues.scalar(true))
                .build();

        ProcessorFailureException observed = assertThrows(
                ProcessorFailureException.class,
                () -> BexEngine.builder()
                        .intrinsics(intrinsics)
                        .build()
                        .compileAndExecute(
                                BexProgramSource.expression(
                                        frozen(op(
                                                "$intrinsic",
                                                obj(
                                                        "type",
                                                        obj(
                                                                "blueId",
                                                                "intrinsic-type"))))),
                                context(
                                        host,
                                        -1L,
                                        BexSemanticIdentityBoundary
                                                .STANDALONE)));

        assertSame(expected, observed);
        assertEquals(2, host.openCount);
        assertEquals(1, host.deterministicFinalizations);
        assertEquals(0, host.unavailableFinalizations);
        assertEquals(0, host.successfulSubmissions);
    }

    @Test
    void processorExecutionContextUsesItsInvocationSemanticOutputBoundary() {
        try (Blue blue = new Blue()) {
            ProcessorEngine.Execution execution =
                    new ProcessorEngine.Execution(
                            blue.getDocumentProcessor(),
                            new Node().properties(
                                    Collections.<String, Node>emptyMap()));
            execution.preflightScope("/");
            long identitiesBefore = gasQuantity(
                    execution.runtime().gasMeter(),
                    GasScheduleConstants.Namespace.SEMANTIC,
                    GasScheduleConstants.SemanticCounter
                            .NODE_IDENTITY_ESTABLISHED);

            BexExecutionResult result;
            try (ProcessorExecutionContext processorContext =
                         execution.createContext(
                                 "/",
                                 execution.bundleForScope("/"),
                                 new Node(),
                                 false)) {
                BexExecutionContext context =
                        BexExecutionContext.builder()
                                .processorExecutionContext(
                                        processorContext,
                                        "bex:semantic-adapter")
                                .build();
                result = BexEngine.builder()
                        .build()
                        .compileAndExecute(
                                BexProgramSource.expression(
                                        FrozenNode.fromResolvedNode(
                                                new Node().value(
                                                        "hosted-output"))),
                                context);

                assertTrue(result.output().reconstructed());
                assertEquals(
                        result.output().nodeBlueId(),
                        BlueIdCalculator.calculateBlueId(
                                result.output().node()));
                processorContext.applyBufferedEffects();
            }

            long identitiesAfter = gasQuantity(
                    execution.runtime().gasMeter(),
                    GasScheduleConstants.Namespace.SEMANTIC,
                    GasScheduleConstants.SemanticCounter
                            .NODE_IDENTITY_ESTABLISHED);
            assertEquals(
                    1L,
                    identitiesAfter - identitiesBefore,
                    "the concrete adapter must cross the invocation-owned "
                            + "semantic boundary exactly once");
            assertTrue(execution.runtime().gasMeter().trace().stream()
                    .anyMatch(entry ->
                            "bex:semantic-adapter".equals(
                                    entry.namespace())));
        }
    }

    @Test
    void hostedOpaqueCyclicMemberSupportsIdentityAndOutputWithoutProofDemand() {
        String memberBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().value("hosted-cyclic-set"))
                        + "#0";
        FrozenNode document = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "member",
                        new Node().blueId(memberBlueId)));
        GasMeter parent = parentMeter(1_000L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(
                        session, "bex:hosted-cyclic");
        AtomicInteger boundaryCalls = new AtomicInteger();
        BexExecutionContext context =
                BexExecutionContext.builder()
                        .document(new FrozenBexDocumentView(document))
                        .gasLedgerHost(host)
                        .semanticIdentityBoundary(node -> {
                            boundaryCalls.incrementAndGet();
                            throw new AssertionError(
                                    "opaque exact cyclic output must not "
                                            + "re-enter semantic admission");
                        })
                        .build();
        Node program = stepDo(list(
                op("$let", obj(
                        "name", "member",
                        "expr", op("$document", "/member"))),
                op("$appendEvent", op("$var", "member")),
                op("$let", obj(
                        "name", "identity",
                        "expr", op(
                                "$nodeBlueId",
                                op("$var", "member")))),
                op("$return", op("$var", "member"))));

        BexExecutionResult result = BexEngine.builder()
                .build()
                .compileAndExecute(
                        BexProgramSource.inline(frozen(program)),
                        context);

        assertEquals(0, boundaryCalls.get());
        assertEquals(memberBlueId,
                result.output().nodeBlueId());
        assertFalse(result.output().reconstructed());
        assertEquals(memberBlueId,
                result.events().admittedEvents().get(0)
                        .nodeBlueId());
        assertEquals(1, host.submitCount);
        assertEquals(0, host.deterministicFailureCount);
        assertEquals(0, host.unavailableCount);

        session.complete();
        assertTrue(parent.trace().stream().anyMatch(
                entry -> "bex:hosted-cyclic".equals(
                        entry.namespace())
                        && BexGasCounter.NODE_IDENTITY_REQUESTED
                        .canonicalName().equals(
                                entry.counter())));
    }

    @Test
    void hostedCyclicProofUnavailabilityUsesSessionDiscardLifecycle() {
        Node placeholder = obj(
                "label", "hosted-proof-unavailable",
                "next", new Node().blueId("this#0"))
                .name("hosted-proof-unavailable-member");
        List<Node> placeholders =
                Collections.singletonList(placeholder);
        String memberBlueId =
                CircularBlueIdCalculator
                        .calculateCircularSetBlueIds(
                                placeholders)
                        .get(0);
        Node resolvedMember = placeholder.clone();
        resolvedMember.getProperties().get("next")
                .blueId(memberBlueId);
        CyclicProofUnavailableProvider provider =
                new CyclicProofUnavailableProvider(
                        memberBlueId,
                        resolvedMember);
        GasMeter parent = parentMeter(1_000L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(
                        session,
                        "bex:cyclic-proof-unavailable");

        try (Blue blue = new Blue(provider)) {
            BexExecutionContext context =
                    BexExecutionContext.builder()
                            .document(
                                    new FrozenBexDocumentView(
                                            FrozenNode.fromResolvedNode(
                                                    obj(
                                                            "member",
                                                            new Node().blueId(
                                                                    memberBlueId)))))
                            .gasLedgerHost(host)
                            .semanticIdentityBoundary(
                                    BexSemanticIdentityBoundary
                                            .STANDALONE)
                            .build();

            ExecutionEvidenceUnavailableException failure =
                    assertThrows(
                            ExecutionEvidenceUnavailableException.class,
                            () -> BexEngine.builder()
                                    .blue(blue)
                                    .build()
                                    .compileAndExecute(
                                            BexProgramSource.expression(
                                                    frozen(op(
                                                            "$kind",
                                                            op(
                                                                    "$document",
                                                                    "/member")))),
                                            context));

            assertEquals(
                    Collections.singletonList(memberBlueId),
                    failure.requiredExactBlueIds());
            assertEquals(
                    "hosted cyclic proof store temporarily unavailable",
                    failure.getMessage());
            assertEquals(1, provider.proofQueries);
            assertEquals(0, host.submitCount);
            assertEquals(0,
                    host.deterministicFailureCount);
            assertEquals(1, host.unavailableCount);
            assertTrue(session.isOpen());
            assertFalse(session.stagedTrace().isEmpty());
        }

        session.suspend();
        assertEquals(0L, parent.totalGas());
        assertEquals(1_000L, parent.remainingGas());
    }

    @Test
    void intrinsicUnavailableUsesHostedDiscardLifecycle() {
        ExecutionEvidenceUnavailableException expected =
                new ExecutionEvidenceUnavailableException(
                        "intrinsic evidence temporarily unavailable");
        IntrinsicFailureScenario scenario =
                intrinsicFailureScenario(expected);

        assertSame(expected, scenario.observed);
        assertEquals(0, scenario.host.submitCount);
        assertEquals(0,
                scenario.host.deterministicFailureCount);
        assertEquals(2, scenario.host.unavailableCount);
        assertFalse(scenario.session.stagedTrace().isEmpty());

        scenario.session.suspend();
        assertEquals(0L, scenario.parent.totalGas());
        assertEquals(1_000L,
                scenario.parent.remainingGas());
    }

    @Test
    void processorFailureWinsOverNestedUnavailableExecutionCause() {
        ExecutionEvidenceUnavailableException nested =
                new ExecutionEvidenceUnavailableException(
                        "nested intrinsic evidence detail");
        ProcessorFailureException expected =
                new ProcessorFailureException(
                        ProcessorErrorCategory
                                .RuntimeExecutionFailure,
                        "intrinsic rejected deterministically",
                        nested);
        IntrinsicFailureScenario scenario =
                intrinsicFailureScenario(expected);

        assertSame(expected, scenario.observed);
        assertEquals(0, scenario.host.submitCount);
        assertEquals(2,
                scenario.host.deterministicFailureCount);
        assertEquals(0, scenario.host.unavailableCount);
        assertFalse(scenario.session.stagedTrace().isEmpty());

        scenario.session.failDeterministically();
        assertTrue(scenario.parent.totalGas() > 0L);
    }

    @Test
    void directInvalidEvidenceWinsOverItsNestedUnavailableCause() {
        ExecutionEvidenceUnavailableException nested =
                new ExecutionEvidenceUnavailableException(
                        "nested invalid-evidence detail");
        InvalidExecutionEvidenceException expected =
                new InvalidExecutionEvidenceException(
                        "direct invalid evidence");
        expected.initCause(nested);
        IntrinsicFailureScenario scenario =
                intrinsicFailureScenario(expected);

        assertSame(expected, scenario.observed);
        assertEquals(0, scenario.host.submitCount);
        assertEquals(2,
                scenario.host.deterministicFailureCount);
        assertEquals(0, scenario.host.unavailableCount);
        assertFalse(scenario.session.stagedTrace().isEmpty());

        scenario.session.failDeterministically();
        assertTrue(scenario.parent.totalGas() > 0L);
    }

    @Test
    void intrinsicInvalidEvidenceUsesHostedDeterministicLifecycle() {
        InvalidExecutionEvidenceException expected =
                new InvalidExecutionEvidenceException(
                        "intrinsic returned invalid evidence");
        IntrinsicFailureScenario scenario =
                intrinsicFailureScenario(expected);

        assertSame(expected, scenario.observed);
        assertEquals(0, scenario.host.submitCount);
        assertEquals(2,
                scenario.host.deterministicFailureCount);
        assertEquals(0, scenario.host.unavailableCount);
        assertFalse(scenario.session.stagedTrace().isEmpty());

        scenario.session.failDeterministically();
        assertTrue(scenario.parent.totalGas() > 0L);
    }

    @Test
    void arbitraryIntrinsicFailureIsNotReclassifiedAsUnavailable() {
        IllegalStateException expected =
                new IllegalStateException(
                        "intrinsic implementation defect");
        IntrinsicFailureScenario scenario =
                intrinsicFailureScenario(expected);

        assertSame(expected, scenario.observed);
        assertEquals(0, scenario.host.submitCount);
        assertEquals(2,
                scenario.host.deterministicFailureCount);
        assertEquals(0, scenario.host.unavailableCount);
        assertFalse(scenario.session.stagedTrace().isEmpty());

        scenario.session.failDeterministically();
        assertTrue(scenario.parent.totalGas() > 0L);
    }

    private static IntrinsicFailureScenario intrinsicFailureScenario(
            RuntimeException expected) {
        GasMeter parent = parentMeter(1_000L);
        RuntimeWorkSession session = session(parent);
        RecordingSessionHost host =
                new RecordingSessionHost(
                        session, "bex:intrinsic-failure");
        BexEngine engine = BexEngine.builder()
                .intrinsic(
                        FAILURE_INTRINSIC,
                        FAILURE_REGISTRY,
                        Collections.singletonMap("work", 1L),
                        invocation -> {
                            throw expected;
                        })
                .build();
        BexProgramSource source =
                BexProgramSource.expression(
                        frozen(op(
                                "$intrinsic",
                                obj(
                                        "type",
                                        obj(
                                                "blueId",
                                                FAILURE_INTRINSIC)))));

        RuntimeException observed = assertThrows(
                RuntimeException.class,
                () -> engine.compileAndExecute(
                        source,
                        context(
                                host,
                                -1L,
                                BexSemanticIdentityBoundary
                                        .STANDALONE)));
        return new IntrinsicFailureScenario(
                parent, session, host, observed);
    }

    private static long gasQuantity(
            GasMeter meter,
            String namespace,
            String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : meter.trace()) {
            if (namespace.equals(entry.namespace())
                    && counter.equals(entry.counter())) {
                quantity += entry.quantity();
            }
        }
        return quantity;
    }

    private static long gasForNamespace(
            List<GasTraceEntry> trace,
            String namespace) {
        long subtotal = 0L;
        for (GasTraceEntry entry : trace) {
            if (namespace.equals(entry.namespace())) {
                subtotal += entry.subtotal();
            }
        }
        return subtotal;
    }

    private static RuntimeWorkSession session(GasMeter parent) {
        return new RuntimeWorkSession(
                parent, RuntimeWorkSession.Mode.PROCESSING);
    }

    private static GasMeter parentMeter(long budget) {
        return new GasMeter(GasSchedule.contracts10(), budget);
    }

    private static BexProgramSource literalExpression() {
        return BexProgramSource.expression(
                FrozenNode.fromResolvedNode(
                        new Node().value(1L)));
    }

    private static Node integerList(int size) {
        List<Node> items = new ArrayList<Node>();
        for (int index = 0; index < size; index++) {
            items.add(new Node().value((long) index));
        }
        return new Node().items(items);
    }

    private static <T extends Throwable> T cause(
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

    private static void writeLongTraceEvidence(
            boolean passed,
            int requestedItems,
            int requiredTraceEntries,
            int observedTraceEntries,
            int observedCounterKinds,
            boolean orderPreserved,
            PortableLimitExceededException portable,
            RuntimeException failure) throws Exception {
        Path output = Paths.get(
                System.getProperty("user.dir"),
                "build",
                "reports",
                "bex-release",
                "host-long-trace.properties");
        Files.createDirectories(output.getParent());
        List<String> lines = new ArrayList<String>();
        lines.add(
                "schema=blue-bex-host-long-trace-evidence/1.0");
        lines.add("status=" + (passed ? "passed" : "blocking"));
        lines.add("requestedItems=" + requestedItems);
        lines.add(
                "requiredMinimumOrderedTraceEntries="
                        + requiredTraceEntries);
        lines.add(
                "maximumObservedOrderedTraceEntries="
                        + observedTraceEntries);
        lines.add(
                "observedDistinctCounterKinds="
                        + observedCounterKinds);
        lines.add("orderPreserved=" + orderPreserved);
        lines.add(
                "failure.class="
                        + (failure == null
                        ? "none"
                        : failure.getClass().getName()));
        lines.add(
                "portableLimit.name="
                        + (portable == null
                        ? "none"
                        : portable.limitName()));
        lines.add(
                "portableLimit.observed="
                        + (portable == null
                        ? "none"
                        : String.valueOf(portable.observed())));
        lines.add(
                "portableLimit.limit="
                        + (portable == null
                        ? "none"
                        : String.valueOf(portable.limit())));
        Files.write(
                output,
                (String.join("\n", lines) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static BexExecutionContext context(
            BexGasLedgerHost host,
            long localLimit,
            BexSemanticIdentityBoundary identityBoundary) {
        FrozenNode document = FrozenNode.fromResolvedNode(
                new Node().properties(
                        Collections.<String, Node>emptyMap()));
        BexExecutionContext.Builder builder =
                BexExecutionContext.builder()
                        .document(new FrozenBexDocumentView(document))
                        .gasLedgerHost(host)
                        .semanticIdentityBoundary(identityBoundary);
        if (localLimit >= 0L) {
            builder.gasLimit(localLimit);
        }
        return builder.build();
    }

    private static final class CyclicProofUnavailableProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node resolvedMember;
        private int proofQueries;

        private CyclicProofUnavailableProvider(
                String memberBlueId,
                Node resolvedMember) {
            this.memberBlueId = memberBlueId;
            this.resolvedMember = resolvedMember.clone();
        }

        @Override
        public List<Node> fetchByBlueId(
                String requestedBlueId) {
            return memberBlueId.equals(requestedBlueId)
                    ? Collections.singletonList(
                            resolvedMember.clone())
                    : Collections.<Node>emptyList();
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(
                String requestedBlueId) {
            proofQueries++;
            return memberBlueId.equals(requestedBlueId)
                    ? CyclicSetProofResult.unavailable(
                            "hosted cyclic proof store temporarily unavailable")
                    : CyclicSetProofResult.notFound();
        }
    }

    private static final class RecordingSessionHost
            implements BexGasLedgerHost {
        private final ProcessorExecutionContextBexGasLedgerHost delegate;
        private final List<String> openLogicalNamespaces =
                new ArrayList<>();
        private final List<GasMeter.ChildGasLedger> openedLedgers =
                new ArrayList<>();
        private int submitCount;
        private int deterministicFailureCount;
        private int unavailableCount;
        private int openSharedBudgetCount;
        private RuntimeWorkBudget sharedBudget;
        private GasLimitExceededException propagatedExhaustion;

        private RecordingSessionHost(
                RuntimeWorkSession session,
                String runtimeNamespace) {
            this.delegate =
                    new ProcessorExecutionContextBexGasLedgerHost(
                            session, runtimeNamespace);
        }

        @Override
        public RuntimeWorkBudget openSharedBudget(
                long maximumGas) {
            openSharedBudgetCount++;
            sharedBudget =
                    delegate.openSharedBudget(maximumGas);
            return sharedBudget;
        }

        @Override
        public GasMeter.ChildGasLedger open(
                String namespace,
                Map<String, Long> counterWeights) {
            return open(
                    namespace,
                    counterWeights,
                    null);
        }

        @Override
        public GasMeter.ChildGasLedger open(
                String namespace,
                Map<String, Long> counterWeights,
                RuntimeWorkBudget sharedBudget) {
            openLogicalNamespaces.add(namespace);
            GasMeter.ChildGasLedger ledger =
                    delegate.open(
                            namespace,
                            counterWeights,
                            sharedBudget);
            openedLedgers.add(ledger);
            return ledger;
        }

        @Override
        public void submit(GasMeter.ChildGasLedger ledger) {
            submitCount++;
            delegate.submit(ledger);
        }

        @Override
        public boolean separatesRuntimeNamespaces() {
            return delegate.separatesRuntimeNamespaces();
        }

        @Override
        public void failedDeterministically(
                GasMeter.ChildGasLedger ledger) {
            deterministicFailureCount++;
            delegate.failedDeterministically(ledger);
        }

        @Override
        public void evidenceUnavailable(
                GasMeter.ChildGasLedger ledger) {
            unavailableCount++;
            delegate.evidenceUnavailable(ledger);
        }

        @Override
        public RuntimeException localGasLimitExceeded(
                BexGasLimitExceededException exhaustion,
                RuntimeException originalFailure) {
            return delegate.localGasLimitExceeded(
                    exhaustion, originalFailure);
        }

        @Override
        public void propagateGasExhaustion(
                GasMeter.ChildGasLedger ledger,
                GasLimitExceededException exhaustion) {
            propagatedExhaustion = exhaustion;
            delegate.propagateGasExhaustion(
                    ledger, exhaustion);
        }
    }

    private static final class IntrinsicFailureScenario {
        private final GasMeter parent;
        private final RuntimeWorkSession session;
        private final RecordingSessionHost host;
        private final RuntimeException observed;

        private IntrinsicFailureScenario(
                GasMeter parent,
                RuntimeWorkSession session,
                RecordingSessionHost host,
                RuntimeException observed) {
            this.parent = parent;
            this.session = session;
            this.host = host;
            this.observed = observed;
        }
    }

    private static final class NonSeparatingHost
            implements BexGasLedgerHost {
        private int openCount;

        @Override
        public GasMeter.ChildGasLedger open(
                String namespace,
                Map<String, Long> counterWeights) {
            openCount++;
            throw new AssertionError(
                    "flattening host must be rejected before open");
        }

        @Override
        public boolean separatesRuntimeNamespaces() {
            return false;
        }

        @Override
        public void submit(GasMeter.ChildGasLedger ledger) {
        }

        @Override
        public void failedDeterministically(
                GasMeter.ChildGasLedger ledger) {
        }

        @Override
        public void evidenceUnavailable(
                GasMeter.ChildGasLedger ledger) {
        }
    }

    private static final class FailingSecondOpenHost
            implements BexGasLedgerHost {
        private final GasMeter parent = parentMeter(100L);
        private final RuntimeException secondOpenFailure;
        private int openCount;
        private int deterministicFinalizations;
        private int unavailableFinalizations;
        private int successfulSubmissions;

        private FailingSecondOpenHost() {
            this(new IllegalStateException(
                    "second open rejected"));
        }

        private FailingSecondOpenHost(
                RuntimeException secondOpenFailure) {
            this.secondOpenFailure =
                    secondOpenFailure;
        }

        @Override
        public GasMeter.ChildGasLedger open(
                String namespace,
                Map<String, Long> counterWeights) {
            openCount++;
            if (openCount == 2) {
                throw secondOpenFailure;
            }
            return parent.childLedger(
                    namespace, counterWeights);
        }

        @Override
        public void submit(GasMeter.ChildGasLedger ledger) {
            successfulSubmissions++;
        }

        @Override
        public void failedDeterministically(
                GasMeter.ChildGasLedger ledger) {
            deterministicFinalizations++;
        }

        @Override
        public void evidenceUnavailable(
                GasMeter.ChildGasLedger ledger) {
            unavailableFinalizations++;
        }
    }
}
