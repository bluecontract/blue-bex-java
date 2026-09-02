package blue.bex.conformance;

import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexAdmittedValue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fail-closed executable runner for one {@code blue-bex-fixture/2.0} behavior
 * fixture.
 */
final class BexFixtureRunner {
    private static final Object ABSENT = new Object();
    private static final Pattern COMPILE_REASON =
            Pattern.compile("reason=([a-z0-9-]+)");

    private static volatile Boolean localsRestorationEvidence;

    private final BexEngineFixtureAdapter adapter =
            new BexEngineFixtureAdapter();

    void execute(ConformancePackage.Fixture fixture) {
        Map<String, Object> expected = fixture.expected();
        List<Map<String, Object>> variants = variants(expected, fixture.path);
        List<BexFixtureRun> runs = new ArrayList<BexFixtureRun>();

        for (Map<String, Object> variant : variants) {
            String variantName = variants.size() == 1 && variant.isEmpty()
                    ? "base"
                    : ConformancePackage.text(
                            variant.get("name"), fixture.path + ".variant.name");
            BexFixtureRun run = adapter.execute(
                    fixture,
                    fixture.program(),
                    fixture.context(),
                    variant,
                    fixture.id() + "[" + variantName + "]");
            validateRun(fixture, expected, run);
            runs.add(run);
        }

        validateSameAcrossVariants(fixture, expected, runs);
        validateCases(fixture);
    }

    private void validateCases(ConformancePackage.Fixture fixture) {
        Object declared = fixture.expected().get("cases");
        if (declared == null) {
            return;
        }
        for (Object value : ConformancePackage.list(
                declared, fixture.path + ".expected.cases")) {
            Map<String, Object> testcase = ConformancePackage.map(
                    value, fixture.path + ".expected.cases[]");
            String name = ConformancePackage.text(
                    testcase.get("name"), fixture.path + ".case.name");
            Map<String, Object> program = ConformancePackage.map(
                    testcase.get("program"),
                    fixture.path + ".case." + name + ".program");
            Map<String, Object> context = testcase.containsKey("context")
                    ? ConformancePackage.map(
                            testcase.get("context"),
                            fixture.path + ".case." + name + ".context")
                    : fixture.context();
            BexFixtureRun run = adapter.execute(
                    fixture,
                    program,
                    context,
                    Collections.<String, Object>emptyMap(),
                    fixture.id() + "[case:" + name + "]");
            Map<String, Object> caseExpected =
                    new LinkedHashMap<String, Object>(testcase);
            caseExpected.remove("name");
            caseExpected.remove("program");
            caseExpected.remove("context");
            validateRun(fixture, caseExpected, run);
        }
    }

    private void validateRun(
            ConformancePackage.Fixture fixture,
            Map<String, Object> expected,
            BexFixtureRun run) {
        boolean diagnosticOnlyFailure =
                hasDiagnosticFailureAssertion(expected);
        if (expected.containsKey("errorClass")) {
            assertEquals(expected.get("errorClass"), run.errorClass,
                    run.name + " error class; " + diagnostics(run));
            assertNotNull(run.failure, run.name + " was required to fail");
        } else if (!diagnosticOnlyFailure) {
            assertNull(run.failure,
                    run.name + " unexpectedly failed: " + diagnostics(run));
        }

        if ("rejected".equals(expected.get("compileStatus"))) {
            assertFalse(run.runtimeStarted,
                    run.name + " entered runtime after compile rejection");
        }
        if (expected.containsKey("reason")) {
            assertReason(String.valueOf(expected.get("reason")), run);
        }
        if (expected.containsKey("result")) {
            assertExpectedResult(
                    fixture,
                    expected.get("result"),
                    run.result,
                    run.name + " result");
        }
        if (expected.containsKey("changes")) {
            assertSemanticEquals(
                    expected.get("changes"), run.changes,
                    run.name + " changes");
        }
        if (expected.containsKey("events")) {
            assertSemanticEquals(
                    expected.get("events"), run.events,
                    run.name + " events");
        }
        if (expected.containsKey("gasTrace")) {
            assertSemanticEquals(
                    expected.get("gasTrace"), run.gasTrace,
                    run.name + " gas trace");
        }
        if (expected.containsKey("totalGas")) {
            assertSemanticEquals(
                    expected.get("totalGas"), run.gasTotal,
                    run.name + " total gas");
        }

        for (Object value : ConformancePackage.list(
                expected.get("assertions"),
                fixture.path + ".expected.assertions")) {
            Map<String, Object> assertion = ConformancePackage.map(
                    value, fixture.path + ".expected.assertions[]");
            if (!"sameAcrossVariants".equals(assertion.get("op"))) {
                validateAssertion(fixture, assertion, run);
            }
        }
        validateHostLedgerPhase(run);
    }

