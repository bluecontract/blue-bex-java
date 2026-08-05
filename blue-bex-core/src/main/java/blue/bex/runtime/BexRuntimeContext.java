package blue.bex.runtime;

import blue.bex.gas.BexGasLedgerLifecycle;
import blue.bex.output.BexFailurePolicy;
import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.spi.BexDocumentAccess;
import blue.bex.value.BexValue;

/** Invocation context consumed by the pure BEX runtime. */
public interface BexRuntimeContext {
    BexDocumentAccess document();
    BexValue event();
    BexValue processingEvent();
    BexValue currentContract();
    BexStepResultView steps();
    BexValue binding(String name);
    String currentScopePath();
    long gasLimit();
    long parentRemainingGas();
    BexGasLedgerLifecycle gasLedgerHost();
    BexSemanticIdentityBoundary semanticIdentityBoundary();
    BexFailurePolicy failureBoundary();
}
