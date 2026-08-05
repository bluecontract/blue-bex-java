package blue.bex.value;

/**
 * BEX list view over computed events.
 */
public final class EventsBexValue extends AbstractBexValue {
    private final BexEventsValueView events;

    public EventsBexValue(BexEventsValueView events) {
        this.events = events;
    }

    @Override
    public boolean isList() {
        return true;
    }

    @Override
    public BexValue get(String key) {
        try {
            int index = Integer.parseInt(key);
            return index >= 0 && index < events.events().size()
                    ? events.events().get(index)
                    : BexValues.undefined();
        } catch (NumberFormatException ex) {
            return BexValues.undefined();
        }
    }

    @Override
    public int size() {
        return events.events().size();
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
