package blue.bex.api;

/**
 * Resolves the exact Blue type identity used by the class-based intrinsic
 * registration convenience.
 *
 * <p>The string-based intrinsic API remains authoritative. This SPI keeps
 * Java-object mapping policy outside the BEX registry and lets applications
 * provide their own explicit class-to-identity catalog.</p>
 */
@FunctionalInterface
public interface BexTypeBlueIdResolver {

    /**
     * Resolves one Java type to an exact BlueId.
     *
     * @param type Java type to resolve
     * @return exact BlueId, or {@code null} when the type is not registered
     */
    String resolve(Class<?> type);
}
