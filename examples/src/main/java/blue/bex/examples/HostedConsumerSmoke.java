package blue.bex.examples;

import blue.bex.api.BexEngine;
import blue.bex.api.BexIntrinsicRegistry;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable public-API smoke for a BEX engine hosted by generic Contracts.
 *
 * <p>A real lifecycle Handler receives a {@link ProcessorExecutionContext},
 * runs BEX through the Contracts gas and semantic-identity adapters, and
 * buffers the computed answer as a Contracts patch. Successful initialization
 * proves that the hosted gas trace was submitted and the patch was committed
 * by the processor rather than by standalone BEX code.</p>
 */
public final class HostedConsumerSmoke {
    private static final String HANDLER_KEY = "runBex";
    private static final String LIFECYCLE_KEY = "lifecycle";
    private static final String ANSWER_KEY = "hostedAnswer";
    private static final String RUNTIME_NAMESPACE = "example-bex";
    private static final String INTRINSIC_BLUE_ID =
            "blue.example/intrinsic/add/1";
    private static final String INTRINSIC_REGISTRY_IDENTITY =
            "blue.example/intrinsics/1";
    private static final String INTRINSIC_NAMESPACE =
            "example-add";
    private static final String INTRINSIC_COUNTER = "addition";

    private HostedConsumerSmoke() {
    }

    /** Runs the hosted smoke and returns its deterministic evidence. */
    public static SmokeResult runSmoke() {
        BexIntrinsicRegistry intrinsics = BexIntrinsicRegistry.builder()
                .register(
                        INTRINSIC_BLUE_ID,
                        INTRINSIC_REGISTRY_IDENTITY,
                        INTRINSIC_NAMESPACE,
                        Collections.singletonMap(
                                INTRINSIC_COUNTER, 3L),
                        invocation -> {
                            invocation.charge(
                                    INTRINSIC_COUNTER,
                                    1L,
                                    "example-addition");
                            BigInteger answer = invocation.field("left")
                                    .asInteger()
                                    .add(invocation.field("right")
                                            .asInteger());
                            Map<String, BexValue> value =
                                    new LinkedHashMap<>();
                            value.put("answer", BexValues.scalar(answer));
                            value.put(
                                    "hosted",
                                    BexValues.scalar(true));
                            return BexValues.map(value);
                        })
                .build();
        BexEngine engine = BexEngine.builder()
                .intrinsics(intrinsics)
                .build();
        HostedHandlerProcessor handler =
                new HostedHandlerProcessor(engine);

        Node handlerType = new Node().name("Hosted BEX Handler");
        String handlerBlueId =
                DirectBlueIdCalculator.calculateBlueId(handlerType);
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                handlerBlueId,
                                handlerType,
                                handler)
                        .build();
        NodeProvider provider = new SequentialNodeProvider(
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider(),
                registry.exactTypeProvider());

