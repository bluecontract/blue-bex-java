package blue.bex.contracts;

import blue.bex.gas.BexGasChargeContext;
import blue.language.processor.GasChargeContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ProcessorExecutionContextBexGasLedgerHostTest {

    @Test
    void keepsBexDiagnosticPathsOutOfManagedScopeAttribution() {
        GasChargeContext projected =
                ProcessorExecutionContextBexGasLedgerHost
                        .contractsAttribution(BexGasChargeContext.of(
                                "function $root /do/0/$appendChange",
                                "compute",
                                "$appendChange",
                                "execute statement"));

        assertNull(projected.scopePath());
        assertEquals("compute", projected.contractKey());
        assertEquals("$appendChange", projected.logicalPath());
        assertEquals("execute statement", projected.reason());
    }
}
