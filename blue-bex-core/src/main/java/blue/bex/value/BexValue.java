package blue.bex.value;

import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Cursor-oriented BEX value.
 *
 * <p>Implementations may wrap immutable frozen nodes, trusted immutable mutable
 * nodes, computed maps/lists, overlays, or result-backed values. Converting to a
 * {@link Node} or simple Java object is an explicit boundary operation.</p>
 */
public interface BexValue {
    /** Returns the closed semantic kind, independent of exactness. */
    default BexValueKind semanticKind() {
        return BexValues.semanticKind(this);
    }

    /**
     * Whether this value is an already established Blue node.
     *
     * <p>Exactness is provenance, not shape. In particular, a transient object
     * containing a {@code blueId} member is not exact.</p>
     */
    default boolean isExact() {
        return false;
    }

    /**
     * Returns the retained Node BlueId of an exact value.
     *
     * @throws IllegalStateException when this value is transient
     */
    default String exactBlueId() {
        throw new IllegalStateException("Transient BEX values do not have a Node BlueId");
    }

    /** Optional type evidence retained by the boundary that established this exact value. */
    default blue.language.identity.CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return null;
    }

    boolean isUndefined();
    boolean isNull();
    boolean isScalar();
    boolean isObject();
    boolean isList();
    BexValue get(String key);
    BexValue at(List<String> pointerSegments);
    BexValue at(String pointer);
    String asText();
    BigInteger asInteger();
    BigDecimal asNumber();
    boolean asBoolean();
    /**
     * Returns this object's already-established canonical Unicode key cursor.
     *
     * <p>Callers must consume this order directly. Implementations establish
     * and retain it before exposing the value; enumerating the cursor is not a
     * request to sort it again.</p>
     */
    List<String> keys();
    int size();
    Node toNode();
    Object toSimple();
}
