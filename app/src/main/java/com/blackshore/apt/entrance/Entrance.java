package com.blackshore.apt.entrance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个“AI 代码生成目标”：编译时注解处理器会读取这里的配置，让 AI 生成一个实现
 * {@link AiProgramEntrance} 的类，并通过 Filer 写回本次编译。
 *
 * <p><b>配置文件优先</b>：application.yml（含环境变量、-D 系统属性、javac -A 参数）里写了非空值的项，
 * 永远压住这里写的同名项；本注解只相当于一份「写在源码里的逐入口默认值」，
 * 只对配置文件没写（或写了空白）的项生效。想让注解里的需求生效，就把 application.yml 的 prompt 删掉或留空；
 * 日志会逐项列出哪些注解默认值被配置文件压住了。</p>
 *
 * <p>每个成员都是独立判断的（包括嵌套分组里的单个成员），所以下面嵌套注解里声明的
 * 默认值只是给 IDE 看的提示，不会写进配置（写 {@code repair = @Entrance.Repair} 等于什么都不改）。</p>
 *
 * <p><b>可以完全不配置</b>：一个成员都不写也能跑（只写 {@code @Entrance} 或 {@code @Entrance("需求")}），
 * 未写的项全部走内置默认值；删掉 application.yml 也一样可用（api-key 可以从环境变量
 * {@code AI_APT_API_KEY} 提供）。内置默认值包括：provider=openai、max-tokens=不限制、
 * thinking=auto（不下发思考字段）、temperature=不下发、repair=开/2 轮、retry=开/3 次、cache=开、
 * fallback=demo、输出类名 aiMain（包名沿用被注解类所在包）、multi-file=开（最多 5 个文件）、
 * sub-packages=开（子包最多 2 级）。</p>
 *
 * <p><b>可以直接写</b>：需求就是 {@link #value()}，多行需求写成字符串数组，每个元素一行
 * （与其它成员同时书写时必须写成 {@code value = ...}，这是 Java 语法限制）；
 * 字符串里的 <code>${NAME}</code> 会展开成同名环境变量或系统属性（可带默认值 <code>${NAME:fallback}</code>），
 * 因此 api-key 不必写死在源码里。</p>
 *
 * <pre>{@code
 * // 1）最简写法：只写需求（application.yml 的 prompt 留空时才会被使用）
 * @Entrance("打印当前时间，并输出 1..100 的偶数和")
 * public class Main {}
 *
 * // 2）多行需求：数组每个元素一行，拼接后就是完整的 prompt
 * @Entrance({
 *         "解析命令行传入的算式，例如 \"1+2*3\"：",
 *         "1. 支持 + - * / 与括号；",
 *         "2. 打印计算结果，遇到除零给出提示。"
 * })
 * public class Main {}
 *
 * // 3）逐入口各写一份默认值（application.yml 把这些项留空时才会各自生效）
 * @Entrance(value = {"写一个 LRU 缓存演示"}, className = "aiLru",
 *         model = @Entrance.Model(value = "deepseek-reasoner", temperature = 0))
 * class LruHint {}
 *
 * // 4）逐目标默认值：换模型与思考等级、定制 prompt 模板与变量、只自愈一轮、失败直接中断构建
 * @Entrance(
 *         className = "aiMain",
 *         model = @Entrance.Model(
 *                 value = "deepseek-flash",
 *                 provider = "openai",
 *                 baseUrl = "https://api.deepseek.com/v1",
 *                 apiKey = "${DEEPSEEK_API_KEY}",
 *                 thinking = "medium",
 *                 temperature = 0.1,
 *                 maxTokens = 0,
 *                 headers = {"x-request-source:ai-apt-compiler"}),
 *         prompt = @Entrance.Prompt(
 *                 value = {"用 Java 实现容量为 8 的 LRU 缓存，并演示 put/get 的输出"},
 *                 system = {"你是资深 Java 工程师，只输出能编译通过的代码。"},
 *                 variables = {"STYLE=effective-java"}),
 *         repair = @Entrance.Repair(rounds = 1),
 *         fallback = @Entrance.Fallback(value = "error", failOnError = true)
 * )
 * public class Main {}
 * }</pre>
 *
 * <p>生成的类默认为 {@code 被注解类所在包.aiMain}，与 {@code Main} 反射加载的名字一致，
 * 可通过 {@link #pkg()} / {@link #className()} 或 {@link Output} 调整（application.yml 写了
 * output.package / output.class-name 时依旧以它为准）。{@link Output#multiFile()} 缺省开着，
 * 需求复杂时 AI 可以一次给出入口类加若干辅助类的<b>多个</b> .java 文件；{@link Output#subPackages()}
 * 同样缺省开着，辅助类可以按职责住进目标包之下的<b>子包</b>（如 {@code pkg.model} / {@code pkg.service}）。
 * 两者一起预编译校验后一起写回本次构建，运行期仍然只加载入口类（它始终钉在 {@link #pkg()}，
 * 不会被挪进子包）。</p>
 *
 * <p>配置项已全部能在注解里写：{@link Model}（含思考等级）、{@link Prompt}（需求/系统/自定义/修复/变量）、
 * {@link Repair}、{@link Retry}、{@link Cache}、{@link Output}、{@link Fallback}、{@link Debug}；
 * 只有全局总开关 {@code enabled} 是例外（它跨整个编译，写在 application.yml 里），
 * 单入口的开关用 {@link #enabled()}。</p>
 *
 * <p><b>嫌配置太长？写成组合注解</b>（类比 Spring Boot 的 {@code @SpringBootApplication}）：
 * 把下面这一堆 {@code @Entrance} 收进项目自己的注解里，宿主类就只剩一行，
 * 启动体也与 {@code SpringApplication.run(...)} 对齐：</p>
 *
 * <pre>{@code
 * // 配置载体：只被处理器当作“默认值来源”，自身不会生成代码
 * @Entrance(value = {"……"}, className = "aiMain",
 *         model = @Entrance.Model(value = "deepseek-flash", apiKey = "${AI_APT_API_KEY}"))
 * @Target(ElementType.TYPE)
 * @Retention(RetentionPolicy.SOURCE)
 * public @interface AiApp {}
 *
 * // 宿主类：与 Spring Boot 启动类一样长
 * @AiApp
 * public class Main {
 *     public static void main(String[] args) {
 *         AiApt.run(Main.class, args);   // 反射加载编译期生成的 aiMain 并执行
 *     }
 * }
 * }</pre>
 *
 * <p>两个要点：类上直接写的 {@code @Entrance} 优先于从组合注解继承的（要改哪项就写哪项，
 * 其余继续继承）；因为本注解是 SOURCE 保留级，组合注解必须与宿主类在<b>同一次编译</b>里，
 * 把它打进独立 jar 给别人用就读不到配置了。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Entrance {

    /** 需求描述（prompt）：可直接 {@code @Entrance("...")}，也可写成多行，每行一个数组元素。 */
    String[] value() default {};

    /** 需求描述的另一种写法，与 {@link #value()} 等价；两者都写了时以本项为准。 */
    String[] task() default {};

    /** 生成类的简单类名，等价于 {@code output = @Output(className = "...")}。 */
    String className() default "";

    /** 生成类的包名，等价于 {@code output = @Output(pkg = "...")}；留空则沿用被注解类所在包。 */
    String pkg() default "";

    /**
     * 只关掉这个入口的 AI 生成（不写任何文件）。这是逐目标开关，对应配置项 target-enabled，
     * 不受 application.yml 里的全局 {@code enabled} 影响；想由配置文件压住它，请写 {@code target-enabled: true}。
     */
    boolean enabled() default true;

    /** 模型接入配置。 */
    Model model() default @Model;

    /** prompt 配置：需求、系统提示、自定义模板、修复模板与模板变量。 */
    Prompt prompt() default @Prompt;

    /** 编译不通过时的自愈修复配置。 */
    Repair repair() default @Repair;

    /** HTTP 请求的重试与退避配置。 */
    Retry retry() default @Retry;

    /** 生成结果缓存配置（命中缓存不会再次调用模型）。 */
    Cache cache() default @Cache;

    /** 生成目标（包名/类名）配置。 */
    Output output() default @Output;

    /** 生成失败时的兜底策略。 */
    Fallback fallback() default @Fallback;

    /** 构建期产物与其它开关。 */
    Debug debug() default @Debug;

    /** 模型接入：协议、端点、密钥、思考等级、采样参数与附加请求头。 */
    @interface Model {

        /** 模型名，例如 deepseek-flash、gpt-4o-mini、claude-sonnet-4-20250514（缺省 provider 对应各自的默认模型）。 */
        String value() default "";

        /** openai 或 anthropic（缺省 openai）；写 deepseek/qwen/claude 等厂商名时会自动归一到对应协议。 */
        String provider() default "";

        /** 接口根地址，可以只到域名（自动补 /v1），也可以写全（缺省 https://api.openai.com/v1）。 */
        String baseUrl() default "";

        /** 密钥，支持 <code>${ENV}</code> 与 <code>${ENV:默认值}</code> 占位符；缺省读环境变量 AI_APT_API_KEY。 */
        String apiKey() default "";

        /**
         * 思考（推理）等级：{@code auto}（缺省，不下发任何思考字段，完全沿用模型默认）、
         * {@code off}（明确关掉思考）、{@code minimal}、{@code low}、{@code medium}、{@code high}。
         * 下发方式由协议决定：OpenAI 兼容协议用 reasoning_effort（off 时用 thinking.type=disabled），
         * Anthropic 用 thinking 块的 budget_tokens（并按协议要求不再下发 temperature）。
         */
        String thinking() default "auto";

        /** 采样温度；写 0 表示要求确定性输出，不写则沿用 application.yml（配置文件里留空时不下发该字段）。 */
        double temperature() default 0.2D;

        /** 单次输出 token 上限；<b>默认 0 = 不限制</b>（OpenAI 兼容协议不下发该字段），写正值即可截断。 */
        int maxTokens() default 0;

        /** 单次请求超时秒数（缺省 120）。 */
        int timeoutSeconds() default 120;

        /** OpenAI 协议输出上限字段：auto（缺省，按模型名判断）/ max_tokens / max_completion_tokens。 */
        String tokenField() default "auto";

        /** Anthropic 协议版本号（provider=anthropic 时生效，缺省 2023-06-01）。 */
        String anthropicVersion() default "2023-06-01";

        /** 追加请求头，每项写成 {@code 名称:值} 或 {@code 名称=值}。 */
        String[] headers() default {};
    }

    /** prompt 配置：需求文本、系统提示、整体自定义模板、修复模板以及模板变量。 */
    @interface Prompt {

        /** 需求描述，与外层 {@link Entrance#value()} 等价（两处都写时只保留一个，避免歧义）。 */
        String[] value() default {};

        /** 覆盖内置的系统提示模板。 */
        String[] system() default {};

        /** 用自定义的用户提示模板整体替换内置模板（占位符同内置模板，如 {{PACKAGE}}、{{REQUIREMENT}}）。 */
        String[] custom() default {};

        /** 覆盖自愈环节把编译诊断回喂给模型的模板，可用占位符 {{ERRORS}}、{{CODE}}。 */
        String[] repair() default {};

        /** 模板变量，每项写成 {@code KEY=VALUE}，在模板里用 <code>{{KEY}}</code> 引用。 */
        String[] variables() default {};
    }

    /** 自愈修复：把 javac 的编译诊断回喂给模型，最多修复几轮。 */
    @interface Repair {

        /** 是否开启自愈（关闭后只用第一次生成的结果）。 */
        boolean enabled() default true;

        /** 最多修复几轮，0 表示不修复。 */
        int rounds() default 2;
    }

    /** 请求失败后的重试策略。 */
    @interface Retry {

        /** 是否重试（关闭后每个请求只发一次）。 */
        boolean enabled() default true;

        /** 最多请求几次（含首次）。 */
        int attempts() default 3;

        /** 退避基数毫秒，第 n 次重试等待 n * 该值。 */
        int backoffMillis() default 1000;
    }

    /** 生成结果缓存：同样的需求与配置再次编译时不再调用模型。 */
    @interface Cache {

        /** 是否使用缓存。 */
        boolean enabled() default true;

        /** 缓存目录，相对路径按工程根解析。 */
        String dir() default "";
    }

    /** 生成目标：包名与类名，以及一次可以写几个文件、能不能建子包。 */
    @interface Output {

        /** 包名；留空则沿用被注解类所在包。生成的子包只能开在这个包之下。 */
        String pkg() default "";

        /** 简单类名。 */
        String className() default "";

        /**
         * 是否允许 AI 一次输出多个 .java 文件（入口类 + 辅助类），缺省开。
         * 关掉后就只认一个编译单元；辅助类与工程里已有的类重名时，处理器会把原因回喂给模型改名。
         */
        boolean multiFile() default true;

        /** 一次最多输出几个文件（含入口文件），超出就会要求模型合并后重出。 */
        int maxFiles() default 5;

        /**
         * 是否允许 AI 在目标包之下创建子包（推荐 {@code pkg.model} / {@code pkg.service} / {@code pkg.util}），缺省开。
         * 子包始终限在目标包的子树里：写到目标包之外（含工程已有的其它包）会被拉回目标包；
         * 入口类任何时候都直接写在目标包下，不会跟着进子包。关掉后所有文件平铺在目标包。
         */
        boolean subPackages() default true;

        /** 目标包之下最多几级子包（如 {@code pkg.model.Order} 是 1 级，{@code pkg.a.b.C} 是 2 级），写 0 等于关掉子包。 */
        int subPackageDepth() default 2;
    }

    /** 生成或预编译失败时的兜底策略。 */
    @interface Fallback {

        /** demo=内置示例实现（保证构建可用）| stub=占位实现（运行时报错）| error=直接让编译失败。 */
        String value() default "demo";

        /** true 时任何生成失败都中断构建（等同于把兜底策略设为 error，但保留诊断信息）。 */
        boolean failOnError() default false;

        /** true 时预编译不过也照样写出模型的最后一次输出。 */
        boolean acceptUnverified() default false;

        /** 内存预编译时额外可用的 classpath，用于放开对某些依赖的引用。 */
        String compileClasspath() default "";
    }

    /** 构建期产物与其它开关。 */
    @interface Debug {

        /** 生成的文件头部是否写明需求、来源、协议与校验结果。 */
        boolean javadoc() default true;

        /** 模型忘记 implements 时是否自动补上接口。 */
        boolean enforceInterface() default true;

        /** 工作目录（prompt、模型原始输出、编译诊断都写在这里），相对路径按工程根解析。 */
        String workDir() default "";
    }
}
