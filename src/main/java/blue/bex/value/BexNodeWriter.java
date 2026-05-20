package blue.bex.value;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Boundary writer from BEX values to mutable Blue nodes.
 */
public final class BexNodeWriter {
    private BexNodeWriter() {
    }

    public static Node toNode(BexValue value) {
        if (value.isUndefined()) {
            throw new blue.bex.BexException("Undefined cannot be emitted as a Blue value");
        }
        if (value.isNull()) {
            return new Node();
        }
        if (value instanceof NodeBexValue || value instanceof FrozenNodeBexValue) {
            return value.toNode();
        }
        if (value.isScalar()) {
            return value.toNode();
        }
        if (value.isList()) {
            ArrayList<Node> items = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                items.add(toNode(value.get(String.valueOf(i))));
            }
            return new Node().items(items);
        }
        LinkedHashMap<String, Node> properties = new LinkedHashMap<>();
        for (String key : value.keys()) {
            BexValue child = value.get(key);
            if (!child.isUndefined()) {
                properties.put(key, toNode(child));
            }
        }
        return new Node().properties(properties);
    }
}
