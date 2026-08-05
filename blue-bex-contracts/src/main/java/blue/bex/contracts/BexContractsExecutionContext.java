package blue.bex.contracts;

import blue.bex.api.BexExecutionContext;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValues;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Invocation-scoped composition point for the Contracts-hosted BEX adapters.
 */
public final class BexContractsExecutionContext {
    private BexContractsExecutionContext() {
    }

    public static BexExecutionContext.Builder builder(
            ProcessorExecutionContext context) {
        return configure(
                BexExecutionContext.builder(),
                context,
                BexGasCounter.NAMESPACE);
    }

    public static BexExecutionContext.Builder builder(
            ProcessorExecutionContext context,
            String runtimeNamespace) {
        return configure(
                BexExecutionContext.builder(),
                context,
                runtimeNamespace);
    }

    public static BexExecutionContext.Builder configure(
            BexExecutionContext.Builder builder,
            ProcessorExecutionContext context) {
        return configure(builder, context, BexGasCounter.NAMESPACE);
    }

    public static BexExecutionContext.Builder configure(
            BexExecutionContext.Builder builder,
            ProcessorExecutionContext context,
            String runtimeNamespace) {
        BexExecutionContext.Builder exactBuilder =
                Objects.requireNonNull(builder, "builder");
        ProcessorExecutionContext exactContext =
                Objects.requireNonNull(context, "context");
        exactBuilder.document(
                new ProcessorExecutionContextBexDocumentView(exactContext));
        exactBuilder.gasLedgerHost(
                new ProcessorExecutionContextBexGasLedgerHost(
                        exactContext, runtimeNamespace));
        exactBuilder.semanticIdentityBoundary(
                new ProcessorExecutionContextBexSemanticIdentityBoundary(
                        exactContext));
        exactBuilder.failureBoundary(BexContractsFailureBoundary.INSTANCE);
        exactBuilder.event(BexValues.nodeSnapshot(exactContext.event()));
        FrozenNode processEvent = exactContext.frozenProcessEvent();
        exactBuilder.processingEvent(processEvent != null
                ? BexValues.frozen(processEvent)
                : BexValues.undefined());
        FrozenNode contract = exactContext.frozenContractNode();
        exactBuilder.currentContract(contract != null
                ? BexValues.frozen(contract)
                : BexValues.undefined());
        return exactBuilder;
    }
}
