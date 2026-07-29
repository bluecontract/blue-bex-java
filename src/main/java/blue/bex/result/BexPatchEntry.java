package blue.bex.result;

import blue.bex.BexException;
import blue.bex.output.BexAdmittedValue;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.utils.JsonPointer;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One ordered JSON patch entry produced by BEX.
 */
public final class BexPatchEntry {
    private final String op;
    private final String authoredPath;
    private final String absolutePath;
    private final List<String> absoluteSegments;
    private final BexValue val;
    private final BexAdmittedValue admittedValue;

    public BexPatchEntry(String op, String authoredPath, String absolutePath, BexValue val) {
        this(op, authoredPath, absolutePath, val, null);
    }

    public BexPatchEntry(String op,
                         String authoredPath,
                         String absolutePath,
                         BexValue val,
                         BexAdmittedValue admittedValue) {
        this.op = Objects.requireNonNull(op, "op");
        if (!"add".equals(op) && !"replace".equals(op) && !"remove".equals(op)) {
            throw new BexException("Unsupported patch op: " + op);
        }
        this.authoredPath = Objects.requireNonNull(authoredPath, "authoredPath");
        this.absolutePath = JsonPointer.canonicalize(Objects.requireNonNull(absolutePath, "absolutePath"));
        this.absoluteSegments = Collections.unmodifiableList(JsonPointer.split(this.absolutePath));
        this.val = val != null ? val : BexValues.undefined();
        this.admittedValue = admittedValue;
    }

    public String op() {
        return op;
    }

    public String authoredPath() {
        return authoredPath;
    }

    public String absolutePath() {
        return absolutePath;
    }

    public List<String> absoluteSegments() {
        return absoluteSegments;
    }

    public BexValue val() {
        return val;
    }

    /**
     * Strict Blue output admitted for this patch value, or {@code null} for a
     * remove entry or a compatibility entry built outside engine execution.
     * When present, {@link #val()} is the corresponding exact admitted value.
     */
    public BexAdmittedValue admittedValue() {
        return admittedValue;
    }
}