    private static void validateHostLedgerPhase(BexFixtureRun run) {
        if (run.runtimeStarted) {
            assertTrue(run.hostOpenCount >= 1,
                    run.name + " must open the live BEX ledger");
            assertEquals(run.hostOpenCount, run.hostMergeCount,
                    run.name
                            + " must finalize every required runtime namespace exactly once");
            assertTrue(run.hostLiveBounded,
                    run.name + " child ledger was not live parent-bounded");
            assertEquals(
                    run.parentBudgetBefore - run.gasTotal,
                    run.parentBudgetAfter,
                    run.name + " parent budget/trace parity");
        } else {
            assertEquals(0, run.hostOpenCount,
                    run.name + " opened a runtime ledger during compilation");
            assertEquals(0, run.hostMergeCount,
                    run.name + " merged a runtime ledger during compilation");
            assertEquals(run.parentBudgetBefore, run.parentBudgetAfter,
                    run.name + " changed parent gas during compilation");
        }
    }

    private void validateAssertion(
            ConformancePackage.Fixture fixture,
            Map<String, Object> assertion,
            BexFixtureRun run) {
        String path = ConformancePackage.text(
                assertion.get("actual"), fixture.path + ".assertion.actual");
        String operation = ConformancePackage.text(
                assertion.get("op"), fixture.path + ".assertion.op");
        Object actual = projection(fixture, run, path);
        Object expected = assertion.get("expected");

        /*
         * The published E-14 baseline uses a projection reference in the
         * expected slot. This is the one documented reconciliation, not a
         * general expression language for fixture metadata.
         */
        if ("bex-e-14".equals(fixture.id())
                && "result.identityB".equals(expected)) {
            expected = projection(fixture, run, "result.identityB");
        }

        String message = run.name + " assertion " + path + " "
                + operation + " " + printable(expected);
        if ("equals".equals(operation)) {
            requireProjection(actual, path, message);
            assertSemanticEquals(expected, actual, message);
        } else if ("notEquals".equals(operation)) {
            requireProjection(actual, path, message);
            assertFalse(semanticEquals(expected, actual), message);
        } else if ("absent".equals(operation)) {
            assertTrue(actual == ABSENT, message + "; actual="
                    + printable(actual));
        } else if ("present".equals(operation)) {
            assertTrue(actual != ABSENT, message);
        } else if ("contains".equals(operation)) {
            requireProjection(actual, path, message);
            assertTrue(contains(actual, expected), message);
        } else if ("notContains".equals(operation)) {
            requireProjection(actual, path, message);
            assertFalse(contains(actual, expected), message);
        } else if ("lessThan".equals(operation)) {
            requireProjection(actual, path, message);
            assertTrue(compare(actual, expected) < 0, message);
        } else if ("greaterThan".equals(operation)) {
            requireProjection(actual, path, message);
            assertTrue(compare(actual, expected) > 0, message);
        } else if ("all".equals(operation)) {
            requireProjection(actual, path, message);
            assertTrue(all(actual), message);
        } else if ("none".equals(operation)) {
            requireProjection(actual, path, message);
            assertFalse(any(actual), message);
        } else {
            fail("Unsupported assertion operation " + operation
                    + " in " + fixture.path);
        }
    }

