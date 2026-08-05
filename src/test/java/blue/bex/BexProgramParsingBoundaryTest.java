package blue.bex;

import blue.bex.test.TestBlue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexProgramParsingBoundaryTest {
    @Test
    void bexEmptyOperatorSurvivesPreprocessingInExactInsertionOrder() {
        try (TestBlue blue = new TestBlue()) {
            Node program = blue.yamlToBexSource(String.join("\n",
                    "type: Blue/BEX Program",
                    "expr:",
                    "  before: first",
                    "  $empty: ''",
                    "  after: last"));
            Node expression = program.getProperties().get("expr");

            assertEquals(Arrays.asList("before", "$empty", "after"),
                    new ArrayList<>(expression.getProperties().keySet()));
            assertEquals("",
                    expression.getProperties().get("$empty").getValue());
        }
    }

    @Test
    void ordinaryBlueParsingStillRejectsMalformedEmptyPlaceholder() {
        try (TestBlue blue = new TestBlue()) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> blue.yamlToNode(String.join("\n",
                            "items:",
                            "  - $empty: false")));

            assertTrue(failure.getMessage().contains(
                    "$empty\" list placeholder must have exact shape"));
        }
    }
}
