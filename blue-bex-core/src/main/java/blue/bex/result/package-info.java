/**
 * Returned values, ordered changesets/events, overlays, gas evidence, and
 * diagnostic execution metrics.
 *
 * <p>A result belongs to one completed run and exposes immutable collections or
 * defensive copies. Mutable metric accumulators are run-owned and are not
 * shared; metric snapshots may be read independently. Required constructor
 * evidence is non-null, while explicitly optional admitted-output metadata may
 * be absent. Result construction does not hide failures or commit effects. Gas
 * totals are derived from the canonical ledger; metrics and cache state never
 * alter portable gas.</p>
 */
package blue.bex.result;
