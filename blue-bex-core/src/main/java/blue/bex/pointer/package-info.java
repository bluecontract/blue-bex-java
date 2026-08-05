/**
 * Canonical JSON Pointer parsing and bounded pointer caching for BEX reads and
 * updates.
 *
 * <p>Parsed pointers are immutable and shareable. The provided LRU cache
 * serializes mutation and may be shared by an engine; run-local metrics are not
 * shared. Pointer text is non-null where parsing is requested, and malformed or
 * invalid dynamic pointers fail deterministically. Cache hits never change
 * portable gas: runtime pointer-segment charges reflect semantic work, not cache
 * state.</p>
 */
package blue.bex.pointer;
