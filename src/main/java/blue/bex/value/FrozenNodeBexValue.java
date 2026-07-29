package blue.bex.value;

import blue.bex.BexException;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Representation-blind cursor over an exact Blue value.
 *
 * <p>The canonical node retains identity and may be only a reference. The
 * semantic node is the resolved view used for ordinary BEX operations. Keeping
 * those roles separate prevents physical {@code blueId} wrappers from leaking
 * into {@code $keys}, {@code $kind}, pointer reads, and equality.</p>
 */
final class FrozenNodeBexValue extends AbstractBexValue {
    private final FrozenNode canonicalIdentityNode;
    private final FrozenNode initialSemanticNode;
    private final String retainedExactBlueId;
    private final Function<String, ResolvedSnapshot> referenceMaterializer;
    private volatile FrozenNode materializedCanonicalNode;
    private volatile FrozenNode materializedSemanticNode;
    private volatile List<String> canonicalKeys;

    FrozenNodeBexValue(FrozenNode canonicalNode, FrozenNode semanticNode) {
        this(canonicalNode, semanticNode, null, null);
    }

    FrozenNodeBexValue(FrozenNode canonicalNode,
                       FrozenNode semanticNode,
                       String retainedExactBlueId) {
        this(canonicalNode, semanticNode, retainedExactBlueId, null);
    }

    private FrozenNodeBexValue(
            FrozenNode canonicalNode,
            FrozenNode semanticNode,
            String retainedExactBlueId,
            Function<String, ResolvedSnapshot> referenceMaterializer) {
        this.canonicalIdentityNode = canonicalNode;
        this.initialSemanticNode =
                semanticNode != null ? semanticNode : canonicalNode;
        this.retainedExactBlueId = retainedExactBlueId;
        this.referenceMaterializer = referenceMaterializer;
    }

    FrozenNode node() {
        return semanticNode();
    }

    FrozenNode canonicalNode() {
        return canonicalIdentityNode;
    }

    Object rawScalar() {
        FrozenNode semantic = semanticNode();
        return semantic != null ? semantic.getValue() : null;
    }

    FrozenNodeBexValue withReferenceMaterializer(
            Function<String, ResolvedSnapshot> materializer) {
        if (materializer == null) {
            return this;
        }
        return new FrozenNodeBexValue(
                canonicalIdentityNode,
                initialSemanticNode,
                retainedExactBlueId,
                materializer);
    }

    @Override
    public boolean isExact() {
        return true;
    }

    @Override
    public String exactBlueId() {
        if (retainedExactBlueId != null) {
            return retainedExactBlueId;
        }
        if (canonicalIdentityNode == null) {
            throw new IllegalStateException("Exact BEX value has no canonical identity node");
        }
        String reference = canonicalIdentityNode.getReferenceBlueId();
        return reference != null ? reference : canonicalIdentityNode.blueId();
    }

    @Override
    public boolean isNull() {
        FrozenNode semantic = semanticNode();
        return semantic != null && semantic.isEmptyNode();
    }

    @Override
    public boolean isScalar() {
        FrozenNode semantic = semanticNode();
        return semantic != null
                && semantic.getValue() != null
                && semantic.getItems() == null
                && semantic.getProperties() == null;
    }

    @Override
    public boolean isObject() {
        FrozenNode semantic = semanticNode();
        return semantic != null
                && (semantic.getProperties() != null
                || hasObjectCompatibleLanguageFields(semantic));
    }

    @Override
    public boolean isList() {
        FrozenNode semantic = semanticNode();
        return semantic != null && semantic.getItems() != null;
    }

