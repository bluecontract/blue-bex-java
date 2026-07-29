package blue.bex.conformance;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed validator for {@code blue-bex-fixture/2.0}.
 *
 * <p>The normative schema is intentionally small. Implementing its closed
 * object rules directly avoids a second schema-dialect dependency and, more
 * importantly, ensures unknown harness capabilities never acquire meaning by
 * accident.</p>
 */
final class BexFixtureSchemaValidator {
    private static final Set<String> TOP_LEVEL = ConformancePackage.stringSet(
            "schema", "id", "vectors", "category", "description",
            "program", "context", "expected");
    private static final Set<String> REQUIRED_TOP_LEVEL =
            ConformancePackage.stringSet(
                    "schema", "id", "vectors", "category",
                    "program", "context", "expected");
    private static final Set<String> CATEGORIES = ConformancePackage.stringSet(
            "c", "e", "g", "gas", "h", "operator", "r", "s");

    private static final Set<String> CONTEXT_FIELDS = ConformancePackage.stringSet(
            "rootDocument", "event", "processingEvent", "currentContract",
            "steps", "bindings", "documentScope", "provider", "gasLimit",
            "parentRemainingGas", "directCounterFixture");
    private static final Set<String> DIRECT_COUNTER_FIELDS =
            ConformancePackage.stringSet(
                    "namespace", "counter", "quantity", "weightManifest");
    private static final Set<String> DIRECT_COUNTER_NAMESPACES =
            ConformancePackage.stringSet("runtime", "semantic", "processor");

    private static final Set<String> EXPECTED_FIELDS = ConformancePackage.stringSet(
            "compileStatus", "result", "changes", "events", "errorClass",
            "gasTrace", "totalGas", "assertions", "additionalCase",
            "variants", "cases", "reason");
    private static final Set<String> ASSERTION_FIELDS =
            ConformancePackage.stringSet("actual", "op", "expected");
    private static final Set<String> ASSERTION_OPERATORS =
            ConformancePackage.stringSet(
                    "equals", "notEquals", "absent", "present", "contains",
                    "notContains", "lessThan", "greaterThan",
                    "sameAcrossVariants", "all", "none");
    private static final Set<String> VARIANT_FIELDS =
            ConformancePackage.stringSet(
                    "name", "rootForm", "cache", "batching",
                    "rawRootDocumentJson", "deliveryKind");
    private static final Set<String> ROOT_FORMS =
            ConformancePackage.stringSet(
                    "inline", "reference", "eager", "lazy", "materialized");
    private static final Set<String> CACHE_FORMS =
            ConformancePackage.stringSet("warm", "cold");
    private static final Set<String> BATCHING_FORMS =
            ConformancePackage.stringSet("batched", "unbatched");
    private static final Set<String> DELIVERY_KINDS =
            ConformancePackage.stringSet(
                    "document-update", "triggered", "lifecycle", "embedded");
    private static final Set<String> CASE_FIELDS =
            ConformancePackage.stringSet(
                    "name", "program", "context", "errorClass", "reason");

    private BexFixtureSchemaValidator() {
    }

    static void validate(ConformancePackage.Fixture fixture) {
        Map<String, Object> root = fixture.data;
        String path = fixture.path;
        requireFields(root, REQUIRED_TOP_LEVEL, path);
        rejectUnknownFields(root, TOP_LEVEL, path);

        requireEquals(root.get("schema"), "blue-bex-fixture/2.0", path + ".schema");
        String id = requireText(root.get("id"), path + ".id");
        if (!ConformancePackage.FIXTURE_ID.matcher(id).matches()) {
            fail(path + ".id has invalid syntax: " + id);
        }

        List<?> vectors = requireList(root.get("vectors"), path + ".vectors");
        if (vectors.isEmpty()) {
            fail(path + ".vectors must not be empty");
        }
        Set<String> uniqueVectors = new HashSet<String>();
        for (int index = 0; index < vectors.size(); index++) {
            String vector = requireText(vectors.get(index),
                    path + ".vectors[" + index + "]");
            if (!ConformancePackage.VECTOR_ID.matcher(vector).matches()) {
                fail(path + ".vectors contains invalid vector id: " + vector);
            }
            if (!uniqueVectors.add(vector)) {
                fail(path + ".vectors contains duplicate vector id: " + vector);
            }
        }

        requireEnum(root.get("category"), CATEGORIES, path + ".category");
        if (root.containsKey("description")) {
            requireText(root.get("description"), path + ".description");
        }
        if (root.get("program") == null) {
            fail(path + ".program must be present");
        }
        validateContext(requireMap(root.get("context"), path + ".context"),
                path + ".context");
        validateExpected(requireMap(root.get("expected"), path + ".expected"),
                path + ".expected");
    }

