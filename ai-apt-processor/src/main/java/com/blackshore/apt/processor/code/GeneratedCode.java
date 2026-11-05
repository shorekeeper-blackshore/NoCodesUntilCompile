package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.util.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次生成的结果快照，供处理器写文件与写头部注释使用。
 *
 * <p>{@code files} 至少包含一个元素，第一个永远是入口类文件（{@code fqn} 就是它的全限定名），
 * 多文件生成时后面的元素是辅助类，它们可以住在目标包之下的子包里。</p>
 */
public record GeneratedCode(String fqn, List<SourceFile> files, Origin origin, boolean verified, int aiCalls,
                            List<String> notes, String cacheKey) {

    /** 入口文件源码（头部注释已写好）。 */
    public String source() {
        return files.isEmpty() ? "" : files.get(0).source();
    }

    /** 除入口文件之外的辅助文件。 */
    public List<SourceFile> auxiliaries() {
        return files.size() <= 1 ? List.of() : List.copyOf(files.subList(1, files.size()));
    }

    /** 本次一共写出几个文件。 */
    public int fileCount() {
        return files.size();
    }

    /** 按包分组的结构摘要，日志与头部注释都用它。 */
    public String structure() {
        return structureOf(files);
    }

    /** 形如 {@code com.x[aiMain]  com.x.model[Order]  com.x.service[OrderService]}。 */
    public static String structureOf(List<SourceFile> files) {
        Map<String, List<String>> byPackage = new LinkedHashMap<>();
        for (SourceFile file : files) {
            byPackage.computeIfAbsent(file.packageName(), key -> new ArrayList<>()).add(file.simpleName());
        }
        StringBuilder sb = new StringBuilder();
        byPackage.forEach((pkg, names) -> {
            if (!sb.isEmpty()) {
                sb.append("  ");
            }
            sb.append(pkg).append('[').append(String.join(", ", names)).append(']');
        });
        return sb.toString();
    }

    /** 代码来源。 */
    public enum Origin {
        /** 本轮直接调用 AI 得到并通过校验。 */
        AI,
        /** 命中上一次构建缓存。 */
        CACHE,
        /** AI 不可用时的内置示例实现。 */
        FALLBACK_DEMO,
        /** AI 不可用时的占位实现（运行时抛异常）。 */
        FALLBACK_STUB,
        /** 校验没通过但仍按用户要求写出的最后版本。 */
        UNVERIFIED_AI
    }

    public boolean isFallback() {
        return origin == Origin.FALLBACK_DEMO || origin == Origin.FALLBACK_STUB;
    }

    public String originLabel() {
        return switch (origin) {
            case AI -> "AI 生成";
            case CACHE -> "AI 生成（构建缓存命中）";
            case FALLBACK_DEMO -> "内置示例兜底";
            case FALLBACK_STUB -> "占位实现兜底";
            case UNVERIFIED_AI -> "AI 生成（未通过预编译校验）";
        };
    }

    /** 去掉可能已存在的头部注释，保证缓存与重复生成的内容一致。 */
    public static String stripHeader(String source) {
        String text = Utils.stripBom(source);
        if (!text.startsWith("/*")) {
            return text;
        }
        int end = text.indexOf("*/");
        return end < 0 ? text : text.substring(end + 2).stripLeading();
    }

    public List<String> notes() {
        return notes == null ? List.of() : List.copyOf(notes);
    }

    public List<SourceFile> files() {
        return files == null ? List.of() : List.copyOf(files);
    }
}
