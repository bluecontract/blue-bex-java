package blue.bex.contracts;

import blue.bex.BexException;
import blue.bex.BexExecutionEvidenceUnavailableException;
import blue.bex.BexInvalidExecutionEvidenceException;
import blue.bex.api.BexFailureBoundary;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexHostGasExhaustion;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorFailureException;

/** Contracts exception classification and translation at the adapter edge. */
public final class BexContractsFailureBoundary
        implements BexFailureBoundary {
    public static final BexContractsFailureBoundary INSTANCE =
            new BexContractsFailureBoundary();

    private BexContractsFailureBoundary() {
    }

    @Override
    public Classification classify(Throwable failure) {
        Throwable current = failure;
        boolean genericBexFailure = false;
        while (current != null) {
            if (current instanceof ExecutionEvidenceUnavailableException
                    || current
                    instanceof BexExecutionEvidenceUnavailableException) {
                return Classification.EVIDENCE_UNAVAILABLE;
            }
            if (current instanceof ProcessorFailureException
                    || current instanceof InvalidExecutionEvidenceException
                    || current instanceof PortableLimitExceededException
                    || current instanceof GasLimitExceededException
                    || current instanceof BexHostGasExhaustion
                    || current instanceof BexGasLimitExceededException
                    || current instanceof BexInvalidExecutionEvidenceException) {
                return Classification.DETERMINISTIC;
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

    @Override
    public RuntimeException translate(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof BexExecutionEvidenceUnavailableException) {
                BexExecutionEvidenceUnavailableException unavailable =
                        (BexExecutionEvidenceUnavailableException) current;
                return new ExecutionEvidenceUnavailableException(
                        unavailable.getMessage(),
                        unavailable.requiredExactBlueIds());
            }
            if (current instanceof BexInvalidExecutionEvidenceException) {
                return new InvalidExecutionEvidenceException(
                        current.getMessage());
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return failure;
    }
}
