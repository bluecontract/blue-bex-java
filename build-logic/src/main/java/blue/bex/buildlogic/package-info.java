/**
 * Typed Gradle convention plugins for BEX module, dependency-mode, Java 8,
 * conformance, architecture, reproducibility, benchmark, publication, and
 * release-evidence configuration.
 *
 * <p>Plugin instances and extensions are owned by their Gradle project and must
 * not be used as application runtime state. Required Gradle properties are
 * non-null and missing or contradictory release inputs fail the build closed.
 * Build plugins do not participate in portable BEX execution and therefore have
 * no BEX gas effects; they verify rather than manufacture runtime evidence.</p>
 */
package blue.bex.buildlogic;
