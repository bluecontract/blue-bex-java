package blue.bex.examples;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedConsumerSmokeTest {
    @Test
    void executesInsideARealContractsLifecycleInvocation() {
        HostedConsumerSmoke.SmokeResult result =
                HostedConsumerSmoke.runSmoke();

        assertEquals(BigInteger.valueOf(42L), result.answer());
        assertTrue(result.bexGas() > 0L);
        assertTrue(result.contractsGas() >= result.bexGas());
        assertFalse(result.outputBlueId().isEmpty());
    }
}
