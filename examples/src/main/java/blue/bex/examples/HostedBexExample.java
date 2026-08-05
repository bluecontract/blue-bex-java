package blue.bex.examples;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.api.BexProgramSource;
import blue.bex.contracts.BexContractsExecutionContext;
import blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost;
import blue.bex.contracts.ProcessorExecutionContextBexSemanticIdentityBoundary;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.result.BexExecutionResult;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Contracts-hosted integration called from an active processor invocation.
 * The surrounding Contracts processor owns commit/rollback of returned effects.
 */
public final class HostedBexExample {
    private final BexEngine engine;

    public HostedBexExample(BexEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public BexExecutionResult run(
            ProcessorExecutionContext processor,
            FrozenNode selectedExpression,
            String runtimeNamespace) {
        BexExecutionContext context = BexContractsExecutionContext
                .builder(
                        Objects.requireNonNull(processor, "processor"),
                        Objects.requireNonNull(
                                runtimeNamespace, "runtimeNamespace"))
                .build();
        BexGasLedgerHost gasHost = context.gasLedgerHost();
        BexSemanticIdentityBoundary identityBoundary =
                context.semanticIdentityBoundary();
        if (!(gasHost
                instanceof ProcessorExecutionContextBexGasLedgerHost)) {
            throw new IllegalStateException(
                    "Contracts execution did not install its BEX gas adapter");
        }
        if (!(identityBoundary
                instanceof ProcessorExecutionContextBexSemanticIdentityBoundary)) {
            throw new IllegalStateException(
                    "Contracts execution did not install its BEX identity adapter");
        }

        return engine.compileAndExecute(
                BexProgramSource.expression(
                        Objects.requireNonNull(
                                selectedExpression, "selectedExpression")),
                context);
    }
}
