package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.config.GenerationTarget;
import com.blackshore.apt.processor.util.Utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型返回的文本里抽出可编译的 Java 编译单元，并把包名、类名、接口实现强制对齐配置。
 *
 * <p>两种形态：单文件模式只认一个编译单元（挑最长的代码块）；多文件模式把输出切成若干单元，
 * 入口文件排第一，其余按各自的顶层类型命名，供处理器逐个交给 {@code Filer} 写回本次编译。</p>
 */
public final class CodeExtractor {

    /** ```/```java 围栏块。 */
    private static final Pattern FENCE = Pattern.compile("(?s)```+\\s*[jJ][aA][vV][aA]?[^\\n]*\\n(.*?)```+");
    /** 顶层类型声明，用于识别并纠正类名。 */
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");
    /** 段内首个类型声明，附带判断它是否 public。 */
    private static final Pattern SEGMENT_TYPE = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern PUBLIC_MODIFIER = Pattern.compile("\\bpublic\\b");
    /** 允许作为编译单元起点的行前缀。 */
    private static final List<String> START_PREFIXES = List.of("package", "import", "/*", "//", "@", "public",
            "final", "abstract", "sealed", "class", "interface", "enum", "record", "non-sealed");
    /** 不能作为顶层类型名的关键字（sealed / non-sealed / var 是合法标识符，故不在此列）。 */
    private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "const", "continue", "default", "do", "double", "else", "enum", "extends", "false", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "null",
            "package", "private", "protected", "public", "record", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "true", "try", "void", "while");
    /** 模型不许生成的模块声明（module-info.java 的内容）。 */
    private static final Pattern MODULE_DECL = Pattern.compile("(?m)^\\s*(?:open\\s+)?module\\s+[A-Za-z_$][A-Za-z0-9_$.]*\\s*[{;]");

    private CodeExtractor() {
    }

    /** 抽取或纠正失败时抛出，交给上层决定重试还是兜底。 */
    public static class ExtractionException extends Exception {
        public ExtractionException(String message) {
            super(message);
        }
    }

    /** 一个代码块解析出的源码、顶层类型清单与它自己声明的包名（没声明时为 null）。 */
    private record ParsedUnit(String code, List<TopType> types, String declaredPackage) {
    }

    /** 顶层类型名 + 是否 public（public 类型名必须与文件名一致，所以要先挑出来）。 */
    private record TopType(String name, boolean isPublic) {
    }

    /**
     * 单文件抽取：从模型输出里取出唯一可编译的 Java 编译单元（只认目标包，不看子包）。
     *
     * @param raw   模型返回的原始文本
     * @param notes 输出：对模型产物做过的修正
     */
    public static String toCompilationUnit(String raw, GenerationTarget target, List<String> notes)
            throws ExtractionException {
        if (raw == null || raw.isBlank()) {
            throw new ExtractionException("模型返回内容为空");
        }
        String code = trimToCompilationUnit(stripFenceNoise(pickCodeBlock(raw)));
        code = fixPackage(code, target.pkg(), notes);
        code = fixTypeName(code, target.className(), notes);
        if (target.enforceInterface()) {
            code = fixImplements(code, target.className(), target.interfaceFqn(), notes);
        }
        if (!code.endsWith("\n")) {
            code = code + "\n";
        }
        if (code.length() < 40) {
            throw new ExtractionException("抽取出的代码过短，疑似模型没有输出完整文件: " + code);
        }
        return code;
    }

    /**
     * 多文件抽取：把模型输出切成若干编译单元，第一个固定是入口类文件，其余是它引用的辅助文件。
     *
     * <p>入口文件永远落在目标包；辅助文件允许落在目标包之下的子包里（包名原样保留），
     * 写到目标包之外、超出层级上限或包名非法时，会被拉回目标包并记下修正。</p>
     */
    public static List<SourceFile> toCompilationUnits(String raw, GenerationTarget target, List<String> notes)
            throws ExtractionException {
        String entranceFqn = target.entranceFqn();
        // 在切块前先查一次原文：模块声明会弄坏整个编译，不能被“某个块解析失败”的容错逻辑静默吞掉
        assertNoModuleDeclaration(raw);
        if (!target.multiFile()) {
            return List.of(new SourceFile(entranceFqn, toCompilationUnit(raw, target, notes)));
        }
        List<ParsedUnit> units = parseBlocks(raw, notes);
        ParsedUnit entrance = pickEntrance(units, target.className(), target.interfaceFqn());
        String entranceCode = fixPackage(entrance.code(), target.pkg(), notes);
        entranceCode = fixTypeName(entranceCode, target.className(), notes);
        if (target.enforceInterface()) {
            entranceCode = fixImplements(entranceCode, target.className(), target.interfaceFqn(), notes);
        }
        if (entranceCode.length() < 40) {
            throw new ExtractionException("入口文件抽取后过短，疑似模型没有输出完整代码: " + entranceCode);
        }
        List<SourceFile> files = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        for (TopType type : topLevelTypes(entranceCode)) {
            declared.add(target.pkg() + "." + type.name());
        }
        if (!declared.contains(entranceFqn)) {
            throw new ExtractionException("入口文件里没有名为 " + target.className() + " 的顶层类型，"
                    + "请把它写成 package " + target.pkg() + " 下的 public class " + target.className());
        }
        files.add(new SourceFile(entranceFqn, entranceCode));
        for (ParsedUnit unit : units) {
            if (unit == entrance) {
                continue;
            }
            String filePkg = resolvedPackage(unit, target, notes);
            String code = fixPackage(unit.code(), filePkg, notes);
            List<TopType> types = unit.types();
            TopType primary = primaryType(types, code);
            for (TopType type : types) {
                if (!declared.add(filePkg + "." + type.name())) {
                    throw new ExtractionException("类型 " + filePkg + "." + type.name()
                            + " 在多个文件里重复声明，每个全限定名只能出现一次");
                }
            }
            files.add(new SourceFile(filePkg + "." + primary.name(), code));
        }
        if (files.size() > target.maxFiles()) {
            throw new ExtractionException("一次输出了 " + files.size() + " 个文件，超过 max-files=" + target.maxFiles()
                    + " 的上限；请把辅助类型合并进入口文件，或减少拆分");
        }
        if (files.size() > 1) {
            notes.add("本次生成 " + files.size() + " 个文件: " + files.stream().map(SourceFile::fqn).toList());
        }
        return files;
    }

    /**
     * 辅助文件真正允许待的包：在目标包子树内、层级与段名都合法时原样保留，否则拉回目标包。
     * 入口类不受影响（它永远被强制写成目标包），所以运行期反射加载的约定不会被子包弄坏。
     */
    private static String resolvedPackage(ParsedUnit unit, GenerationTarget target, List<String> notes) {
        String declared = unit.declaredPackage();
        if (declared == null || declared.equals(target.pkg())) {
            return target.pkg();
        }
        if (!target.allowsSubPackage(declared)) {
            notes.add("包 " + declared + " 不在 " + target.pkg() + " 的子树内（或子包已关闭），已把该文件拉回 "
                    + target.pkg());
            return target.pkg();
        }
        String relative = declared.substring(target.pkg().length() + 1);
        if (target.depthUnder(declared) > target.subPackageDepth() || !isUsablePackage(relative)) {
            notes.add("子包 " + declared + " 超出 " + target.subPackageDepth() + " 级上限或包名非法，"
                    + "已把该文件拉回 " + target.pkg());
            return target.pkg();
        }
        return declared;
    }

    /** 包名的每一段都必须是合法标识符（大小写不限，全小写的约定交给 prompt 去管）。 */
    private static boolean isUsablePackage(String relativePackage) {
        for (String segment : relativePackage.split("\\.")) {
            if (!isUsableTypeName(segment)) {
                return false;
            }
        }
        return true;
    }

    /** 把多个文件渲染成回喂给模型的文本：每个文件标上全限定名并包成 ```java 代码块。 */
    public static String renderForRepair(List<SourceFile> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            SourceFile file = files.get(i);
            sb.append(i == 0 ? "入口文件 " : "文件 ").append(file.fqn())
                    .append("（").append(file.pathName()).append("）：\n");
            sb.append("```java\n").append(file.source().stripTrailing()).append("\n```\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 所有文件的代码行数，日志用。 */
    public static int totalLines(List<SourceFile> files) {
        return files.stream().mapToInt(SourceFile::lines).sum();
    }

    // ------------------------------------------------------------------ 切块

    /** 把模型输出切成若干候选编译单元，认不出的段落只记 note，全认不出才报错。 */
    private static List<ParsedUnit> parseBlocks(String raw, List<String> notes) throws ExtractionException {
        List<String> blocks = splitBlocks(raw);
        List<ParsedUnit> units = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i);
            try {
                String code = trimToCompilationUnit(stripFenceNoise(block));
                List<TopType> types = topLevelTypes(code);
                if (types.isEmpty()) {
                    throw new ExtractionException("代码块里没有 class/interface/enum/record 声明");
                }
                units.add(new ParsedUnit(code, types, declaredPackage(code)));
            } catch (ExtractionException e) {
                failures.add("第 " + (i + 1) + " 段: " + e.getMessage());
            }
        }
        if (units.isEmpty()) {
            throw new ExtractionException(failures.isEmpty()
                    ? "没有从模型输出里识别出任何 Java 代码块" : String.join("；", failures));
        }
        if (!failures.isEmpty()) {
            notes.add("忽略了无法解析的代码块: " + String.join("；", failures));
        }
        return units;
    }

    /** 代码块自己声明的包名，没写 package 时返回 null。 */
    private static String declaredPackage(String code) {
        Matcher matcher = PACKAGE_DECL.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 优先用 ```java 围栏切块；一个围栏都没有时，按 package 声明把整段文本切成多个单元。 */
    private static List<String> splitBlocks(String raw) {
        String text = stripBom(raw);
        List<String> blocks = new ArrayList<>();
        Matcher matcher = FENCE.matcher(text);
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        if (!blocks.isEmpty()) {
            return blocks;
        }
        return splitByPackageDeclarations(stripFenceNoise(text));
    }

    private static List<String> splitByPackageDeclarations(String text) {
        List<String> segments = new ArrayList<>();
        Matcher matcher = PACKAGE_DECL.matcher(text);
        int start = -1;
        int offset = 0;
        while (matcher.find()) {
            if (start < 0) {
                start = matcher.start();
                continue;
            }
            segments.add(text.substring(offset, matcher.start()));
            offset = matcher.start();
        }
        String tail = start < 0 ? text : text.substring(offset);
        if (!tail.isBlank()) {
            segments.add(tail);
        }
        return segments;
    }

    /** 挑出入口文件：类名对上最可靠，其次看有没有 run 方法或 implements，最后取最大的代码块。 */
    private static ParsedUnit pickEntrance(List<ParsedUnit> units, String className, String interfaceFqn) {
        for (ParsedUnit unit : units) {
            if (unit.types().stream().anyMatch(type -> type.name().equals(className))) {
                return unit;
            }
        }
        String simple = interfaceSimpleName(interfaceFqn);
        for (ParsedUnit unit : units) {
            if (unit.code().contains("run(String[]") || unit.code().contains("implements " + simple)) {
                return unit;
            }
        }
        ParsedUnit best = units.get(0);
        for (ParsedUnit unit : units) {
            if (unit.code().length() > best.code().length()) {
                best = unit;
            }
        }
        return best;
    }

    /** 文件名用的顶层类型：public 的必须只有一个且与文件名一致，没有 public 就取第一个。 */
    private static TopType primaryType(List<TopType> types, String code) throws ExtractionException {
        List<TopType> publicTypes = types.stream().filter(TopType::isPublic).toList();
        if (publicTypes.size() > 1) {
            throw new ExtractionException("文件里声明了 " + publicTypes.size()
                    + " 个 public 顶层类型（" + publicTypes.stream().map(TopType::name).toList()
                    + "），每个文件最多只能有一个 public 顶层类型");
        }
        TopType primary = publicTypes.isEmpty() ? types.get(0) : publicTypes.get(0);
        if (!isUsableTypeName(primary.name())) {
            throw new ExtractionException("类型名 " + primary.name() + " 不能用作文件名（不是合法的 Java 标识符）: "
                    + Utils.truncate(code, 120));
        }
        return primary;
    }

    /** 模块声明（module-info.java 的内容）写进普通源文件会直接搞坏整个编译。 */
    private static void assertNoModuleDeclaration(String raw) throws ExtractionException {
        if (MODULE_DECL.matcher(raw).find()) {
            throw new ExtractionException("不允许生成 module-info（模块声明），把所有类型写进 package 下的普通类文件即可");
        }
    }

    private static boolean isUsableTypeName(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) || RESERVED.contains(name)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 顶层类型扫描

    /** 把注释与字面量里的内容抹成空格后扫描，只报花括号深度为 0 处声明的类型。 */
    private static List<TopType> topLevelTypes(String code) {
        String clean = stripCommentsAndLiterals(code);
        List<TopType> types = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    collectType(types, segment);
                }
                depth++;
                segment.setLength(0);
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
                segment.setLength(0);
            } else if (c == ';' && depth == 0) {
                // record Point(int x, int y); 这类没有花括号体的顶层声明
                collectType(types, segment);
            } else {
                segment.append(c);
            }
        }
        return types;
    }

    private static void collectType(List<TopType> types, StringBuilder segment) {
        String text = segment.toString();
        Matcher matcher = SEGMENT_TYPE.matcher(text);
        if (!matcher.find()) {
            return;
        }
        types.add(new TopType(matcher.group(2),
                PUBLIC_MODIFIER.matcher(text.substring(0, matcher.start())).find()));
    }

    /** 把注释、字符串与字符字面量里的内容抹成空格（保留换行与长度，行号仍然对得上）。 */
    private static String stripCommentsAndLiterals(String code) {
        char[] chars = code.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '/' && i + 1 < chars.length && chars[i + 1] == '/') {
                while (i < chars.length && chars[i] != '\n') {
                    chars[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
                chars[i] = chars[i + 1] = ' ';
                i += 2;
                while (i < chars.length) {
                    if (chars[i] == '*' && i + 1 < chars.length && chars[i + 1] == '/') {
                        chars[i] = chars[i + 1] = ' ';
                        i++;
                        break;
                    }
                    if (chars[i] != '\n' && chars[i] != '\r') {
                        chars[i] = ' ';
                    }
                    i++;
                }
            } else if (c == '"' && i + 2 < chars.length && chars[i + 1] == '"' && chars[i + 2] == '"') {
                chars[i] = chars[i + 1] = chars[i + 2] = ' ';
                i += 3;
                while (i < chars.length) {
                    if (chars[i] == '"' && i + 2 < chars.length && chars[i + 1] == '"' && chars[i + 2] == '"') {
                        chars[i] = chars[i + 1] = chars[i + 2] = ' ';
                        i += 2;
                        break;
                    }
                    if (chars[i] != '\n' && chars[i] != '\r') {
                        chars[i] = ' ';
                    }
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                chars[i] = ' ';
                i++;
                while (i < chars.length) {
                    if (chars[i] == '\\') {
                        chars[i] = ' ';
                        if (i + 1 < chars.length) {
                            chars[i + 1] = ' ';
                        }
                        i += 2;
                        continue;
                    }
                    if (chars[i] == quote) {
                        chars[i] = ' ';
                        break;
                    }
                    if (chars[i] == '\n') {
                        break;
                    }
                    chars[i] = ' ';
                    i++;
                }
            }
        }
        return new String(chars);
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

    /** 从第一个像 Java 声明的行开始，到最后一个完整语句（{@code }} 或 {@code ;} 结尾）为止。 */
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
            String trimmed = lines[i].trim();
            // 不能只认单独一行的 }：单行写法 class Helper { void go() { } } 与 record Point(int x, int y);
            // 都是合法收尾，否则会把这个文件截成只剩 package 声明
            if (trimmed.endsWith("}") || trimmed.endsWith(";")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new ExtractionException("代码不完整：找不到作为文件结尾的 } 或 ;");
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
