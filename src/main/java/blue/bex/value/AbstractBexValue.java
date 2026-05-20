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

abstract class AbstractBexValue implements BexValue {
    @Override
    public boolean isUndefined() { return false; }

    @Override
    public boolean isNull() { return false; }

    @Override
    public boolean isScalar() { return false; }

    @Override
    public boolean isObject() { return false; }

    @Override
    public boolean isList() { return false; }

    @Override
    public BexValue get(String key) { return BexValues.UNDEFINED; }

    @Override
    public BexValue at(List<String> pointerSegments) { return BexValues.atSegments(this, pointerSegments); }

    @Override
    public BexValue at(String pointer) { return at(JsonPointer.split(pointer)); }

    @Override
    public String asText() {
        if (isUndefined() || isNull()) {
            return "";
        }
        if (!isScalar()) {
            throw new BexException("Value cannot be converted to text");
        }
        Object simple = toSimple();
        return String.valueOf(simple);
    }

    @Override
    public BigInteger asInteger() {
        if (!isScalar()) {
            throw new BexException("Value cannot be converted to integer");
        }
        Object simple = toSimple();
        if (simple instanceof BigInteger) {
            return (BigInteger) simple;
        }
        if (simple instanceof BigDecimal) {
            try {
                return ((BigDecimal) simple).toBigIntegerExact();
            } catch (ArithmeticException ex) {
                throw new BexException("Value cannot be converted to integer");
            }
        }
        if (simple instanceof String && ((String) simple).matches("-?\\d+")) {
            return new BigInteger((String) simple);
        }
        throw new BexException("Value cannot be converted to integer");
    }

    @Override
    public BigDecimal asNumber() {
        Object simple = toSimple();
        if (simple instanceof BigDecimal) {
            return (BigDecimal) simple;
        }
        if (simple instanceof BigInteger) {
            return new BigDecimal((BigInteger) simple);
        }
        if (simple instanceof String) {
            try {
                return new BigDecimal((String) simple);
            } catch (NumberFormatException ex) {
                throw new BexException("Value cannot be converted to number");
            }
        }
        throw new BexException("Value cannot be converted to number");
    }

    @Override
    public boolean asBoolean() {
        if (isUndefined() || isNull()) {
            return false;
        }
        if (!isScalar()) {
            return BexValues.truthy(this);
        }
        Object simple = toSimple();
        if (simple instanceof Boolean) {
            return (Boolean) simple;
        }
        if ("true".equals(simple)) {
            return true;
        }
        if ("false".equals(simple)) {
            return false;
        }
        return BexValues.truthy(this);
    }

    @Override
    public List<String> keys() { return Collections.emptyList(); }

    @Override
    public int size() { return 0; }
}
