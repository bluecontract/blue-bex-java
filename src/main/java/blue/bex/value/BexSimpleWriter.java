package blue.bex.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Debug/compatibility boundary writer from BEX values to simple Java values.
 */
public final class BexSimpleWriter {
    private BexSimpleWriter() {
    }

    public static Object toSimple(BexValue value) {
        if (value.isUndefined() || value.isNull()) {
            return null;
        }
        if (value.isScalar()) {
            return value.toSimple();
        }
        if (value.isList()) {
            ArrayList<Object> out = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                out.add(toSimple(value.get(String.valueOf(i))));
            }
            return out;
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (String key : value.keys()) {
            BexValue child = value.get(key);
            if (!child.isUndefined()) {
                out.put(key, toSimple(child));
            }
        }
        return out;
    }
}
