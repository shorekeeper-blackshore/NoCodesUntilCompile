package com.blackshore.apt.processor.prompt;

import com.blackshore.apt.processor.config.GenerationTarget;
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

            输出形态（必须严格遵守）：
            {{FILE_RULES}}

            硬性约束（逐条检查后再输出）：
            1. 每个文件的 package 必须是 {{PACKAGE_SCOPE}}；{{NAME_RULES}}。
            2. 入口类必须是 public class，实现接口 {{INTERFACE}}，并保留默认的无参构造（不要声明任何构造函数）。
            3. 必须实现 void run(String[] args)，需求在该方法内完成；接口方法没有 throws，
               因此 run 内部不能向外抛出受检异常（必要时自行 try/catch 并打印）。
            4. 只允许使用 JDK {{JDK_VERSION}} 标准库（java.*、javax.* 等）和第 2 条的接口；
               禁止 import 任何第三方依赖，禁止引用工程里的其它类，禁止反射访问不存在的类，禁止读写外部文件与访问网络。
            5. 每个文件内最多一个 public 顶层类型，且 public 类型名必须与文件名一致；可以额外定义私有方法、
               私有静态内部类或 record；不要生成 module-info、package-info，不要生成第二个 main 方法入口来取代 run。
            6. 语法、符号、泛型都必须能编译通过，不要留 TODO、占位注释或伪代码。
            7. 运行结果要有可见输出（System.out 打印），并简要体现需求已完成。

            正确输出形态示例：
            {{OUTPUT_EXAMPLE}}
            """;

    /** 单文件形态：只允许一个编译单元（与历史行为一致）。 */
    private static final String FILE_RULES_SINGLE =
            "只输出一个完整的 Java 编译单元，放在一个 ```java 代码块里；代码块之外不要写任何解释文字。";

    /** 多文件形态：允许拆成多个文件，但必须有一个入口文件，且总量不超 max-files。 */
    private static final String FILE_RULES_MULTI = """
            本次允许把实现拆成多个 Java 文件（含入口文件在内最多 {{MAX_FILES}} 个）：
            - 每个文件单独放在一个 ```java 代码块里，代码块内从 package 声明开始，代码块之外不要写任何解释文字；
            - 有且只有一个代码块声明入口类 public class {{CLASS_NAME}}，其余代码块只放它用到的辅助类型；
            - 一个文件只放一个顶层类型，文件名由处理器按顶层类型名自动确定，同一个文件里不要写两个 public 顶层类型；
            {{PACKAGE_RULES}}
            - 拆分不是越多越好，能一个文件写清楚就不要再拆。""";

    /** 允许子包时的包结构约束：职责分包 + 入口钉在目标包 + 不越界。 */
    private static final String PACKAGE_RULES_TREE = """
            - 包结构要清晰：入口类固定写在 {{PACKAGE}}（运行期按这个全限定名反射加载，任何情况下都不要把它挪进子包），
              辅助类型按职责放进 {{PACKAGE}} 之下的子包，依次用 {{PACKAGE}}.model（数据模型：record / enum / 契约接口）、
              {{PACKAGE}}.service（业务逻辑）、{{PACKAGE}}.util（无状态工具），实在归不进这三类时才用 {{PACKAGE}}.support；
            - 子包最多 {{SUB_PACKAGE_DEPTH}} 级，包名全小写、一段一个单词（写 {{PACKAGE}}.order，不要 {{PACKAGE}}.OrderLogic），
              不要写到 {{PACKAGE}} 之外，也不要复用工程里已经存在的包名；
            - 跨包引用必须显式 import（禁止 import *），同一个子包内的类型之间不需要 import；""";

    private static final String PACKAGE_RULES_FLAT =
            "- 所有文件都必须写在 {{PACKAGE}} 这一层（本次不允许建子包），同包内互相引用不需要 import；";

    private static final String PACKAGE_SCOPE_FLAT = "{{PACKAGE}}";
    private static final String PACKAGE_SCOPE_TREE = "{{PACKAGE}}，或者是它下面的子包（入口类那个文件除外）";

    private static final String NAME_RULES_SINGLE =
            "顶层公共类型名必须严格等于 {{CLASS_NAME}}（大小写、拼写都不能改）";

    private static final String NAME_RULES_MULTI = """
            入口文件的顶层公共类型名必须严格等于 {{CLASS_NAME}}（大小写、拼写都不能改），
            辅助类型的简单名不要与入口类、其它文件或工程里已有的类重复""";

    private static final String OUTPUT_EXAMPLE_SINGLE = """
            ```java
            package {{PACKAGE}};

            import {{INTERFACE}};

            public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}} {
                @Override
                public void run(String[] args) {
                    System.out.println("Hello, AI APT Compiler!");
                }
            }
            ```""";

    private static final String OUTPUT_EXAMPLE_FLAT = """
            入口文件：
            ```java
            package {{PACKAGE}};

            import {{INTERFACE}};

            public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}} {
                @Override
                public void run(String[] args) {
                    System.out.println(new {{CLASS_NAME}}Greeter().greet());
                }
            }
            ```
            辅助文件（同一个包）：
            ```java
            package {{PACKAGE}};

            class {{CLASS_NAME}}Greeter {

                String greet() {
                    return "Hello, AI APT Compiler!";
                }
            }
            ```""";

    /** 子包形态示例：入口留在目标包，模型与逻辑各回各的子包。 */
    private static final String OUTPUT_EXAMPLE_TREE = """
            入口文件（必须直接写在 {{PACKAGE}} 下）：
            ```java
            package {{PACKAGE}};

            import {{INTERFACE}};
            import {{PACKAGE}}.model.Order;
            import {{PACKAGE}}.service.OrderService;

            public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}} {
                @Override
                public void run(String[] args) {
                    OrderService service = new OrderService();
                    Order order = service.create("A-1", 3);
                    System.out.println(service.describe(order));
                }
            }
            ```
            数据模型文件：
            ```java
            package {{PACKAGE}}.model;

            public record Order(String id, int count) {
            }
            ```
            业务逻辑文件：
            ```java
            package {{PACKAGE}}.service;

            import {{PACKAGE}}.model.Order;

            public class OrderService {

                public Order create(String id, int count) {
                    return new Order(id, count);
                }

                public String describe(Order order) {
                    return "订单 " + order.id() + " x" + order.count();
                }
            }
            ```""";

    /** 用户提示：需求 + 关键约束复述，避免长对话里约束被稀释。 */
    public static final String USER = """
            需求：
            {{REQUIREMENT}}

            {{USER_HINT}}
            """;

    private static final String USER_HINT_SINGLE = """
            请按系统约束生成文件：package {{PACKAGE}}，public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}}。
            只输出这一个 Java 编译单元。""";

    private static final String USER_HINT_MULTI = """
            请按系统约束生成入口文件：package {{PACKAGE}}，public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}}；
            确实需要拆分时，{{PACKAGING_HINT}}最多 {{MAX_FILES}} 个文件，每个文件单独一个 ```java 代码块。""";

    private static final String PACKAGING_HINT_TREE =
            "辅助类型按职责放进 {{PACKAGE}} 之下的子包（model / service / util），入口类自己留在 {{PACKAGE}}，";

    private static final String PACKAGING_HINT_FLAT = "所有文件都写在 {{PACKAGE}} 同一个包里，";

    /** 修复提示：把真实编译诊断回喂给模型，是自愈环节的核心。 */
    public static final String REPAIR = """
            上一轮生成的代码没有通过编译，请修复后重新输出**完整文件**（不要只给diff、不要解释）。

            编译诊断：
            {{ERRORS}}

            上一轮输出的文件：
            {{CODE}}

            修复时必须继续满足：{{REPAIR_RULES}}
            """;

    private static final String REPAIR_RULES_SINGLE = """
            package {{PACKAGE}}、public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}}、
            实现 void run(String[] args)、只使用 JDK 标准库、一个文件内只有一个 public 顶层类型、
            只输出一个 ```java 代码块""";

    private static final String REPAIR_RULES_MULTI = """
            package {{PACKAGE_SCOPE}}、入口文件里必须有 public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}}、
            实现 void run(String[] args)、只使用 JDK 标准库、{{PACKAGING_HINT}}不同文件不能有同全限定名的类型、
            最多 {{MAX_FILES}} 个文件且每个文件单独一个 ```java 代码块""";

    /** 抽取失败（输出形态不合规）时的重出提示。 */
    public static final String REEXTRACT = """
            上一次的输出不能直接作为 Java 源文件（原因: {{REASON}}）。
            请重新输出符合约束的内容：{{EXTRACT_RULES}}
            """;

    private static final String EXTRACT_RULES_SINGLE = """
            只输出**一个完整的 Java 编译单元**，只包含 package {{PACKAGE}} 下的
            public class {{CLASS_NAME}}，用 ```java 代码块包裹，不要输出其它文字""";

    private static final String EXTRACT_RULES_MULTI = """
            重新输出**若干个完整的 Java 编译单元**，每个单独用 ```java 代码块包裹（代码块内从 package 声明开始），
            其中必须有一个是 package {{PACKAGE}} 下的 public class {{CLASS_NAME}} implements {{INTERFACE_SIMPLE}}，
            {{PACKAGING_HINT}}最多 {{MAX_FILES}} 个文件，代码块之外不要输出其它文字""";

    private PromptTemplates() {
    }

    /**
     * 上下文变量表：模板与用户自定义 prompt 共用，形态完全由 {@link GenerationTarget} 决定。
     *
     * <p>形态相关的片段会先按基础变量展开一次再入表（{@link #render} 只单遍扫描，变量里套变量不会被
     * 二次展开），因此这里的写入顺序就是依赖顺序：先生成被别人引用的 PACKAGE_*，再生成 FILE_RULES 等成品。</p>
     */
    public static Map<String, String> context(GenerationTarget target, String jdkVersion, String requirement) {
        boolean multi = target.multiFile();
        boolean tree = multi && target.subPackages() && target.subPackageDepth() > 0;
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("PACKAGE", target.pkg());
        vars.put("CLASS_NAME", target.className());
        vars.put("SIMPLE_NAME", target.className());
        vars.put("INTERFACE", target.interfaceFqn());
        vars.put("INTERFACE_SIMPLE", simpleName(target.interfaceFqn()));
        vars.put("JDK_VERSION", jdkVersion);
        vars.put("JDK", jdkVersion);
        vars.put("MAX_FILES", String.valueOf(target.maxFiles()));
        vars.put("SUB_PACKAGE_DEPTH", String.valueOf(target.subPackageDepth()));
        vars.put("REQUIREMENT", requirement == null ? "" : requirement.trim());
        vars.put("PROMPT", vars.get("REQUIREMENT"));
        vars.put("GENERATED_BY", "AI APT Compiler");
        // 先算被嵌套引用的片段，否则外层模板里的 {{PACKAGE_RULES}} 会原样漏进最终 prompt
        vars.put("PACKAGE_RULES", render(tree ? PACKAGE_RULES_TREE : PACKAGE_RULES_FLAT, vars, null).stripTrailing());
        vars.put("PACKAGE_SCOPE", render(tree ? PACKAGE_SCOPE_TREE : PACKAGE_SCOPE_FLAT, vars, null));
        vars.put("PACKAGING_HINT", render(tree ? PACKAGING_HINT_TREE : PACKAGING_HINT_FLAT, vars, null));
        vars.put("NAME_RULES", render(multi ? NAME_RULES_MULTI : NAME_RULES_SINGLE, vars, null));
        vars.put("OUTPUT_EXAMPLE", render(tree ? OUTPUT_EXAMPLE_TREE
                : multi ? OUTPUT_EXAMPLE_FLAT : OUTPUT_EXAMPLE_SINGLE, vars, null));
        vars.put("USER_HINT", render(multi ? USER_HINT_MULTI : USER_HINT_SINGLE, vars, null));
        vars.put("REPAIR_RULES", render(multi ? REPAIR_RULES_MULTI : REPAIR_RULES_SINGLE, vars, null));
        vars.put("EXTRACT_RULES", render(multi ? EXTRACT_RULES_MULTI : EXTRACT_RULES_SINGLE, vars, null));
        vars.put("FILE_RULES", render(multi ? FILE_RULES_MULTI : FILE_RULES_SINGLE, vars, null));
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
