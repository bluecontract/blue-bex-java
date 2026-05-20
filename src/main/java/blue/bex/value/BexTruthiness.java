package blue.bex.value;

final class BexTruthiness {
    private BexTruthiness() {
    }

    static boolean truthy(BexValue value) {
        if (value == null || value.isUndefined() || value.isNull()) {
            return false;
        }
        if (value.isScalar()) {
            Object simple = BexValues.rawScalar(value);
            if (simple instanceof Boolean) {
                return (Boolean) simple;
            }
            if (simple instanceof String) {
                return !((String) simple).isEmpty();
            }
            return true;
        }
        return value.size() > 0;
    }
}
