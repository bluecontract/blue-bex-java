package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.result.BexPatchEntry;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

final class BexPatchEntryParser {
    private BexPatchEntryParser() {
    }

    static BexPatchEntry fromFields(CompiledFrame frame,
                                    String op,
                                    String authoredPath,
                                    boolean hasVal,
                                    BexValue val) {
        validateOp(op);
        if (authoredPath == null) {
            throw new BexException("Patch path is required");
        }
        BexValue normalizedVal = BexValues.undefined();
        if ("add".equals(op) || "replace".equals(op)) {
            if (!hasVal || val == null || val.isUndefined()) {
                throw new BexException("Patch op " + op + " requires val");
            }
            normalizedVal = val;
        }
        String absolute = frame.runtime().resolvePointer(authoredPath);
        return new BexPatchEntry(op, authoredPath, absolute, normalizedVal);
    }

    static boolean requiresValue(String op) {
        validateOp(op);
        return !"remove".equals(op);
    }

    static BexPatchEntry fromValue(CompiledFrame frame, BexValue entry) {
        if (entry == null || !entry.isObject()) {
            throw new BexException("Patch entry must be an object");
        }
        String op = requiredText(entry.get("op"), "patch.op");
        String path = requiredText(entry.get("path"), "patch.path");
        BexValue val = entry.get("val");
        return fromFields(frame, op, path, !val.isUndefined(), val);
    }

    private static void validateOp(String op) {
        if (!"add".equals(op) && !"replace".equals(op) && !"remove".equals(op)) {
            throw new BexException("Unsupported patch op: " + op);
        }
    }

    private static String requiredText(BexValue value, String label) {
        if (value == null || value.isUndefined() || value.isNull()) {
            throw new BexException("Missing required text field: " + label);
        }
        return value.asText();
    }
}
