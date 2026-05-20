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

final class ScalarBexValue extends AbstractBexValue {
    private final Object value;

    ScalarBexValue(Object value) {
        this.value = value;
    }

    @Override
    public boolean isScalar() { return true; }

    Object raw() {
        return value;
    }

    @Override
    public String asText() {
        return String.valueOf(value);
    }

    @Override
    public BigInteger asInteger() {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof BigDecimal) {
            try {
                return ((BigDecimal) value).toBigIntegerExact();
            } catch (ArithmeticException ex) {
                throw new BexException("Value cannot be converted to integer");
            }
        }
        if (value instanceof String && ((String) value).matches("-?\\d+")) {
            return new BigInteger((String) value);
        }
        throw new BexException("Value cannot be converted to integer");
    }

    @Override
    public BigDecimal asNumber() {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (NumberFormatException ex) {
                throw new BexException("Value cannot be converted to number");
            }
        }
        throw new BexException("Value cannot be converted to number");
    }

    @Override
    public boolean asBoolean() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        return value instanceof String ? !((String) value).isEmpty() : true;
    }

    @Override
    public Node toNode() { return new Node().value(value); }

    @Override
    public Object toSimple() { return value; }

    @Override
    public String toString() { return String.valueOf(value); }
}
