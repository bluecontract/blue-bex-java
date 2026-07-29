package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasCounter;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.value.BexValue;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValues;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BexGasWork {
    private static final int TEXT_BLOCK_CODE_POINTS = 64;
    private static final BigInteger TEN = BigInteger.TEN;

    private BexGasWork() {
    }

    static void charge(CompiledFrame frame, BexGasCounter counter) {
        charge(frame, counter, 1L);
    }

    static void charge(CompiledFrame frame, BexGasCounter counter, long quantity) {
        if (quantity <= 0L) {
            return;
        }
        BexSourcePath source = frame.sourcePath();
        frame.runtime().gas().charge(counter,
                quantity,
                source,
                source != null ? source.operator() : null,
                counter.canonicalName());
    }

    static long textBlocks(String text) {
        return textBlocksForCodePoints(textCodePoints(text));
    }

    static long textCodePoints(String text) {
        return text == null || text.isEmpty()
                ? 0L
                : text.codePointCount(0, text.length());
    }

    static long textBlocksForCodePoints(long codePoints) {
        if (codePoints < 0L) {
            throw new IllegalArgumentException(
                    "Text code-point count must be non-negative");
        }
        return codePoints == 0L
                ? 0L
                : 1L + (codePoints - 1L) / TEXT_BLOCK_CODE_POINTS;
    }

    /**
     * Converts one scalar to canonical text while admitting every full-scan
     * block before the conversion or scan which consumes that block.
     */
    static MeteredText fullText(
            CompiledFrame frame,
            BexValue value) {
        return fullText(frame, value, false);
    }

    /**
     * `$text` additionally constructs a new BEX Text value. Each output
     * block's examination and construction units are admitted before the
     * scalar formatter computes that block.
     */
    static MeteredText constructedText(
            CompiledFrame frame,
            BexValue value) {
        return fullText(frame, value, true);
    }

    private static MeteredText fullText(
            CompiledFrame frame,
            BexValue value,
            boolean constructed) {
        BexValue exactValue =
                value != null ? value : BexValues.undefined();
        if (exactValue.isUndefined() || exactValue.isNull()) {
            return new MeteredText("", 0L, 0L);
        }
        if (!exactValue.isScalar()) {
            throw new BexException("Value cannot be converted to text");
        }

        Object raw = exactValue.toSimple();
        if (raw instanceof String) {
            TextScan scan = chargeTextScan(
                    frame,
                    BexGasCounter.TEXT_BLOCK_EXAMINED,
                    (String) raw,
                    0,
                    ((String) raw).length());
            if (constructed) {
                charge(
                        frame,
                        BexGasCounter.TEXT_BLOCK_CONSTRUCTED,
                        textBlocksForCodePoints(scan.codePoints));
            }
            return new MeteredText(
                    (String) raw,
                    scan.codePoints,
                    scan.pointerEscapeExpansions);
        }

        ScalarTextCursor cursor = scalarTextCursor(raw);
        StringBuilder text = null;
        long codePoints = 0L;
        while (cursor.hasNext()) {
            charge(
                    frame,
                    BexGasCounter.TEXT_BLOCK_EXAMINED);
            if (constructed) {
                charge(
                        frame,
                        BexGasCounter.TEXT_BLOCK_CONSTRUCTED);
            }
            String block = cursor.nextBlock(
                    TEXT_BLOCK_CODE_POINTS);
            if (block.isEmpty()) {
                throw new BexException(
                        "Scalar text conversion made no progress");
            }
            if (text == null) {
                text = new StringBuilder();
            }
            text.append(block);
            codePoints += block.codePointCount(
                    0, block.length());
        }
        return new MeteredText(
                text != null ? text.toString() : "",
                codePoints,
                0L);
    }

    /**
     * Charges one construction unit before scanning each output block and
     * creates the requested substring only after its final unit is admitted.
     */
    static String constructSubstring(
            CompiledFrame frame,
            String text,
            int start,
            int end) {
        chargeSubstringConstruction(
                frame, text, start, end);
        return text.substring(start, end);
    }

    static void chargeSubstringConstruction(
            CompiledFrame frame,
            String text,
            int start,
            int end) {
        chargeTextScan(
                frame,
                BexGasCounter.TEXT_BLOCK_CONSTRUCTED,
                text,
                start,
                end);
    }

    static void chargeTextConstruction(
            CompiledFrame frame,
            String text) {
        chargeSubstringConstruction(
                frame, text, 0, text.length());
    }

    /**
     * Canonical Unicode code-point comparison with block-pair admission before
     * each compared block is read.
     */
    static int compareText(
            CompiledFrame frame,
            String left,
            String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            charge(
                    frame,
                    BexGasCounter.TEXT_BLOCK_EXAMINED,
                    2L);
            int inBlock = 0;
            while (inBlock < TEXT_BLOCK_CODE_POINTS
                    && leftOffset < left.length()
                    && rightOffset < right.length()) {
                int leftCodePoint = codePointAt(
                        left, leftOffset, left.length());
                int rightCodePoint = codePointAt(
                        right, rightOffset, right.length());
                leftOffset += charCountAt(
                        left, leftOffset, left.length());
                rightOffset += charCountAt(
                        right, rightOffset, right.length());
                if (leftCodePoint != rightCodePoint) {
                    return Integer.compare(
                            leftCodePoint, rightCodePoint);
                }
                inBlock++;
            }
        }
        return Integer.compare(
                left.length() - leftOffset,
                right.length() - rightOffset);
    }

    /**
     * Scalar Text equality with each pair of source blocks admitted before it
     * is read. This deliberately avoids eagerly materializing formatted
     * numeric scalars, even though equality currently calls it only for Text.
     */
    static boolean equalText(
            CompiledFrame frame,
            BexValue left,
            BexValue right) {
        ScalarTextCursor leftCursor =
                scalarTextCursor(left);
        ScalarTextCursor rightCursor =
                scalarTextCursor(right);
        while (leftCursor.hasNext()
                && rightCursor.hasNext()) {
            charge(
                    frame,
                    BexGasCounter.TEXT_BLOCK_EXAMINED,
                    2L);
            String leftBlock = leftCursor.nextBlock(
                    TEXT_BLOCK_CODE_POINTS);
            String rightBlock = rightCursor.nextBlock(
                    TEXT_BLOCK_CODE_POINTS);
            if (!leftBlock.equals(rightBlock)) {
                return false;
            }
        }
        return !leftCursor.hasNext()
                && !rightCursor.hasNext();
    }

    /**
     * Lazily converts and compares prefix blocks. Non-Text scalar formatting
     * is deferred until after the exact block-pair charge which consumes it.
     */
    static PrefixResult comparePrefix(
            CompiledFrame frame,
            BexValue text,
            BexValue prefix) {
        ScalarTextCursor textCursor =
                scalarTextCursor(text);
        ScalarTextCursor prefixCursor =
                scalarTextCursor(prefix);
        while (prefixCursor.hasNext()) {
            if (!textCursor.hasNext()) {
                return new PrefixResult(
                        false, textCursor);
            }
            charge(
                    frame,
                    BexGasCounter.TEXT_BLOCK_EXAMINED,
                    2L);
            String prefixBlock = prefixCursor.nextBlock(
                    TEXT_BLOCK_CODE_POINTS);
            int comparedCodePoints = prefixBlock.codePointCount(
                    0, prefixBlock.length());
            String textBlock = textCursor.nextBlock(
                    comparedCodePoints);
            if (!textBlock.equals(prefixBlock)) {
                return new PrefixResult(
                        false, textCursor);
            }
        }
        return new PrefixResult(
                true, textCursor);
    }

    static long integerLimbs(BigInteger value) {
        if (value == null) {
            return 1L;
        }
        int bits = value.abs().bitLength();
        return Math.max(1L, (bits + 31L) / 32L);
    }

    /**
     * Exact Integer conversion with admission before parsing or decimal
     * conversion work.
     */
    static BigInteger convertInteger(
            CompiledFrame frame,
            BexValue value) {
        Object raw = numericScalar(value, "integer");
        if (raw instanceof BigInteger) {
            BigInteger integer = (BigInteger) raw;
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(integer));
            return integer;
        }
        if (isIntegralPrimitive(raw)) {
            long primitive = ((Number) raw).longValue();
            long limbs = primitive == Long.MIN_VALUE
                    || primitive > 0xFFFFFFFFL
                    || primitive < -0xFFFFFFFFL
                    ? 2L
                    : 1L;
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    limbs);
            return BigInteger.valueOf(primitive);
        }
        if (raw instanceof String) {
            return parseIntegerText(
                    frame, (String) raw);
        }
        if (raw instanceof BigDecimal) {
            return exactDecimalInteger(
                    frame, (BigDecimal) raw);
        }
        if (raw instanceof Float || raw instanceof Double) {
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION);
            BigDecimal decimal;
            try {
                decimal = BigDecimal.valueOf(
                        ((Number) raw).doubleValue());
            } catch (NumberFormatException ex) {
                throw new BexException(
                        "Value cannot be converted to integer");
            }
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(decimal.unscaledValue()));
            BigInteger integer;
            try {
                integer = decimal.toBigIntegerExact();
            } catch (ArithmeticException ex) {
                throw new BexException(
                        "Value cannot be converted to integer");
            }
            return integer;
        }
        throw new BexException(
                "Value cannot be converted to integer");
    }

    /**
     * Reads an already-integer operand without adding a conversion charge,
     * while routing Text and decimal coercion through the metered conversion
     * path before arithmetic begins.
     */
    static BigInteger integerOperand(
            CompiledFrame frame,
            BexValue value) {
        Object raw = numericScalar(value, "integer");
        if (raw instanceof BigInteger) {
            return (BigInteger) raw;
        }
        if (isIntegralPrimitive(raw)) {
            return BigInteger.valueOf(
                    ((Number) raw).longValue());
        }
        return convertInteger(frame, value);
    }

    /**
     * Decimal conversion with one scale-alignment unit and every unscaled
     * magnitude limb admitted before parsing or allocation.
     */
    static BigDecimal convertNumber(
            CompiledFrame frame,
            BexValue value) {
        Object raw = numericScalar(value, "number");
        if (raw instanceof String) {
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    2L);
            return parseDecimalText(
                    frame, (String) raw, 1L);
        }
        if (raw instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) raw;
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(decimal.unscaledValue()) + 1L);
            return decimal;
        }
        if (raw instanceof BigInteger) {
            BigInteger integer = (BigInteger) raw;
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(integer) + 1L);
            return new BigDecimal(integer);
        }
        if (isIntegralPrimitive(raw)) {
            BigInteger integer = BigInteger.valueOf(
                    ((Number) raw).longValue());
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(integer) + 1L);
            return new BigDecimal(integer);
        }
        if (raw instanceof Float || raw instanceof Double) {
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    2L);
            BigDecimal decimal;
            try {
                decimal = BigDecimal.valueOf(
                        ((Number) raw).doubleValue());
            } catch (NumberFormatException ex) {
                throw new BexException(
                        "Value cannot be converted to number");
            }
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(decimal.unscaledValue()) - 1L);
            return decimal;
        }
        throw new BexException(
                "Value cannot be converted to number");
    }

    /**
     * Numeric equality/ordering conversion and comparison. The guaranteed
     * first limb of each operand (plus the canonical decimal alignment unit)
     * is admitted before any Text parse or Integer-to-decimal allocation.
     */
    static int compareNumbers(
            CompiledFrame frame,
            BexValue left,
            BexValue right,
            boolean decimalAlignment) {
        Object leftRaw = numericScalar(left, "number");
        Object rightRaw = numericScalar(right, "number");
        long base = 2L;
        if (decimalAlignment
                && (isDecimalRaw(leftRaw)
                || isDecimalRaw(rightRaw))) {
            base++;
        }
        charge(
                frame,
                BexGasCounter.INTEGER_LIMB_OPERATION,
                base);
        BigDecimal leftNumber = comparisonNumber(
                frame, leftRaw);
        BigDecimal rightNumber = comparisonNumber(
                frame, rightRaw);
        return leftNumber.compareTo(rightNumber);
    }

    private static BigDecimal comparisonNumber(
            CompiledFrame frame,
            Object raw) {
        if (raw instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) raw;
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(decimal.unscaledValue()) - 1L);
            return decimal;
        }
        if (raw instanceof BigInteger) {
            BigInteger integer = (BigInteger) raw;
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(integer) - 1L);
            return new BigDecimal(integer);
        }
        if (isIntegralPrimitive(raw)) {
            BigInteger integer = BigInteger.valueOf(
                    ((Number) raw).longValue());
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(integer) - 1L);
            return new BigDecimal(integer);
        }
        if (raw instanceof String) {
            return parseDecimalText(
                    frame, (String) raw, 1L);
        }
        if (raw instanceof Float || raw instanceof Double) {
            BigDecimal decimal;
            try {
                decimal = BigDecimal.valueOf(
                        ((Number) raw).doubleValue());
            } catch (NumberFormatException ex) {
                throw new BexException(
                        "Value cannot be converted to number");
            }
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(decimal.unscaledValue()) - 1L);
            return decimal;
        }
        throw new BexException(
                "Value cannot be converted to number");
    }

    private static BigInteger parseIntegerText(
            CompiledFrame frame,
            String text) {
        charge(
                frame,
                BexGasCounter.INTEGER_LIMB_OPERATION);
        if (text == null || text.isEmpty()) {
            throw new BexException(
                    "Value cannot be converted to integer");
        }
        int offset = 0;
        boolean negative = false;
        if (text.charAt(0) == '-') {
            negative = true;
            offset = 1;
        }
        if (offset == text.length()) {
            throw new BexException(
                    "Value cannot be converted to integer");
        }

        IncrementalMagnitude magnitude =
                new IncrementalMagnitude(frame, 1L);
        while (offset < text.length()) {
            char character = text.charAt(offset++);
            if (character < '0' || character > '9') {
                throw new BexException(
                        "Value cannot be converted to integer");
            }
            magnitude.append(character - '0');
        }
        BigInteger integer = magnitude.value();
        return negative ? integer.negate() : integer;
    }

    private static BigInteger exactDecimalInteger(
            CompiledFrame frame,
            BigDecimal decimal) {
        int scale = decimal.scale();
        BigInteger unscaled = decimal.unscaledValue();
        if (scale <= 0) {
            long admittedMagnitudeLimbs =
                    integerLimbs(unscaled);
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    admittedMagnitudeLimbs + 1L);
            boolean negative = unscaled.signum() < 0;
            IncrementalMagnitude magnitude =
                    new IncrementalMagnitude(
                            frame,
                            unscaled.abs(),
                            admittedMagnitudeLimbs);
            for (long remaining = -(long) scale;
                 remaining > 0L;
                 remaining--) {
                magnitude.append(0);
            }
            BigInteger integer = magnitude.value();
            return negative ? integer.negate() : integer;
        }

        /*
         * Exact scale reduction must inspect the unscaled magnitude. Admit
         * every such limb before BigDecimal performs that work; charging only
         * the eventual result limbs would allow a large fractional magnitude
         * to be inspected before a later admission.
         */
        charge(
                frame,
                BexGasCounter.INTEGER_LIMB_OPERATION,
                integerLimbs(unscaled) + 1L);
        BigInteger integer;
        try {
            integer = decimal.toBigIntegerExact();
        } catch (ArithmeticException ex) {
            throw new BexException(
                    "Value cannot be converted to integer");
        }
        return integer;
    }

    private static BigDecimal parseDecimalText(
            CompiledFrame frame,
            String text,
            long admittedLimbs) {
        if (text == null || text.isEmpty()) {
            throw new BexException(
                    "Value cannot be converted to number");
        }
        int offset = 0;
        boolean negative = false;
        char first = text.charAt(offset);
        if (first == '+' || first == '-') {
            negative = first == '-';
            offset++;
        }

        IncrementalMagnitude magnitude =
                new IncrementalMagnitude(
                        frame, admittedLimbs);
        boolean decimalPoint = false;
        boolean digitSeen = false;
        long fractionalDigits = 0L;
        while (offset < text.length()) {
            char character = text.charAt(offset);
            if (character == 'e' || character == 'E') {
                break;
            }
            if (character == '.') {
                if (decimalPoint) {
                    throw new BexException(
                            "Value cannot be converted to number");
                }
                decimalPoint = true;
                offset++;
                continue;
            }
            int digit = Character.digit(character, 10);
            if (digit < 0) {
                throw new BexException(
                        "Value cannot be converted to number");
            }
            digitSeen = true;
            magnitude.append(digit);
            if (decimalPoint) {
                fractionalDigits++;
            }
            offset++;
        }
        if (!digitSeen) {
            throw new BexException(
                    "Value cannot be converted to number");
        }

        long exponent = 0L;
        if (offset < text.length()) {
            offset++;
            boolean exponentNegative = false;
            if (offset < text.length()
                    && (text.charAt(offset) == '+'
                    || text.charAt(offset) == '-')) {
                exponentNegative = text.charAt(offset) == '-';
                offset++;
            }
            if (offset == text.length()) {
                throw new BexException(
                        "Value cannot be converted to number");
            }
            while (offset < text.length()) {
                int digit = Character.digit(
                        text.charAt(offset++), 10);
                if (digit < 0
                        || exponent > (Long.MAX_VALUE - digit) / 10L) {
                    throw new BexException(
                            "Value cannot be converted to number");
                }
                exponent = exponent * 10L + digit;
            }
            if (exponentNegative) {
                exponent = -exponent;
            }
        }

        long scale;
        try {
            scale = Math.subtractExact(
                    fractionalDigits, exponent);
        } catch (ArithmeticException overflow) {
            throw new BexException(
                    "Value cannot be converted to number");
        }
        if (scale < Integer.MIN_VALUE
                || scale > Integer.MAX_VALUE) {
            throw new BexException(
                    "Value cannot be converted to number");
        }
        BigInteger unscaled = magnitude.value();
        if (negative) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, (int) scale);
    }

    private static Object numericScalar(
            BexValue value,
            String target) {
        BexValue exactValue =
                value != null ? value : BexValues.undefined();
        if (!exactValue.isScalar()) {
            throw new BexException(
                    "Value cannot be converted to " + target);
        }
        return exactValue.toSimple();
    }

    private static ScalarTextCursor scalarTextCursor(
            BexValue value) {
        BexValue exactValue =
                value != null ? value : BexValues.undefined();
        if (exactValue.isUndefined() || exactValue.isNull()) {
            return new RawStringCursor("");
        }
        if (!exactValue.isScalar()) {
            throw new BexException(
                    "Value cannot be converted to text");
        }
        return scalarTextCursor(exactValue.toSimple());
    }

    private static ScalarTextCursor scalarTextCursor(
            Object raw) {
        if (raw instanceof String) {
            return new RawStringCursor((String) raw);
        }
        if (raw instanceof BigInteger) {
            return new IntegerTextCursor(
                    (BigInteger) raw);
        }
        if (raw instanceof BigDecimal) {
            return new DecimalTextCursor(
                    (BigDecimal) raw);
        }
        if (isIntegralPrimitive(raw)
                || raw instanceof Float
                || raw instanceof Double
                || raw instanceof Boolean) {
            return new FixedScalarTextCursor(raw);
        }
        throw new BexException(
                "Value cannot be converted to text");
    }

    private static boolean isIntegralPrimitive(Object raw) {
        return raw instanceof Byte
                || raw instanceof Short
                || raw instanceof Integer
                || raw instanceof Long;
    }

    private static boolean isDecimalRaw(Object raw) {
        return raw instanceof BigDecimal
                || raw instanceof Float
                || raw instanceof Double;
    }

    private static int decimalDigits(BigInteger magnitude) {
        if (magnitude.signum() == 0) {
            return 1;
        }
        int bitLength = magnitude.bitLength();
        int estimate = Math.max(
                1,
                (int) Math.floor(
                        (bitLength - 1)
                                * 0.3010299956639812d)
                        + 1);
        BigInteger lower = TEN.pow(estimate - 1);
        while (magnitude.compareTo(lower) < 0) {
            estimate--;
            lower = lower.divide(TEN);
        }
        BigInteger upper = lower.multiply(TEN);
        while (magnitude.compareTo(upper) >= 0) {
            estimate++;
            lower = upper;
            upper = upper.multiply(TEN);
        }
        return estimate;
    }

    private static int decimalDigits(long value) {
        long remaining = value < 0L ? -value : value;
        int digits = 1;
        while (remaining >= 10L) {
            remaining /= 10L;
            digits++;
        }
        return digits;
    }

    private static TextScan chargeTextScan(
            CompiledFrame frame,
            BexGasCounter counter,
            String text,
            int start,
            int end) {
        int offset = start;
        long codePoints = 0L;
        long pointerEscapeExpansions = 0L;
        while (offset < end) {
            charge(frame, counter);
            int inBlock = 0;
            while (inBlock < TEXT_BLOCK_CODE_POINTS
                    && offset < end) {
                char character = text.charAt(offset);
                if (character == '~' || character == '/') {
                    pointerEscapeExpansions++;
                }
                offset += charCountAt(text, offset, end);
                codePoints++;
                inBlock++;
            }
        }
        return new TextScan(
                codePoints,
                pointerEscapeExpansions);
    }

    private static int codePointAt(
            String text,
            int offset,
            int end) {
        char first = text.charAt(offset);
        if (Character.isHighSurrogate(first)
                && offset + 1 < end) {
            char second = text.charAt(offset + 1);
            if (Character.isLowSurrogate(second)) {
                return Character.toCodePoint(
                        first, second);
            }
        }
        return first;
    }

    private static int charCountAt(
            String text,
            int offset,
            int end) {
        char first = text.charAt(offset);
        return Character.isHighSurrogate(first)
                && offset + 1 < end
                && Character.isLowSurrogate(
                text.charAt(offset + 1))
                ? 2
                : 1;
    }

    static final class MeteredText {
        private final String text;
        private final long codePoints;
        private final long pointerEscapeExpansions;

        private MeteredText(
                String text,
                long codePoints,
                long pointerEscapeExpansions) {
            this.text = text;
            this.codePoints = codePoints;
            this.pointerEscapeExpansions =
                    pointerEscapeExpansions;
        }

        String text() {
            return text;
        }

        long codePoints() {
            return codePoints;
        }

        long pointerEscapeExpansions() {
            return pointerEscapeExpansions;
        }
    }

    private static final class TextScan {
        private final long codePoints;
        private final long pointerEscapeExpansions;

        private TextScan(
                long codePoints,
                long pointerEscapeExpansions) {
            this.codePoints = codePoints;
            this.pointerEscapeExpansions =
                    pointerEscapeExpansions;
        }
    }

    static final class PrefixResult {
        private final boolean matched;
        private final ScalarTextCursor text;

        private PrefixResult(
                boolean matched,
                ScalarTextCursor text) {
            this.matched = matched;
            this.text = text;
        }

        boolean matched() {
            return matched;
        }

        String constructSuffix(
                CompiledFrame frame) {
            if (!matched) {
                return "";
            }
            StringBuilder suffix = null;
            while (text.hasNext()) {
                charge(
                        frame,
                        BexGasCounter.TEXT_BLOCK_CONSTRUCTED);
                String block = text.nextBlock(
                        TEXT_BLOCK_CODE_POINTS);
                if (suffix == null) {
                    suffix = new StringBuilder();
                }
                suffix.append(block);
            }
            return suffix != null
                    ? suffix.toString()
                    : "";
        }
    }

    private interface ScalarTextCursor {
        boolean hasNext();

        String nextBlock(int maxCodePoints);
    }

    private static final class RawStringCursor
            implements ScalarTextCursor {
        private final String text;
        private int offset;

        private RawStringCursor(String text) {
            this.text = text;
        }

        @Override
        public boolean hasNext() {
            return offset < text.length();
        }

        @Override
        public String nextBlock(int maxCodePoints) {
            int start = offset;
            int consumed = 0;
            while (consumed < maxCodePoints
                    && offset < text.length()) {
                offset += charCountAt(
                        text, offset, text.length());
                consumed++;
            }
            return text.substring(start, offset);
        }
    }

    private static final class FixedScalarTextCursor
            implements ScalarTextCursor {
        private final Object raw;
        private String text;
        private int offset;

        private FixedScalarTextCursor(Object raw) {
            this.raw = raw;
        }

        @Override
        public boolean hasNext() {
            return text == null || offset < text.length();
        }

        @Override
        public String nextBlock(int maxCodePoints) {
            if (text == null) {
                text = String.valueOf(raw);
            }
            int end = Math.min(
                    text.length(),
                    offset + maxCodePoints);
            String block = text.substring(offset, end);
            offset = end;
            return block;
        }
    }

    private static final class IntegerTextCursor
            implements ScalarTextCursor {
        private final boolean negative;
        private final DecimalDigitsCursor digits;
        private boolean signPending;

        private IntegerTextCursor(BigInteger integer) {
            this.negative = integer.signum() < 0;
            this.signPending = negative;
            this.digits = new DecimalDigitsCursor(integer);
        }

        @Override
        public boolean hasNext() {
            return signPending || digits.hasNext();
        }

        @Override
        public String nextBlock(int maxCodePoints) {
            StringBuilder block =
                    new StringBuilder(maxCodePoints);
            if (signPending && block.length() < maxCodePoints) {
                block.append('-');
                signPending = false;
            }
            if (block.length() < maxCodePoints
                    && digits.hasNext()) {
                block.append(digits.nextDigits(
                        maxCodePoints - block.length()));
            }
            return block.toString();
        }
    }

    private static final class DecimalTextCursor
            implements ScalarTextCursor {
        private static final int LITERAL = 0;
        private static final int DIGITS = 1;
        private static final int ZEROS = 2;
        private static final int EXPONENT = 3;

        private final BigDecimal decimal;
        private final DecimalDigitsCursor digits;
        private List<TextPart> parts;
        private int partIndex;

        private DecimalTextCursor(BigDecimal decimal) {
            this.decimal = decimal;
            this.digits = new DecimalDigitsCursor(
                    decimal.unscaledValue());
        }

        @Override
        public boolean hasNext() {
            if (parts == null) {
                return true;
            }
            skipEmptyParts();
            return partIndex < parts.size();
        }

        @Override
        public String nextBlock(int maxCodePoints) {
            if (parts == null) {
                initialize();
            }
            StringBuilder block =
                    new StringBuilder(maxCodePoints);
            while (block.length() < maxCodePoints) {
                skipEmptyParts();
                if (partIndex >= parts.size()) {
                    break;
                }
                TextPart part = parts.get(partIndex);
                int capacity =
                        maxCodePoints - block.length();
                if (part.kind == DIGITS) {
                    int take = (int) Math.min(
                            part.remaining,
                            (long) capacity);
                    block.append(
                            digits.nextDigits(take));
                    part.remaining -= take;
                } else if (part.kind == ZEROS) {
                    int take = (int) Math.min(
                            part.remaining,
                            (long) capacity);
                    for (int index = 0;
                         index < take;
                         index++) {
                        block.append('0');
                    }
                    part.remaining -= take;
                } else {
                    if (part.text == null) {
                        part.text = part.kind == EXPONENT
                                ? Long.toString(part.exponent)
                                : "";
                    }
                    int take = Math.min(
                            capacity,
                            part.text.length()
                                    - part.textOffset);
                    block.append(
                            part.text,
                            part.textOffset,
                            part.textOffset + take);
                    part.textOffset += take;
                    part.remaining -= take;
                }
            }
            return block.toString();
        }

        private void initialize() {
            parts = new ArrayList<>();
            if (decimal.signum() < 0) {
                parts.add(TextPart.literal("-"));
            }
            long precision = digits.digitCount();
            long scale = decimal.scale();
            long adjustedExponent =
                    -scale + precision - 1L;
            if (scale >= 0L
                    && adjustedExponent >= -6L) {
                if (scale == 0L) {
                    parts.add(TextPart.digits(
                            precision));
                } else if (scale < precision) {
                    parts.add(TextPart.digits(
                            precision - scale));
                    parts.add(TextPart.literal("."));
                    parts.add(TextPart.digits(scale));
                } else {
                    parts.add(TextPart.literal("0."));
                    parts.add(TextPart.zeros(
                            scale - precision));
                    parts.add(TextPart.digits(
                            precision));
                }
                return;
            }

            parts.add(TextPart.digits(1L));
            if (precision > 1L) {
                parts.add(TextPart.literal("."));
                parts.add(TextPart.digits(
                        precision - 1L));
            }
            parts.add(TextPart.literal(
                    adjustedExponent >= 0L
                            ? "E+"
                            : "E-"));
            parts.add(TextPart.exponent(
                    Math.abs(adjustedExponent)));
        }

        private void skipEmptyParts() {
            while (partIndex < parts.size()
                    && parts.get(partIndex).remaining == 0L) {
                partIndex++;
            }
        }
    }

    private static final class DecimalDigitsCursor {
        private final BigInteger signed;
        private BigInteger magnitude;
        private BigInteger emittedPrefix = BigInteger.ZERO;
        private int totalDigits;
        private int emittedDigits;
        private boolean initialized;

        private DecimalDigitsCursor(BigInteger signed) {
            this.signed = signed;
        }

        private boolean hasNext() {
            return !initialized || emittedDigits < totalDigits;
        }

        private int digitCount() {
            initialize();
            return totalDigits;
        }

        private String nextDigits(int maximum) {
            if (maximum <= 0) {
                throw new IllegalArgumentException(
                        "Decimal digit block must be positive");
            }
            initialize();
            int take = Math.min(
                    maximum, totalDigits - emittedDigits);
            int after = totalDigits
                    - emittedDigits
                    - take;

            /*
             * Extract only the prefix ending at this admitted output block.
             * In particular, do not use divideAndRemainder here: its remainder
             * materializes every lower digit even though a later text-block
             * charge may still reject before those digits are examined.
             *
             * Recomputing the progressively longer quotient from the original
             * magnitude is deliberate.  Work for a lower block begins only
             * when nextDigits is called for that block, after its caller has
             * admitted the corresponding text counter.
             */
            BigInteger divisor = TEN.pow(after);
            BigInteger prefixThroughBlock =
                    magnitude.divide(divisor);
            BigInteger blockMagnitude =
                    prefixThroughBlock.subtract(
                            emittedPrefix.multiply(
                                    TEN.pow(take)));
            String digits = blockMagnitude.toString();
            emittedPrefix = prefixThroughBlock;
            emittedDigits += take;
            if (digits.length() == take) {
                return digits;
            }
            StringBuilder padded =
                    new StringBuilder(take);
            for (int index = digits.length();
                 index < take;
                 index++) {
                padded.append('0');
            }
            padded.append(digits);
            return padded.toString();
        }

        private void initialize() {
            if (initialized) {
                return;
            }
            magnitude = signed.signum() < 0
                    ? signed.negate()
                    : signed;
            totalDigits = decimalDigits(
                    magnitude);
            initialized = true;
        }
    }

    private static final class TextPart {
        private final int kind;
        private long remaining;
        private String text;
        private int textOffset;
        private long exponent;

        private TextPart(
                int kind,
                long remaining,
                String text,
                long exponent) {
            this.kind = kind;
            this.remaining = remaining;
            this.text = text;
            this.exponent = exponent;
        }

        private static TextPart literal(String value) {
            return new TextPart(
                    DecimalTextCursor.LITERAL,
                    value.length(),
                    value,
                    0L);
        }

        private static TextPart digits(long count) {
            return new TextPart(
                    DecimalTextCursor.DIGITS,
                    count,
                    null,
                    0L);
        }

        private static TextPart zeros(long count) {
            return new TextPart(
                    DecimalTextCursor.ZEROS,
                    count,
                    null,
                    0L);
        }

        private static TextPart exponent(long value) {
            return new TextPart(
                    DecimalTextCursor.EXPONENT,
                    decimalDigits(value),
                    null,
                    value);
        }
    }

    private static final class IncrementalMagnitude {
        private final CompiledFrame frame;
        private BigInteger value;
        private long admittedLimbs;

        private IncrementalMagnitude(
                CompiledFrame frame,
                long admittedLimbs) {
            this(frame, BigInteger.ZERO, admittedLimbs);
        }

        private IncrementalMagnitude(
                CompiledFrame frame,
                BigInteger value,
                long admittedLimbs) {
            this.frame = frame;
            this.value = value;
            this.admittedLimbs = admittedLimbs;
        }

        private void append(int digit) {
            if (wouldGrow(digit)) {
                charge(
                        frame,
                        BexGasCounter.INTEGER_LIMB_OPERATION);
                admittedLimbs++;
            }
            value = value.multiply(TEN)
                    .add(BigInteger.valueOf(digit));
        }

        private boolean wouldGrow(int digit) {
            long admittedBits = admittedLimbs * 32L;
            if (admittedBits > Integer.MAX_VALUE) {
                throw new BexException(
                        "Integer magnitude exceeds supported range");
            }
            if ((long) value.bitLength() + 4L
                    <= admittedBits) {
                return false;
            }
            BigInteger boundary = BigInteger.ONE.shiftLeft(
                    (int) admittedBits);
            BigInteger largestWithoutGrowth =
                    boundary.subtract(
                            BigInteger.ONE
                                    .add(BigInteger.valueOf(digit)))
                            .divide(TEN);
            return value.compareTo(
                    largestWithoutGrowth) > 0;
        }

        private BigInteger value() {
            return value;
        }
    }
}

