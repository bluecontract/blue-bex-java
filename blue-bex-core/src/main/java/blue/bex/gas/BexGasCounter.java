package blue.bex.gas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The closed Blue BEX 2.0 portable gas-counter vocabulary.
 *
 * <p>Declaration order is manifest order. Portable weights and identities are
 * loaded from the exact bound manifest rather than duplicated here.</p>
 */
public enum BexGasCounter {
    EXPRESSION_EVALUATED("expressionEvaluated"),
    STATEMENT_EXECUTED("statementExecuted"),
    FUNCTION_CALLED("functionCalled"),
    INTRINSIC_CALLED("intrinsicCalled"),
    DOCUMENT_READ("documentRead"),
    EVENT_READ("eventRead"),
    PROCESSING_EVENT_READ("processingEventRead"),
    CURRENT_CONTRACT_READ("currentContractRead"),
    STEPS_READ("stepsRead"),
    BINDING_READ("bindingRead"),
    VARIABLE_READ("variableRead"),
    CONSTANT_READ("constantRead"),
    RESULT_VALUE_READ("resultValueRead"),
    POINTER_SEGMENT_READ("pointerSegmentRead"),
    POINTER_SEGMENT_WRITTEN("pointerSegmentWritten"),
    OBJECT_MEMBER_READ("objectMemberRead"),
    LIST_ITEM_READ("listItemRead"),
    COLLECTION_ITEM_VISITED("collectionItemVisited"),
    COLLECTION_ITEM_PRODUCED("collectionItemProduced"),
    TEXT_BLOCK_EXAMINED("textBlockExamined"),
    TEXT_BLOCK_CONSTRUCTED("textBlockConstructed"),
    INTEGER_LIMB_OPERATION("integerLimbOperation"),
    COMPARISON_NODE_VISITED("comparisonNodeVisited"),
    SORT_COMPARISON("sortComparison"),
    PATCH_APPENDED("patchAppended"),
    EVENT_APPENDED("eventAppended"),
    TRANSIENT_OBJECT_MEMBER_PRODUCED("transientObjectMemberProduced"),
    TRANSIENT_LIST_ITEM_PRODUCED("transientListItemProduced"),
    BLUE_OUTPUT_BOUNDARY("blueOutputBoundary"),
    NODE_IDENTITY_REQUESTED("nodeIdentityRequested");

    private static final BexGasManifest DEFAULT_MANIFEST =
            BexGasManifest.loadDefault();

    /** Namespace used when a BEX ledger is attached to the shared host meter. */
    public static final String NAMESPACE = "bex";

    /** Canonical schedule identifier from the BEX 2.0 gas manifest. */
    public static final String SCHEDULE_ID =
            DEFAULT_MANIFEST.scheduleId();

    /** Exact implementation-baseline BEX 2.0 gas-manifest package identity. */
    public static final String MANIFEST_IDENTITY =
            DEFAULT_MANIFEST.packageIdentity();

    private static final Map<String, BexGasCounter> BY_CANONICAL_NAME;
    private static final Map<String, Long> DEFAULT_WEIGHTS;

    static {
        LinkedHashMap<String, BexGasCounter> byName = new LinkedHashMap<>();
        LinkedHashMap<String, Long> weights = new LinkedHashMap<>();
        for (BexGasCounter counter : values()) {
            byName.put(counter.canonicalName, counter);
            Long weight = DEFAULT_MANIFEST.counterWeights()
                    .get(counter.canonicalName);
            if (weight == null) {
                throw new ExceptionInInitializerError(
                        "BEX gas manifest is missing "
                                + counter.canonicalName);
            }
            weights.put(counter.canonicalName, weight);
        }
        if (weights.size()
                != DEFAULT_MANIFEST.counterWeights().size()) {
            throw new ExceptionInInitializerError(
                    "BEX gas manifest contains unknown counters");
        }
        BY_CANONICAL_NAME = Collections.unmodifiableMap(byName);
        DEFAULT_WEIGHTS = Collections.unmodifiableMap(weights);
    }

    private final String canonicalName;

    BexGasCounter(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    /**
     * Returns the exact lower-camel-case counter name used in fixtures and
     * host-ledger entries.
     */
    public String canonicalName() {
        return canonicalName;
    }

    /** Alias for {@link #canonicalName()}. */
    public String counterName() {
        return canonicalName;
    }

    public long defaultWeight() {
        return DEFAULT_WEIGHTS.get(canonicalName);
    }

    public static BexGasCounter fromCanonicalName(String name) {
        BexGasCounter counter = BY_CANONICAL_NAME.get(name);
        if (counter == null) {
            throw new IllegalArgumentException("Unknown BEX gas counter: " + name);
        }
        return counter;
    }

    /** Alias for {@link #fromCanonicalName(String)}. */
    public static BexGasCounter fromName(String name) {
        return fromCanonicalName(name);
    }

    /**
     * Returns an immutable, manifest-ordered map suitable for creating a
     * parent-bounded runtime child ledger.
     */
    public static Map<String, Long> defaultWeights() {
        return DEFAULT_WEIGHTS;
    }

    @Override
    public String toString() {
        return canonicalName;
    }
}
