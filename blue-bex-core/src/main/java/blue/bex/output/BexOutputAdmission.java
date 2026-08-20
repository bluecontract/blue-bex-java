package blue.bex.output;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasMeter;
import blue.bex.value.BexBlueNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.value.BexFrozenWriter;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.BlueIds;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Atomic exact/transient Blue output boundary.
 */
public final class BexOutputAdmission {
    private final BexGasMeter gas;
    private final BexSemanticIdentityBoundary semanticIdentity;
    private final BexFailurePolicy failureBoundary;
    private final Map<BexValue, BexAdmittedValue> admittedTransientValues =
            new IdentityHashMap<>();
    private long semanticIdentityMergeCount;

    public BexOutputAdmission(BexGasMeter gas,
                              BexSemanticIdentityBoundary semanticIdentity) {
        this(gas, semanticIdentity, BexFailurePolicy.STANDALONE);
    }

    public BexOutputAdmission(
            BexGasMeter gas,
            BexSemanticIdentityBoundary semanticIdentity,
            BexFailurePolicy failureBoundary) {
        this.gas = Objects.requireNonNull(gas, "gas");
        this.semanticIdentity = semanticIdentity != null
                ? semanticIdentity
                : BexSemanticIdentityBoundary.STANDALONE;
        this.failureBoundary = failureBoundary != null
                ? failureBoundary
                : BexFailurePolicy.STANDALONE;
    }

    public BexAdmittedValue admit(BexValue value, BexOutputKind kind) {
        Objects.requireNonNull(kind, "kind");
        /*
         * Admission precedes validation/materialization. If conversion fails,
         * the admitted trace prefix remains canonical.
         */
        gas.charge(BexGasCounter.BLUE_OUTPUT_BOUNDARY, 1L, kind.reason());
        if (value == null || value.isUndefined()) {
            throw new BexException("Blue output conversion failed: root value is undefined");
        }

        if (value.isExact()) {
            String exactId = BlueIds.requireBlueIdOrCyclicMember(
                    value.exactBlueId(), "BEX exact output blueId");
            BexEstablishedIdentity carried = Objects.requireNonNull(
                    semanticIdentity.carryExactIdentity(
                            exactId,
                            BexFrozenWriter.toFrozen(value)),
                    "carried exact identity");
            if (!exactId.equals(carried.blueId())) {
                throw new BexException(
                        "Exact BEX output identity changed at the host boundary");
            }
            return new BexAdmittedValue(
                    value,
                    value,
                    new Node().blueId(exactId),
                    exactId,
                    false,
                    carried.exactCapability());
        }

        BexAdmittedValue prior = admittedTransientValues.get(value);
        if (prior != null) {
            return prior;
        }

        Node node = BexBlueNodeWriter.toNode(value);

        final BexEstablishedIdentity established;
        try {
            established = Objects.requireNonNull(
                    semanticIdentity.establishIdentity(node.clone()),
                    "semantic identity result");
        } catch (BexException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failureBoundary.preserveOrWrap(
                    "Blue output identity establishment failed",
                    ex);
        }
        String blueId = BlueIds.requireBlueIdOrCyclicMember(
                established.blueId(),
                "BEX admitted output blueId");
        if (blueId.indexOf('#') >= 0) {
            throw new BexException(
                    "Transient BEX output cannot establish a cyclic-set "
                            + "member identity: " + blueId);
        }
        semanticIdentityMergeCount++;

        /*
         * The host already established both the identity and the exact
         * semantic value. Retain that result without asking BEX to reconstruct
         * or hash the complete value again, alongside a cheap pure-reference
         * canonical lane.
         */
        EstablishedTransientIdentity identity =
                new EstablishedTransientIdentity(
                        established.frozenValue(),
                        blueId,
                        established.exactCapability());
        BexAdmittedValue admitted =
                identity.admit(value);
        admittedTransientValues.put(value, admitted);
        return admitted;
    }

    private static final class EstablishedTransientIdentity {
        private final FrozenNode frozenValue;
        private final String blueId;
        private final BexExactValueCapability exactCapability;

        private EstablishedTransientIdentity(
                FrozenNode frozenValue,
                String blueId,
                BexExactValueCapability exactCapability) {
            this.frozenValue = Objects.requireNonNull(
                    frozenValue, "frozenValue");
            this.blueId = Objects.requireNonNull(
                    blueId, "blueId");
            this.exactCapability = exactCapability;
        }

        private BexAdmittedValue admit(
                BexValue suppliedValue) {
            BexValue exact = BexValues.admittedExact(
                    frozenValue,
                    blueId,
                    suppliedValue);
            return new BexAdmittedValue(
                    exact,
                    exact,
                    frozenValue.toNode(),
                    blueId,
                    true,
                    exactCapability);
        }
    }

    public long semanticIdentityMergeCount() {
        return semanticIdentityMergeCount;
    }
}
