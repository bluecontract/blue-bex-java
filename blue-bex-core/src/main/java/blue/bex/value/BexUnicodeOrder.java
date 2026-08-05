package blue.bex.value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Canonical BEX ordering for text keys.
 *
 * <p>Java's natural {@link String} ordering compares UTF-16 code units. BEX
 * compares Unicode code points, which differs for supplementary characters
 * versus values in the private-use area.</p>
 */
public final class BexUnicodeOrder {
    /**
     * One admitted canonical comparison.
     *
     * <p>Metered callers admit {@code sortComparison},
     * {@code comparisonNodeVisited}, and scalar work in this method before
     * inspecting either operand.</p>
     */
    @FunctionalInterface
    public interface Comparison {
        int compare(String left, String right);
    }

    private static final Comparison UNMETERED =
            new Comparison() {
                @Override
                public int compare(
                        String left,
                        String right) {
                    return compareCodePoints(left, right);
                }
            };

    public static final Comparator<String> CODE_POINT_COMPARATOR =
            new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    return compareCodePoints(left, right);
                }
            };

    private BexUnicodeOrder() {
    }

    public static int compareCodePoints(String left, String right) {
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
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            if (leftCodePoint != rightCodePoint) {
                return leftCodePoint < rightCodePoint ? -1 : 1;
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        if (leftOffset == left.length() && rightOffset == right.length()) {
            return 0;
        }
        return leftOffset == left.length() ? -1 : 1;
    }

    public static List<String> sortedCopy(Collection<String> values) {
        return sortedCopy(values, UNMETERED);
    }

    /**
     * Returns a canonical stable ordering using the normative bottom-up merge
     * schedule.
     *
     * <p>The supplied comparison runs exactly once for every comparison in the
     * canonical trace. Tail copies do not invoke it. A metered comparison must
     * admit all comparison work before it inspects either operand; if an
     * admission rejects, no corresponding scalar comparison is performed.</p>
     */
    public static List<String> sortedCopy(
            Collection<String> values,
            Comparison comparison) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(comparison, "comparison");
        String[] source = values.toArray(new String[values.size()]);
        int size = source.length;
        if (size < 2) {
            return new ArrayList<String>(
                    java.util.Arrays.asList(source));
        }
        String[] target = new String[size];
        for (long width = 1L;
             width < size;
             width *= 2L) {
            for (long left = 0L;
                 left < size;
                 left += 2L * width) {
                int first = (int) left;
                int middle = (int) Math.min(
                        left + width, size);
                int second = middle;
                int right = (int) Math.min(
                        left + 2L * width, size);
                int output = first;
                while (first < middle
                        && second < right) {
                    String leftValue = source[first];
                    String rightValue = source[second];
                    if (comparison.compare(
                            leftValue, rightValue) <= 0) {
                        target[output++] =
                                source[first++];
                    } else {
                        target[output++] =
                                source[second++];
                    }
                }
                while (first < middle) {
                    target[output++] =
                            source[first++];
                }
                while (second < right) {
                    target[output++] =
                            source[second++];
                }
            }
            String[] swap = source;
            source = target;
            target = swap;
        }
        return new ArrayList<String>(
                java.util.Arrays.asList(source));
    }
}
