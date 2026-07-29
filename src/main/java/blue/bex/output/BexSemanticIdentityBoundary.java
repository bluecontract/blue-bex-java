package blue.bex.output;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;

/**
 * Host-owned Blue semantic identity establishment.
 *
 * <p>BEX invokes this exactly once for each admitted transient value. Hosted
 * runtimes can replace the default with their Contracts semantic ledger
 * boundary; standalone execution uses the canonical Language calculator.</p>
 */
@FunctionalInterface
public interface BexSemanticIdentityBoundary {
    BexSemanticIdentityBoundary STANDALONE = node -> {
        FrozenNode frozen = FrozenNode.fromResolvedNode(
                node.clone());
        return new BexEstablishedIdentity(
                BlueIdCalculator.calculateBlueId(
                        frozen.toNode()),
                frozen);
    };

    BexEstablishedIdentity establishIdentity(Node node);
}
