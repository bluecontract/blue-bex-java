package blue.bex.conformance;

import blue.bex.gas.BexGasCharge;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Directly binds each gas microfixture to one public named-counter charge and
 * verifies the complete canonical trace entry.
 */
class BexGasMicrofixtureTest {
    @TestFactory
    Collection<DynamicTest> allNamedCounterMicrofixturesExecute() {
        List<ConformancePackage.Fixture> fixtures =
                ConformancePackage.gasFixtures();
        assertEquals(
                ConformancePackage.GAS_FIXTURE_COUNT,
                fixtures.size(),
                "The manifest must expose exactly 30 gas microfixtures");

        List<DynamicTest> tests =
                new ArrayList<DynamicTest>(fixtures.size());
        for (ConformancePackage.Fixture fixture : fixtures) {
            tests.add(DynamicTest.dynamicTest(
                    fixture.id() + " :: " + fixture.path,
                    () -> execute(fixture)));
        }
        return tests;
    }

    private static void execute(ConformancePackage.Fixture fixture) {
        Map<String, Object> direct = ConformancePackage.map(
                fixture.context().get("directCounterFixture"),
                fixture.path + ".context.directCounterFixture");
        String name = ConformancePackage.text(
                direct.get("counter"), fixture.path + ".counter");
        long quantity = ConformancePackage.integer(
                direct.get("quantity"), fixture.path + ".quantity")
                .longValueExact();
        BexGasCounter counter =
                BexGasCounter.fromCanonicalName(name);
        BexGasSchedule schedule = BexGasSchedule.defaults();
        BexGasMeter meter = new BexGasMeter(schedule, Long.MAX_VALUE);

        meter.charge(counter, quantity, "conformance-microfixture");

        List<BexGasCharge> trace = meter.trace();
        assertEquals(1, trace.size(), fixture.id() + " trace size");
        BexGasCharge charge = trace.get(0);
        assertEquals(0L, charge.sequence(), fixture.id() + " sequence");
        assertEquals("bex", charge.namespace(), fixture.id() + " namespace");
        assertEquals(counter, charge.counter(), fixture.id() + " counter");
        assertEquals(name, charge.counterName(), fixture.id() + " name");
        assertEquals(quantity, charge.quantity(), fixture.id() + " quantity");
        assertEquals(schedule.weight(counter), charge.weight(),
                fixture.id() + " weight");
        assertEquals(quantity * schedule.weight(counter), charge.gas(),
                fixture.id() + " gas");
        assertEquals("conformance-microfixture", charge.reason(),
                fixture.id() + " reason");
        assertEquals(charge.gas(), meter.totalGas(),
                fixture.id() + " trace-derived total");

        Map<String, Object> expected = fixture.expected();
        List<?> expectedTrace = ConformancePackage.list(
                expected.get("gasTrace"), fixture.path + ".expected.gasTrace");
        assertEquals(1, expectedTrace.size(), fixture.id() + " fixture trace");
        Map<String, Object> expectedEntry = ConformancePackage.map(
                expectedTrace.get(0), fixture.path + ".expected.gasTrace[0]");
        assertEquals(
                ConformancePackage.integer(
                        expectedEntry.get("sequence"), "sequence").longValueExact(),
                charge.sequence());
        assertEquals(expectedEntry.get("counter"), charge.counterName());
        assertEquals(
                ConformancePackage.integer(
                        expectedEntry.get("quantity"), "quantity").longValueExact(),
                charge.quantity());
        assertEquals(
                ConformancePackage.integer(
                        expectedEntry.get("weight"), "weight").longValueExact(),
                charge.weight());
        assertEquals(
                ConformancePackage.integer(
                        expectedEntry.get("gas"), "gas").longValueExact(),
                charge.gas());
        assertEquals(
                ConformancePackage.integer(
                        expected.get("totalGas"), "totalGas").longValueExact(),
                meter.totalGas());
    }
}
