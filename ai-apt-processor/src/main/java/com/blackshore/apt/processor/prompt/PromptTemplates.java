package com.blackshore.apt.processor.prompt;

import com.blackshore.apt.processor.util.Utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置 prompt 模板。占位符写作 {{NAME}}，单次扫描替换，模板变量本身包含 {{ }} 时不会被二次展开。
 */
public final class PromptTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_.\\-]+)}}");

    /** 系统提示：把“必须能编译”这件事拆成模型可执行的硬约束。 */
    public static final String SYSTEM = """
            你是资深 Java 工程师。你的输出会被注解处理器在编译期直接写成 .java 源文件并交给 javac 编译，
            任何不符合约束的输出都会导致用户的构建失败。

            硬性约束（逐条检查后再输出）：
            1. 只输出一个完整的 Java 编译单元，放在 ```java 代码块里，代码块之外不要写任何解释文字。
            2. package 必须是 {{PACKAGE}}；顶层公共类型名必须严格等于 {{CLASS_NAME}}（大小写、拼写都不能改）。
            3. 该类型必须是 public class，实现接口 {{INTERFACE}}，并保留默认的无参构造（不要声明任何构造函数）。
            4. 必须实现 void run(String[] args)，需求在该方法内完成；接口方法没有 throws，
               因此 run 内部不能向外抛出受检异常（必要时自行 try/catch 并打印）。
            5. 只允许使用 JDK {{JDK_VERSION}} 标准库（java.*、javax.* 等）和第 3 条的接口；
               禁止 import 任何第三方依赖，禁止引用工程里的其它类，禁止反射访问不存在的类，禁止读写外部文件与访问网络。
            6. 文件内最多一个 public 顶层类型；可以额外定义私有方法、私有静态内部类或 record；
               不要生成 module-info、package-info，不要生成第二个 main 方法入口来取代 run。
            7. 语法、符号、泛型都必须能编译通过，不要留 TODO、占位注释或伪代码。
            8. 运行结果要有可见输出（System.out 打印），并简要体现需求已完成。

            正确输出形态示例：
            ```java
            package {{PACKAGE}};

            import {{INTERFACE}};

            public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}} {
                @Override
                public void run(String[] args) {
                    System.out.println("Hello, AI APT Compiler!");
                }
            }
            ```
            """;

    /** 用户提示：需求 + 关键约束复述，避免长对话里约束被稀释。 */
    public static final String USER = """
            需求：
            {{REQUIREMENT}}

            请按系统约束生成文件：package {{PACKAGE}}，public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}}。
            只输出这一个 Java 编译单元。
            """;

    /** 修复提示：把真实编译诊断回喂给模型，是自愈环节的核心。 */
    public static final String REPAIR = """
            上一轮生成的代码没有通过编译，请修复后重新输出**完整文件**（不要只给diff、不要解释）。

            编译诊断：
            {{ERRORS}}

            待修复代码：
            ```java
            {{CODE}}
            ```

            修复时必须继续满足：package {{PACKAGE}}、public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}}、
            实现 void run(String[] args)、只使用 JDK 标准库、一个文件内只有一个 public 顶层类型。
            """;

    private PromptTemplates() {
    }

    /** 上下文变量表：模板与用户自定义 prompt 共用。 */
    public static Map<String, String> context(String pkg, String className, String interfaceFqn, String jdkVersion,
                                              String requirement) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("PACKAGE", pkg);
        vars.put("CLASS_NAME", className);
        vars.put("SIMPLE_NAME", className);
        vars.put("INTERFACE", interfaceFqn);
        vars.put("INTERFACE_SIMPLE", simpleName(interfaceFqn));
        vars.put("JDK_VERSION", jdkVersion);
        vars.put("JDK", jdkVersion);
        vars.put("REQUIREMENT", requirement == null ? "" : requirement.trim());
        vars.put("PROMPT", vars.get("REQUIREMENT"));
        vars.put("GENERATED_BY", "AI APT Compiler");
        return vars;
    }

    /** 渲染模板：模板变量 + 上下文合并后单次替换。 */
    public static String render(String template, Map<String, String> context, Map<String, String> extraVariables) {
        Map<String, String> merged = new LinkedHashMap<>(context);
        if (extraVariables != null) {
            extraVariables.forEach((key, value) -> merged.putIfAbsent(key, value));
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = merged.get(key);
            if (value == null) {
                value = merged.get(key.toLowerCase(java.util.Locale.ROOT));
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static String simpleName(String fqn) {
        int index = fqn.lastIndexOf('.');
        return index < 0 ? fqn : fqn.substring(index + 1);
    }

    /** 需求为空时给模型的兜底描述，避免生成无意义代码。 */
    public static String describeMissingRequirement() {
        return "生成一个 Hello World 程序：打印问候语，并计算 1 到 100 的和后输出结果。";
    }

    /** 把多行文本压进单行日志用。 */
    public static String brief(String text) {
        return Utils.truncate(text == null ? "" : text.replace('\n', ' ').replace('\r', ' '), 160);
    }
}
