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
    List<String> keys();
    int size();
    Node toNode();
    Object toSimple();
}
