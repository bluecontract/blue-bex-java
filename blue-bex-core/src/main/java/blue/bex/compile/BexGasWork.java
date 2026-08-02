package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.math.BigDecimal;
import java.math.BigInteger;

final class BexGasWork {
    private static final int TEXT_BLOCK_CODE_POINTS = 64;

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
        frame.machine().gas().charge(counter,
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
            TextScan scan = BexGasWorkSupport.chargeTextScan(
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

        BexGasWorkSupport.ScalarTextCursor cursor = BexGasWorkSupport.scalarTextCursor(raw);
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
        BexGasWorkSupport.chargeTextScan(
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
                int leftCodePoint = BexGasWorkSupport.codePointAt(
                        left, leftOffset, left.length());
                int rightCodePoint = BexGasWorkSupport.codePointAt(
                        right, rightOffset, right.length());
                leftOffset += BexGasWorkSupport.charCountAt(
                        left, leftOffset, left.length());
                rightOffset += BexGasWorkSupport.charCountAt(
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
        BexGasWorkSupport.ScalarTextCursor leftCursor =
                BexGasWorkSupport.scalarTextCursor(left);
        BexGasWorkSupport.ScalarTextCursor rightCursor =
                BexGasWorkSupport.scalarTextCursor(right);
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
        BexGasWorkSupport.ScalarTextCursor textCursor =
                BexGasWorkSupport.scalarTextCursor(text);
        BexGasWorkSupport.ScalarTextCursor prefixCursor =
                BexGasWorkSupport.scalarTextCursor(prefix);
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
        Object raw = BexGasWorkSupport.numericScalar(value, "integer");
        if (raw instanceof BigInteger) {
            BigInteger integer = (BigInteger) raw;
            charge(
                    frame,
                    BexGasCounter.INTEGER_LIMB_OPERATION,
                    integerLimbs(integer));
            return integer;
        }
        if (BexGasWorkSupport.isIntegralPrimitive(raw)) {
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
        Object raw = BexGasWorkSupport.numericScalar(value, "integer");
        if (raw instanceof BigInteger) {
            return (BigInteger) raw;
        }
        if (BexGasWorkSupport.isIntegralPrimitive(raw)) {
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
        Object raw = BexGasWorkSupport.numericScalar(value, "number");
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
        if (BexGasWorkSupport.isIntegralPrimitive(raw)) {
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
        Object leftRaw = BexGasWorkSupport.numericScalar(left, "number");
        Object rightRaw = BexGasWorkSupport.numericScalar(right, "number");
        long base = 2L;
        if (decimalAlignment
                && (BexGasWorkSupport.isDecimalRaw(leftRaw)
                || BexGasWorkSupport.isDecimalRaw(rightRaw))) {
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
        if (BexGasWorkSupport.isIntegralPrimitive(raw)) {
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

        BexGasWorkSupport.IncrementalMagnitude magnitude =
                new BexGasWorkSupport.IncrementalMagnitude(frame, 1L);
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
            BexGasWorkSupport.IncrementalMagnitude magnitude =
                    new BexGasWorkSupport.IncrementalMagnitude(
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

        BexGasWorkSupport.IncrementalMagnitude magnitude =
                new BexGasWorkSupport.IncrementalMagnitude(
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

    static final class TextScan {
        private final long codePoints;
        private final long pointerEscapeExpansions;

        TextScan(
                long codePoints,
                long pointerEscapeExpansions) {
            this.codePoints = codePoints;
            this.pointerEscapeExpansions =
                    pointerEscapeExpansions;
        }
    }

    static final class PrefixResult {
        private final boolean matched;
        private final BexGasWorkSupport.ScalarTextCursor text;

        private PrefixResult(
                boolean matched,
                BexGasWorkSupport.ScalarTextCursor text) {
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

}
