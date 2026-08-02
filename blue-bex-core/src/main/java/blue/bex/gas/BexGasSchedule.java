package blue.bex.gas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable deterministic BEX gas schedule backed by the complete BEX 2.0
 * counter vocabulary.
 *
 * <p>The public lower-camel-case fields are convenient read-only projections
 * of {@link #weight(BexGasCounter)}.</p>
 */
public final class BexGasSchedule {
    public static final String SCHEDULE_ID = BexGasCounter.SCHEDULE_ID;
    public static final String MANIFEST_IDENTITY =
            BexGasCounter.MANIFEST_IDENTITY;

    public final long expressionEvaluated;
    public final long statementExecuted;
    public final long functionCalled;
    public final long intrinsicCalled;
    public final long documentRead;
    public final long eventRead;
    public final long processingEventRead;
    public final long currentContractRead;
    public final long stepsRead;
    public final long bindingRead;
    public final long variableRead;
    public final long constantRead;
    public final long resultValueRead;
    public final long pointerSegmentRead;
    public final long pointerSegmentWritten;
    public final long objectMemberRead;
    public final long listItemRead;
    public final long collectionItemVisited;
    public final long collectionItemProduced;
    public final long textBlockExamined;
    public final long textBlockConstructed;
    public final long integerLimbOperation;
    public final long comparisonNodeVisited;
    public final long sortComparison;
    public final long patchAppended;
    public final long eventAppended;
    public final long transientObjectMemberProduced;
    public final long transientListItemProduced;
    public final long blueOutputBoundary;
    public final long nodeIdentityRequested;

    private final Map<BexGasCounter, Long> weights;
    private final Map<String, Long> counterWeights;
    private final String scheduleId;
    private final String manifestIdentity;

    private BexGasSchedule(Builder builder) {
        EnumMap<BexGasCounter, Long> copy =
                new EnumMap<>(BexGasCounter.class);
        LinkedHashMap<String, Long> named = new LinkedHashMap<>();
        for (BexGasCounter counter : BexGasCounter.values()) {
            Long weight = builder.weights.get(counter);
            if (weight == null || weight <= 0L) {
                throw new IllegalArgumentException(
                        "Missing or non-positive gas weight for "
                                + counter.canonicalName());
            }
            copy.put(counter, weight);
            named.put(counter.canonicalName(), weight);
        }
        this.weights = Collections.unmodifiableMap(copy);
        this.counterWeights = Collections.unmodifiableMap(named);
        boolean exactManifest = true;
        for (BexGasCounter counter : BexGasCounter.values()) {
            if (copy.get(counter).longValue()
                    != counter.defaultWeight()) {
                exactManifest = false;
                break;
            }
        }
        this.manifestIdentity = exactManifest
                ? MANIFEST_IDENTITY
                : customIdentity(named);
        this.scheduleId = exactManifest
                ? SCHEDULE_ID
                : "blue-bex-gas/custom@" + manifestIdentity;

        expressionEvaluated = weight(BexGasCounter.EXPRESSION_EVALUATED);
        statementExecuted = weight(BexGasCounter.STATEMENT_EXECUTED);
        functionCalled = weight(BexGasCounter.FUNCTION_CALLED);
        intrinsicCalled = weight(BexGasCounter.INTRINSIC_CALLED);
        documentRead = weight(BexGasCounter.DOCUMENT_READ);
        eventRead = weight(BexGasCounter.EVENT_READ);
        processingEventRead = weight(BexGasCounter.PROCESSING_EVENT_READ);
        currentContractRead = weight(BexGasCounter.CURRENT_CONTRACT_READ);
        stepsRead = weight(BexGasCounter.STEPS_READ);
        bindingRead = weight(BexGasCounter.BINDING_READ);
        variableRead = weight(BexGasCounter.VARIABLE_READ);
        constantRead = weight(BexGasCounter.CONSTANT_READ);
        resultValueRead = weight(BexGasCounter.RESULT_VALUE_READ);
        pointerSegmentRead = weight(BexGasCounter.POINTER_SEGMENT_READ);
        pointerSegmentWritten = weight(BexGasCounter.POINTER_SEGMENT_WRITTEN);
        objectMemberRead = weight(BexGasCounter.OBJECT_MEMBER_READ);
        listItemRead = weight(BexGasCounter.LIST_ITEM_READ);
        collectionItemVisited = weight(BexGasCounter.COLLECTION_ITEM_VISITED);
        collectionItemProduced = weight(BexGasCounter.COLLECTION_ITEM_PRODUCED);
        textBlockExamined = weight(BexGasCounter.TEXT_BLOCK_EXAMINED);
        textBlockConstructed = weight(BexGasCounter.TEXT_BLOCK_CONSTRUCTED);
        integerLimbOperation = weight(BexGasCounter.INTEGER_LIMB_OPERATION);
        comparisonNodeVisited = weight(BexGasCounter.COMPARISON_NODE_VISITED);
        sortComparison = weight(BexGasCounter.SORT_COMPARISON);
        patchAppended = weight(BexGasCounter.PATCH_APPENDED);
        eventAppended = weight(BexGasCounter.EVENT_APPENDED);
        transientObjectMemberProduced =
                weight(BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
        transientListItemProduced =
                weight(BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
        blueOutputBoundary = weight(BexGasCounter.BLUE_OUTPUT_BOUNDARY);
        nodeIdentityRequested = weight(BexGasCounter.NODE_IDENTITY_REQUESTED);

    }

    public static BexGasSchedule defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public long weight(BexGasCounter counter) {
        Long weight = weights.get(Objects.requireNonNull(counter, "counter"));
        if (weight == null) {
            throw new IllegalArgumentException("Unknown BEX gas counter: " + counter);
        }
        return weight;
    }

    public long weight(String counterName) {
        return weight(BexGasCounter.fromCanonicalName(counterName));
    }

    public Map<BexGasCounter, Long> weights() {
        return weights;
    }

    /**
     * Returns the exact manifest-ordered name-to-weight map expected by the
     * shared host's runtime child-ledger factory.
     */
    public Map<String, Long> counterWeights() {
        return counterWeights;
    }

    /** Alias for {@link #counterWeights()}. */
    public Map<String, Long> namedWeights() {
        return counterWeights;
    }

    public String scheduleId() {
        return scheduleId;
    }

    public String manifestIdentity() {
        return manifestIdentity;
    }

    private static String customIdentity(Map<String, Long> weights) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, Long> entry : weights.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(String.valueOf(entry.getValue())
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit(
                        (value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", ex);
        }
    }

    public static final class Builder {
        private final EnumMap<BexGasCounter, Long> weights =
                new EnumMap<>(BexGasCounter.class);

        private Builder() {
            for (BexGasCounter counter : BexGasCounter.values()) {
                weights.put(counter, counter.defaultWeight());
            }
        }

        private Builder(BexGasSchedule schedule) {
            weights.putAll(schedule.weights);
        }

        public Builder weight(BexGasCounter counter, long value) {
            if (value <= 0L) {
                throw new IllegalArgumentException(
                        "Portable gas weight must be positive");
            }
            weights.put(Objects.requireNonNull(counter, "counter"), value);
            return this;
        }

        public Builder weight(String counterName, long value) {
            return weight(BexGasCounter.fromCanonicalName(counterName), value);
        }

        public Builder expressionEvaluated(long value) {
            return weight(BexGasCounter.EXPRESSION_EVALUATED, value);
        }

        public Builder statementExecuted(long value) {
            return weight(BexGasCounter.STATEMENT_EXECUTED, value);
        }

        public Builder functionCalled(long value) {
            return weight(BexGasCounter.FUNCTION_CALLED, value);
        }

        public Builder intrinsicCalled(long value) {
            return weight(BexGasCounter.INTRINSIC_CALLED, value);
        }

        public Builder documentRead(long value) {
            return weight(BexGasCounter.DOCUMENT_READ, value);
        }

        public Builder eventRead(long value) {
            return weight(BexGasCounter.EVENT_READ, value);
        }

        public Builder processingEventRead(long value) {
            return weight(BexGasCounter.PROCESSING_EVENT_READ, value);
        }

        public Builder currentContractRead(long value) {
            return weight(BexGasCounter.CURRENT_CONTRACT_READ, value);
        }

        public Builder stepsRead(long value) {
            return weight(BexGasCounter.STEPS_READ, value);
        }

        public Builder bindingRead(long value) {
            return weight(BexGasCounter.BINDING_READ, value);
        }

        public Builder variableRead(long value) {
            return weight(BexGasCounter.VARIABLE_READ, value);
        }

        public Builder constantRead(long value) {
            return weight(BexGasCounter.CONSTANT_READ, value);
        }

        public Builder resultValueRead(long value) {
            return weight(BexGasCounter.RESULT_VALUE_READ, value);
        }

        public Builder pointerSegmentRead(long value) {
            return weight(BexGasCounter.POINTER_SEGMENT_READ, value);
        }

        public Builder pointerSegmentWritten(long value) {
            return weight(BexGasCounter.POINTER_SEGMENT_WRITTEN, value);
        }

        public Builder objectMemberRead(long value) {
            return weight(BexGasCounter.OBJECT_MEMBER_READ, value);
        }

        public Builder listItemRead(long value) {
            return weight(BexGasCounter.LIST_ITEM_READ, value);
        }

        public Builder collectionItemVisited(long value) {
            return weight(BexGasCounter.COLLECTION_ITEM_VISITED, value);
        }

        public Builder collectionItemProduced(long value) {
            return weight(BexGasCounter.COLLECTION_ITEM_PRODUCED, value);
        }

        public Builder textBlockExamined(long value) {
            return weight(BexGasCounter.TEXT_BLOCK_EXAMINED, value);
        }

        public Builder textBlockConstructed(long value) {
            return weight(BexGasCounter.TEXT_BLOCK_CONSTRUCTED, value);
        }

        public Builder integerLimbOperation(long value) {
            return weight(BexGasCounter.INTEGER_LIMB_OPERATION, value);
        }

        public Builder comparisonNodeVisited(long value) {
            return weight(BexGasCounter.COMPARISON_NODE_VISITED, value);
        }

        public Builder sortComparison(long value) {
            return weight(BexGasCounter.SORT_COMPARISON, value);
        }

        public Builder patchAppended(long value) {
            return weight(BexGasCounter.PATCH_APPENDED, value);
        }

        public Builder eventAppended(long value) {
            return weight(BexGasCounter.EVENT_APPENDED, value);
        }

        public Builder transientObjectMemberProduced(long value) {
            return weight(BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED, value);
        }

        public Builder transientListItemProduced(long value) {
            return weight(BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED, value);
        }

        public Builder blueOutputBoundary(long value) {
            return weight(BexGasCounter.BLUE_OUTPUT_BOUNDARY, value);
        }

        public Builder nodeIdentityRequested(long value) {
            return weight(BexGasCounter.NODE_IDENTITY_REQUESTED, value);
        }

        public BexGasSchedule build() {
            return new BexGasSchedule(this);
        }
    }
}