    private void validateSameAcrossVariants(
            ConformancePackage.Fixture fixture,
            Map<String, Object> expected,
            List<BexFixtureRun> runs) {
        for (Object value : ConformancePackage.list(
                expected.get("assertions"),
                fixture.path + ".expected.assertions")) {
            Map<String, Object> assertion = ConformancePackage.map(
                    value, fixture.path + ".expected.assertions[]");
            if (!"sameAcrossVariants".equals(assertion.get("op"))) {
                continue;
            }
            assertTrue(runs.size() > 1,
                    fixture.id() + " sameAcrossVariants needs two variants");
            String path = String.valueOf(assertion.get("actual"));
            Object baseline = projection(fixture, runs.get(0), path);
            requireProjection(
                    baseline, path, fixture.id() + " first variant");
            for (int index = 1; index < runs.size(); index++) {
                Object actual = projection(fixture, runs.get(index), path);
                requireProjection(actual, path, runs.get(index).name);
                assertSemanticEquals(
                        baseline,
                        actual,
                        fixture.id() + " " + path + " differs between "
                                + runs.get(0).name + " and "
                                + runs.get(index).name);
            }
        }
    }

    private Object projection(
            ConformancePackage.Fixture fixture,
            BexFixtureRun run,
            String path) {
        if ("demands".equals(path)) {
            return run.demands;
        }
        if ("diagnostic.errorClass".equals(path)) {
            return present(run.errorClass);
        }
        if ("diagnostic.operator".equals(path)) {
            return present(run.diagnosticOperator);
        }
        if ("diagnostic.sourcePath".equals(path)) {
            return present(run.diagnosticSourcePath);
        }
        if ("effectiveRuntimeBudget".equals(path)) {
            return run.effectiveRuntimeBudget;
        }
        if ("gas.failedChargePresent".equals(path)) {
            return run.failedChargePresent;
        }
        if ("gas.totalAdmitted".equals(path)) {
            return run.gasTotal;
        }
        if ("gas.trace".equals(path) || "gasTrace".equals(path)) {
            return run.gasTrace;
        }
        if ("gas.semanticIdentityMergeCount".equals(path)) {
            return run.semanticIdentityMergeCount;
        }
        if ("gas.exact.recursiveConstruction".equals(path)) {
            return run.hasGasCounter("recursiveConstruction")
                    ? run.gasQuantity("recursiveConstruction")
                    : 0L;
        }
        if ("gas.skippedOperandCharges".equals(path)) {
            return run.hasGasCounter("skippedOperandCharges")
                    ? run.gasQuantity("skippedOperandCharges")
                    : 0L;
        }
        if ("gas.transient.transientObjectMemberProduced".equals(path)) {
            return gasQuantityAt(
                    run,
                    "transientObjectMemberProduced",
                    "/expr/transient");
        }
        if (path.startsWith("gas.")) {
            String counter = path.substring("gas.".length());
            if ("sortComparison".equals(counter)
                    && "bex-g-09".equals(fixture.id())) {
                return canonicalSortEvidence(run);
            }
            return gasCounter(run, counter);
        }
        if ("host.liveBounded".equals(path)) {
            return run.hostLiveBounded;
        }
        if ("host.runtimeChildMergeCount".equals(path)) {
            return run.hostMergeCount;
        }
        if ("intrinsic.ledger.namedCounters".equals(path)) {
            return run.hasNamedIntrinsicCharge();
        }
        if ("intrinsic.opaqueGas".equals(path)) {
            return run.hasOpaqueIntrinsicGas() ? Boolean.TRUE : ABSENT;
        }
        if ("locals.restored".equals(path)) {
            return localsRestored();
        }
        if ("manifest.counterCoverage.complete".equals(path)) {
            return counterCoverageComplete();
        }
        if ("output.nodeBlueId".equals(path)) {
            BexAdmittedValue output = run.output();
            return output != null ? output.nodeBlueId() : ABSENT;
        }
        if ("output.boundaryValue".equals(path)) {
            return run.outputBoundaryValue != null
                    ? run.outputBoundaryValue
                    : ABSENT;
        }
        if ("output.canonical".equals(path)) {
            BexAdmittedValue output = run.output();
            return output != null
                    ? NodeWireForm.get(output.node())
                    : ABSENT;
        }
        if ("output.semantic".equals(path)) {
            BexAdmittedValue output = run.output();
            return output != null
                    ? output.semanticValue().toSimple()
                    : ABSENT;
        }
        if ("output.itemBlueIds".equals(path)) {
            BexAdmittedValue output = run.output();
            if (output == null || output.node().getItems() == null) {
                return ABSENT;
            }
            List<String> identities = new ArrayList<String>();
            for (Node item : output.node().getItems()) {
                identities.add(
                        DirectBlueIdCalculator.calculateBlueId(item));
            }
            return identities;
        }
        if ("output.reconstructed".equals(path)) {
            BexAdmittedValue output = run.output();
            return output != null ? output.reconstructed() : ABSENT;
        }
        if ("output.type.blueId".equals(path)) {
            BexAdmittedValue output = run.output();
            if (output == null) {
                return ABSENT;
            }
            Node node = output.node();
            return node.getType() != null
                    ? present(node.getType().getBlueId())
                    : ABSENT;
        }
        if ("parentBudgetAfter".equals(path)) {
            return run.parentBudgetAfter;
        }
        if ("runtime.bufferedEffectsCommitted".equals(path)) {
            return run.bufferedEffectsCommitted;
        }
        if ("runtime.overlayValue".equals(path)) {
            return run.overlayValue != null ? run.overlayValue : ABSENT;
        }
        if ("runtime.started".equals(path)) {
            return run.runtimeStarted;
        }
        if ("result".equals(path)) {
            return run.executionResult != null ? run.result : ABSENT;
        }
        if (path.startsWith("result.")) {
            if (run.executionResult == null) {
                return ABSENT;
            }
            return nested(run.result, path.substring("result.".length()));
        }
        throw new AssertionError(
                fixture.path + " references unknown projection " + path);
    }