abstract class Expr implements CompiledExpression {
    @Override
    public final BexValue eval(CompiledFrame frame) {
        BexGasWork.charge(frame, BexGasCounter.EXPRESSION_EVALUATED);
        frame.runtime().metrics().incrementExpressionEvaluations();
        try {
            return doEval(frame);
        } catch (BexException ex) {
            BexSourcePath sourcePath = frame.sourcePath();
            if (sourcePath != null && !ex.sourcePath().isPresent()) {
                throw ex.withSourcePath(sourcePath);
            }
            throw ex;
        }
    }

    protected abstract BexValue doEval(CompiledFrame frame);
}

final class SourceExpr implements CompiledExpression {
    private final BexSourcePath sourcePath;
    private final CompiledExpression delegate;

    SourceExpr(BexSourcePath sourcePath, CompiledExpression delegate) {
        this.sourcePath = sourcePath;
        this.delegate = delegate;
    }

    @Override
    public BexValue eval(CompiledFrame frame) {
        BexSourcePath previous = frame.enter(sourcePath);
        try {
            return delegate.eval(frame);
        } catch (BexException ex) {
            if (!ex.sourcePath().isPresent()) {
                throw ex.withSourcePath(sourcePath);
            }
            throw ex;
        } finally {
            frame.restore(previous);
        }
    }
}

