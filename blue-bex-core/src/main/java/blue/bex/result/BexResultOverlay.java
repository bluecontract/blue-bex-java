package blue.bex.result;

import blue.bex.spi.BexDocumentAccess;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.wire.JsonPointer;
import blue.language.runtime.BlueLanguage;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered overlay materializing accumulated patch effects for $resultValue reads.
 */
public final class BexResultOverlay {
    private final BexDocumentAccess document;
    private final List<BexPatchEntry> entries = new ArrayList<>();
    private final BexMetricsRecorder metrics;
    private final BlueLanguage blue;

    public BexResultOverlay(BexDocumentAccess document, BexMetricsRecorder metrics) {
        this(document, metrics, null);
    }

    public BexResultOverlay(
            BexDocumentAccess document,
            BexMetricsRecorder metrics,
            BlueLanguage blue) {
        this.document = document;
        this.metrics = metrics;
        this.blue = blue;
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
            return exactAt(pointer);
        }
        BexValue materialized = exactAt("/");
        for (BexPatchEntry entry : entries) {
            materialized = apply(materialized, entry);
        }
        return materialized.at(selected);
    }

    /**
     * Returns the current transient overlay root without selecting a child.
     * Pointer traversal and its gas belong to the BEX runtime.
     */
    public BexValue rootValue() {
        if (entries.isEmpty()) {
            return exactAt("/");
        }
        BexValue materialized = exactAt("/");
        for (BexPatchEntry entry : entries) {
            materialized = apply(materialized, entry);
        }
        return materialized;
    }

    private BexValue exactAt(String pointer) {
        return BexValues.referenceBacked(
                document.canonicalAt(pointer),
                blue);
    }

    private BexValue apply(BexValue root, BexPatchEntry entry) {
        if (entry.absoluteSegments().isEmpty()) {
            return "remove".equals(entry.op()) ? BexValues.undefined() : entry.val();
        }
        return BexValues.resultOverlayPointerSet(
                root,
                entry.absoluteSegments(),
                entry.val(),
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
