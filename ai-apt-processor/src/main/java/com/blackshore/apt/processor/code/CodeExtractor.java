package com.blackshore.apt.processor.code;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型返回的文本里抽出唯一可编译的 Java 编译单元，并把包名、类名、接口实现强制对齐配置。
 */
public final class CodeExtractor {

    /** ```/```java 围栏块。 */
    private static final Pattern FENCE = Pattern.compile("(?s)```+\\s*[jJ][aA][vV][aA]?[^\\n]*\\n(.*?)```+");
    /** 顶层类型声明，用于识别并纠正类名。 */
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");
    /** 允许作为编译单元起点的行前缀。 */
    private static final List<String> START_PREFIXES = List.of("package", "import", "/*", "//", "@", "public",
            "final", "abstract", "sealed", "class", "interface", "enum", "record", "non-sealed");

    private CodeExtractor() {
    }

    /** 抽取或纠正失败时抛出，交给上层决定重试还是兜底。 */
    public static class ExtractionException extends Exception {
        public ExtractionException(String message) {
            super(message);
        }
    }

    /**
     * @param raw              模型返回的原始文本
     * @param pkg              期望包名
     * @param className        期望的公共类简单名
     * @param interfaceFqn     必须实现的接口全限定名
     * @param enforceInterface 是否自动补上 implements
     * @param notes            输出：对模型产物做过的修正
     */
    public static String toCompilationUnit(String raw, String pkg, String className, String interfaceFqn,
                                           boolean enforceInterface, List<String> notes) throws ExtractionException {
        if (raw == null || raw.isBlank()) {
            throw new ExtractionException("模型返回内容为空");
        }
        String code = trimToCompilationUnit(stripFenceNoise(pickCodeBlock(raw)));
        code = fixPackage(code, pkg, notes);
        code = fixTypeName(code, className, notes);
        if (enforceInterface) {
            code = fixImplements(code, className, interfaceFqn, notes);
        }
        if (!code.endsWith("\n")) {
            code = code + "\n";
        }
        if (code.length() < 40) {
            throw new ExtractionException("抽取出的代码过短，疑似模型没有输出完整文件: " + code);
        }
        return code;
    }

    /** 优先挑选包含类型声明的最长代码块，兼容“解释文字 + 多个代码块”的糟糕输出。 */
    private static String pickCodeBlock(String raw) {
        Matcher matcher = FENCE.matcher(stripBom(raw));
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        if (blocks.isEmpty()) {
            return stripBom(raw);
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String block : blocks) {
            int score = block.length() + (block.matches("(?s).*\\bclass\\s+\\w+.*") ? 1_000_000 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = block;
            }
        }
        return best == null ? stripBom(raw) : best;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** 去掉残留的围栏标记（模型偶尔忘记闭合）。 */
    private static String stripFenceNoise(String code) {
        return code.replaceAll("(?m)^\\s*```[^\\n]*$", "");
    }

    private static String trimToCompilationUnit(String code) throws ExtractionException {
        String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            for (String prefix : START_PREFIXES) {
                if (trimmed.startsWith(prefix)) {
                    start = i;
                    break;
                }
            }
            if (start >= 0) {
                break;
            }
        }
        if (start < 0) {
            throw new ExtractionException("没有识别到 Java 代码（既没有 package/import 也没有类型声明）");
        }
        int end = -1;
        for (int i = lines.length - 1; i >= start; i--) {
            if (lines[i].trim().equals("}")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new ExtractionException("代码不完整：找不到作为文件结尾的 }");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().strip();
    }

    private static String fixPackage(String code, String pkg, List<String> notes) {
        Matcher matcher = PACKAGE_DECL.matcher(code);
        if (matcher.find()) {
            String found = matcher.group(1);
            if (found.equals(pkg)) {
                return code;
            }
            notes.add("把包名 " + found + " 修正为 " + pkg);
            return matcher.replaceFirst(Matcher.quoteReplacement("package " + pkg + ";"));
        }
        // 没有 package 声明：插到首个 import/类型声明之前，同时保留文件头部注释
        String[] lines = code.split("\n", -1);
        int insertAt = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import") || trimmed.startsWith("public") || trimmed.startsWith("class")
                    || trimmed.startsWith("final") || trimmed.startsWith("abstract") || trimmed.startsWith("@")) {
                insertAt = i;
                break;
            }
            insertAt = i + 1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < insertAt; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("package ").append(pkg).append(";\n\n");
        for (int i = insertAt; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        notes.add("补上缺失的 package " + pkg);
        return sb.toString();
    }

    /** 模型常把类名写成 AiMain/HelloWorld 之类，这里统一改回配置里约定的名字。 */
    private static String fixTypeName(String code, String className, List<String> notes) throws ExtractionException {
        Matcher matcher = TYPE_DECL.matcher(code);
        if (!matcher.find()) {
            throw new ExtractionException("代码里没有 class/enum/record/interface 声明");
        }
        String found = matcher.group(1);
        if (found.equals(className)) {
            return code;
        }
        notes.add("把类型名 " + found + " 修正为 " + className);
        return code.replaceAll("\\b" + Pattern.quote(found) + "\\b", Matcher.quoteReplacement(className));
    }

    /** 保证顶层类确实实现了入口接口，否则 Main 反射加载后会强转失败。 */
    private static String fixImplements(String code, String className, String interfaceFqn, List<String> notes) {
        int declIndex = indexOfPrimaryDeclaration(code, className);
        if (declIndex < 0) {
            return code;
        }
        int braceIndex = code.indexOf('{', declIndex);
        if (braceIndex < 0) {
            return code;
        }
        String header = code.substring(declIndex, braceIndex);
        String simple = interfaceSimpleName(interfaceFqn);
        int implementsIndex = header.indexOf("implements");
        if (implementsIndex < 0) {
            notes.add("为 " + className + " 补上 implements " + interfaceFqn);
            return code.substring(0, braceIndex) + " implements " + interfaceFqn + " " + code.substring(braceIndex);
        }
        String implemented = header.substring(implementsIndex + "implements".length());
        if (implemented.contains(simple)) {
            return code;
        }
        notes.add("在 implements 列表中追加 " + interfaceFqn);
        // implements 子句一直延伸到类型体之前的最后一个非空白字符
        String before = code.substring(0, braceIndex).stripTrailing();
        String after = code.substring(braceIndex);
        return before + ", " + interfaceFqn + " " + after;
    }

    private static int indexOfPrimaryDeclaration(String code, String className) {
        Pattern pattern = Pattern.compile("\\b(?:class|enum|record)\\s+" + Pattern.quote(className) + "\\b");
        Matcher matcher = pattern.matcher(code);
        return matcher.find() ? matcher.start() : -1;
    }

    private static String interfaceSimpleName(String interfaceFqn) {
        int index = interfaceFqn.lastIndexOf('.');
        return index < 0 ? interfaceFqn : interfaceFqn.substring(index + 1);
    }
}
