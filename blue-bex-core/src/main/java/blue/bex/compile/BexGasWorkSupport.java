package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Scalar conversion and incremental text cursor support for gas work. */
final class BexGasWorkSupport {
    private static final int TEXT_BLOCK_CODE_POINTS = 64;
    private static final BigInteger TEN = BigInteger.TEN;

    private BexGasWorkSupport() {
    }

    static Object numericScalar(
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

    static ScalarTextCursor scalarTextCursor(
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

    static ScalarTextCursor scalarTextCursor(
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

    static boolean isIntegralPrimitive(Object raw) {
        return raw instanceof Byte
                || raw instanceof Short
                || raw instanceof Integer
                || raw instanceof Long;
    }

    static boolean isDecimalRaw(Object raw) {
        return raw instanceof BigDecimal
                || raw instanceof Float
                || raw instanceof Double;
    }

    static int decimalDigits(BigInteger magnitude) {
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

    static int decimalDigits(long value) {
        long remaining = value < 0L ? -value : value;
        int digits = 1;
        while (remaining >= 10L) {
            remaining /= 10L;
            digits++;
        }
        return digits;
    }

    static BexGasWork.TextScan chargeTextScan(
            CompiledFrame frame,
            BexGasCounter counter,
            String text,
            int start,
            int end) {
        int offset = start;
        long codePoints = 0L;
        long pointerEscapeExpansions = 0L;
        while (offset < end) {
            BexGasWork.charge(frame, counter);
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
        return new BexGasWork.TextScan(
                codePoints,
                pointerEscapeExpansions);
    }

    static int codePointAt(
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

    static int charCountAt(
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


    interface ScalarTextCursor {
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

    static final class IncrementalMagnitude {
        private final CompiledFrame frame;
        private BigInteger value;
        private long admittedLimbs;

        IncrementalMagnitude(
                CompiledFrame frame,
                long admittedLimbs) {
            this(frame, BigInteger.ZERO, admittedLimbs);
        }

        IncrementalMagnitude(
                CompiledFrame frame,
                BigInteger value,
                long admittedLimbs) {
            this.frame = frame;
            this.value = value;
            this.admittedLimbs = admittedLimbs;
        }

        void append(int digit) {
            if (wouldGrow(digit)) {
                BexGasWork.charge(
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

        BigInteger value() {
            return value;
        }
    }
}