final class LiteralExpr extends Expr {
    private final BexValue value;

    LiteralExpr(BexValue value) {
        this.value = value;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return value;
    }
}

final class TransientLiteralExpr extends Expr {
    private final Object scalar;

    TransientLiteralExpr(Object scalar) {
        this.scalar = scalar;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        if (scalar instanceof String) {
            BexGasWork.chargeTextConstruction(
                    frame, (String) scalar);
        }
        return BexValues.scalar(scalar);
    }
}

final class DocumentExpr extends Expr {
    private final PointerOperand pointer;
    private final boolean resolved;

    DocumentExpr(PointerOperand pointer, boolean resolved) {
        this.pointer = pointer;
        this.resolved = resolved;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        ResolvedPointer resolvedPointer = pointer.resolve(frame);
        return frame.readDocument(resolvedPointer.absolute(), resolvedPointer.segments(), resolved);
    }
}

enum ContextKind { EVENT, PROCESSING_EVENT, CURRENT_CONTRACT }

final class ContextPointerExpr extends Expr {
    private final PointerOperand pointer;
    private final ContextKind kind;

    ContextPointerExpr(PointerOperand pointer, ContextKind kind) {
        this.pointer = pointer;
        this.kind = kind;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        List<String> segments = pointer.segments(frame);
        if (kind == ContextKind.EVENT) {
            return frame.readEvent(segments);
        }
        if (kind == ContextKind.PROCESSING_EVENT) {
            return frame.readProcessingEvent(segments);
        }
        return frame.readCurrentContract(segments);
    }
}

