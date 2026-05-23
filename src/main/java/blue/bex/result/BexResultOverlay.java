package blue.bex.result;

import blue.bex.api.BexDocumentView;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered overlay materializing accumulated patch effects for $resultValue reads.
 */
public final class BexResultOverlay {
    private final BexDocumentView document;
    private final List<BexPatchEntry> entries = new ArrayList<>();
    private final BexMetrics metrics;

    public BexResultOverlay(BexDocumentView document, BexMetrics metrics) {
        this.document = document;
        this.metrics = metrics;
    }

    public void append(BexPatchEntry entry) {
        entries.add(entry);
    }

    public BexValue valueAt(String absolutePointer, List<String> segments) {
        if (metrics != null) {
            metrics.incrementResultValueReads();
        }
        String pointer = JsonPointer.canonicalize(absolutePointer);
        List<String> selected = segments != null ? segments : JsonPointer.split(pointer);
        recordOverlayMetric(pointer, selected);
        if (entries.isEmpty()) {
            return document.canonicalAt(pointer);
        }
        BexValue materialized = document.canonicalAt("/");
        for (BexPatchEntry entry : entries) {
            materialized = apply(materialized, entry);
        }
        return materialized.at(selected);
    }

    private BexValue apply(BexValue root, BexPatchEntry entry) {
        if (entry.absoluteSegments().isEmpty()) {
            return "remove".equals(entry.op()) ? BexValues.undefined() : entry.val();
        }
        return BexValues.pointerSet(root, entry.absoluteSegments(), entry.val(),
                "remove".equals(entry.op()) ? "remove" : "set");
    }

    private void recordOverlayMetric(String pointer, List<String> selected) {
        if (metrics == null) {
            return;
        }
        boolean exact = false;
        boolean ancestor = false;
        boolean descendant = false;
        for (BexPatchEntry entry : entries) {
            if (entry.absolutePath().equals(pointer)) {
                exact = true;
            } else if (isPrefix(entry.absoluteSegments(), selected)) {
                ancestor = true;
            } else if (isPrefix(selected, entry.absoluteSegments())) {
                descendant = true;
            }
        }
        if (exact) {
            metrics.incrementResultOverlayExactHits();
            return;
        }
        if (ancestor) {
            metrics.incrementResultOverlayAncestorHits();
            return;
        }
        if (!descendant) {
            metrics.incrementResultOverlayDocumentFallbacks();
        }
    }

    private boolean isPrefix(List<String> prefix, List<String> segments) {
        if (prefix.size() >= segments.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(segments.get(i))) {
                return false;
            }
        }
        return true;
    }
}
