package blue.bex;

import java.util.Optional;

/**
 * Deterministic BEX execution or compilation failure.
 */
public class BexException extends RuntimeException {
    private final BexSourcePath sourcePath;

    public BexException(String message) {
        this(message, null, null);
    }

    public BexException(String message, Throwable cause) {
        this(message, cause, null);
    }

    private BexException(String message, Throwable cause, BexSourcePath sourcePath) {
        super(sourcePath != null ? message + " at " + sourcePath : message, cause);
        this.sourcePath = sourcePath;
    }

    public static BexException at(BexSourcePath sourcePath, String message) {
        return new BexException(message, null, sourcePath);
    }

    public static BexException at(BexSourcePath sourcePath, String message, Throwable cause) {
        return new BexException(message, cause, sourcePath);
    }

    public Optional<BexSourcePath> sourcePath() {
        return Optional.ofNullable(sourcePath);
    }

    public BexException withSourcePath(BexSourcePath sourcePath) {
        if (this.sourcePath != null || sourcePath == null) {
            return this;
        }
        return at(sourcePath, super.getMessage(), this);
    }
}
