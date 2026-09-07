package blue.bex.output;

import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.runtime.LanguageRuntimeAccess;

import java.util.Objects;

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
                DirectBlueIdCalculator.calculateBlueId(
                        frozen.toNode()),
                frozen);
    };

    /**
     * Creates the standalone boundary for one configured Language runtime.
     *
     * <p>Runtime-produced BEX output is authored Blue content, so Language
     * must first resolve inline type declarations and validate payload kinds
     * before direct identity calculation.  Keeping this factory here gives
     * standalone execution the same semantic admission rule as the hosted
     * Contracts boundary without giving BEX a second identity algorithm.</p>
     *
     * @param languageRuntime configured Language semantic runtime
     * @return runtime-bound standalone identity boundary
     */
    static BexSemanticIdentityBoundary standalone(
            LanguageRuntimeAccess languageRuntime) {
        LanguageRuntimeAccess runtime = Objects.requireNonNull(
                languageRuntime, "languageRuntime");
        return node -> {
            ResolvedSnapshot snapshot = runtime.canonicalizeWithEvidence(
                    Objects.requireNonNull(node, "node").clone());
            return new BexEstablishedIdentity(
                    snapshot.blueId(), snapshot.frozenCanonicalRoot(),
                    snapshot.frozenResolvedRoot(), null, snapshot.canonicalTypeIdentities());
        };
    }

    BexEstablishedIdentity establishIdentity(Node node);

    /**
     * Carries an existing exact value through the host boundary.
     *
     * <p>The standalone boundary retains only the supplied identity and
     * representation. Hosted boundaries may override this method to attach an
     * invocation-owned exact capability.</p>
     *
     * @param blueId the already established exact Blue identity
     * @param frozenValue the verified frozen value carrying that identity
     * @return the established identity and any host-owned exact capability
     */
    default BexEstablishedIdentity carryExactIdentity(
            String blueId,
            FrozenNode frozenValue) {
        return new BexEstablishedIdentity(blueId, frozenValue);
    }

}
