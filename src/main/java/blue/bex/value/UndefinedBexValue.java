package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class UndefinedBexValue extends AbstractBexValue {
    @Override
    public boolean isUndefined() { return true; }

    @Override
    public String asText() { return ""; }

    @Override
    public BigInteger asInteger() { throw new BexException("Undefined cannot be converted to integer"); }

    @Override
    public BigDecimal asNumber() { throw new BexException("Undefined cannot be converted to number"); }

    @Override
    public boolean asBoolean() { return false; }

    @Override
    public Node toNode() { throw new BexException("Undefined cannot be emitted as a Blue value"); }

    @Override
    public Object toSimple() { return null; }

    @Override
    public String toString() { return "undefined"; }
}
