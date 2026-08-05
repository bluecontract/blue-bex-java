/**
 * Focused adapter from BEX values to Blue Language type and shape matching.
 *
 * <p>A matcher boundary uses immutable patterns and run-owned gas/evidence
 * state; callers should not assume an instance is safe for concurrent mutable
 * sessions. Required matcher and gas inputs are non-null, while documented
 * absent candidates/patterns follow BEX matching rules. Provider or validation
 * failures propagate rather than becoming a non-match. Comparison occurrences
 * are charged before semantic access, and warm caches receive no gas credit.</p>
 */
package blue.bex.type;
