package blue.bex.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BexEngineBuilderValidationTest {

    @Test
    void gasScheduleRejectsNullImmediately() {
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> BexEngine.builder().gasSchedule(null));

        assertEquals("gasSchedule", failure.getMessage());
    }

    @Test
    void cacheRejectsNullImmediately() {
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> BexEngine.builder().cache(null));

        assertEquals("cache", failure.getMessage());
    }
}
