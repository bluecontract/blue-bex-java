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

final class NullBexValue extends AbstractBexValue {
    @Override
    public boolean isNull() { return true; }

    @Override
    public Node toNode() { return new Node(); }

    @Override
    public Object toSimple() { return null; }
}
