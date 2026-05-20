package blue.bex.result;

import blue.bex.api.BexDocumentView;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexed overlay implementing observable reverse-scan $resultValue semantics.
 */
public final class BexResultOverlay {
    private final BexDocumentView document;
    private final List<BexPatchEntry> entries = new ArrayList<>();
    private final Map<String, BexPatchEntry> latestByPath = new LinkedHashMap<>();
    private final BexMetrics metrics;

    public BexResultOverlay(BexDocumentView document, BexMetrics metrics) {
        this.document = document;
        this.metrics = metrics;
    }

    public void append(BexPatchEntry entry) {
        entries.add(entry);
        latestByPath.put(entry.absolutePath(), entry);
    }

    public BexValue valueAt(String absolutePointer, List<String> segments) {
        if (metrics != null) {
            metrics.incrementResultValueReads();
        }
        String pointer = JsonPointer.canonicalize(absolutePointer);
        BexPatchEntry exact = latestByPath.get(pointer);
        if (exact != null) {
            if (metrics != null) {
                metrics.incrementResultOverlayExactHits();
            }
            return "remove".equals(exact.op()) ? BexValues.undefined() : exact.val();
        }
        List<String> selected = segments != null ? segments : JsonPointer.split(pointer);
        for (int length = selected.size() - 1; length >= 0; length--) {
            String ancestorPointer = JsonPointer.toPointer(selected.subList(0, length));
            BexPatchEntry ancestor = latestByPath.get(ancestorPointer);
            if (ancestor == null) {
                continue;
            }
            if ("remove".equals(ancestor.op())) {
                if (metrics != null) {
                    metrics.incrementResultOverlayAncestorHits();
                }
                return BexValues.undefined();
            }
            BexValue value = ancestor.val().at(selected.subList(length, selected.size()));
            if (metrics != null) {
                metrics.incrementResultOverlayAncestorHits();
            }
            return value;
        }
        if (metrics != null) {
            metrics.incrementResultOverlayDocumentFallbacks();
        }
        return document.canonicalAt(pointer);
    }
}
