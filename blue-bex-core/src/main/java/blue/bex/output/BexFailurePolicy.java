package blue.bex.output;

import blue.bex.BexExecutionEvidenceUnavailableException;

import java.util.Objects;

/** Host-neutral failure policy consumed by the pure runtime and output code. */
public interface BexFailurePolicy {
    /** Pure-runtime policy with no host-specific exception dependency. */
    BexFailurePolicy STANDALONE = failure -> {
        Throwable current = failure;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if (current instanceof BexExecutionEvidenceUnavailableException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause;
        }
        return false;
    };

    /** Whether this failure represents transient evidence unavailability. */
    boolean evidenceUnavailable(Throwable failure);

    /** Translates a failure at the outer hosting boundary. */
    default RuntimeException translate(RuntimeException failure) {
        return Objects.requireNonNull(failure, "failure");
    }

    /** Preserves failure identity; unknown implementation faults are not semantic BEX failures. */
    default RuntimeException preserveOrWrap(
            String operation,
            RuntimeException failure) {
        RuntimeException exact = Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(operation, "operation");
        return exact;
    }
}
