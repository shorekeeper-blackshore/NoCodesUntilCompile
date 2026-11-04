package com.blackshore.apt.processor.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

/**
 * 处理器内部使用的零依赖小工具。
 */
public final class Utils {

    private Utils() {
    }

    /** SHA-256 十六进制串，用于配置指纹与生成结果缓存。 */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    public static String shortHash(String text) {
        return sha256(text).substring(0, 16);
    }

    public static String readString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    public static Path writeString(Path file, String content) throws IOException {
        mkdirs(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    public static void mkdirs(Path dir) throws IOException {
        if (dir != null && !Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    /** 返回第一个非空白值，全为空白时返回 defaultValue。 */
    public static String firstNonBlank(String defaultValue, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return defaultValue;
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String trimEnd(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...(共 " + flat.length() + " 字符)";
    }

    public static Boolean parseBoolean(String value, boolean defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on")) {
            return true;
        }
        if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off")) {
            return false;
        }
        return defaultValue;
    }

    public static int parseInt(String value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static long parseLong(String value, long defaultValue) {
        try {
            return value == null ? defaultValue : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Double parseDouble(String value, Double defaultValue) {
        try {
            return value == null ? defaultValue : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static String join(Collection<String> items, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (!sb.isEmpty()) {
                sb.append(separator);
            }
            sb.append(item);
        }
        return sb.toString();
    }

    /** 给每一行加上前缀，用于把用户多行 prompt 缩进进模板块。 */
    public static String indentLines(String text, String prefix) {
        if (text == null) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].isEmpty() ? "" : prefix + lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 取全限定名的最后一段。 */
    public static String simpleName(String fqn) {
        if (fqn == null) {
            return "";
        }
        int index = fqn.lastIndexOf('.');
        return index < 0 ? fqn : fqn.substring(index + 1);
    }

    public static String stripTrailingSlashes(String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** 去掉 BOM，避免 Windows 上的 UTF-8 BOM 干扰解析。 */
    public static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }
}