final class StepsExpr extends Expr {
    private final TextOperand step;
    private final PointerOperand pointer;

    StepsExpr(TextOperand step, PointerOperand pointer) {
        this.step = step;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        String stepName = step.get(frame);
        if (stepName.isEmpty()) {
            throw new BexException("$steps.step cannot be empty");
        }
        return frame.runtime().readSteps(stepName, pointer.segments(frame));
    }
}

final class BindingExpr extends Expr {
    private final TextOperand name;
    private final PointerOperand pointer;

    BindingExpr(TextOperand name, PointerOperand pointer) {
        this.name = name;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        String bindingName = name.get(frame);
        if (bindingName.isEmpty()) {
            throw new BexException("$binding.name cannot be empty");
        }
        return frame.readBinding(bindingName, pointer.segments(frame));
    }
}

final class VarExpr extends Expr {
    private final int slot;
    private final PointerOperand pointer;

    VarExpr(int slot) {
        this(slot, null);
    }

    VarExpr(int slot, PointerOperand pointer) {
        this.slot = slot;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexGasWork.charge(frame, BexGasCounter.VARIABLE_READ);
        BexValue value = frame.getRequired(slot);
        return pointer != null
                ? frame.runtime().readValuePointer(value, pointer.segments(frame))
                : value;
    }
}

