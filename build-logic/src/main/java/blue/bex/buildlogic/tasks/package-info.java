/**
 * Cache-aware Gradle tasks that fingerprint sources and verify architecture and
 * published Language evidence.
 *
 * <p>Task instances are Gradle-owned and may rely only on declared inputs and
 * outputs; they are not general thread-safe utilities. Required file/property
 * inputs are non-null, and absent, stale, ambiguous, or malformed evidence fails
 * closed. These tasks consume no portable BEX gas and must never report an
 * unexecuted check as successful.</p>
 */
package blue.bex.buildlogic.tasks;
