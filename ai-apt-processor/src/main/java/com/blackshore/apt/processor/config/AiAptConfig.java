package com.blackshore.apt.processor.config;

import com.blackshore.apt.processor.util.Utils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 编译期 AI 生成代码的配置。配置项一律以扁平的规范名存取（见 {@link ConfigKeys}），
 * 因此 application.yml 里既可以写成 ai-apt.model.provider，也可以写成 model.provider 或 provider。
 *
 * <p>优先级：环境变量 / -D / javac -A &lt; application.yml 里写下的非空值 &lt; 全部来源都没写时的内置默认值，
 * {@code @Entrance} 里显式写出的项只作为当前目标的默认值参与最底层兜底（见 {@link #withAnnotationDefaults}），
 * 所以改配置文件就能压住源码里的注解，不必改代码。实例本身不可变。</p>
 */
public final class AiAptConfig {

    /** AI 生成代码需要实现的入口接口。 */
    public static final String ENTRANCE_INTERFACE = "com.blackshore.apt.entrance.AiProgramEntrance";
    /** 触发生成的注解。 */
    public static final String ENTRANCE_ANNOTATION = "com.blackshore.apt.entrance.Entrance";

    private static final String PATH_SEPARATOR = System.getProperty("path.separator", ";");

    /** 生成/校验失败时的兜底策略。 */
    public enum Fallback {
        /** 生成一份内置示例实现，保证构建可用（离线默认）。 */
        DEMO,
        /** 生成占位实现，编译通过但运行时会抛出带原因的异常。 */
        STUB,
        /** 直接让编译失败。 */
        ERROR
    }

    private final Map<String, String> values;
    private final Map<String, String> headers;
    private final Map<String, String> variables;
    private final Path projectDir;
    private final String configFile;
    private final String configKind;

    AiAptConfig(Map<String, String> values, Map<String, String> headers, Map<String, String> variables,
                Path projectDir, String configFile, String configKind) {
        this.values = Map.copyOf(values);
        this.headers = Map.copyOf(headers);
        this.variables = new LinkedHashMap<>(variables);
        this.projectDir = projectDir;
        this.configFile = configFile;
        this.configKind = configKind;
    }

    String raw(String key) {
        return values.get(key);
    }

    /**
     * 把 {@code @Entrance} 写出的内容当作「当前目标的默认值」合进来：只有配置文件（含环境变量、
     * 系统属性、javac -A）没写或写为空白的项，注解值才会生效；配置文件一写就压住注解。
     * 覆盖层为空时直接返回自身，避免无谓的对象创建。
     */
    public AiAptConfig withAnnotationDefaults(AnnotationOverlay overlay) {
        if (overlay == null || overlay.isEmpty()) {
            return this;
        }
        Map<String, String> mergedValues = new LinkedHashMap<>(overlay.values());
        keepHigher(values, mergedValues);
        Map<String, String> mergedHeaders = new LinkedHashMap<>(overlay.headers());
        keepHigher(headers, mergedHeaders);
        Map<String, String> mergedVariables = new LinkedHashMap<>(overlay.variables());
        keepHigher(variables, mergedVariables);
        return new AiAptConfig(mergedValues, mergedHeaders, mergedVariables, projectDir, configFile, configKind);
    }

    /** 高优先级来源（配置文件侧）的非空值逐键压过注解默认值。 */
    private static void keepHigher(Map<String, String> higher, Map<String, String> target) {
        higher.forEach((key, value) -> {
            if (!Utils.isBlank(value)) {
                target.put(key, value);
            }
        });
    }

    /**
     * 注解写出的项里，被更高优先级来源改成了不同值的部分；用于日志说明为什么某项注解没生效。
     * 两边值相同（例如注解写的 ${AI_APT_API_KEY} 与同名环境变量展开结果一致）就不算被压住。
     */
    public List<String> shadowedKeys(AnnotationOverlay overlay) {
        List<String> shadowed = new ArrayList<>();
        if (overlay == null || overlay.isEmpty()) {
            return shadowed;
        }
        for (Map.Entry<String, String> entry : overlay.values().entrySet()) {
            String higher = values.get(entry.getKey());
            if (!Utils.isBlank(higher) && !higher.trim().equals(entry.getValue().trim())) {
                shadowed.add(entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : overlay.headers().entrySet()) {
            String higher = headers.get(entry.getKey());
            if (higher != null && !higher.equals(entry.getValue())) {
                shadowed.add("header:" + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : overlay.variables().entrySet()) {
            String higher = variables.get(entry.getKey());
            if (higher != null && !higher.equals(entry.getValue())) {
                shadowed.add("var:" + entry.getKey());
            }
        }
        return shadowed;
    }

    private String str(String key, String defaultValue) {
        String value = values.get(key);
        return Utils.isBlank(value) ? defaultValue : value.trim();
    }

    private boolean bool(String key, boolean defaultValue) {
        return Utils.parseBoolean(values.get(key), defaultValue);
    }

    private int integer(String key, int defaultValue) {
        return Utils.parseInt(values.get(key), defaultValue);
    }

    public boolean enabled() {
        return bool(ConfigKeys.ENABLED, true);
    }

    /**
     * 逐目标开关：对应 {@code @Entrance(enabled = false)}。
     * 与全局 enabled 分开存取，否则配置文件里那句 enabled: true 会把源码上的单点关闭压掉。
     */
    public boolean targetEnabled() {
        return bool(ConfigKeys.TARGET_ENABLED, true);
    }

    /** 归一化后的协议名，只会是 openai 或 anthropic。 */
    public String protocol() {
        return ConfigKeys.normalizeProtocol(str(ConfigKeys.PROVIDER, ConfigKeys.DEFAULT_PROVIDER));
    }

    public boolean isAnthropic() {
        return "anthropic".equals(protocol());
    }

    public String provider() {
        return str(ConfigKeys.PROVIDER, ConfigKeys.DEFAULT_PROVIDER);
    }

    public String baseUrl() {
        return Utils.stripTrailingSlashes(str(ConfigKeys.BASE_URL, ConfigKeys.DEFAULT_BASE_URL));
    }

    public String apiKey() {
        String key = str(ConfigKeys.API_KEY, "");
        return ConfigKeys.isPlaceholderApiKey(key) ? "" : key;
    }

    public boolean hasApiKey() {
        return !apiKey().isEmpty();
    }

    public String model() {
        return str(ConfigKeys.MODEL, isAnthropic() ? ConfigKeys.DEFAULT_ANTHROPIC_MODEL : ConfigKeys.DEFAULT_OPENAI_MODEL);
    }

    /**
     * 思考/推理强度：只会是 auto|off|minimal|low|medium|high。
     * auto（缺省）表示不下发任何思考相关字段，完全沿用模型自己的默认行为，所以零配置可用。
     */
    public String reasoningEffort() {
        return ConfigKeys.normalizeReasoningEffort(str(ConfigKeys.REASONING_EFFORT, ConfigKeys.DEFAULT_THINKING));
    }

    /** 是否明确关掉思考（对应 @Entrance.Model(thinking = "off")）。 */
    public boolean thinkingDisabled() {
        return ConfigKeys.isReasoningOff(reasoningEffort());
    }

    public Double temperature() {
        String value = values.get(ConfigKeys.TEMPERATURE);
        return Utils.isBlank(value) ? null : Utils.parseDouble(value, null);
    }

    /** &lt;=0（默认）表示不限制输出长度：OpenAI 兼容协议不下发该字段，Anthropic 必填时由客户端给上限。 */
    public int maxTokens() {
        return integer(ConfigKeys.MAX_TOKENS, ConfigKeys.DEFAULT_MAX_TOKENS);
    }

    public int timeoutSeconds() {
        return Math.max(5, integer(ConfigKeys.TIMEOUT_SECONDS, ConfigKeys.DEFAULT_TIMEOUT_SECONDS));
    }

    public int retryAttempts() {
        return Math.max(1, integer(ConfigKeys.RETRY_ATTEMPTS, ConfigKeys.DEFAULT_RETRY_ATTEMPTS));
    }

    /** 关闭后每个请求只发一次（对应 @Entrance.Retry(enabled = false)）。 */
    public boolean retryEnabled() {
        return bool(ConfigKeys.RETRY_ENABLED, true);
    }

    public long retryBackoffMillis() {
        return Math.max(0, (long) integer(ConfigKeys.RETRY_BACKOFF_MILLIS, ConfigKeys.DEFAULT_RETRY_BACKOFF_MILLIS));
    }

    public boolean repairEnabled() {
        return bool(ConfigKeys.REPAIR_ENABLED, true);
    }

    public int repairRounds() {
        return Math.max(0, integer(ConfigKeys.REPAIR_ROUNDS, ConfigKeys.DEFAULT_REPAIR_ROUNDS));
    }

    public boolean cacheEnabled() {
        return bool(ConfigKeys.CACHE_ENABLED, true);
    }

    public Path cacheDir() {
        return resolve(str(ConfigKeys.CACHE_DIR, ConfigKeys.DEFAULT_CACHE_DIR));
    }

    public Path workDir() {
        return resolve(str(ConfigKeys.WORK_DIR, ConfigKeys.DEFAULT_WORK_DIR));
    }

    public String outputPackage() {
        return str(ConfigKeys.OUTPUT_PACKAGE, ConfigKeys.DEFAULT_PACKAGE);
    }

    /** 区分“没写包名”与“写了包名”，前者让处理器沿用 @Entrance 所在包。 */
    public boolean hasOutputPackage() {
        return !Utils.isBlank(values.get(ConfigKeys.OUTPUT_PACKAGE));
    }

    public boolean hasOutputClassName() {
        return !Utils.isBlank(values.get(ConfigKeys.OUTPUT_CLASS_NAME));
    }

    public String outputClassName() {
        return str(ConfigKeys.OUTPUT_CLASS_NAME, ConfigKeys.DEFAULT_CLASS_NAME);
    }

    public boolean failOnError() {
        return bool(ConfigKeys.FAIL_ON_ERROR, false);
    }

    public Fallback fallback() {
        String mode = str(ConfigKeys.FALLBACK, "demo").toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "stub", "placeholder", "runtime-error" -> Fallback.STUB;
            case "error", "fail", "abort", "break" -> Fallback.ERROR;
            default -> Fallback.DEMO;
        };
    }

    /** 需求描述：application.yml 写了就用它，没写时才用 @Entrance(value/task) 的默认值。 */
    public String prompt() {
        return str(ConfigKeys.PROMPT, "");
    }

    public String systemPrompt() {
        return str(ConfigKeys.SYSTEM_PROMPT, "");
    }

    /** 完全自定义 prompt（对应早期配置里的 AIIprompt），为空则使用内置模板。 */
    public String customPrompt() {
        return str(ConfigKeys.CUSTOM_PROMPT, "");
    }

    /** 自定义的修复模板（@Entrance.Prompt(repair = ...)），为空则使用内置模板。 */
    public String repairPrompt() {
        return str(ConfigKeys.REPAIR_PROMPT, "");
    }

    public boolean javadocEnabled() {
        return bool(ConfigKeys.JAVADOC_ENABLED, true);
    }

    public boolean enforceInterface() {
        return bool(ConfigKeys.ENFORCE_INTERFACE, true);
    }

    /** true 时，即使预编译不通过也把模型最后一次输出写出去（适合想拿到原始 AI 产物自行修改的场景）。 */
    public boolean acceptUnverified() {
        return bool(ConfigKeys.ACCEPT_UNVERIFIED, false);
    }

    /** OpenAI 协议里输出上限字段：auto / max_tokens / max_completion_tokens。 */
    public String tokenField() {
        return str(ConfigKeys.TOKEN_FIELD, "auto").toLowerCase(Locale.ROOT);
    }

    public String anthropicVersion() {
        return str(ConfigKeys.ANTHROPIC_VERSION, ConfigKeys.DEFAULT_ANTHROPIC_VERSION);
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, String> variables() {
        return variables;
    }

    /** 生成代码在内存校验时可用的额外 classpath（默认只允许使用 JDK 与入口接口）。 */
    public String compileClasspath() {
        String extra = str(ConfigKeys.COMPILE_CLASSPATH, "");
        String jvm = System.getProperty("java.class.path", "");
        if (extra.isEmpty()) {
            return jvm;
        }
        return jvm.isEmpty() ? extra : extra + PATH_SEPARATOR + jvm;
    }

    public Path projectDir() {
        return projectDir;
    }

    public String configFile() {
        return configFile;
    }

    /** 配置来源：file 或 classpath，仅用于日志。 */
    public String configKind() {
        return configKind;
    }

    public Path resolve(String maybeRelative) {
        Path path = Path.of(maybeRelative);
        if (path.isAbsolute() || projectDir == null) {
            return path;
        }
        return projectDir.resolve(path).normalize();
    }

    /** 用于日志的配置摘要（api-key 脱敏）；生成目标由处理器自己输出，因为包名可能沿用被注解类所在包。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("protocol=").append(protocol())
                .append(", base-url=").append(baseUrl())
                .append(", model=").append(model())
                .append(", thinking=").append(reasoningEffort())
                .append(", max-tokens=").append(maxTokens() > 0 ? String.valueOf(maxTokens()) : "无限制")
                .append(", api-key=").append(ConfigKeys.mask(apiKey()))
                .append(", repair=").append(repairEnabled()).append('/').append(repairRounds())
                .append(", retry=").append(retryEnabled()).append('/').append(retryAttempts())
                .append(", cache=").append(cacheEnabled())
                .append(", fallback=").append(fallback().name().toLowerCase(Locale.ROOT))
                .append(", config=").append(configFile == null ? "(未找到配置文件)" : configFile);
        return sb.toString();
    }

    /** 全部已识别配置的排序视图，便于排查写错的键名。 */
    Map<String, String> snapshot() {
        return new TreeMap<>(values);
    }

    @Override
    public String toString() {
        return describe();
    }
}