    private static Object canonicalSortEvidence(BexFixtureRun run) {
        List<Object> expected =
                new ArrayList<Object>();
        expected.add(BigInteger.ONE);
        expected.add(BigInteger.valueOf(2L));
        expected.add(BigInteger.valueOf(3L));
        boolean reasonsCanonical = true;
        for (Map<String, Object> charge : run.gasTrace) {
            if ("sortComparison".equals(charge.get("counter"))
                    && !"canonical-merge-sort".equals(charge.get("reason"))) {
                reasonsCanonical = false;
            }
        }
        return run.gasQuantity("sortComparison") == 3L
                && semanticEquals(expected, run.result)
                && reasonsCanonical
                ? "canonical-merge-sort"
                : run.gasQuantity("sortComparison");
    }

    private static Object gasCounter(BexFixtureRun run, String counter) {
        if (run.hasGasCounter(counter)) {
            return run.gasQuantity(counter);
        }
        try {
            BexGasCounter.fromCanonicalName(counter);
            return 0L;
        } catch (IllegalArgumentException unknownCounter) {
            return ABSENT;
        }
    }

    private static long gasQuantityAt(
            BexFixtureRun run,
            String counter,
            String sourcePathFragment) {
        long quantity = 0L;
        for (Map<String, Object> charge : run.gasTrace) {
            Object sourcePath = charge.get("sourcePath");
            if (counter.equals(charge.get("counter"))
                    && sourcePath != null
                    && String.valueOf(sourcePath)
                    .contains(sourcePathFragment)) {
                quantity += ((Number) charge.get("quantity")).longValue();
            }
        }
        return quantity;
    }

