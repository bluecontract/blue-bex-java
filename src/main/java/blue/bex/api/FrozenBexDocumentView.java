package blue.bex.api;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Document view backed directly by frozen canonical/resolved roots.
 */
public final class FrozenBexDocumentView implements BexDocumentView {
    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final String currentScopePath;

    public FrozenBexDocumentView(FrozenNode canonicalRoot) {
        this(canonicalRoot, canonicalRoot, "/");
    }

    public FrozenBexDocumentView(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String currentScopePath) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = resolvedRoot != null ? resolvedRoot : canonicalRoot;
        this.currentScopePath = JsonPointer.canonicalize(currentScopePath == null ? "/" : currentScopePath);
    }

    @Override
    public String resolvePointer(String authoredPointer) {
        if (authoredPointer == null || authoredPointer.isEmpty()) {
            return currentScopePath;
        }
        if (authoredPointer.charAt(0) == '/') {
            return JsonPointer.canonicalize(authoredPointer);
        }
        List<String> segments = new ArrayList<>(JsonPointer.split(currentScopePath));
        segments.addAll(JsonPointer.split(authoredPointer));
        return JsonPointer.toPointer(segments);
    }

    @Override
    public BexValue canonicalAt(String absolutePointer) {
        return read(canonicalRoot, absolutePointer);
    }

    @Override
    public BexValue resolvedAt(String absolutePointer) {
        return read(resolvedRoot, absolutePointer);
    }

    @Override
    public String currentScopePath() {
        return currentScopePath;
    }

    private BexValue read(FrozenNode root, String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        FrozenNode selected = root.at(segments);
        if (selected != null) {
            return BexValues.frozen(selected);
        }
        return BexValues.frozen(root).at(segments);
    }
}
