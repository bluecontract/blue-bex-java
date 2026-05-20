package blue.bex.value;

import blue.bex.result.BexMetrics;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Map;

/**
 * Factory used by {@link BexFrozenWriter} when BEX values cross a FrozenNode boundary.
 */
public interface BexFrozenNodeFactory {
    FrozenNode empty(BexMetrics metrics);

    FrozenNode scalar(Object value, BexMetrics metrics);

    FrozenNode list(List<FrozenNode> items, BexMetrics metrics);

    FrozenNode object(Map<String, FrozenNode> properties, BexMetrics metrics);
}
