package blue.bex;

import blue.bex.result.BexMetrics;
import blue.bex.value.BexFrozenNodeFactory;
import blue.bex.value.BexFrozenWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexFrozenValueTest {
    @Test
    void frozenValueReturnsSameFrozenNodeWithoutFactoryRoundtrip() {
        FrozenNode frozen = frozen(obj("a", 1));
        RecordingFactory factory = new RecordingFactory();

        FrozenNode out = new BexFrozenWriter(factory, new BexMetrics()).toFrozenValue(BexValues.frozen(frozen));

        assertSame(frozen, out);
        assertEquals(0, factory.calls);
    }

    @Test
    void scalarListAndObjectUseFactoryAndMetrics() {
        BexMetrics metrics = new BexMetrics();
        RecordingFactory factory = new RecordingFactory();
        BexValue value = BexValues.map((Map<String, BexValue>) (Map) m(
                "text", BexValues.scalar("x"),
                "list", BexValues.list(Arrays.asList(BexValues.scalar("a")))
        ));

        FrozenNode frozen = new BexFrozenWriter(factory, metrics).toFrozenValue(value);

        assertEquals(m("list", l("a"), "text", "x"), simple(BexValues.frozen(frozen)));
        assertEquals(1, metrics.frozenOutputConversions());
        assertTrue(factory.scalarCalls >= 2);
        assertEquals(1, factory.listCalls);
        assertEquals(1, factory.objectCalls);
    }

    static final class RecordingFactory implements BexFrozenNodeFactory {
        int calls;
        int scalarCalls;
        int listCalls;
        int objectCalls;

        @Override
        public FrozenNode empty(BexMetrics metrics) {
            calls++;
            return FrozenNode.empty();
        }

        @Override
        public FrozenNode scalar(Object value, BexMetrics metrics) {
            calls++;
            scalarCalls++;
            return frozen(v(value));
        }

        @Override
        public FrozenNode list(List<FrozenNode> items, BexMetrics metrics) {
            calls++;
            listCalls++;
            return frozen(new blue.language.model.Node().items(new java.util.ArrayList<blue.language.model.Node>() {{
                for (FrozenNode item : items) add(item.toNode());
            }}));
        }

        @Override
        public FrozenNode object(Map<String, FrozenNode> properties, BexMetrics metrics) {
            calls++;
            objectCalls++;
            java.util.LinkedHashMap<String, blue.language.model.Node> nodes = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
                nodes.put(entry.getKey(), entry.getValue().toNode());
            }
            return frozen(new blue.language.model.Node().properties(nodes));
        }
    }
}
