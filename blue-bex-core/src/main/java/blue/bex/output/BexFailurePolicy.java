package blue.bex.output;

import blue.bex.BexException;
import blue.bex.BexExecutionEvidenceUnavailableException;

import java.util.Objects;

/** Host-neutral failure policy consumed by the pure runtime and output code. */
public interface BexFailurePolicy {
    /** Pure-runtime policy with no host-specific exception dependency. */
    BexFailurePolicy STANDALONE = failure -> {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof BexExecutionEvidenceUnavailableException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
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

    /** Preserves classified host failures and wraps implementation failures. */
    default RuntimeException preserveOrWrap(
            String operation,
            RuntimeException failure) {
        RuntimeException exact = Objects.requireNonNull(failure, "failure");
        if (evidenceUnavailable(exact) || exact instanceof BexException) {
            return exact;
        }
        return new BexException(
                Objects.requireNonNull(operation, "operation")
                        + ": " + exact.getMessage(),
                exact);
    }
}
