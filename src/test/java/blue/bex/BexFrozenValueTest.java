package blue.bex;

import blue.bex.value.BexFrozenWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexFrozenValueTest {
    @Test
    void frozenValueReturnsSameCanonicalFrozenNode() {
        FrozenNode frozen = frozen(obj("a", 1));

        FrozenNode out = BexFrozenWriter.toFrozen(BexValues.frozen(frozen));

        assertSame(frozen, out);
    }

    @Test
    void transientTreeUsesStrictBlueOutputConversion() {
        BexValue value = BexValues.map((Map<String, BexValue>) (Map) m(
                "text", BexValues.scalar("x"),
                "list", BexValues.list(Arrays.asList(BexValues.scalar("a")))
        ));

        FrozenNode frozen = BexFrozenWriter.toFrozen(value);

        assertEquals(m("list", l("a"), "text", "x"), simple(BexValues.frozen(frozen)));
    }
}
