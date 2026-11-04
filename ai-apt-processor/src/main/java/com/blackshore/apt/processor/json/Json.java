package com.blackshore.apt.processor.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写器。注解处理器需要被打进 `-processorpath` 单独运行，
 * 因此这里不引入 Jackson/Gson 等第三方依赖，只用 JDK。
 */
public final class Json {

    private Json() {
    }

    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    /** 解析 JSON 文本，返回 Map / List / String / Long / Double / Boolean / null。 */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseDocument();
        parser.skipWhitespace();
        if (!parser.eof()) {
            parser.error("文档结尾存在多余内容");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("期望 JSON 对象，实际为: " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /** 按路径取值，字符串段表示对象键，整数段表示数组下标；任一段缺失返回 null。 */
    public static Object at(Object node, Object... path) {
        Object current = node;
        for (Object segment : path) {
            if (current instanceof Map<?, ?> map && segment instanceof String key) {
                current = map.get(key);
            } else if (current instanceof List<?> list && segment instanceof Integer index) {
                current = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    /** 按路径取字符串，数字/布尔也会转成字符串；缺失返回 null。 */
    public static String text(Object node, Object... path) {
        Object value = at(node, path);
        if (value == null) {
            return null;
        }
        return value instanceof String s ? s : String.valueOf(value);
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    public static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        writeString(sb, raw);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** 递归下降解析器，出错时抛出带位置的 IllegalArgumentException。 */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src == null ? "" : src;
        }

        boolean eof() {
            return pos >= src.length();
        }

        Object parseDocument() {
            skipWhitespace();
            if (eof()) {
                error("内容为空");
            }
            return parseValue();
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private Object parseValue() {
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f', 'n' -> parseLiteral();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                char c = next("对象未闭合");
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    error("对象中期望 ',' 或 '}'，实际为 '" + c + "'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                char c = next("数组未闭合");
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    error("数组中期望 ',' 或 ']'，实际为 '" + c + "'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    error("字符串未闭合");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    error("转义字符不完整");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            error("\\u 转义长度不足");
                        }
                        String hex = src.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            error("\\u 转义非法: " + hex);
                        }
                        pos += 4;
                    }
                    default -> error("非法转义 '\\'" + esc);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            boolean floating = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    floating |= c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String raw = src.substring(start, pos);
            if (raw.isEmpty()) {
                error("无法解析的值，位置处为 '" + peek() + "'");
            }
            try {
                return floating ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
            } catch (NumberFormatException e) {
                error("数字格式非法: " + raw);
                return null;
            }
        }

        private Object parseLiteral() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            error("无法解析的值");
            return null;
        }

        private char peek() {
            return eof() ? '\0' : src.charAt(pos);
        }

        private char next(String hint) {
            if (eof()) {
                error(hint);
            }
            return src.charAt(pos++);
        }

        private void expect(char c) {
            if (eof() || src.charAt(pos) != c) {
                error("期望 '" + c + "'，实际为 '" + peek() + "'");
            }
            pos++;
        }

        void error(String message) {
            throw new IllegalArgumentException("JSON 解析失败[" + message + "]，行 " + currentLine() + " 偏移 " + pos);
        }

        private int currentLine() {
            int limit = Math.min(pos, src.length());
            int line = 1;
            for (int i = 0; i < limit; i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }
}
