package blue.bex;

import blue.bex.gas.BexSizeEstimator;
import blue.bex.result.BexMetrics;
import blue.bex.value.BexNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.NodeToMapListOrValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static blue.bex.test.BexTestFixtures.m;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexSchemaValueTest {
    @Test
    void frozenSchemaIsExposedAsFiniteObjectWithDeterministicSize() {
        assertFiniteSchemaValue(BexValues.nodeSnapshot(schemaBearingObject()));
    }

    @Test
    void trustedCursorSchemaIsExposedAsFiniteObjectWithDeterministicSize() {
        assertFiniteSchemaValue(BexValues.nodeCursorTrustedImmutable(schemaBearingObject()));
    }

    @Test
    void allSchemaKeywordsRoundTripThroughBexObjectForm() {
        Node source = new Node()
                .schema(allKeywordsSchema())
                .properties("payload", new Node().value("x"));
        BexValue sourceValue = BexValues.nodeSnapshot(source);

        BexValue schema = sourceValue.get("schema");
        assertEquals(Arrays.asList(
                "enum",
                "exclusiveMaximum",
                "exclusiveMinimum",
                "maxFields",
                "maxItems",
                "maxLength",
                "maximum",
                "minFields",
                "minItems",
                "minLength",
                "minimum",
                "multipleOf",
                "required",
                "uniqueItems"), schema.keys());
        assertTrue(schema.get("required").asBoolean());
        assertEquals(BigInteger.valueOf(2), schema.get("multipleOf").asInteger());
        assertEquals("draft", schema.get("enum").get("0").asText());
        assertEquals("Active option", schema.get("enum").get("1").get("name").asText());
        assertEquals("active", schema.get("enum").get("1").get("value").asText());

        Node roundTripped = BexNodeWriter.toNode(BexValues.fromSimple(sourceValue.toSimple()));
        assertEquals(NodeToMapListOrValue.get(source), NodeToMapListOrValue.get(roundTripped));
    }

    private static void assertFiniteSchemaValue(BexValue value) {
        BexValue schema = value.get("schema");
        assertEquals(m("required", true), schema.toSimple());
        assertTrue(schema.get("required").asBoolean());
        assertTrue(schema.get("schema").isUndefined());

        BexMetrics metrics = new BexMetrics();
        BexSizeEstimator estimator = new BexSizeEstimator(metrics);
        assertEquals(29L, estimator.estimate(value));
        assertEquals(29L, estimator.estimate(value));
        assertTrue(metrics.sizeEstimateCacheHits() > 0L);

        assertEquals(m("payload", "x", "schema", m("required", true)), value.toSimple());
        assertTrue(value.isObject());
    }

    private static Node schemaBearingObject() {
        return new Node()
                .schema(new Schema().required(new Node().value(true)))
                .properties("payload", new Node().value("x"));
    }

    private static Schema allKeywordsSchema() {
        return new Schema()
                .required(bool(true))
                .minLength(integer(1))
                .maxLength(integer(9))
                .minimum(integer(-3))
                .maximum(integer(12))
                .exclusiveMinimum(integer(-4))
                .exclusiveMaximum(integer(13))
                .multipleOf(integer(2))
                .minItems(integer(1))
                .maxItems(integer(7))
                .uniqueItems(bool(true))
                .minFields(integer(1))
                .maxFields(integer(5))
                .enumValues(Arrays.asList(
                        new Node().value("draft"),
                        new Node().name("Active option").value("active")));
    }

    private static Node integer(long value) {
        return new Node().value(BigInteger.valueOf(value));
    }

    private static Node bool(boolean value) {
        return new Node().value(value);
    }
}
