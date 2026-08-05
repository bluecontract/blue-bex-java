package blue.bex.buildlogic.tasks;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal strict JSON reader for release evidence supplied to build tasks. */
final class StrictJson {
    private StrictJson() {
    }

    static Map<String, Object> object(String text) {
        Object value = new Parser(text).parse();
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON root must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    static Map<String, Object> object(
            Map<String, Object> parent,
            String field) {
        Object value = parent.get(field);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    static List<Object> array(
            Map<String, Object> parent,
            String field) {
        Object value = parent.get(field);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) value;
        return result;
    }

    static String string(
            Map<String, Object> parent,
            String field) {
        Object value = parent.get(field);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return (String) value;
    }

    static boolean bool(
            Map<String, Object> parent,
            String field) {
        Object value = parent.get(field);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return (Boolean) value;
    }

    static long integer(
            Map<String, Object> parent,
            String field) {
        Object value = parent.get(field);
        if (!(value instanceof BigDecimal)) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        try {
            return ((BigDecimal) value).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    field + " must be an integer", exception);
        }
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char next = value.charAt(index);
            switch (next) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (next < 0x20) {
                        result.append(String.format("\\u%04x", (int) next));
                    } else {
                        result.append(next);
                    }
            }
        }
        return result.append('"').toString();
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;

        private final String text;
        private int index;

        private Parser(String text) {
            if (text == null) {
                throw new IllegalArgumentException("JSON text is required");
            }
            this.text = text;
        }

        private Object parse() {
            skipWhitespace();
            Object value = value(0);
            skipWhitespace();
            if (index != text.length()) {
                fail("trailing content");
            }
            return value;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                fail("nesting exceeds " + MAX_DEPTH);
            }
            if (index >= text.length()) {
                fail("unexpected end of input");
            }
            char next = text.charAt(index);
            if (next == '{') {
                return object(depth + 1);
            }
            if (next == '[') {
                return array(depth + 1);
            }
            if (next == '"') {
                return string();
            }
            if (next == 't') {
                literal("true");
                return Boolean.TRUE;
            }
            if (next == 'f') {
                literal("false");
                return Boolean.FALSE;
            }
            if (next == 'n') {
                literal("null");
                return null;
            }
            if (next == '-' || isDigit(next)) {
                return number();
            }
            fail("unexpected character");
            return null;
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (take('}')) {
                return result;
            }
            while (true) {
                if (index >= text.length() || text.charAt(index) != '"') {
                    fail("object key must be a string");
                }
                String key = string();
                if (result.containsKey(key)) {
                    fail("duplicate object key " + key);
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                result.put(key, value(depth));
                skipWhitespace();
                if (take('}')) {
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(value(depth));
                skipWhitespace();
                if (take(']')) {
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char next = text.charAt(index++);
                if (next == '"') {
                    return result.toString();
                }
                if (next < 0x20) {
                    fail("unescaped control character in string");
                }
                if (next != '\\') {
                    result.append(next);
                    continue;
                }
                if (index >= text.length()) {
                    fail("incomplete string escape");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        result.append(escaped);
                        break;
                    case 'b':
                        result.append('\b');
                        break;
                    case 'f':
                        result.append('\f');
                        break;
                    case 'n':
                        result.append('\n');
                        break;
                    case 'r':
                        result.append('\r');
                        break;
                    case 't':
                        result.append('\t');
                        break;
                    case 'u':
                        result.append(unicode());
                        break;
                    default:
                        fail("invalid string escape");
                }
            }
            fail("unterminated string");
            return "";
        }

        private char unicode() {
            if (index + 4 > text.length()) {
                fail("incomplete unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) {
                    fail("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private BigDecimal number() {
            int start = index;
            take('-');
            if (take('0')) {
                if (index < text.length() && isDigit(text.charAt(index))) {
                    fail("number has a leading zero");
                }
            } else {
                digits("integer digits are required");
            }
            if (take('.')) {
                digits("fraction digits are required");
            }
            if (take('e') || take('E')) {
                if (!take('+')) {
                    take('-');
                }
                digits("exponent digits are required");
            }
            try {
                return new BigDecimal(text.substring(start, index));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Invalid JSON number at offset " + start, exception);
            }
        }

        private void digits(String message) {
            int start = index;
            while (index < text.length() && isDigit(text.charAt(index))) {
                index++;
            }
            if (start == index) {
                fail(message);
            }
        }

        private void literal(String expected) {
            if (!text.regionMatches(index, expected, 0, expected.length())) {
                fail("expected " + expected);
            }
            index += expected.length();
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char next = text.charAt(index);
                if (next != ' ' && next != '\n'
                        && next != '\r' && next != '\t') {
                    return;
                }
                index++;
            }
        }

        private boolean take(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                fail("expected " + expected);
            }
        }

        private void fail(String message) {
            throw new IllegalArgumentException(
                    "Invalid JSON at offset " + index + ": " + message);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }
    }
}
