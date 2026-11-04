package com.blackshore.apt.processor.config;

import com.blackshore.apt.processor.util.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * application.yml 用的轻量 YAML 解析器（注解处理器不便引入 SnakeYAML）。
 *
 * <p>支持本工程配置需要的子集：注释、多级缩进映射、块序列、{@code key: value} 与
 * {@code key = value}（兼容早期的 properties 风格写法）、块标量 {@code | > |- >-}、
 * 双/单引号标量。不支持锚点(&amp;alias)、流式集合({@code {a: 1}})、多文档等高级特性，
 * 复杂结构请统一使用块式缩进。</p>
 */
public final class YamlLite {

    /** 块标量指示符：| 或 >，可带 chomping(- / +) 与缩进数字。 */
    private static final Pattern BLOCK_HEADER = Pattern.compile("^([|>])([+-]?)(\\d?)([+-]?)\\s*(?:#.*)?$");
    private static final Pattern NUMBERED_KEY = Pattern.compile("^-\\s+(.*)$");

    private final List<String> lines;
    private int cursor;

    private YamlLite(String text) {
        this.lines = expandTabs(text);
    }

    /** 解析整份配置文本，顶层一定是映射。 */
    public static Map<String, Object> parse(String text) {
        YamlLite parser = new YamlLite(Utils.stripBom(text == null ? "" : text));
        int first = parser.nextSignificant();
        if (first < 0) {
            return new LinkedHashMap<>();
        }
        String text0 = parser.textOf(first);
        if (isSequenceItem(text0)) {
            throw new IllegalArgumentException("配置顶层必须是键值结构（YAML 映射）");
        }
        Object root = parser.parseMap(parser.indentOf(first));
        int trailing = parser.nextSignificant();
        if (trailing >= 0) {
            throw new IllegalArgumentException("第 " + (trailing + 1) + " 行附近存在无法归属的内容: " + parser.textOf(trailing));
        }
        return asMap(root);
    }

    // ------------------------------------------------------------------ 结构解析

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        if (node instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("期望映射结构，实际为: " + node);
    }

