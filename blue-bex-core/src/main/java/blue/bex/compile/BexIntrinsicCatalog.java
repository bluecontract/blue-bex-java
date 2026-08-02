package blue.bex.compile;

/**
 * Immutable compile-time view of the intrinsic types available to a host.
 *
 * <p>The compiler needs only membership, not processors, gas ledgers, or any
 * other runtime capability. Implementations must return a stable answer for
 * their entire lifetime.</p>
 */
@FunctionalInterface
public interface BexIntrinsicCatalog {
    boolean supports(String blueId);
}
