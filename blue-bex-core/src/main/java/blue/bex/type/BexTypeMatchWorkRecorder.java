package blue.bex.type;

import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasMeter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** Records the closed, cache-independent gas model for Blue type matching. */
public final class BexTypeMatchWorkRecorder {
    private static final int TEXT_BLOCK_CODE_POINTS = 64;

    private final BexGasMeter gas;
    private final BexSourcePath sourcePath;

    public BexTypeMatchWorkRecorder(
            BexGasMeter gas,
            BexSourcePath sourcePath) {
        this.gas = Objects.requireNonNull(gas, "gas");
        this.sourcePath = sourcePath;
    }

    public void comparisonNode() {
        charge(BexGasCounter.COMPARISON_NODE_VISITED, 1L);
    }

    public boolean scalarMatches(Object candidate, Object target) {
        if (target == null) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof Number && target instanceof Number) {
            integerLimbs(
                    integerLimbs(unscaled(candidate))
                            + integerLimbs(unscaled(target))
                            + (candidate instanceof BigDecimal
                            || target instanceof BigDecimal ? 1L : 0L));
            return number(candidate).compareTo(number(target)) == 0;
        }
        if (candidate instanceof String && target instanceof String) {
            return compareText((String) candidate, (String) target) == 0;
        }
        return candidate.equals(target);
    }

    /**
     * Canonical comparison with each pair of 64-code-point blocks admitted
     * before either block is inspected.
     */
    public int compareText(String left, String right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length()
                && rightOffset < right.length()) {
            charge(BexGasCounter.TEXT_BLOCK_EXAMINED, 2L);
            int inBlock = 0;
            while (inBlock < TEXT_BLOCK_CODE_POINTS
                    && leftOffset < left.length()
                    && rightOffset < right.length()) {
                int leftCodePoint = left.codePointAt(leftOffset);
                int rightCodePoint = right.codePointAt(rightOffset);
                leftOffset += Character.charCount(leftCodePoint);
                rightOffset += Character.charCount(rightCodePoint);
                if (leftCodePoint != rightCodePoint) {
                    return Integer.compare(leftCodePoint, rightCodePoint);
                }
                inBlock++;
            }
        }
        return Integer.compare(
                left.length() - leftOffset,
                right.length() - rightOffset);
    }

    private void integerLimbs(long quantity) {
        charge(BexGasCounter.INTEGER_LIMB_OPERATION, quantity);
    }

    private void charge(BexGasCounter counter, long quantity) {
        if (quantity <= 0L) {
            return;
        }
        gas.charge(
                counter,
                quantity,
                sourcePath,
                sourcePath != null ? sourcePath.operator() : null,
                counter.canonicalName());
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        return new BigDecimal(value.toString());
    }

    private static BigInteger unscaled(Object value) {
        return number(value).unscaledValue();
    }

    private static long integerLimbs(BigInteger value) {
        int bits = value.abs().bitLength();
        return Math.max(1L, (bits + 31L) / 32L);
    }
}
