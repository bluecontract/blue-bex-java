package blue.bex.pointer;

import blue.bex.BexException;
import blue.language.utils.JsonPointer;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Parsed JSON pointer used by compiled BEX programs.
 */
public final class BexPointer {
    private final String text;
    private final List<String> segments;

    private BexPointer(String text, List<String> segments) {
        this.text = text;
        this.segments = Collections.unmodifiableList(segments);
    }

    public static BexPointer parse(String pointer) {
        if (pointer == null) {
            throw new BexException("Pointer must not be null");
        }
        String normalized = JsonPointer.canonicalize(pointer);
        return new BexPointer(normalized, JsonPointer.split(normalized));
    }

    public String text() {
        return text;
    }

    public List<String> segments() {
        return segments;
    }

    public boolean root() {
        return segments.isEmpty();
    }

    public BexPointer descendant(List<String> suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return this;
        }
        java.util.ArrayList<String> next = new java.util.ArrayList<>(segments);
        next.addAll(suffix);
        return parse(JsonPointer.toPointer(next));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BexPointer)) {
            return false;
        }
        BexPointer that = (BexPointer) other;
        return text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
