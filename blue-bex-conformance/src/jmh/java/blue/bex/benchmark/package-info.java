/**
 * JMH benchmarks that validate BEX results and exact named gas traces before
 * recording throughput and allocation evidence.
 *
 * <p>Benchmark states are fork-owned and never shared with production
 * invocations. Parameters are non-null and an identity or gas mismatch aborts
 * the run, preventing performance numbers from masking semantic drift.</p>
 */
package blue.bex.benchmark;
