package blue.bex.runtime;

import blue.bex.value.BexValue;

/** Read-only access to prior workflow step results. */
public interface BexStepResultView {
    BexValue step(String name);
    BexValue asValue();
}
