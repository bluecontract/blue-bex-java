package blue.bex.api;

import blue.bex.value.BexValue;

/**
 * Host-provided implementation for one BEX intrinsic BlueId.
 */
@FunctionalInterface
public interface BexIntrinsicProcessor {
    BexValue execute(BexIntrinsicInvocation invocation);
}
