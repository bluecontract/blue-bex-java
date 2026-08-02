/**
 * Compile-tested standalone and Contracts-hosted BEX integrations.
 *
 * <p>Example engines may be shared, while each context/result is owned by one
 * run. Required inputs are non-null and examples fail fast on unexpected output.
 * Hosted failures remain owned by the active processor invocation. Examples use
 * real gas boundaries and never substitute benchmark timing or opaque totals
 * for the canonical named trace.</p>
 */
package blue.bex.examples;
