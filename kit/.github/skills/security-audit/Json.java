import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What the two validators share: a strict JSON reader that behaves like JavaScript's {@code JSON.parse}, and the few JavaScript behaviours
 * the validation rules were written against (so the rules give the same answers as the vendored originals).
 *
 * <p>A JSON value is a {@code Map<String,Object>} (an object, in insertion order), a {@code List<Object>}, a {@code String}, a {@code Double}
 * (every number is a double, as in JavaScript), a {@code Boolean} or {@link #NULL}. A Java {@code null} is JavaScript's {@code undefined}: a
 * property that is not there. Runs as part of {@code java ValidateFindings.java} (Java 25 compiles the other source files of the folder).
 */
final class Json {

    /** The JSON value {@code null}. */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** The input is not JSON. */
    static final class SyntaxError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyntaxError(String message) {
            super(message, null, false, false);
        }
    }

    private Json() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------------

    static Object parse(String text) {
        return new Reader(text).parseDocument();
    }

    /** {@code JSON.parse}: strict RFC 8259 text, duplicate keys keep the last value in the first position, lone surrogate escapes are kept. */
    private static final class Reader {
        private static final int MAX_DEPTH = 10_000;
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        Object parseDocument() {
            skipWhitespace();
            Object value = value(0);
            skipWhitespace();
            if (i != s.length()) {
                throw fail();
            }
            return value;
        }

        private SyntaxError fail() {
            return new SyntaxError("invalid JSON syntax");
        }

        private void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    return;
                }
            }
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH || i >= s.length()) {
                throw fail();
            }
            return switch (s.charAt(i)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", NULL);
                default -> number();
            };
        }

        private Object literal(String word, Object result) {
            if (!s.startsWith(word, i)) {
                throw fail();
            }
            i += word.length();
            return result;
        }

        private Object number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
            }
            if (i >= s.length()) {
                throw fail();
            }
            if (s.charAt(i) == '0') {
                i++;
            } else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') {
                digits();
            } else {
                throw fail();
            }
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (digits() == 0) {
                    throw fail();
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                if (digits() == 0) {
                    throw fail();
                }
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private int digits() {
            int start = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            return i - start;
        }

        private String string() {
            i++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw fail();
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw fail();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw fail();
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw fail();
                        }
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = Character.digit(s.charAt(i + k), 16);
                            if (digit < 0 || s.charAt(i + k) > 'f') {
                                throw fail();
                            }
                            code = code * 16 + digit;
                        }
                        i += 4;
                        out.append((char) code);
                    }
                    default -> throw fail();
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            i++;
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                if (i >= s.length()) {
                    throw fail();
                }
                char c = s.charAt(i++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw fail();
                }
            }
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw fail();
                }
                String key = string();
                skipWhitespace();
                if (i >= s.length() || s.charAt(i++) != ':') {
                    throw fail();
                }
                skipWhitespace();
                map.put(key, value(depth + 1));
                skipWhitespace();
                if (i >= s.length()) {
                    throw fail();
                }
                char c = s.charAt(i++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw fail();
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Values, the way JavaScript sees them
    // ---------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value) {
        return (List<Object>) value;
    }

    /** An object that is not an array and not null: {@code isObject} of the originals. */
    static boolean isObject(Object value) {
        return value instanceof Map;
    }

    static boolean isArray(Object value) {
        return value instanceof List;
    }

    /** JavaScript's {@code typeof value === "object"}: objects, arrays and null. */
    static boolean typeofObject(Object value) {
        return value instanceof Map || value instanceof List || value == NULL;
    }

    static boolean hasOwn(Object value, String key) {
        return value instanceof Map<?, ?> map && map.containsKey(key);
    }

    /** {@code value[key]} of an object; {@code undefined} (Java null) for anything else or a missing key. */
    static Object get(Object value, String key) {
        return value instanceof Map<?, ?> map ? map.get(key) : null;
    }

    static boolean truthy(Object value) {
        if (value == null || value == NULL || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Double number) {
            return number != 0 && !number.isNaN();
        }
        return true;
    }

    /** {@code Object.keys}: integer-like keys first in ascending order, then the others in insertion order. */
    static List<String> keys(Map<String, Object> map) {
        List<String> indexes = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String key : map.keySet()) {
            (isArrayIndex(key) ? indexes : others).add(key);
        }
        if (indexes.isEmpty()) {
            return others;
        }
        indexes.sort((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
        indexes.addAll(others);
        return indexes;
    }

    private static boolean isArrayIndex(String key) {
        if (key.isEmpty() || key.length() > 10 || (key.length() > 1 && key.charAt(0) == '0')) {
            return false;
        }
        for (int k = 0; k < key.length(); k++) {
            if (key.charAt(k) < '0' || key.charAt(k) > '9') {
                return false;
            }
        }
        return Long.parseLong(key) < 4_294_967_295L;
    }

    /** The {@code typeOf} of the originals: array, null, or {@code typeof}. */
    static String typeOf(Object value) {
        if (value instanceof List) {
            return "array";
        }
        if (value == NULL) {
            return "null";
        }
        if (value == null) {
            return "undefined";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Double) {
            return "number";
        }
        return "boolean";
    }

    static boolean isInteger(Object value) {
        return value instanceof Double d && !d.isInfinite() && !d.isNaN() && d == Math.floor(d);
    }

    static boolean isFiniteNumber(Object value) {
        return value instanceof Double d && !d.isInfinite() && !d.isNaN();
    }

    static boolean deepEqual(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (!typeOf(left).equals(typeOf(right))) {
            return false;
        }
        if (left instanceof List<?> l) {
            List<?> r = (List<?>) right;
            if (l.size() != r.size()) {
                return false;
            }
            for (int k = 0; k < l.size(); k++) {
                if (!deepEqual(l.get(k), r.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Map<?, ?> l) {
            Map<?, ?> r = (Map<?, ?>) right;
            if (l.size() != r.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : l.entrySet()) {
                if (!r.containsKey(entry.getKey()) || !deepEqual(entry.getValue(), r.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Double l) {
            return l.doubleValue() == ((Double) right).doubleValue();
        }
        return left.equals(right);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Text, the way JavaScript sees it
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code String(number)} of ECMAScript. */
    static String numberToString(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == 0) {
            return "0";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        String sign = value < 0 ? "-" : "";
        // The fewest digits that read back as the same number (Java's own text always has two digits, which is not the shortest for 5e-324).
        BigDecimal exact = new BigDecimal(Math.abs(value));
        BigDecimal shortest = exact;
        for (int digitCount = 1; digitCount <= 17; digitCount++) {
            BigDecimal rounded = exact.round(new java.math.MathContext(digitCount, java.math.RoundingMode.HALF_EVEN));
            if (rounded.doubleValue() == Math.abs(value)) {
                shortest = rounded;
                break;
            }
        }
        shortest = shortest.stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        int n = k - shortest.scale();
        String body;
        if (k <= n && n <= 21) {
            body = digits + "0".repeat(n - k);
        } else if (0 < n && n <= 21) {
            body = digits.substring(0, n) + "." + digits.substring(n);
        } else if (-6 < n && n <= 0) {
            body = "0." + "0".repeat(-n) + digits;
        } else {
            int exponent = n - 1;
            String tail = (exponent < 0 ? "-" : "+") + Math.abs(exponent);
            body = k == 1 ? digits + "e" + tail : digits.charAt(0) + "." + digits.substring(1) + "e" + tail;
        }
        return sign + body;
    }

    /** {@code JSON.stringify} of a string: well-formed, so a lone surrogate is written as an escape. */
    static String stringify(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else if (Character.isHighSurrogate(c) && k + 1 < text.length() && Character.isLowSurrogate(text.charAt(k + 1))) {
                        out.append(c).append(text.charAt(++k));
                    } else if (Character.isSurrogate(c)) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** {@code /\s/} of JavaScript. */
    static boolean isJsSpace(int c) {
        return c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r' || c == ' ' || c == 0xa0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000 || c == 0xfeff;
    }

    /** {@code String.prototype.trim}. */
    static String jsTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isJsSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isJsSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** The Unicode property {@code White_Space}. */
    static boolean isUnicodeWhiteSpace(int c) {
        return (c >= 0x09 && c <= 0x0d) || c == 0x20 || c == 0x85 || c == 0xa0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200a)
                || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000;
    }

    /** The Unicode property {@code Default_Ignorable_Code_Point} (Unicode 16; Java has no such class). */
    static boolean isDefaultIgnorable(int c) {
        return c == 0x00ad || c == 0x034f || c == 0x061c || (c >= 0x115f && c <= 0x1160) || (c >= 0x17b4 && c <= 0x17b5)
                || (c >= 0x180b && c <= 0x180f) || (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e)
                || (c >= 0x2060 && c <= 0x206f) || c == 0x3164 || (c >= 0xfe00 && c <= 0xfe0f) || c == 0xfeff || c == 0xffa0
                || (c >= 0xfff0 && c <= 0xfff8) || (c >= 0x1bca0 && c <= 0x1bca3) || (c >= 0x1d173 && c <= 0x1d17a)
                || (c >= 0xe0000 && c <= 0xe0fff);
    }

    private static boolean isCc(int c) {
        return Character.getType(c) == Character.CONTROL;
    }

    private static boolean isCf(int c) {
        return Character.getType(c) == Character.FORMAT;
    }

    private static boolean isCs(int c) {
        return Character.getType(c) == Character.SURROGATE;
    }

    private static boolean isZlOrZp(int c) {
        int type = Character.getType(c);
        return type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    /** {@code UNSAFE_DIAGNOSTIC_CHARACTER}: Cc, Cf, Cs, Zl, Zp or default-ignorable. */
    static boolean isUnsafeDiagnostic(int c) {
        return isCc(c) || isCf(c) || isCs(c) || isZlOrZp(c) || isDefaultIgnorable(c);
    }

    /** {@code PATH_FORBIDDEN_CHARACTER}: Cc, Cf, Zl, Zp or default-ignorable. */
    static boolean isPathForbidden(int c) {
        return isCc(c) || isCf(c) || isZlOrZp(c) || isDefaultIgnorable(c);
    }

    static boolean anyPathForbidden(String text) {
        return text.codePoints().anyMatch(Json::isPathForbidden);
    }

    /** {@code VISIBLE_CONTENT.test}: some code point that is not white space, a control, a format or a default-ignorable character. */
    static boolean hasVisibleCharacter(String text) {
        return text.codePoints().anyMatch(c -> !isUnicodeWhiteSpace(c) && !isCc(c) && !isCf(c) && !isDefaultIgnorable(c));
    }

    /** {@code escapeUnsafeDiagnosticCharacters}. */
    static String escapeUnsafe(String text) {
        StringBuilder out = null;
        int k = 0;
        while (k < text.length()) {
            int cp = text.codePointAt(k);
            int width = Character.charCount(cp);
            if (isUnsafeDiagnostic(cp)) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16).append(text, 0, k);
                }
                out.append(cp <= 0xffff ? "\\u%04x".formatted(cp) : "\\u{%x}".formatted(cp));
            } else if (out != null) {
                out.append(text, k, k + width);
            }
            k += width;
        }
        return out == null ? text : out.toString();
    }

    static boolean hasValidUnicodeScalarValues(String text) {
        int k = 0;
        while (k < text.length()) {
            char first = text.charAt(k++);
            if (Character.isHighSurrogate(first)) {
                if (k >= text.length() || !Character.isLowSurrogate(text.charAt(k++))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(first)) {
                return false;
            }
        }
        return true;
    }

    static boolean hasVisibleProse(String text) {
        return hasValidUnicodeScalarValues(text) && hasVisibleCharacter(text);
    }

    static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    /** {@code Buffer.byteLength}: a lone surrogate counts as three bytes, as the replacement character it is written as. */
    static int utf8Length(String text) {
        int bytes = 0;
        for (int k = 0; k < text.length(); ) {
            int cp = text.codePointAt(k);
            k += Character.charCount(cp);
            bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        }
        return bytes;
    }

    static String normalizeNfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /** {@code safeQuote} of the originals: a value shown in a message, clipped and made safe. */
    static String safeQuote(Object value) {
        String serialized;
        if (value instanceof String text) {
            String clipped = text.length() > 256 ? text.substring(0, 256) + "..." : text;
            serialized = stringify(clipped);
        } else if (value == NULL) {
            serialized = "null";
        } else if (value instanceof Boolean flag) {
            serialized = flag.toString();
        } else if (isFiniteNumber(value)) {
            serialized = numberToString((Double) value);
        } else {
            serialized = "\"<" + (value instanceof List ? "array" : typeOf(value).equals("undefined") ? "undefined" : "object") + ">\"";
        }
        return escapeUnsafe(serialized);
    }

    /** An identifier-like key is shown as {@code base.key}, any other as {@code base["key"]}. */
    static String propertyPath(String base, String key) {
        return key.matches("[A-Za-z_][A-Za-z0-9_]*") ? base + "." + key : base + "[" + safeQuote(key) + "]";
    }

    /**
     * A regular expression of the originals ({@code new RegExp(source)}) as a Java pattern: a {@code $} outside a character class means the end
     * of the text only (Java's would also match before a final line break).
     */
    static Pattern compile(String source, int flags) {
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        for (int k = 0; k < source.length(); k++) {
            char c = source.charAt(k);
            if (c == '\\' && k + 1 < source.length()) {
                out.append(c).append(source.charAt(++k));
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            }
            out.append(c == '$' && !inClass ? "\\z" : String.valueOf(c));
        }
        return Pattern.compile(out.toString(), flags);
    }

    /** Whether a schema pattern is a regular expression; {@code false} when Java cannot compile it. */
    static boolean isRegex(String source) {
        try {
            compile(source, 0);
            return true;
        } catch (PatternSyntaxException _) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading a file the way the originals do (minus the operating-system limits: Java can refuse a link on every system)
    // ---------------------------------------------------------------------------------------------------------------

    /** The input cannot be read safely; the message says why. */
    static final class SafeInputError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SafeInputError(String message) {
            super(message, null, false, false);
        }
    }

    /** The text of a regular file that is not a link, within the size limit, as strict UTF-8 (a byte order mark is kept). */
    static String readWithinLimit(java.nio.file.Path file, int limitBytes) {
        try {
            var attributes = java.nio.file.Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new SafeInputError("input must not be a symlink");
            }
            if (!attributes.isRegularFile()) {
                throw new SafeInputError("input must be a regular file");
            }
            if (attributes.size() > limitBytes) {
                throw new SafeInputError("input exceeds " + limitBytes + " byte limit");
            }
            try (var channel = java.nio.file.Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.allocate(64 * 1024);
                var bytes = new java.io.ByteArrayOutputStream();
                int total = 0;
                int count;
                while ((count = channel.read(buffer)) > 0) {
                    total += count;
                    if (total > limitBytes) {
                        throw new SafeInputError("input exceeds " + limitBytes + " byte limit");
                    }
                    bytes.write(buffer.array(), 0, count);
                    buffer.clear();
                }
                try {
                    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                } catch (java.nio.charset.CharacterCodingException _) {
                    throw new SafeInputError("input is not valid UTF-8");
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            if (e instanceof SafeInputError safe) {
                throw safe;
            }
            throw new java.io.UncheckedIOException(new java.io.IOException("input could not be opened or read safely"));
        }
    }

    /** The folder of the source file being run ({@code java <folder>/ValidateFindings.java}), or null when it cannot be told. */
    static java.nio.file.Path scriptFolder() {
        String command = System.getProperty("sun.java.command", "");
        int launcher = command.indexOf("SourceLauncher ");
        if (launcher < 0) {
            return null;
        }
        String rest = command.substring(launcher + "SourceLauncher ".length());
        int end = rest.indexOf(".java");
        if (end < 0) {
            return null;
        }
        java.nio.file.Path parent = java.nio.file.Path.of(rest.substring(0, end + ".java".length())).toAbsolutePath().getParent();
        return parent;
    }
}
