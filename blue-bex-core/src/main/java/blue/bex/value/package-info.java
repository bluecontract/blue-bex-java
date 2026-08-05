/**
 * Representation-blind BEX values, exact Blue cursors, transient collections,
 * overlays, conversion helpers, equality, truthiness, and deterministic order.
 *
 * <p>Immutable exact/scalar values may be shared; overlays, lazy materializers,
 * and writers are owned by the execution that creates them unless documented
 * otherwise. Java null is accepted only by factories that explicitly map it to
 * BEX null/undefined. Invalid conversion or unavailable/invalid reference
 * evidence fails instead of fabricating a value. Merely carrying an exact value
 * has no recursive gas cost; callers charge actual reads, traversal,
 * construction, comparison, and output work before invoking it.</p>
 */
package blue.bex.value;
