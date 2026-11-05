import com.blackshore.apt.processor.code.CodeExtractor;
import com.blackshore.apt.processor.code.SourceFile;
import com.blackshore.apt.processor.config.GenerationTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * 多文件 / 子包抽取的边界用例检查（联调小工具，与 StubServer 一样不参与 Maven 构建）：
 *   mvn -q -pl ai-apt-processor compile
 *   java -cp ai-apt-processor/target/classes tools/ExtractorCheck.java
 */
public class ExtractorCheck {

    private static final String PKG = "com.demo";
    private static final String CLASS = "aiMain";
    private static final String IFACE = "com.demo.AiProgramEntrance";

    private static final String ENTRANCE = fence("package " + PKG + ";\n"
            + "import " + IFACE + ";\n"
            + "public class " + CLASS + " implements AiProgramEntrance {\n"
            + "    public void run(String[] args) { }\n"
            + "}");

    private static int failures;

    public static void main(String[] args) {
        List<String> notes = new ArrayList<>();

        // 1) 没有 ``` 围栏，只有两段 package —— 应切成 2 个文件
        String noFence = "这是入口类：\n"
                + "package com.demo;\n"
                + "public class aiMain implements AiProgramEntrance {\n"
                + "    public void run(String[] args) { new Helper().go(); }\n"
                + "}\n"
                + "这是辅助类：\n"
                + "package com.demo;\n"
                + "public class Helper {\n"
                + "    public void go() { }\n"
                + "}\n";
        check("无围栏时按 package 切成两块", () -> {
            List<SourceFile> files = extract(noFence, tree(5, 2), notes);
            expect(files.size() == 2, "实际 " + files.size());
            expect(files.get(0).fqn().equals(PKG + "." + CLASS), "入口 fqn " + files.get(0).fqn());
            expect(files.get(1).fileName().equals("Helper.java"), "辅助文件名 " + files.get(1).fileName());
        });

        // 2) 单文件模式（multi-file=false）即使模型给了两段，也只认一个编译单元
        check("单文件模式只出一个文件", () -> {
            List<SourceFile> files = extract(noFence, target(false, 5, false, 0), notes);
            expect(files.size() == 1, "实际 " + files.size());
        });

        // 3) 一个文件里两个 public 顶层类型 —— 必须报错让模型改
        check("一个文件里两个 public 顶层类型", () -> expectThrows("public 顶层类型",
                () -> extract(ENTRANCE + fence("package " + PKG + ";\npublic class A1 { }\npublic class A2 { }"),
                        tree(5, 2), notes)));

        // 4) 同一个包里跨文件重名 —— 报错
        String duplicate = fence("package " + PKG + ";\n"
                + "public class aiMain implements AiProgramEntrance {\n"
                + "    public void run(String[] args) { new Helper().go(); }\n"
                + "}")
                + fence("package " + PKG + ";\nclass Helper { void go() { } }")
                + fence("package " + PKG + ";\nclass Helper { void go2() { } }");
        check("同包跨文件重名", () -> expectThrows("重复声明", () -> extract(duplicate, tree(5, 2), notes)));

        // 5) module-info（模块声明）一律拒绝，而且不能当成“某个块解析失败”静默丢掉
        check("拒绝模块声明", () -> expectThrows("module-info",
                () -> extract(ENTRANCE + fence("open module com.demo {\n    requires java.base;\n}"),
                        tree(5, 2), notes)));

        // 6) 注释与字符串里的 class 关键字不能骗过顶层类型扫描
        check("注释/字符串里的 class", () -> {
            List<SourceFile> files = extract(fence("package " + PKG + ";\n"
                    + "// class FakeInsideComment { }\n"
                    + "/** 说明：class AnotherFake { } */\n"
                    + "public class aiMain implements AiProgramEntrance {\n"
                    + "    String s = \"class StringFake { }\";\n"
                    + "    public void run(String[] args) { }\n"
                    + "}"), tree(5, 2), notes);
            expect(files.size() == 1, "实际 " + files.size());
            expect(files.get(0).fqn().equals(PKG + "." + CLASS), "fqn " + files.get(0).fqn());
        });

        // 7) 文本块里的花括号不能打乱深度统计；record Point(int x, int y); 也能作为独立文件
        String triple = "\"\"\"";
        check("文本块 + 以 ; 收尾的 record", () -> {
            List<SourceFile> files = extract(fence("package " + PKG + ";\n"
                    + "public class aiMain implements AiProgramEntrance {\n"
                    + "    String template = " + triple + "\n{ 不是花括号 }\n" + triple + ";\n"
                    + "    public void run(String[] args) { }\n"
                    + "}") + fence("package " + PKG + ".model;\nrecord Point(int x, int y);"), tree(5, 2), notes);
            expect(files.size() == 2, "实际 " + files.size());
            expect(files.get(1).fqn().equals(PKG + ".model.Point"), "第二个文件 fqn " + files.get(1).fqn());
        });

        // 8) 子包原样保留：包名与路径都按模型写的走
        String subPackages = ENTRANCE
                + fence("package " + PKG + ".model;\npublic record Order(String id) { }")
                + fence("package " + PKG + ".service;\nimport " + PKG + ".model.Order;\n"
                        + "public class OrderService { public String describe(Order o) { return o.id(); } }");
        check("子包原样保留", () -> {
            List<SourceFile> files = extract(subPackages, tree(5, 2), notes);
            expect(files.size() == 3, "实际 " + files.size());
            expect(files.get(0).fqn().equals(PKG + "." + CLASS), "入口仍在目标包: " + files.get(0).fqn());
            expect(files.get(1).fqn().equals(PKG + ".model.Order"), "fqn " + files.get(1).fqn());
            expect(files.get(2).pathName().equals("com/demo/service/OrderService.java"),
                    "pathName " + files.get(2).pathName());
        });

        // 9) 写到目标包之外（含工程已有的包）—— 拉回目标包，绝不越界
        check("越界的包被拉回目标包", () -> {
            List<SourceFile> files = extract(ENTRANCE
                    + fence("package com.other.deep.helper;\npublic class Printer { void go() { } }"),
                    tree(5, 2), notes);
            expect(files.get(1).fqn().equals(PKG + ".Printer"), "拉回后 fqn " + files.get(1).fqn());
            expect(files.get(1).source().contains("package " + PKG + ";"), "包声明没改写:\n" + files.get(1).source());
        });

        // 10) 子包层级超上限 —— 同样拉回
        check("子包层级超上限被拉回", () -> {
            List<SourceFile> files = extract(ENTRANCE
                    + fence("package " + PKG + ".a.b.c;\npublic class Deep { }"), tree(5, 2), notes);
            expect(files.get(1).fqn().equals(PKG + ".Deep"), "拉回后 fqn " + files.get(1).fqn());
        });

        // 11) 子包开关关掉 —— 所有文件平铺回目标包
        check("关掉子包后全部平铺", () -> {
            List<SourceFile> files = extract(subPackages, target(true, 5, false, 2), notes);
            expect(files.size() == 3, "实际 " + files.size());
            for (SourceFile file : files) {
                expect(file.packageName().equals(PKG), file.fqn() + " 不在 " + PKG);
            }
        });

        // 12) 不同子包里的同名类型是合法 Java，不该报错；同包重复才拦
        check("不同子包同名类型允许", () -> {
            List<SourceFile> files = extract(ENTRANCE
                    + fence("package " + PKG + ".model;\npublic class Order { }")
                    + fence("package " + PKG + ".dto;\npublic class Order { }"), tree(5, 2), notes);
            expect(files.size() == 3, "实际 " + files.size());
            expect(files.get(1).fqn().equals(PKG + ".model.Order"), files.get(1).fqn());
            expect(files.get(2).fqn().equals(PKG + ".dto.Order"), files.get(2).fqn());
        });

        // 13) 入口类被写进子包 —— 必须拉回目标包，否则运行期反射加载不到
        check("入口类不许住进子包", () -> {
            List<SourceFile> files = extract(fence("package " + PKG + ".app;\n"
                    + "public class aiMain implements AiProgramEntrance {\n"
                    + "    public void run(String[] args) { }\n"
                    + "}") + fence("package " + PKG + ".model;\npublic class Order { }"), tree(5, 2), notes);
            expect(files.get(0).fqn().equals(PKG + "." + CLASS), "入口 fqn " + files.get(0).fqn());
            expect(files.get(0).source().contains("package " + PKG + ";"), "入口包声明没拉回");
        });

        // 14) 非法子包段名（数字开头）—— 拉回而不是原样写出去
        check("非法子包段名被拉回", () -> {
            List<SourceFile> files = extract(ENTRANCE
                    + fence("package " + PKG + ".1bad;\npublic class Odd { }"), tree(5, 2), notes);
            expect(files.get(1).fqn().equals(PKG + ".Odd"), "拉回后 fqn " + files.get(1).fqn());
        });

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static List<SourceFile> extract(String raw, GenerationTarget target, List<String> notes)
            throws CodeExtractor.ExtractionException {
        return CodeExtractor.toCompilationUnits(raw, target, notes);
    }

    private static GenerationTarget tree(int maxFiles, int depth) {
        return target(true, maxFiles, true, depth);
    }

    /** enforceInterface 固定开（用例只关心切块与包归属），其余形态逐项参数化。 */
    private static GenerationTarget target(boolean multiFile, int maxFiles, boolean subPackages, int depth) {
        return new GenerationTarget(PKG, CLASS, IFACE, true, multiFile, maxFiles, subPackages, depth);
    }

    private static String fence(String code) {
        return "```java\n" + code + "\n```\n";
    }

    private static void check(String name, ThrowingRunnable body) {
        try {
            body.run();
            System.out.println("PASS  " + name);
        } catch (AssertionError e) {
            failures++;
            System.out.println("FAIL  " + name + " -> " + e.getMessage());
        } catch (Exception e) {
            failures++;
            System.out.println("FAIL  " + name + " -> 意外异常 " + e);
        }
    }

    private static void expect(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
    }

    private static void expectThrows(String messageFragment, ThrowingRunnable body) throws Exception {
        try {
            body.run();
        } catch (CodeExtractor.ExtractionException e) {
            expect(e.getMessage().contains(messageFragment),
                    "错误信息里没有 '" + messageFragment + "'：" + e.getMessage());
            return;
        } catch (Exception e) {
            throw new AssertionError("异常类型不对: " + e);
        }
        throw new AssertionError("本该报错，却成功返回了");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