final class ConstExpr extends Expr {
    private final String name;
    private final PointerOperand pointer;

    ConstExpr(String name) {
        this(name, null);
    }

    ConstExpr(String name, PointerOperand pointer) {
        this.name = name;
        this.pointer = pointer;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexGasWork.charge(frame, BexGasCounter.CONSTANT_READ);
        BexValue value = frame.runtime().program().constant(name);
        return pointer != null
                ? frame.runtime().readValuePointer(value, pointer.segments(frame))
                : value;
    }
}

final class GetExpr extends Expr {
    private final CompiledExpression object;
    private final TextOperand key;

    GetExpr(CompiledExpression object, TextOperand key) {
        this.object = object;
        this.key = key;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        BexValue value = object.eval(frame);
        String evaluatedKey = key.get(frame);
        if (value.isObject()) {
            BexGasWork.charge(frame, BexGasCounter.OBJECT_MEMBER_READ);
        }
        return value.get(evaluatedKey);
    }
}

final class ObjectExpr extends Expr {
    private final Map<String, CompiledExpression> fields;

    ObjectExpr(Map<String, CompiledExpression> fields) {
        this.fields = new LinkedHashMap<>();
        for (String key : BexUnicodeOrder.sortedCopy(fields.keySet())) {
            this.fields.put(key, fields.get(key));
        }
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        Map<String, BexValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledExpression> entry : fields.entrySet()) {
            BexValue value = entry.getValue().eval(frame);
            if (!value.isUndefined()) {
                BexGasWork.charge(frame, BexGasCounter.TRANSIENT_OBJECT_MEMBER_PRODUCED);
                out.put(entry.getKey(), value);
            }
        }
        return BexValues.map(out);
    }
}