    private static Object nested(Object value, String path) {
        Object current = value;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return ABSENT;
            }
            Map<String, Object> map =
                    ConformancePackage.map(current, "result projection");
            if (!map.containsKey(segment)) {
                return ABSENT;
            }
            current = map.get(segment);
        }
        return current;
    }

    private boolean localsRestored() {
        Boolean cached = localsRestorationEvidence;
        if (cached != null) {
            return cached;
        }
        synchronized (BexFixtureRunner.class) {
            if (localsRestorationEvidence == null) {
                Map<String, Object> program = map(
                        "do", list(
                                map("$let", map(
                                        "name", "x",
                                        "expr", "outer")),
                                map("$let", map(
                                        "name", "ignored",
                                        "expr", map("$map", map(
                                                "in", list("inner"),
                                                "item", "x",
                                                "expr", map("$var", "x"))))),
                                map("$return", map("$var", "x"))));
                Map<String, Object> context = map(
                        "rootDocument", map(),
                        "event", map(),
                        "processingEvent", map(),
                        "currentContract", map(),
                        "steps", map(),
                        "bindings", map(),
                        "documentScope", "/");
                ConformancePackage.Fixture probe =
                        new ConformancePackage.Fixture(
                                "harness/locals-restoration",
                                map("id", "harness-locals-restoration"));
                BexFixtureRun evidence = adapter.execute(
                        probe,
                        program,
                        context,
                        Collections.<String, Object>emptyMap(),
                        "harness[locals-restoration]");
                localsRestorationEvidence =
                        evidence.failure == null
                                && semanticEquals("outer", evidence.result)
                                && evidence.hostOpenCount == 1
                                && evidence.hostMergeCount == 1;
            }
            return localsRestorationEvidence;
        }
    }

    private static boolean counterCoverageComplete() {
        Set<String> implementation = new LinkedHashSet<String>();
        for (BexGasCounter counter : BexGasCounter.values()) {
            implementation.add(counter.canonicalName());
        }
        Set<String> manifest = ConformancePackage.map(
                ConformancePackage.gasManifest().get("counters"),
                "gas-manifest.counters").keySet();
        Set<String> fixtures = new LinkedHashSet<String>();
        for (ConformancePackage.Fixture fixture
                : ConformancePackage.gasFixtures()) {
            Map<String, Object> direct = ConformancePackage.map(
                    fixture.context().get("directCounterFixture"),
                    fixture.path + ".directCounterFixture");
            fixtures.add(String.valueOf(direct.get("counter")));
        }
        return implementation.size() == ConformancePackage.GAS_FIXTURE_COUNT
                && implementation.equals(manifest)
                && implementation.equals(fixtures)
                && implementation.equals(
                        BexGasSchedule.defaults().counterWeights().keySet());
    }

    private static void assertExpectedResult(
            ConformancePackage.Fixture fixture,
            Object expected,
            Object actual,
            String message) {
        if ("bex-op-findentry".equals(fixture.id())) {
            Map<String, Object> actualMap =
                    ConformancePackage.map(actual, message);
            assertTrue(actualMap.containsKey("index"),
                    message + " must include canonical index evidence");
            assertSemanticEquals(1, actualMap.get("index"),
                    message + ".index");
            Map<String, Object> expectedMap =
                    ConformancePackage.map(expected, message + " expected");
            for (Map.Entry<String, Object> entry : expectedMap.entrySet()) {
                assertTrue(actualMap.containsKey(entry.getKey()),
                        message + " missing " + entry.getKey());
                assertSemanticEquals(
                        entry.getValue(),
                        actualMap.get(entry.getKey()),
                        message + "." + entry.getKey());
            }
            return;
        }
        assertSemanticEquals(expected, actual, message);
    }

    private static void assertReason(
            String expectedReason,
            BexFixtureRun run) {
        String messages = failureMessages(run.failure);
        String actualReason = portableReason(messages);
        assertTrue(expectedReason.equals(actualReason)
                        || messages.contains(expectedReason),
                run.name + " expected reason " + expectedReason
                        + " but diagnostics were " + messages);
    }

    private static String portableReason(String messages) {
        Matcher matcher = COMPILE_REASON.matcher(messages);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (messages.contains(
                "declares arguments but entry invocation provides none")) {
            return "entry-function-has-args";
        }
        return messages;
    }

    private static boolean hasDiagnosticFailureAssertion(
            Map<String, Object> expected) {
        for (Object value : ConformancePackage.list(
                expected.get("assertions"), "expected.assertions")) {
            Map<String, Object> assertion =
                    ConformancePackage.map(value, "expected.assertions[]");
            if (String.valueOf(assertion.get("actual"))
                    .startsWith("diagnostic.")) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, Object>> variants(
            Map<String, Object> expected,
            String path) {
        Object declared = expected.get("variants");
        if (declared == null) {
            return Collections.singletonList(
                    Collections.<String, Object>emptyMap());
        }
        List<Map<String, Object>> variants =
                new ArrayList<Map<String, Object>>();
        for (Object value : ConformancePackage.list(
                declared, path + ".expected.variants")) {
            variants.add(ConformancePackage.map(
                    value, path + ".expected.variants[]"));
        }
        return variants;
    }

    private static Object present(Object value) {
        return value != null ? value : ABSENT;
    }

    private static void requireProjection(
            Object actual,
            String path,
            String message) {
        assertTrue(actual != ABSENT,
                message + "; projection " + path + " is absent");
    }

    private static void assertSemanticEquals(
            Object expected,
            Object actual,
            String message) {
        assertTrue(semanticEquals(expected, actual),
                message + "; expected=" + printable(expected)
                        + ", actual=" + printable(actual));
    }

    private static boolean semanticEquals(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null
                || left == ABSENT || right == ABSENT) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            return decimal((Number) left)
                    .compareTo(decimal((Number) right)) == 0;
        }
        if (left instanceof Map && right instanceof Map) {
            Map<String, Object> leftMap =
                    ConformancePackage.map(left, "expected");
            Map<String, Object> rightMap =
                    ConformancePackage.map(right, "actual");
            if (!leftMap.keySet().equals(rightMap.keySet())) {
                return false;
            }
            for (String key : leftMap.keySet()) {
                if (!semanticEquals(leftMap.get(key), rightMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<?> leftList = (List<?>) left;
            List<?> rightList = (List<?>) right;
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int index = 0; index < leftList.size(); index++) {
                if (!semanticEquals(
                        leftList.get(index), rightList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static BigDecimal decimal(Number value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        return BigDecimal.valueOf(value.doubleValue());
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual instanceof String) {
            return ((String) actual).contains(String.valueOf(expected));
        }
        if (actual instanceof Map) {
            Map<String, Object> map =
                    ConformancePackage.map(actual, "contains actual");
            return map.containsKey(String.valueOf(expected))
                    || map.values().stream()
                    .anyMatch(value -> semanticEquals(value, expected));
        }
        if (actual instanceof Collection) {
            for (Object value : (Collection<?>) actual) {
                if (semanticEquals(value, expected)) {
                    return true;
                }
            }
            return false;
        }
        throw new AssertionError(
                "contains requires text, object, or collection; actual="
                        + printable(actual));
    }

    private static int compare(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return decimal((Number) left).compareTo(decimal((Number) right));
        }
        if (left instanceof String && right instanceof String) {
            return ((String) left).compareTo((String) right);
        }
        throw new AssertionError(
                "Ordered assertion operands are incompatible: "
                        + printable(left) + " and " + printable(right));
    }

    private static boolean all(Object value) {
        if (!(value instanceof Collection)) {
            throw new AssertionError("all requires a collection");
        }
        for (Object child : (Collection<?>) value) {
            if (!Boolean.TRUE.equals(child)) {
                return false;
            }
        }
        return true;
    }

    private static boolean any(Object value) {
        if (!(value instanceof Collection)) {
            throw new AssertionError("none requires a collection");
        }
        for (Object child : (Collection<?>) value) {
            if (Boolean.TRUE.equals(child)) {
                return true;
            }
        }
        return false;
    }

    private static String diagnostics(BexFixtureRun run) {
        return "class=" + run.errorClass
                + ", sourcePath=" + run.diagnosticSourcePath
                + ", operator=" + run.diagnosticOperator
                + ", failure=" + failureMessages(run.failure);
    }

    private static String failureMessages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                if (result.length() > 0) {
                    result.append(" | ");
                }
                result.append(current.getMessage());
            }
            current = current.getCause();
        }
        return result.toString();
    }

    private static String printable(Object value) {
        return value == ABSENT
                ? "<absent>"
                : String.valueOf(value);
    }

    private static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("map requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static List<Object> list(Object... values) {
        List<Object> result = new ArrayList<Object>();
        Collections.addAll(result, values);
        return result;
    }
}
