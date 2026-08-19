package blue.bex.value;

import blue.bex.BexException;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexValuesIdentityValidationTest {

    @Test
    void rejectsMalformedRetainedExactBlueIdsAtThePublicBoundary() {
        FrozenNode value = frozen("value");

        for (String malformed : new String[] {
                "",
                "not-a-blue-id",
                "1234",
                "this#0",
                ordinaryBlueId("master") + "#01"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BexValues.exact(value, value, malformed),
                    malformed);
        }
    }

    @Test
    void retainsValidatedOrdinaryBlueIdWithoutRehashingTheValue() {
        FrozenNode value = frozen("value");
        String contentBlueId = ordinaryBlueId("value");
        String retainedBlueId = ordinaryBlueId("different-value");
        assertNotEquals(contentBlueId, retainedBlueId);

        BexValue exact = BexValues.exact(
                value, value, retainedBlueId);

        assertSame(retainedBlueId, exact.exactBlueId());
    }

    @Test
    void retainsValidatedCyclicMemberBlueIdAsAnOpaqueReferenceWithoutRehashing() {
        FrozenNode value = frozen("value");
        String retainedBlueId = ordinaryBlueId("cycle-master") + "#7";

        BexValue exact = BexValues.exact(
                value, value, retainedBlueId);

        assertSame(retainedBlueId, exact.exactBlueId());
        assertTrue(exact.isExact());
        BexException unavailable = assertThrows(
                BexException.class, exact::isObject);
        assertTrue(unavailable.getMessage().contains(retainedBlueId));
    }

    @Test
    void admittedExactAlsoRejectsMalformedRetainedIdentity() {
        FrozenNode value = frozen("value");

        assertThrows(
                IllegalArgumentException.class,
                () -> BexValues.admittedExact(
                        value, "malformed", BexValues.scalar("value")));
    }

    @Test
    void frozenWriterPreservesHostAuthenticatedAdmittedRepresentation() {
        FrozenNode established = FrozenNode.fromNode(new Node()
                .properties("kind", new Node().value("established")));
        BexValue admitted = BexValues.admittedExact(
                established,
                established.blueId(),
                BexValues.fromSimple(java.util.Collections.singletonMap(
                        "kind", "different-semantic-cursor")));

        FrozenNode frozen = BexFrozenWriter.toFrozen(admitted);

        assertSame(established, frozen);
        assertSame(established.blueId(), frozen.blueId());
    }

    private static FrozenNode frozen(String value) {
        return FrozenNode.fromResolvedNode(new Node().value(value));
    }

    private static String ordinaryBlueId(String value) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value(value));
    }
}
