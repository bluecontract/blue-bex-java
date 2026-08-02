/**
 * Deterministic BEX compilation, immutable compiled programs, operator metadata,
 * and compiled-program caches.
 *
 * <p>Compiled programs and keys are immutable and shareable. Cache
 * implementations document their synchronization; compiler instances are
 * operation-owned. Required source/configuration arguments are non-null.
 * Invalid static source fails compilation rather than becoming runtime
 * undefined. Compilation is outside portable runtime gas, while every compiled
 * instruction must preserve the named charges and lazy order of its execution.</p>
 */
package blue.bex.compile;
