/**
 * BEX-owned adapters from one Contracts {@code ProcessorExecutionContext} to
 * portable BEX document, exact-value, gas, semantic-output, evidence, and
 * failure boundaries.
 *
 * <p>Adapter instances are invocation-owned and must not outlive or be shared
 * across their processor work session. Processor contexts, builders, namespaces,
 * and admitted nodes are non-null. Host evidence and processor failures retain
 * their Contracts classification rather than becoming undefined or generic BEX
 * success. Hosted gas uses a live parent-bounded child capability, admits before
 * work, omits rejected charges, and submits/merges each child ledger exactly
 * once.</p>
 */
package blue.bex.contracts;
