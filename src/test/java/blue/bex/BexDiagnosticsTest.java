package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.gas.BexGasSchedule;
import org.junit.jupiter.api.Test;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexDiagnosticsTest {
    @Test
    void unknownVariableIncludesFunctionAndOperatorPath() {
        BexException ex = assertThrows(BexException.class,
                () -> runStep(stepExpr(op("$var", "missing")), defaultContext()));

        assertTrue(ex.getMessage().contains("Unknown variable: missing"));
        assertTrue(ex.getMessage().contains("function $root /expr/$var $var"));
        assertTrue(ex.sourcePath().isPresent());
    }

    @Test
    void unknownFunctionIncludesCallLocation() {
        BexException ex = assertThrows(BexException.class,
                () -> runStep(stepExpr(op("$call", obj("function", "missing"))), defaultContext()));

        assertTrue(ex.getMessage().contains("Unknown function: missing"));
        assertTrue(ex.getMessage().contains("/expr/$call"));
    }

    @Test
    void invalidIntegerConversionIncludesExpressionLocation() {
        BexException ex = assertThrows(BexException.class,
                () -> runStep(stepExpr(op("$integer", "not-int")), defaultContext()));

        assertTrue(ex.getMessage().contains("Value cannot be converted to integer"));
        assertTrue(ex.getMessage().contains("/expr/$integer"));
    }

    @Test
    void gasExhaustionIncludesCurrentOperatorWhenPossible() {
        BexEngine engine = BexEngine.builder()
                .gasSchedule(BexGasSchedule.builder().expressionBase(100).build())
                .build();
        BexExecutionContext context = BexExecutionContext.builder()
                .document(defaultDocumentView())
                .gasLimit(1)
                .build();

        BexException ex = assertThrows(BexException.class,
                () -> engine.compileAndExecute(BexProgramSource.inline(frozen(stepExpr(op("$add", list(1, 2))))), context));

        assertTrue(ex.getMessage().contains("BEX gas exhausted"));
    }
}