final class ListExpr extends Expr {
    private final List<CompiledExpression> items;

    ListExpr(List<CompiledExpression> items) {
        this.items = items;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        List<BexValue> out = new ArrayList<>();
        for (CompiledExpression item : items) {
            BexValue value = item.eval(frame);
            if (value.isUndefined()) {
                throw new BexException(
                        "Undefined cannot appear in a Blue list");
            }
            BexGasWork.charge(frame, BexGasCounter.TRANSIENT_LIST_ITEM_PRODUCED);
            out.add(value);
        }
        return BexValues.list(out);
    }
}

final class IntrinsicExpr extends Expr {
    private final String blueId;
    private final BexValue type;
    private final Map<String, CompiledExpression> fields;

    IntrinsicExpr(String blueId, BexValue type, Map<String, CompiledExpression> fields) {
        this.blueId = blueId;
        this.type = type;
        this.fields = new LinkedHashMap<>();
        for (String key : BexUnicodeOrder.sortedCopy(fields.keySet())) {
            this.fields.put(key, fields.get(key));
        }
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        Map<String, BexValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledExpression> entry : fields.entrySet()) {
            BexValue value = entry.getValue().eval(frame);
            if (!value.isUndefined()) {
                values.put(entry.getKey(), value);
            }
        }
        BexGasWork.charge(frame, BexGasCounter.INTRINSIC_CALLED);
        return frame.runtime().invokeIntrinsic(blueId, type, values);
    }
}

final class FailExpr extends Expr {
    private final CompiledExpression message;

    FailExpr(CompiledExpression message) {
        this.message = message;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        throw new BexException(message.eval(frame).asText());
    }
}

final class NodeBlueIdExpr extends Expr {
    private final CompiledExpression expression;

    NodeBlueIdExpr(CompiledExpression expression) {
        this.expression = expression;
    }

    @Override
    protected BexValue doEval(CompiledFrame frame) {
        return frame.runtime().nodeBlueId(expression.eval(frame));
    }
}
