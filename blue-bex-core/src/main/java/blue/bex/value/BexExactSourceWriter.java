package blue.bex.value;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;

import java.util.Map;

/** Retains canonical payloads and resolver-issued inline type Source. */
final class BexExactSourceWriter {
    private BexExactSourceWriter() {
    }

    static Node toSource(BexValue value) {
        Node canonical = BexFrozenWriter.toFrozen(value).toNode();
        CanonicalTypeIdentityLookup identities = value.canonicalTypeIdentities();
        if (identities != null && !canonical.isReferenceOnly()) {
            restoreInlineTypes(canonical, value.toNode(), identities);
        }
        return canonical;
    }

    private static void restoreInlineTypes(
            Node canonical, Node resolved, CanonicalTypeIdentityLookup identities) {
        if (canonical == null || resolved == null || canonical.isReferenceOnly()) {
            return;
        }
        canonical.type(sourceType(canonical.getType(), resolved.getType(), identities));
        canonical.itemType(sourceType(canonical.getItemType(), resolved.getItemType(), identities));
        canonical.keyType(sourceType(canonical.getKeyType(), resolved.getKeyType(), identities));
        canonical.valueType(sourceType(canonical.getValueType(), resolved.getValueType(), identities));
        restoreInlineTypes(canonical.getContracts(), resolved.getContracts(), identities);
        if (canonical.getProperties() != null && resolved.getProperties() != null) {
            for (Map.Entry<String, Node> entry : canonical.getProperties().entrySet()) {
                restoreInlineTypes(entry.getValue(), resolved.getProperties().get(entry.getKey()), identities);
            }
        }
        if (canonical.getItems() != null && resolved.getItems() != null) {
            int count = Math.min(canonical.getItems().size(), resolved.getItems().size());
            for (int i = 0; i < count; i++) {
                restoreInlineTypes(canonical.getItems().get(i), resolved.getItems().get(i), identities);
            }
        }
    }

    private static Node sourceType(
            Node canonical, Node resolved, CanonicalTypeIdentityLookup identities) {
        if (canonical == null || !canonical.isReferenceOnly()
                || resolved == null || resolved.isReferenceOnly()) {
            return canonical;
        }
        CanonicalTypeIdentityEvidence evidence = identities
                .findCanonicalTypeIdentityEvidence(resolved).orElse(null);
        if (evidence == null) {
            return canonical;
        }
        if (!canonical.getBlueId().equals(evidence.blueId())) {
            throw new IllegalStateException("Exact output type Source conflicts with canonical identity");
        }
        Node source = evidence.authoredTypeSource();
        return source != null ? source : canonical;
    }
}
