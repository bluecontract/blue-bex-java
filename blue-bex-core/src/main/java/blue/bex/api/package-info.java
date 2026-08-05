/**
 * Stable engine/context entry points plus host and intrinsic service-provider
 * boundaries.
 *
 * <p>An engine and immutable intrinsic registry may be shared; each execution
 * context, lazy-binding state, document view, gas capability, and result belongs
 * to one run unless its implementation explicitly guarantees otherwise.
 * Required builder inputs are non-null, while documented null defaults map only
 * to the stated default/undefined behavior. Compilation, runtime, evidence,
 * failure-boundary, and exhaustion errors fail closed. Hosts supply gas and
 * identity capabilities; they may reduce budgets but must not bypass named
 * charge-before-work accounting.</p>
 */
package blue.bex.api;
