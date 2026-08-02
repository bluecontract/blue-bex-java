package blue.bex.examples;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.result.BexExecutionResult;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;

/** Minimal standalone BEX program with an exact result and gas assertion. */
public final class StandaloneBexExample {
    private StandaloneBexExample() {
    }

    public static void main(String[] args) {
        FrozenNode expression = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "$add",
                        new Node().items(
                                new Node().value(40L),
                                new Node().value(2L))));
        FrozenNode document = FrozenNode.fromResolvedNode(new Node());

        BexExecutionResult result = BexEngine.builder()
                .build()
                .compileAndExecute(
                        BexProgramSource.expression(expression),
                        BexExecutionContext.builder()
                                .document(new FrozenBexDocumentView(document))
                                .gasLimit(10_000L)
                                .build());

        Object value = result.value().toSimple();
        if (!BigInteger.valueOf(42L).equals(value)) {
            throw new AssertionError("expected 42, got " + value);
        }
        if (result.gasLedger().trace().isEmpty()) {
            throw new AssertionError("expected a non-empty canonical gas trace");
        }

        System.out.println("result=" + value);
        System.out.println("gas=" + result.gasUsed());
    }
}
