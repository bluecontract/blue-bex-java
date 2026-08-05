package blue.bex;

/** Deterministic rejection of invalid exact Blue execution evidence. */
public final class BexInvalidExecutionEvidenceException extends BexException {
    private static final long serialVersionUID = 1L;

    public BexInvalidExecutionEvidenceException(String message) {
        super(message);
    }
}
