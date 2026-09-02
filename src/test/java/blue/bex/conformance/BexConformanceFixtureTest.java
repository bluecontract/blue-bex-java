package blue.bex.conformance;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Executes every manifest-declared BEX behavior fixture. There are no
 * assumptions, disabled tests, or skip paths in this suite.
 */
class BexConformanceFixtureTest {
    @TestFactory
    Collection<DynamicTest> allBehaviorFixturesExecute() {
        List<ConformancePackage.Fixture> fixtures =
                ConformancePackage.behaviorFixtures();
        assertEquals(
                ConformancePackage.BEHAVIOR_FIXTURE_COUNT,
                fixtures.size(),
                "The manifest must expose exactly 120 behavior fixtures");

        List<DynamicTest> tests =
                new ArrayList<DynamicTest>(fixtures.size());
        for (ConformancePackage.Fixture fixture : fixtures) {
            tests.add(DynamicTest.dynamicTest(
                    fixture.id() + " :: " + fixture.path,
                    () -> new BexFixtureRunner().execute(fixture)));
        }
        return tests;
    }
}
