/**
 * Host-neutral, read-only ports used below the public BEX composition API.
 *
 * <p>Implementations are invocation-owned unless their type explicitly states
 * that they are immutable and shareable. Inputs are non-null, evidence failures
 * remain fail-closed, and a port must not perform work after rejecting a gas
 * charge. These interfaces carry no Contracts processor types; hosted failure,
 * provenance, and lifecycle translation belongs to {@code blue.bex.contracts}.</p>
 */
package blue.bex.spi;
