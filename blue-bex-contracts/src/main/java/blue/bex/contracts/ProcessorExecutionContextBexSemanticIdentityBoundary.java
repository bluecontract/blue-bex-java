package blue.bex.contracts;

import blue.bex.output.BexEstablishedIdentity;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.language.model.Node;
import blue.language.processor.ExactBlueValue;
import blue.language.processor.ProcessorExecutionContext;

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
                exact.frozenValue());
    }
}
