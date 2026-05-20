package blue.bex.result;

import blue.bex.value.BexValue;
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

    public BexPatchEntry(String op, String authoredPath, String absolutePath, BexValue val) {
        this.op = Objects.requireNonNull(op, "op");
        this.authoredPath = Objects.requireNonNull(authoredPath, "authoredPath");
        this.absolutePath = JsonPointer.canonicalize(Objects.requireNonNull(absolutePath, "absolutePath"));
        this.absoluteSegments = Collections.unmodifiableList(JsonPointer.split(this.absolutePath));
        this.val = val;
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
}
