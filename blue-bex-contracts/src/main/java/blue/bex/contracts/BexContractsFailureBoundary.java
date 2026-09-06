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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
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
            Throwable cause = current.getCause();
            if (cause == null) {
                return current instanceof BexException
                        ? Classification.DETERMINISTIC : Classification.UNCLASSIFIED;
            }
            current = cause;
        }
        return Classification.UNCLASSIFIED;
    }

    @Override
    public RuntimeException translate(RuntimeException failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
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
            current = cause;
        }
        return failure;
    }
}
