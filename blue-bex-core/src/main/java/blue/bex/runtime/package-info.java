/**
 * Run-local execution orchestration, host context, gas sessions, and buffered
 * effect accumulation.
 *
 * <p>Frames, variables, accumulators, metrics, and runtime instances belong to
 * one execution and are not shareable. Compiler-owned frames, control flow,
 * and instruction interfaces live in {@code blue.bex.compile}; this package
 * consumes their opaque immutable program handle and host context values.
 * Required runtime inputs are non-null; language-level
 * absence is represented by an explicit BEX undefined value. Runtime failure or
 * exhaustion stops later work and leaves buffered effects uncommitted. Every
 * instruction admits its named gas charge before performing the associated
 * logical work.</p>
 */
package blue.bex.runtime;
