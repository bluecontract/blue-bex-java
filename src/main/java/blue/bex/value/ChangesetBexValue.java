package blue.bex.value;

import blue.bex.result.BexChangeset;

/**
 * BEX list view over an ordered changeset.
 */
public final class ChangesetBexValue extends AbstractBexValue {
    private final BexChangeset changeset;

    public ChangesetBexValue(BexChangeset changeset) {
        this.changeset = changeset;
    }

    @Override
    public boolean isList() {
        return true;
    }

    @Override
    public BexValue get(String key) {
        try {
            int index = Integer.parseInt(key);
            return index >= 0 && index < changeset.entries().size()
                    ? new PatchEntryBexValue(changeset.entries().get(index))
                    : BexValues.undefined();
        } catch (NumberFormatException ex) {
            return BexValues.undefined();
        }
    }

    @Override
    public int size() {
        return changeset.entries().size();
    }

    @Override
    public blue.language.model.Node toNode() {
        return BexNodeWriter.toNode(this);
    }

    @Override
    public Object toSimple() {
        return BexSimpleWriter.toSimple(this);
    }
}
