package blue.bex.value;

import blue.bex.result.BexMetrics;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FrozenNode factory backed by the public Node-to-FrozenNode APIs in blue-language-java.
 */
public final class NodeRoundTripFrozenNodeFactory implements BexFrozenNodeFactory {
    public static final NodeRoundTripFrozenNodeFactory INSTANCE = new NodeRoundTripFrozenNodeFactory();

    private NodeRoundTripFrozenNodeFactory() {
    }

    @Override
    public FrozenNode empty(BexMetrics metrics) {
        return FrozenNode.empty();
    }

    @Override
    public FrozenNode scalar(Object value, BexMetrics metrics) {
        return FrozenNode.fromResolvedNode(new Node().value(value));
    }

    @Override
    public FrozenNode list(List<FrozenNode> items, BexMetrics metrics) {
        ArrayList<Node> nodes = new ArrayList<>(items.size());
        for (FrozenNode item : items) {
            nodes.add(toNodeRoundTrip(item, metrics));
        }
        return FrozenNode.fromResolvedNode(new Node().items(nodes));
    }

    @Override
    public FrozenNode object(Map<String, FrozenNode> properties, BexMetrics metrics) {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
            nodes.put(entry.getKey(), toNodeRoundTrip(entry.getValue(), metrics));
        }
        return FrozenNode.fromResolvedNode(new Node().properties(nodes));
    }

    private Node toNodeRoundTrip(FrozenNode value, BexMetrics metrics) {
        if (metrics != null) {
            metrics.incrementFrozenWriterChildNodeRoundTrips();
        }
        return value.toNode();
    }
}
