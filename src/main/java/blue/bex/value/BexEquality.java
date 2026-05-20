package blue.bex.value;

import java.util.List;

final class BexEquality {
    private BexEquality() {
    }

    static boolean equal(BexValue left, BexValue right) {
        if (left == null) {
            left = BexValues.UNDEFINED;
        }
        if (right == null) {
            right = BexValues.UNDEFINED;
        }
        if (left.isUndefined() || right.isUndefined()) {
            return left.isUndefined() && right.isUndefined();
        }
        if (left.isNull() || right.isNull()) {
            return left.isNull() && right.isNull();
        }
        if (left.isScalar() || right.isScalar()) {
            if (!left.isScalar() || !right.isScalar()) {
                return false;
            }
            Object a = BexValues.rawScalar(left);
            Object b = BexValues.rawScalar(right);
            if (a instanceof java.math.BigInteger && b instanceof java.math.BigInteger) {
                return a.equals(b);
            }
            if (a instanceof Number && b instanceof Number) {
                return left.asNumber().compareTo(right.asNumber()) == 0;
            }
            return a.equals(b);
        }
        if (left.isList() || right.isList()) {
            if (!left.isList() || !right.isList() || left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!equal(left.get(String.valueOf(i)), right.get(String.valueOf(i)))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isObject() || right.isObject()) {
            if (!left.isObject() || !right.isObject()) {
                return false;
            }
            List<String> leftKeys = left.keys();
            List<String> rightKeys = right.keys();
            if (!leftKeys.equals(rightKeys)) {
                return false;
            }
            for (String key : leftKeys) {
                if (!equal(left.get(key), right.get(key))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
