package blue.bex.value;

import blue.bex.result.BexMetrics;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boundary writer to immutable Blue nodes.
 */
public final class BexFrozenWriter {
    private final BexFrozenNodeFactory factory;
    private final BexMetrics metrics;

    public BexFrozenWriter(BexFrozenNodeFactory factory, BexMetrics metrics) {
        this.factory = factory != null ? factory : NodeRoundTripFrozenNodeFactory.INSTANCE;
        this.metrics = metrics;
    }

    public static FrozenNode toFrozen(BexValue value) {
        return toFrozen(value, null);
    }

    public static FrozenNode toFrozen(BexValue value, BexMetrics metrics) {
        return new BexFrozenWriter(NodeRoundTripFrozenNodeFactory.INSTANCE, metrics).toFrozenValue(value);
    }

    public FrozenNode toFrozenValue(BexValue value) {
        if (metrics != null) {
            metrics.incrementFrozenOutputConversions();
        }
        return toFrozenInternal(value);
    }

    private FrozenNode toFrozenInternal(BexValue value) {
        if (value.isUndefined()) {
            throw new blue.bex.BexException("Undefined cannot be emitted as a Blue value");
        }
        if (value instanceof FrozenNodeBexValue) {
            return ((FrozenNodeBexValue) value).node();
        }
        if (value.isNull()) {
            return factory.empty(metrics);
        }
        if (value.isScalar()) {
            return factory.scalar(BexValues.rawScalar(value), metrics);
        }
        if (value.isList()) {
            List<FrozenNode> items = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                items.add(toFrozenInternal(value.get(String.valueOf(i))));
            }
            return factory.list(items, metrics);
        }
        if (value.isObject()) {
            Map<String, FrozenNode> properties = new LinkedHashMap<>();
            for (String key : value.keys()) {
                BexValue child = value.get(key);
                if (!child.isUndefined()) {
                    properties.put(key, toFrozenInternal(child));
                }
            }
            return factory.object(properties, metrics);
        }
        if (metrics != null) {
            metrics.incrementFrozenWriterNodeFallbacks();
        }
        return FrozenNode.fromResolvedNode(BexNodeWriter.toNode(value));
    }
}