        DocumentProcessingResult processed;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             DocumentProcessor contracts = DocumentProcessor.builder()
                     .runtimeRegistry(registry)
                     .nodeProvider(provider)
                     .matchingService(new ContractMatchingService(
                             language.processing().runtimeAccess()))
                     .build()) {
            processed = contracts.initializeDocument(
                    rootDocument(handlerBlueId));
        }

        BexExecutionResult bexResult = handler.lastResult.get();
        require(
                processed.status() == ProcessorStatus.SUCCESS,
                "Contracts initialization failed: " + diagnostic(processed));
        require(
                handler.invocations.get() == 1,
                "expected one hosted Handler invocation");
        require(bexResult != null, "hosted Handler did not return a BEX result");
        require(
                bexResult.output() != null
                        && bexResult.output().reconstructed()
                        && bexResult.output().value().isExact(),
                "expected transient BEX output to cross the hosted identity boundary"
                        + " (output=" + bexResult.output()
                        + ", admittedExact="
                        + (bexResult.output() != null
                        && bexResult.output().value().isExact())
                        + ")");
        require(
                bexResult.gasLedger().quantity(
                        INTRINSIC_NAMESPACE,
                        INTRINSIC_COUNTER) == 1L,
                "expected the intrinsic named-gas charge");
        require(
                !bexResult.gasLedger().trace().isEmpty(),
                "expected a non-empty canonical BEX gas trace");
        require(
                processed.totalGas() >= bexResult.gasUsed(),
                "Contracts gas must include the submitted BEX child trace");

        Node answerNode = processed.document()
                .getProperties()
                .get(ANSWER_KEY);
        BigInteger answer = answerNode != null
                && answerNode.getValue() instanceof BigInteger
                ? (BigInteger) answerNode.getValue()
                : null;
        require(
                BigInteger.valueOf(42L).equals(answer),
                "expected committed hosted answer 42, got " + answer);

        return new SmokeResult(
                answer,
                bexResult.gasUsed(),
                processed.totalGas(),
                bexResult.output().nodeBlueId());
    }

    /** Runs the smoke as an executable consumer. */
    public static void main(String[] args) {
        SmokeResult result = runSmoke();
        System.out.println("hostedResult=" + result.answer());
        System.out.println("bexGas=" + result.bexGas());
        System.out.println("contractsGas=" + result.contractsGas());
        System.out.println("outputBlueId=" + result.outputBlueId());
    }

    private static Node rootDocument(String handlerBlueId) {
        Node lifecycle = typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL);
        Node handler = typed(handlerBlueId)
                .properties("channel", text(LIFECYCLE_KEY))
                .properties(
                        "event",
                        typed(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED));
        return new Node()
                .name("Hosted BEX smoke")
                .properties(ANSWER_KEY, integer(0L))
                .contracts(new Node()
                        .properties(LIFECYCLE_KEY, lifecycle)
                        .properties(HANDLER_KEY, handler));
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static Node text(String value) {
        return new Node().value(value);
    }

    private static Node integer(long value) {
        return new Node().value(BigInteger.valueOf(value));
    }

    private static String diagnostic(DocumentProcessingResult result) {
        return result.diagnostic() == null
                ? result.status().name()
                : result.status().name()
                + ": " + result.diagnostic().message();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Exact Contracts model for the lifecycle Handler used by the smoke. */
    public static final class HostedHandler extends HandlerContract {
        /** Creates an empty model for Contracts mapping. */
        public HostedHandler() {
        }
    }

    private static final class HostedHandlerProcessor
            implements HandlerProcessor<HostedHandler> {
        private final HostedBexExample hosted;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<BexExecutionResult> lastResult =
                new AtomicReference<>();

        private HostedHandlerProcessor(BexEngine engine) {
            this.hosted = new HostedBexExample(engine);
        }

        @Override
        public Class<HostedHandler> contractType() {
            return HostedHandler.class;
        }

        @Override
        public void execute(
                HostedHandler contract,
                ProcessorExecutionContext context) {
            BexExecutionResult result = hosted.run(
                    context,
                    expression(),
                    RUNTIME_NAMESPACE);
            BigInteger answer = result.value()
                    .get("answer")
                    .asInteger();
            context.applyPatch(JsonPatch.replace(
                    "/" + ANSWER_KEY,
                    new Node().value(answer)));
            lastResult.set(result);
            invocations.incrementAndGet();
        }
    }

    private static blue.language.snapshot.FrozenNode expression() {
        Node intrinsic = new Node()
                .properties(
                        "type",
                        new Node().properties(
                                "blueId",
                                text(INTRINSIC_BLUE_ID)))
                .properties("left", integer(40L))
                .properties("right", integer(2L));
        return blue.language.snapshot.FrozenNode.fromResolvedNode(
                new Node().properties("$intrinsic", intrinsic));
    }

    /** Immutable summary printed by the executable and asserted by tests. */
    public static final class SmokeResult {
        private final BigInteger answer;
        private final long bexGas;
        private final long contractsGas;
        private final String outputBlueId;

        private SmokeResult(
                BigInteger answer,
                long bexGas,
                long contractsGas,
                String outputBlueId) {
            this.answer = answer;
            this.bexGas = bexGas;
            this.contractsGas = contractsGas;
            this.outputBlueId = outputBlueId;
        }

        public BigInteger answer() {
            return answer;
        }

        public long bexGas() {
            return bexGas;
        }

        public long contractsGas() {
            return contractsGas;
        }

        public String outputBlueId() {
            return outputBlueId;
        }
    }
}
