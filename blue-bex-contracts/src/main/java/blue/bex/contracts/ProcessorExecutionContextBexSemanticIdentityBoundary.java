package blue.bex.contracts;

import blue.bex.output.BexEstablishedIdentity;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.language.model.Node;
import blue.language.processor.ExactBlueValue;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Hosted semantic-output boundary owned by one processor invocation. */
public final class ProcessorExecutionContextBexSemanticIdentityBoundary
        implements BexSemanticIdentityBoundary {
    private final ProcessorExecutionContext context;

    public ProcessorExecutionContextBexSemanticIdentityBoundary(
            ProcessorExecutionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public BexEstablishedIdentity establishIdentity(Node node) {
        ExactBlueValue exact = context.semanticOutputBoundary().admit(
                Objects.requireNonNull(node, "node"));
        return new BexEstablishedIdentity(
                exact.blueId(),
                exact.frozenValue(),
                new ProcessorExactBlueValueCapability(exact));
    }

    @Override
    public BexEstablishedIdentity carryExactIdentity(
            String blueId,
            FrozenNode frozenValue) {
        ExactBlueValue exact = context.semanticOutputBoundary()
                .carryExactValue(
                        Objects.requireNonNull(blueId, "blueId"),
                        Objects.requireNonNull(frozenValue, "frozenValue"));
        if (!Objects.requireNonNull(blueId, "blueId").equals(
                exact.blueId())) {
            throw new IllegalArgumentException(
                    "Exact BEX value identity changed at the processor boundary: "
                            + "expected " + blueId + " but found "
                            + exact.blueId() + " (strict="
                            + frozenValue.isStrictCanonical() + ", reference="
                            + frozenValue.isReferenceOnly() + ")");
        }
        return new BexEstablishedIdentity(
                exact.blueId(),
                exact.frozenValue(),
                new ProcessorExactBlueValueCapability(exact));
    }

}