    @Override
    public BexValue get(String key) {
        FrozenNode semantic = semanticNode();
        if (semantic == null) {
            return BexValues.UNDEFINED;
        }
        if (semantic.getProperties() != null
                && semantic.getProperties().containsKey(key)) {
            return exactChild(canonicalProperty(key),
                    semantic.getProperties().get(key));
        }
        if (semantic.getItems() != null) {
            try {
                int index = Integer.parseInt(key);
                if (index < 0 || index >= semantic.getItems().size()) {
                    return BexValues.UNDEFINED;
                }
                return exactChild(canonicalItem(index),
                        semantic.getItems().get(index));
            } catch (NumberFormatException ignored) {
                return BexValues.UNDEFINED;
            }
        }
        if ("name".equals(key)) {
            return scalarOrUndefined(semantic.getName());
        }
        if ("description".equals(key)) {
            return scalarOrUndefined(semantic.getDescription());
        }
        /*
         * Physical Blue identity metadata is not a semantic child. This check
         * intentionally follows the properties lookup: an actual logical
         * property named "blueId" remains visible. A collapsed reference must
         * therefore be materialized before absence can be established.
         */
        if ("blueId".equals(key)) {
            return BexValues.UNDEFINED;
        }
        if ("value".equals(key)) {
            return scalarOrUndefined(semantic.getValue());
        }
        if ("type".equals(key)) {
            FrozenNode canonical = canonicalCursor();
            return exactChild(canonical != null ? canonical.getType() : null,
                    semantic.getType());
        }
        if ("itemType".equals(key)) {
            FrozenNode canonical = canonicalCursor();
            return exactChild(
                    canonical != null ? canonical.getItemType() : null,
                    semantic.getItemType());
        }
        if ("keyType".equals(key)) {
            FrozenNode canonical = canonicalCursor();
            return exactChild(
                    canonical != null ? canonical.getKeyType() : null,
                    semantic.getKeyType());
        }
        if ("valueType".equals(key)) {
            FrozenNode canonical = canonicalCursor();
            return exactChild(
                    canonical != null ? canonical.getValueType() : null,
                    semantic.getValueType());
        }
        if ("blue".equals(key)) {
            FrozenNode canonical = canonicalCursor();
            return exactChild(canonical != null ? canonical.getBlue() : null,
                    semantic.getBlue());
        }
        if ("contracts".equals(key)) {
            FrozenNode canonical = canonicalCursor();
            return exactChild(
                    canonical != null ? canonical.getContracts() : null,
                    semantic.getContracts());
        }
        if ("schema".equals(key)) {
            return BexValues.schemaSnapshot(semantic.getSchema());
        }
        if ("mergePolicy".equals(key)) {
            return scalarOrUndefined(semantic.getMergePolicy());
        }
        return BexValues.UNDEFINED;
    }

    @Override
    public BexValue at(List<String> pointerSegments) {
        /*
         * Deliberately traverse the semantic cursor rather than FrozenNode.at:
         * the latter cannot pair a pure canonical reference with its resolved
         * graph and could expose representation details.
         */
        return BexValues.atSegments(this, pointerSegments);
    }

    @Override
    public String asText() {
        FrozenNode semantic = semanticNode();
        Object value = semantic != null ? semantic.getValue() : null;
        return value != null ? String.valueOf(value) : super.asText();
    }

    @Override
    public BigInteger asInteger() {
        FrozenNode semantic = semanticNode();
        Object value = semantic != null ? semantic.getValue() : null;
        return value != null ? BexValues.scalar(value).asInteger() : super.asInteger();
    }

    @Override
    public BigDecimal asNumber() {
        FrozenNode semantic = semanticNode();
        Object value = semantic != null ? semantic.getValue() : null;
        return value != null ? BexValues.scalar(value).asNumber() : super.asNumber();
    }

    @Override
    public boolean asBoolean() {
        FrozenNode semantic = semanticNode();
        Object value = semantic != null ? semantic.getValue() : null;
        return value != null ? BexValues.scalar(value).asBoolean() : super.asBoolean();
    }

    @Override
    public List<String> keys() {
        List<String> established = canonicalKeys;
        if (established != null) {
            return established;
        }
        FrozenNode semantic = semanticNode();
        if (semantic == null
                || (semantic.getProperties() == null
                && !hasObjectCompatibleLanguageFields(semantic))) {
            canonicalKeys = Collections.emptyList();
            return canonicalKeys;
        }
        synchronized (this) {
            established = canonicalKeys;
            if (established == null) {
                established = establishCanonicalKeys(semantic);
                canonicalKeys = established;
            }
        }
        return established;
    }

