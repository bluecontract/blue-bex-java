package blue.bex.output;

/**
 * Opaque host-owned capability accompanying an exact BEX output value.
 *
 * <p>BEX never interprets or manufactures implementations. A hosted runtime
 * may retain an invocation-scoped proof here so its result adapter can carry
 * the already-admitted value without reopening a provider or rebuilding the
 * value from its semantic cursor.</p>
 */
public interface BexExactValueCapability {
}
