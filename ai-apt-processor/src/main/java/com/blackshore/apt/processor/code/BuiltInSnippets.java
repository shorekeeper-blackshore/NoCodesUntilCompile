package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.util.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 不依赖 AI 的内置源码模板：AI 不可用（没配 api-key、网络失败、多次修复仍编译不过）时使用，
 * 保证构建产物依然可运行，或者至少在运行时把原因讲清楚。
 */
public final class BuiltInSnippets {

    private BuiltInSnippets() {
    }

    /** 兜底示例实现：打印问候语、演示一段小计算，并把本次未调用 AI 的原因输出出来。 */
    public static String demo(String pkg, String className, String requirement, String reason) {
        return """
                package %s;

                import %s;

                import java.util.Arrays;
                import java.util.stream.IntStream;

                /**
                 * AI 不可用时的内置兜底实现。
                 * 原因：%s
                 */
                public class %s implements %s {

                    @Override
                    public void run(String[] args) {
                        System.out.println("Hello, AI APT Compiler!");
                        long sum = IntStream.rangeClosed(1, 100).asLongStream().sum();
                        System.out.println("1 + 2 + ... + 100 = " + sum);
                        if (args.length > 0) {
                            System.out.println("命令行参数: " + Arrays.toString(args));
                        }
                        System.out.println("本次未调用 AI（%s），期望需求是: %s");
                        System.out.println("在 @Entrance(model = @Model(apiKey = ...)) 或 application.yml 里配好 api-key / provider，"
                                + "再 mvn clean compile 即可获得 AI 生成的实现。");
                    }
                }
                """.formatted(pkg, AiAptConfig.ENTRANCE_INTERFACE, commentBlock(reason), className,
                Utils.simpleName(AiAptConfig.ENTRANCE_INTERFACE), singleLine(reason), singleLine(requirement));
    }

    /** 占位实现：编译通过，运行时抛异常，适合 fallback=stub 的严格场景。 */
    public static String stub(String pkg, String className, String reason) {
        return """
                package %s;

                import %s;

                /**
                 * 占位入口：AI 代码生成失败，保留类结构以便工程继续编译。
                 * 原因：%s
                 */
                public class %s implements %s {

                    private static final String REASON = %s;

                    @Override
                    public void run(String[] args) {
                        System.err.println("AI 生成的入口未能就绪，原因: " + REASON);
                        throw new IllegalStateException("AI APT Compiler 生成失败: " + REASON);
                    }
                }
                """.formatted(pkg, AiAptConfig.ENTRANCE_INTERFACE, commentBlock(reason), className,
                Utils.simpleName(AiAptConfig.ENTRANCE_INTERFACE), quoted(singleLine(reason)));
    }

    private static String singleLine(String text) {
        if (Utils.isBlank(text)) {
            return "(空)";
        }
        return text.replace("\r", " ").replace("\n", " ").replace("\"", "'").trim();
    }

    /** 把原因折成多行注释续行，首行由模板里的“原因：”引导。 */
    private static String commentBlock(String text) {
        String flat = singleLine(text);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < flat.length()) {
            int end = Math.min(flat.length(), start + 90);
            if (end < flat.length()) {
                int space = flat.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                }
            }
            chunks.add(flat.substring(start, end).trim());
            start = end;
            while (start < flat.length() && flat.charAt(start) == ' ') {
                start++;
            }
        }
        if (chunks.isEmpty()) {
            return "(无)";
        }
        return String.join("\n * ", chunks);
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
