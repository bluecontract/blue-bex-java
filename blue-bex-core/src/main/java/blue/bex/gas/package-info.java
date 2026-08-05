/**
 * Closed BEX 2.0 gas vocabulary, immutable schedule/trace evidence, run-local
 * meters, and host-neutral ledger/shared-budget capabilities.
 *
 * <p>Schedules, counters, charges, and completed ledgers are immutable and may
 * be shared; meters and open host capabilities belong to one execution and are
 * not generally thread-safe. Counter names, weights, charge contexts, and host
 * capabilities are non-null, and quantities/limits must satisfy their documented
 * ranges. Exhaustion fails before work: the rejected charge and all later work
 * are absent. Local limits may reduce but never replenish a parent/shared
 * budget, and totals are always derived from admitted named entries.</p>
 */
package blue.bex.gas;