    private Map<String, Object> parseMap(int indent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int i = nextSignificant();
            if (i < 0) {
                break;
            }
            int current = indentOf(i);
            String raw = textOf(i);
            if (current < indent || (current == indent && isSequenceItem(raw))) {
                break;
            }
            if (current > indent) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行缩进与同级条目不一致");
            }
            cursor = i + 1;
            KeyValue kv = splitKeyValue(raw);
            if (kv == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行不是合法的 key: value 结构: " + raw);
            }
            map.put(kv.key(), parseValueAfterKey(current, kv.value()));
        }
        return map;
    }

    private Object parseValueAfterKey(int keyIndent, String inlineValue) {
        if (inlineValue != null && !inlineValue.isEmpty()) {
            Matcher block = BLOCK_HEADER.matcher(inlineValue);
            if (block.matches()) {
                return readBlockScalar(keyIndent, block.group(1).equals(">"), block.group(2), block.group(4));
            }
            return scalar(inlineValue);
        }
        int next = nextSignificant();
        if (next < 0) {
            return "";
        }
        int nextIndent = indentOf(next);
        String nextText = textOf(next);
        if (nextIndent > keyIndent) {
            return isSequenceItem(nextText) ? parseSequence(nextIndent) : parseMap(nextIndent);
        }
        // YAML 允许序列与 key 同缩进
        if (nextIndent == keyIndent && isSequenceItem(nextText)) {
            return parseSequence(nextIndent);
        }
        return "";
    }

    private List<Object> parseSequence(int indent) {
        List<Object> items = new ArrayList<>();
        while (true) {
            int i = nextSignificant();
            if (i < 0 || indentOf(i) < indent) {
                break;
            }
            String raw = textOf(i);
            if (indentOf(i) > indent || !isSequenceItem(raw)) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行不是合法的序列项: " + raw);
            }
            String item = NUMBERED_KEY.matcher(raw).matches() ? NUMBERED_KEY.matcher(raw).replaceFirst("$1") : raw.substring(1).trim();
            cursor = i + 1;
            if (item.isEmpty()) {
                items.add(parseValueAfterKey(indent, null));
            } else {
                KeyValue kv = splitKeyValue(item);
                if (kv == null) {
                    items.add(scalar(item));
                } else {
                    // 序列项内联映射： "- key: value" 后面的更深缩进属于同一个映射
                    int itemIndent = indent + numberWidth(raw);
                    Map<String, Object> single = new LinkedHashMap<>();
                    single.put(kv.key(), parseValueAfterKey(itemIndent, kv.value()));
                    int next = nextSignificant();
                    if (next >= 0 && indentOf(next) > indent) {
                        single.putAll(parseMap(indentOf(next)));
                    }
                    items.add(single);
                }
            }
        }
        return items;
    }

    /** "- " 前缀占用的宽度，用于把序列项内的 key 当作更深一层处理。 */
    private static int numberWidth(String raw) {
        int index = raw.indexOf('-');
        return index + 1;
    }

    // ------------------------------------------------------------------ 块标量

    private String readBlockScalar(int keyIndent, boolean folded, String chompFirst, String chompSecond) {
        List<String> collected = new ArrayList<>();
        int minIndent = Integer.MAX_VALUE;
        while (cursor < lines.size()) {
            String line = lines.get(cursor);
            boolean blank = line.isBlank();
            int indent = blank ? -1 : indentOf(cursor);
            if (!blank && indent <= keyIndent) {
                break;
            }
            if (!blank) {
                minIndent = Math.min(minIndent, indent);
            }
            collected.add(line);
            cursor++;
        }
        // 去掉块标量之前的空行
        while (!collected.isEmpty() && collected.get(0).isBlank()) {
            collected.remove(0);
        }
        // 去掉块标量之后的空行，chomping 由指示符决定
        boolean keepTrailingBlanks = "+".equals(chompFirst) || "+".equals(chompSecond);
        while (!keepTrailingBlanks && !collected.isEmpty() && collected.get(collected.size() - 1).isBlank()) {
            collected.remove(collected.size() - 1);
        }
        int strip = minIndent == Integer.MAX_VALUE ? 0 : minIndent;
        List<String> body = new ArrayList<>();
        for (String line : collected) {
            body.add(line.isBlank() ? "" : line.substring(Math.min(strip, line.length())));
        }
        String joined = folded ? foldLines(body) : String.join("\n", body);
        boolean stripAll = "-".equals(chompFirst) || "-".equals(chompSecond);
        if (stripAll) {
            return Utils.trimEnd(joined);
        }
        if (keepTrailingBlanks) {
            return joined + "\n".repeat(Math.max(1, trailingBlankCount(collected) + 1));
        }
        return joined.isEmpty() ? "" : joined + "\n";
    }

    private static int trailingBlankCount(List<String> lines) {
        int count = 0;
        for (int i = lines.size() - 1; i >= 0 && lines.get(i).isBlank(); i--) {
            count++;
        }
        return count;
    }

    /** 折叠标量：连续非空行用空格连接，空行视为段落分隔。 */
    private static String foldLines(List<String> body) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (i > 0) {
                String prev = body.get(i - 1);
                sb.append(prev.isEmpty() || line.isEmpty() ? "\n" : " ");
            }
            sb.append(line);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 行工具

    private static List<String> expandTabs(String text) {
        List<String> result = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            int i = 0;
            StringBuilder lead = new StringBuilder();
            while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
                if (raw.charAt(i) == '\t') {
                    lead.append("    ");
                } else {
                    lead.append(' ');
                }
                i++;
            }
            result.add(lead + raw.substring(i));
        }
        return result;
    }

    private int nextSignificant() {
        while (cursor < lines.size()) {
            String line = lines.get(cursor);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("---") || trimmed.equals("...")) {
                cursor++;
            } else {
                return cursor;
            }
        }
        return -1;
    }

    private int indentOf(int index) {
        String line = lines.get(index);
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private String textOf(int index) {
        return lines.get(index).trim();
    }

    private static boolean isSequenceItem(String text) {
        return text.equals("-") || text.startsWith("- ") || text.startsWith("-\t");
    }

    // ------------------------------------------------------------------ key/value

    private record KeyValue(String key, String value) {
    }

    /**
     * 以第一个位于引号外、且后面跟空白或行尾的 ':' 或 '=' 作为分隔符。
     * 这样 base-url: https://api.x（冒号后无空格）与 a = b 两种写法都能正确解析。
     */
    private static KeyValue splitKeyValue(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '#' && i > 0 && text.charAt(i - 1) == ' ') {
                break;
            }
            if (c == ':' || c == '=') {
                boolean atEnd = i == text.length() - 1;
                char after = atEnd ? ' ' : text.charAt(i + 1);
                if (atEnd || after == ' ' || after == '\t') {
                    String key = text.substring(0, i).trim();
                    String value = text.substring(i + 1).trim();
                    return key.isEmpty() ? null : new KeyValue(unquote(key), value);
                }
            }
        }
        return null;
    }

    private static String scalar(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
            char quote = value.charAt(0);
            if (value.charAt(value.length() - 1) == quote) {
                String inner = value.substring(1, value.length() - 1);
                if (quote == '\'') {
                    return inner.replace("''", "'");
                }
                return unescape(inner);
            }
        }
        return stripInlineComment(value).trim();
    }

    private static String stripInlineComment(String value) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && i > 0 && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.charAt(text.length() - 1) == text.charAt(0)) {
            return unescape(text.substring(1, text.length() - 1));
        }
        return text;
    }

    private static String unescape(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                sb.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '"' -> sb.append('"');
                case '\'' -> sb.append('\'');
                case '\\' -> sb.append('\\');
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }
}