    private static void validateContext(Map<String, Object> context, String path) {
        rejectUnknownFields(context, CONTEXT_FIELDS, path);
        requireObjectWhenPresent(context, "steps", path);
        requireObjectWhenPresent(context, "bindings", path);
        requireObjectWhenPresent(context, "provider", path);
        requireTextWhenPresent(context, "documentScope", path);
        requireNonNegativeIntegerWhenPresent(context, "gasLimit", path);
        requireNonNegativeIntegerWhenPresent(
                context, "parentRemainingGas", path);

        if (context.containsKey("directCounterFixture")) {
            Map<String, Object> direct = requireMap(
                    context.get("directCounterFixture"),
                    path + ".directCounterFixture");
            rejectUnknownFields(
                    direct, DIRECT_COUNTER_FIELDS, path + ".directCounterFixture");
            requireFields(direct,
                    ConformancePackage.stringSet("counter", "quantity"),
                    path + ".directCounterFixture");
            if (direct.containsKey("namespace")) {
                requireEnum(direct.get("namespace"), DIRECT_COUNTER_NAMESPACES,
                        path + ".directCounterFixture.namespace");
            }
            requireText(direct.get("counter"),
                    path + ".directCounterFixture.counter");
            requireNonNegativeInteger(direct.get("quantity"),
                    path + ".directCounterFixture.quantity");
            if (direct.containsKey("weightManifest")) {
                requireText(direct.get("weightManifest"),
                        path + ".directCounterFixture.weightManifest");
            }
        }
    }

    private static void validateExpected(
            Map<String, Object> expected,
            String path) {
        rejectUnknownFields(expected, EXPECTED_FIELDS, path);
        requireTextWhenPresent(expected, "compileStatus", path);
        requireTextWhenPresent(expected, "errorClass", path);
        requireTextWhenPresent(expected, "reason", path);
        requireListWhenPresent(expected, "changes", path);
        requireListWhenPresent(expected, "events", path);
        requireListWhenPresent(expected, "gasTrace", path);
        requireNonNegativeIntegerWhenPresent(expected, "totalGas", path);

        if (expected.containsKey("assertions")) {
            List<?> assertions = requireList(
                    expected.get("assertions"), path + ".assertions");
            for (int index = 0; index < assertions.size(); index++) {
                String assertionPath = path + ".assertions[" + index + "]";
                Map<String, Object> assertion =
                        requireMap(assertions.get(index), assertionPath);
                rejectUnknownFields(assertion, ASSERTION_FIELDS, assertionPath);
                requireFields(assertion,
                        ConformancePackage.stringSet("actual", "op"),
                        assertionPath);
                requireText(assertion.get("actual"), assertionPath + ".actual");
                requireEnum(
                        assertion.get("op"), ASSERTION_OPERATORS,
                        assertionPath + ".op");
            }
        }

        if (expected.containsKey("variants")) {
            List<?> variants = requireList(
                    expected.get("variants"), path + ".variants");
            if (variants.isEmpty()) {
                fail(path + ".variants must not be empty");
            }
            Set<String> names = new HashSet<String>();
            for (int index = 0; index < variants.size(); index++) {
                String variantPath = path + ".variants[" + index + "]";
                Map<String, Object> variant =
                        requireMap(variants.get(index), variantPath);
                rejectUnknownFields(variant, VARIANT_FIELDS, variantPath);
                requireFields(variant,
                        ConformancePackage.stringSet("name"), variantPath);
                String name = requireText(
                        variant.get("name"), variantPath + ".name");
                if (!names.add(name)) {
                    fail(path + ".variants contains duplicate name: " + name);
                }
                requireOptionalEnum(
                        variant, "rootForm", ROOT_FORMS, variantPath);
                requireOptionalEnum(
                        variant, "cache", CACHE_FORMS, variantPath);
                requireOptionalEnum(
                        variant, "batching", BATCHING_FORMS, variantPath);
                requireOptionalEnum(
                        variant, "deliveryKind", DELIVERY_KINDS, variantPath);
                requireTextWhenPresent(
                        variant, "rawRootDocumentJson", variantPath);
            }
        }

        if (expected.containsKey("cases")) {
            List<?> cases = requireList(expected.get("cases"), path + ".cases");
            if (cases.isEmpty()) {
                fail(path + ".cases must not be empty");
            }
            Set<String> names = new HashSet<String>();
            for (int index = 0; index < cases.size(); index++) {
                String casePath = path + ".cases[" + index + "]";
                Map<String, Object> fixtureCase =
                        requireMap(cases.get(index), casePath);
                rejectUnknownFields(fixtureCase, CASE_FIELDS, casePath);
                requireFields(fixtureCase,
                        ConformancePackage.stringSet(
                                "name", "program", "errorClass"),
                        casePath);
                String name = requireText(
                        fixtureCase.get("name"), casePath + ".name");
                if (!names.add(name)) {
                    fail(path + ".cases contains duplicate name: " + name);
                }
                if (fixtureCase.get("program") == null) {
                    fail(casePath + ".program must be present");
                }
                requireText(
                        fixtureCase.get("errorClass"),
                        casePath + ".errorClass");
                requireTextWhenPresent(fixtureCase, "reason", casePath);
                if (fixtureCase.containsKey("context")) {
                    validateContext(
                            requireMap(fixtureCase.get("context"),
                                    casePath + ".context"),
                            casePath + ".context");
                }
            }
        }
    }

