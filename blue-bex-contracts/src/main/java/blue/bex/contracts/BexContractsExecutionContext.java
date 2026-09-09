package blue.bex.contracts;

import blue.bex.api.BexExecutionContext;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValues;
import blue.bex.value.BexValue;
import blue.language.identity.BlueIds;
import blue.language.processor.ExactEventIdentityEvidence;
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
        FrozenNode exactEvent = exactContext.frozenEvent();
        exactBuilder.event(exactEvent != null
                ? exactEventValue(exactContext, exactEvent)
                : BexValues.nodeSnapshot(exactContext.event()));
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

    private static BexValue exactEventValue(
            ProcessorExecutionContext context, FrozenNode event) {
        ExactEventIdentityEvidence evidence = context.exactEventIdentityEvidence();
        if (event.isStrictCanonical() || event.isReferenceOnly()
                || evidence == null
                || BlueIds.hasCyclicMemberSeparator(evidence.eventBlueId())) {
            return BexValues.frozen(event);
        }
        // A resolved cursor is not a canonical identity cursor. Promote only
        // complete canonical bytes authenticated by this channelized event's
        // existing root capability; keep the semantic cursor unchanged.
        final FrozenNode canonical;
        try {
            canonical = FrozenNode.fromNode(event.toNode());
        } catch (IllegalArgumentException notCanonical) {
            return BexValues.frozen(event);
        }
        if (!evidence.eventBlueId().equals(canonical.blueId())) {
            return BexValues.frozen(event);
        }
        return BexValues.exact(canonical, event, evidence.eventBlueId());
    }
}