    private List<String> establishCanonicalKeys(
            FrozenNode semantic) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (hasObjectCompatibleLanguageFields(semantic)) {
            addLanguageKeys(fields, semantic);
        }
        if (semantic.getProperties() != null) {
            fields.addAll(semantic.getProperties().keySet());
        }
        return Collections.unmodifiableList(
                BexUnicodeOrder.sortedCopy(fields));
    }

    @Override
    public int size() {
        FrozenNode semantic = semanticNode();
        if (semantic == null) {
            return 0;
        }
        if (semantic.getItems() != null) {
            return semantic.getItems().size();
        }
        if (semantic.getProperties() != null) {
            return keys().size();
        }
        return hasObjectCompatibleLanguageFields(semantic)
                ? keys().size()
                : (isScalar() ? 1 : 0);
    }

    @Override
    public Node toNode() {
        FrozenNode semantic = semanticNode();
        return semantic != null
                ? semantic.toNode()
                : new Node().blueId(exactBlueId());
    }

    @Override
    public Object toSimple() {
        FrozenNode semantic = semanticNode();
        if (semantic == null) {
            return null;
        }
        if (semantic.getValue() != null) {
            return semantic.getValue();
        }
        if (semantic.getItems() != null) {
            ArrayList<Object> out = new ArrayList<>();
            for (int i = 0; i < semantic.getItems().size(); i++) {
                out.add(get(String.valueOf(i)).toSimple());
            }
            return out;
        }
        if (semantic.getProperties() != null
                || hasObjectCompatibleLanguageFields(semantic)) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (String key : keys()) {
                BexValue value = get(key);
                if (!value.isUndefined()) {
                    out.put(key, value.toSimple());
                }
            }
            return out;
        }
        return null;
    }

    private BexValue exactChild(FrozenNode canonical, FrozenNode semantic) {
        if (canonical == null && semantic == null) {
            return BexValues.UNDEFINED;
        }
        FrozenNode exactCanonical =
                canonical != null ? canonical : semantic;
        FrozenNode exactSemantic = semantic;
        String canonicalReference =
                exactCanonical != null
                        && exactCanonical.isReferenceOnly()
                        ? exactCanonical.getReferenceBlueId()
                        : null;
        /*
         * A cyclic member has no independently verifiable body.  An inline
         * resolved child supplied beside its canonical MASTER#index reference
         * is therefore not structural evidence: retain the child as an opaque
         * exact reference until the cyclic-aware materializer establishes the
         * complete set proof.  Ordinary exact descendants keep their supplied
         * resolved cursor.
         */
        if (canonicalReference != null
                && canonicalReference.indexOf('#') >= 0) {
            exactSemantic = exactCanonical;
        }
        return new FrozenNodeBexValue(
                exactCanonical,
                exactSemantic,
                null,
                referenceMaterializer);
    }

    private FrozenNode canonicalProperty(String key) {
        FrozenNode canonical = canonicalCursor();
        return canonical != null
                && canonical.getProperties() != null
                ? canonical.getProperties().get(key)
                : null;
    }

    private FrozenNode canonicalItem(int index) {
        FrozenNode canonical = canonicalCursor();
        return canonical != null
                && canonical.getItems() != null
                && index >= 0
                && index < canonical.getItems().size()
                ? canonical.getItems().get(index)
                : null;
    }

    private static BexValue scalarOrUndefined(Object value) {
        return value != null ? BexValues.scalar(value) : BexValues.UNDEFINED;
    }

    private boolean hasObjectCompatibleLanguageFields(FrozenNode semantic) {
        return semantic != null
                && semantic.getValue() == null
                && semantic.getItems() == null
                && (semantic.getName() != null
                || semantic.getDescription() != null
                || semantic.getType() != null
                || semantic.getItemType() != null
                || semantic.getKeyType() != null
                || semantic.getValueType() != null
                || semantic.getBlue() != null
                || semantic.getContracts() != null
                || semantic.getSchema() != null
                || semantic.getMergePolicy() != null);
    }

    private void addLanguageKeys(
            LinkedHashSet<String> keys,
            FrozenNode semantic) {
        if (semantic.getName() != null) keys.add("name");
        if (semantic.getDescription() != null) keys.add("description");
        if (semantic.getType() != null) keys.add("type");
        if (semantic.getItemType() != null) keys.add("itemType");
        if (semantic.getKeyType() != null) keys.add("keyType");
        if (semantic.getValueType() != null) keys.add("valueType");
        if (semantic.getBlue() != null) keys.add("blue");
        if (semantic.getContracts() != null) keys.add("contracts");
        if (semantic.getSchema() != null) keys.add("schema");
        if (semantic.getMergePolicy() != null) keys.add("mergePolicy");
    }

    private FrozenNode canonicalCursor() {
        FrozenNode materialized = materializedCanonicalNode;
        if (materialized != null) {
            return materialized;
        }
        FrozenNode canonical = canonicalIdentityNode;
        if (canonical == null || !canonical.isReferenceOnly()) {
            return canonical;
        }
        materializeReference(canonical.getReferenceBlueId());
        return materializedCanonicalNode;
    }

    /**
     * Returns semantic content, materializing a collapsed exact reference only
     * when an operation actually needs to inspect it.
     */
    private FrozenNode semanticNode() {
        FrozenNode materialized = materializedSemanticNode;
        if (materialized != null) {
            return materialized;
        }
        FrozenNode semantic = initialSemanticNode;
        if (semantic == null || !semantic.isReferenceOnly()) {
            return semantic;
        }
        materializeReference(semantic.getReferenceBlueId());
        return materializedSemanticNode;
    }

    private void materializeReference(String blueId) {
        synchronized (this) {
            boolean semanticNeedsContent =
                    initialSemanticNode != null
                            && initialSemanticNode.isReferenceOnly()
                            && materializedSemanticNode == null;
            if (materializedCanonicalNode != null
                    && !semanticNeedsContent) {
                return;
            }
            if (referenceMaterializer == null) {
                throw new BexException(
                        "Semantic content is unavailable for exact Blue reference "
                                + blueId);
            }
            ResolvedSnapshot snapshot = referenceMaterializer.apply(blueId);
            if (snapshot == null) {
                throw new BexException(
                        "Reference materializer returned no evidence for "
                                + blueId);
            }
            if (!snapshot.isResolutionComplete()) {
                throw new BexException(
                        "Reference materializer returned incomplete evidence for "
                                + blueId);
            }
            FrozenNode loadedCanonical = snapshot.frozenCanonicalRoot();
            FrozenNode loadedSemantic = snapshot.frozenResolvedRoot();
            if (loadedCanonical == null
                    || !blueId.equals(snapshot.blueId())) {
                throw new BexException(
                        "Reference materializer returned mismatched evidence for "
                                + blueId);
            }
            if (loadedSemantic == null || loadedSemantic.isReferenceOnly()) {
                throw new BexException(
                        "Reference materializer did not establish semantic content for "
                                + blueId);
            }
            /*
             * demandedPath("") returns the verified provider direct fragment
             * as the resolved lane while the snapshot canonical root remains
             * the requested pure reference. That direct fragment is the
             * canonical structural cursor: its children retain their exact
             * provider BlueIds. Keep an already supplied resolved semantic
             * lane rather than replacing it with the shallower fragment.
             *
             * Publish in semantic-last order. A volatile semantic read then
             * observes its matching canonical cursor.
             */
            materializedCanonicalNode = loadedCanonical.isReferenceOnly()
                    ? loadedSemantic
                    : loadedCanonical;
            if (semanticNeedsContent) {
                materializedSemanticNode = loadedSemantic;
            }
        }
    }
}
