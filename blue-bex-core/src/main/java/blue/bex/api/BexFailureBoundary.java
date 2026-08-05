package blue.bex.api;

import blue.bex.BexException;
import blue.bex.BexExecutionEvidenceUnavailableException;
import blue.bex.output.BexFailurePolicy;

import java.util.Objects;

/**
 * Host-neutral classification and translation boundary for execution
 * failures which cross into or out of BEX core.
 *
 * <p>Core never needs to know a host exception hierarchy. A hosting adapter
 * can preserve its native exception instances, classify suspension versus
 * deterministic failure, and translate BEX-owned evidence failures at the
 * outer execution boundary.</p>
 */
public interface BexFailureBoundary extends BexFailurePolicy {
    /** Host-neutral lifecycle outcome used by the gas-ledger boundary. */
    enum Classification {
        DETERMINISTIC,
        EVIDENCE_UNAVAILABLE,
        UNCLASSIFIED
    }

    /** Pure-runtime classification with no Contracts dependency. */
    BexFailureBoundary STANDALONE = new BexFailureBoundary() {
        @Override
        public Classification classify(Throwable failure) {
            Throwable current = failure;
            boolean genericBexFailure = false;
            while (current != null) {
                if (current instanceof BexExecutionEvidenceUnavailableException) {
                    return Classification.EVIDENCE_UNAVAILABLE;
                }
                if (current instanceof BexException) {
                    genericBexFailure = true;
                }
                Throwable cause = current.getCause();
                if (cause == current) {
                    break;
                }
                current = cause;
            }
            return genericBexFailure
                    ? Classification.DETERMINISTIC
                    : Classification.UNCLASSIFIED;
        }
    };

    /** Classifies a failure for host-ledger finalization. */
    Classification classify(Throwable failure);

    @Override
    default boolean evidenceUnavailable(Throwable failure) {
        return classify(failure) == Classification.EVIDENCE_UNAVAILABLE;
    }

    /**
     * Translates a BEX-owned exception at the outer hosted boundary.
     * Existing host exceptions should be returned by identity.
     */
    default RuntimeException translate(RuntimeException failure) {
        return Objects.requireNonNull(failure, "failure");
    }

    /**
     * Preserves a classified host failure and wraps only an unexpected
     * implementation failure.
     */
    default RuntimeException preserveOrWrap(
            String operation,
            RuntimeException failure) {
        RuntimeException exact = Objects.requireNonNull(failure, "failure");
        if (classify(exact) != Classification.UNCLASSIFIED) {
            return exact;
        }
        return new BexException(
                Objects.requireNonNull(operation, "operation")
                        + ": " + exact.getMessage(),
                exact);
    }
}