    private static void requireObjectWhenPresent(
            Map<String, Object> source,
            String field,
            String path) {
        if (source.containsKey(field)) {
            requireMap(source.get(field), path + "." + field);
        }
    }

    private static void requireListWhenPresent(
            Map<String, Object> source,
            String field,
            String path) {
        if (source.containsKey(field)) {
            requireList(source.get(field), path + "." + field);
        }
    }

    private static void requireTextWhenPresent(
            Map<String, Object> source,
            String field,
            String path) {
        if (source.containsKey(field)) {
            requireText(source.get(field), path + "." + field);
        }
    }

    private static void requireNonNegativeIntegerWhenPresent(
            Map<String, Object> source,
            String field,
            String path) {
        if (source.containsKey(field)) {
            requireNonNegativeInteger(
                    source.get(field), path + "." + field);
        }
    }

    private static void requireOptionalEnum(
            Map<String, Object> source,
            String field,
            Set<String> values,
            String path) {
        if (source.containsKey(field)) {
            requireEnum(source.get(field), values, path + "." + field);
        }
    }

    private static void requireNonNegativeInteger(Object value, String path) {
        BigInteger integer = ConformancePackage.integer(value, path);
        if (integer.signum() < 0) {
            fail(path + " must be non-negative");
        }
    }

    private static Map<String, Object> requireMap(Object value, String path) {
        try {
            return ConformancePackage.map(value, path);
        } catch (IllegalArgumentException ex) {
            return fail(ex.getMessage());
        }
    }

    private static List<?> requireList(Object value, String path) {
        try {
            return ConformancePackage.list(value, path);
        } catch (IllegalArgumentException ex) {
            return fail(ex.getMessage());
        }
    }

    private static String requireText(Object value, String path) {
        try {
            return ConformancePackage.text(value, path);
        } catch (IllegalArgumentException ex) {
            return fail(ex.getMessage());
        }
    }

    private static void requireEnum(
            Object value,
            Set<String> allowed,
            String path) {
        String text = requireText(value, path);
        if (!allowed.contains(text)) {
            fail(path + " has unsupported value: " + text);
        }
    }

    private static void requireEquals(Object value, String expected, String path) {
        if (!expected.equals(value)) {
            fail(path + " must equal " + expected);
        }
    }

    private static void requireFields(
            Map<String, Object> source,
            Set<String> required,
            String path) {
        for (String field : required) {
            if (!source.containsKey(field) || source.get(field) == null) {
                fail(path + " is missing required field " + field);
            }
        }
    }

    private static void rejectUnknownFields(
            Map<String, Object> source,
            Set<String> allowed,
            String path) {
        for (String field : source.keySet()) {
            if (!allowed.contains(field)) {
                fail(path + " contains unknown field " + field);
            }
        }
    }

    private static <T> T fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
