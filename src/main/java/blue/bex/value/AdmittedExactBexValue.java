package blue.bex.value;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Exact identity established by an output boundary.
 *
 * <p>The host-established frozen value is authoritative for all semantic
 * reads. The supplied run-local value is consulted only at the corresponding
 * child path, and only an already-exact child with the same established
 * identity is reused directly. This retains locally resolved exact
 * descendants without allowing a transient {@code blueId}-shaped object or
 * stale pre-normalization content to override the host result.</p>
 */
final class AdmittedExactBexValue implements BexValue {
    private final BexValue establishedValue;
    private final BexValue suppliedValue;

    AdmittedExactBexValue(FrozenNode frozenValue,
                          String blueId,
                          BexValue suppliedValue) {
        this(
                BexValues.exact(
                        Objects.requireNonNull(
                                frozenValue, "frozenValue"),
                        frozenValue,
                        Objects.requireNonNull(
                                blueId, "blueId")),
                suppliedValue);
    }

    private AdmittedExactBexValue(BexValue establishedValue,
                                  BexValue suppliedValue) {
        this.establishedValue = Objects.requireNonNull(
                establishedValue, "establishedValue");
        if (!establishedValue.isExact()) {
            throw new IllegalArgumentException(
                    "Admitted semantic value must be exact");
        }
        this.suppliedValue = suppliedValue != null
                ? suppliedValue
                : BexValues.UNDEFINED;
    }

    @Override
    public boolean isExact() {
        return true;
    }

    @Override
    public String exactBlueId() {
        return establishedValue.exactBlueId();
    }

    @Override
    public boolean isUndefined() {
        return establishedValue.isUndefined();
    }

    @Override
    public boolean isNull() {
        return establishedValue.isNull()
                && !usesEmptyObjectShapeWitness();
    }

    @Override
    public boolean isScalar() {
        return establishedValue.isScalar();
    }

    @Override
    public boolean isObject() {
        return establishedValue.isObject()
                || usesEmptyObjectShapeWitness();
    }

    @Override
    public boolean isList() {
        return establishedValue.isList();
    }

    @Override
    public BexValue get(String key) {
        BexValue establishedChild =
                establishedValue.get(key);
        if (establishedChild == null
                || establishedChild.isUndefined()) {
            return BexValues.UNDEFINED;
        }

        BexValue suppliedChild =
                suppliedValue.get(key);
        if (suppliedChild == null
                || suppliedChild.isUndefined()) {
            return establishedChild;
        }
        if (suppliedChild.isExact()) {
            if (establishedChild.isExact()
                    && establishedChild.exactBlueId().equals(
                    suppliedChild.exactBlueId())) {
                return suppliedChild;
            }
            /*
             * A mismatched exact source is not a valid cursor for any
             * descendant of the established host value.
             */
            return establishedChild;
        }
        if (establishedChild.isExact()) {
            return new AdmittedExactBexValue(
                    establishedChild,
                    suppliedChild);
        }
        return establishedChild;
    }

    @Override
    public BexValue at(List<String> pointerSegments) {
        return BexValues.atSegments(this, pointerSegments);
    }

    @Override
    public BexValue at(String pointer) {
        return at(JsonPointer.split(pointer));
    }

    @Override
    public String asText() {
        return establishedValue.asText();
    }

    @Override
    public BigInteger asInteger() {
        return establishedValue.asInteger();
    }

    @Override
    public BigDecimal asNumber() {
        return establishedValue.asNumber();
    }

    @Override
    public boolean asBoolean() {
        return establishedValue.asBoolean();
    }

    @Override
    public List<String> keys() {
        return establishedValue.keys();
    }

    @Override
    public int size() {
        return establishedValue.size();
    }

    @Override
    public Node toNode() {
        return establishedValue.toNode();
    }

    Object rawScalar() {
        return BexValues.rawScalar(establishedValue);
    }

    /*
     * Blue's resolved frozen representation retains an empty object member as
     * an empty node, which is also the representation of BEX null. The exact
     * host value remains authoritative for every modeled field and for
     * identity; the supplied value is used only as the otherwise-lost shape
     * witness when it is precisely an empty object.
     */
    private boolean usesEmptyObjectShapeWitness() {
        return establishedValue.isNull()
                && suppliedValue.isObject()
                && suppliedValue.size() == 0;
    }

    @Override
    public Object toSimple() {
        return isScalar()
                ? rawScalar()
                : BexSimpleWriter.toSimple(this);
    }
}
