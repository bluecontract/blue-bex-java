/**
 * Root BEX failures and source-location value objects.
 *
 * <p>Source paths are immutable and may be shared. Failure instances belong to
 * one failed compile or execution and should not be reused as control state.
 * Public inputs are non-null unless their member documentation explicitly says
 * otherwise. BEX failures stop the current operation and preserve their cause;
 * host adapters may translate only the classifications they own. Constructing
 * diagnostics does not consume portable gas, but the admitted trace prefix of
 * the failed work remains authoritative.</p>
 */
package blue.bex;
