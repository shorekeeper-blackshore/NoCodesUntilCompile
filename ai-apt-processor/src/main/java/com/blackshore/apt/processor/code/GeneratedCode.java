package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.util.Utils;

import java.util.List;

/**
 * 一次生成的结果快照，供处理器写文件与写头部注释使用。
 */
public record GeneratedCode(String fqn, String source, Origin origin, boolean verified, int aiCalls,
                            List<String> notes, String cacheKey) {

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
}
