package com.blackshore.apt.processor.config;

import com.blackshore.apt.processor.util.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置键名规范表：把 application.yml 里的任意书写形式（多级缩进、点号扁平键、
 * properties 的 key = value、历史遗留键名）统一收敛成一份规范键，供 {@link AiAptConfig} 读取。
 */
public final class ConfigKeys {

    public static final String DEFAULT_PACKAGE = "com.blackshore.apt";
    public static final String DEFAULT_CLASS_NAME = "aiMain";
    public static final String DEFAULT_PROVIDER = "openai";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-20250514";
    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    public static final int DEFAULT_MAX_TOKENS = 8000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_RETRY_ATTEMPTS = 3;
    public static final int DEFAULT_RETRY_BACKOFF_MILLIS = 1000;
    public static final int DEFAULT_REPAIR_ROUNDS = 2;
    public static final String DEFAULT_WORK_DIR = "target/ai-apt";
    public static final String DEFAULT_CACHE_DIR = "target/ai-apt/cache";

    // 规范键名
    public static final String ENABLED = "enabled";
    public static final String PROVIDER = "provider";
    public static final String BASE_URL = "base-url";
    public static final String API_KEY = "api-key";
    public static final String MODEL = "model";
    public static final String TEMPERATURE = "temperature";
    public static final String MAX_TOKENS = "max-tokens";
    public static final String TIMEOUT_SECONDS = "timeout-seconds";
    public static final String RETRY_ATTEMPTS = "retry-attempts";
    public static final String RETRY_BACKOFF_MILLIS = "retry-backoff-millis";
    public static final String REPAIR_ENABLED = "repair-enabled";
    public static final String REPAIR_ROUNDS = "repair-rounds";
    public static final String CACHE_ENABLED = "cache-enabled";
    public static final String CACHE_DIR = "cache-dir";
    public static final String WORK_DIR = "work-dir";
    public static final String OUTPUT_PACKAGE = "output-package";
    public static final String OUTPUT_CLASS_NAME = "output-class-name";
    public static final String FAIL_ON_ERROR = "fail-on-error";
    public static final String FALLBACK = "fallback";
    public static final String PROMPT = "prompt";
    public static final String SYSTEM_PROMPT = "system-prompt";
    public static final String CUSTOM_PROMPT = "custom-prompt";
    public static final String JAVADOC_ENABLED = "javadoc-enabled";
    public static final String ENFORCE_INTERFACE = "enforce-interface";
    public static final String ACCEPT_UNVERIFIED = "accept-unverified";
    public static final String TOKEN_FIELD = "token-field";
    public static final String ANTHROPIC_VERSION = "anthropic-version";
    public static final String COMPILE_CLASSPATH = "compile-classpath";

    /** 全部规范键，也是“哪些配置项被识别”的唯一来源。 */
    public static final List<String> CANONICAL_KEYS = List.of(
            ENABLED, PROVIDER, BASE_URL, API_KEY, MODEL, TEMPERATURE, MAX_TOKENS, TIMEOUT_SECONDS,
            RETRY_ATTEMPTS, RETRY_BACKOFF_MILLIS, "retry-enabled", REPAIR_ENABLED, REPAIR_ROUNDS,
            CACHE_ENABLED, CACHE_DIR, WORK_DIR, OUTPUT_PACKAGE, OUTPUT_CLASS_NAME, FAIL_ON_ERROR, FALLBACK,
            PROMPT, SYSTEM_PROMPT, CUSTOM_PROMPT, JAVADOC_ENABLED, ENFORCE_INTERFACE, ACCEPT_UNVERIFIED,
            TOKEN_FIELD, ANTHROPIC_VERSION, COMPILE_CLASSPATH);

    /** normalize(规范键) -> 规范键，让 baseUrl / base_url / base-url 三种写法等价。 */
    private static final Map<String, String> BY_NORMALIZED = new HashMap<>();

    /** 允许出现在根部的命名空间前缀，会被丢弃。 */
    private static final Set<String> ROOT_PREFIXES = Set.of("aiapt", "aiaptcompiler", "aiaptcompilerx", "apt", "aicode");

    /** 父级分组 + 叶子组成的路径别名，主要用来兼容历史配置与嵌套写法。 */
    private static final Map<String, String> PATH_ALIASES = new HashMap<>();

    /** 单看叶子即可确定的别名。 */
    private static final Map<String, String> LEAF_ALIASES = new HashMap<>();

    static {
        // 历史配置：aiAptCompiler.model.anthropic = openai / baseUrl / apiKey / model
        PATH_ALIASES.put("model.anthropic", PROVIDER);
        PATH_ALIASES.put("model.openai", PROVIDER);
        PATH_ALIASES.put("model.provider", PROVIDER);
        PATH_ALIASES.put("model.protocol", PROVIDER);
        PATH_ALIASES.put("model.baseurl", BASE_URL);
        PATH_ALIASES.put("model.apikey", API_KEY);
        PATH_ALIASES.put("model.model", MODEL);
        PATH_ALIASES.put("model.name", MODEL);
        PATH_ALIASES.put("retry.maxattempts", RETRY_ATTEMPTS);
        PATH_ALIASES.put("retry.attempts", RETRY_ATTEMPTS);
        PATH_ALIASES.put("retry.maxretries", RETRY_ATTEMPTS);
        PATH_ALIASES.put("retry.backoffmillis", RETRY_BACKOFF_MILLIS);
        PATH_ALIASES.put("retry.enabled", "retry-enabled");
        PATH_ALIASES.put("repair.enabled", REPAIR_ENABLED);
        PATH_ALIASES.put("repair.rounds", REPAIR_ROUNDS);
        PATH_ALIASES.put("repair.maxrounds", REPAIR_ROUNDS);
        PATH_ALIASES.put("repair.attempts", REPAIR_ROUNDS);
        PATH_ALIASES.put("selfheal.enabled", REPAIR_ENABLED);
        PATH_ALIASES.put("selfheal.rounds", REPAIR_ROUNDS);
        PATH_ALIASES.put("cache.enabled", CACHE_ENABLED);
        PATH_ALIASES.put("cache.dir", CACHE_DIR);
        PATH_ALIASES.put("cache.directory", CACHE_DIR);
        PATH_ALIASES.put("output.package", OUTPUT_PACKAGE);
        PATH_ALIASES.put("output.packagename", OUTPUT_PACKAGE);
        PATH_ALIASES.put("output.classname", OUTPUT_CLASS_NAME);
        PATH_ALIASES.put("output.name", OUTPUT_CLASS_NAME);
        PATH_ALIASES.put("output.mainclass", OUTPUT_CLASS_NAME);
        PATH_ALIASES.put("prompt.user", PROMPT);
        PATH_ALIASES.put("prompt.task", PROMPT);
        PATH_ALIASES.put("prompt.system", SYSTEM_PROMPT);
        PATH_ALIASES.put("prompt.custom", CUSTOM_PROMPT);
        PATH_ALIASES.put("debug.workdir", WORK_DIR);
        PATH_ALIASES.put("build.workdir", WORK_DIR);

        LEAF_ALIASES.put("aienabled", ENABLED);
        LEAF_ALIASES.put("processorenabled", ENABLED);
        LEAF_ALIASES.put("protocol", PROVIDER);
        LEAF_ALIASES.put("vendor", PROVIDER);
        LEAF_ALIASES.put("apistyle", PROVIDER);
        LEAF_ALIASES.put("type", PROVIDER);
        LEAF_ALIASES.put("anthropic", PROVIDER);
        LEAF_ALIASES.put("openai", PROVIDER);
        LEAF_ALIASES.put("url", BASE_URL);
        LEAF_ALIASES.put("endpoint", BASE_URL);
        LEAF_ALIASES.put("apihost", BASE_URL);
        LEAF_ALIASES.put("host", BASE_URL);
        LEAF_ALIASES.put("serverurl", BASE_URL);
        LEAF_ALIASES.put("key", API_KEY);
        LEAF_ALIASES.put("apikey", API_KEY);
        LEAF_ALIASES.put("token", API_KEY);
        LEAF_ALIASES.put("secret", API_KEY);
        LEAF_ALIASES.put("apisecret", API_KEY);
        LEAF_ALIASES.put("modelname", MODEL);
        LEAF_ALIASES.put("modelid", MODEL);
        LEAF_ALIASES.put("chatmodel", MODEL);
        LEAF_ALIASES.put("maxtokens", MAX_TOKENS);
        LEAF_ALIASES.put("maxoutputtokens", MAX_TOKENS);
        LEAF_ALIASES.put("completiontokens", MAX_TOKENS);
        LEAF_ALIASES.put("timeout", TIMEOUT_SECONDS);
        LEAF_ALIASES.put("timeoutseconds", TIMEOUT_SECONDS);
        LEAF_ALIASES.put("requesttimeout", TIMEOUT_SECONDS);
        LEAF_ALIASES.put("retry", RETRY_ATTEMPTS);
        LEAF_ALIASES.put("retries", RETRY_ATTEMPTS);
        LEAF_ALIASES.put("retryattempts", RETRY_ATTEMPTS);
        LEAF_ALIASES.put("maxretry", RETRY_ATTEMPTS);
        LEAF_ALIASES.put("backoff", RETRY_BACKOFF_MILLIS);
        LEAF_ALIASES.put("backoffmillis", RETRY_BACKOFF_MILLIS);
        LEAF_ALIASES.put("retrybackoffmillis", RETRY_BACKOFF_MILLIS);
        LEAF_ALIASES.put("selfheal", REPAIR_ENABLED);
        LEAF_ALIASES.put("autofix", REPAIR_ENABLED);
        LEAF_ALIASES.put("repairenabled", REPAIR_ENABLED);
        LEAF_ALIASES.put("repairrounds", REPAIR_ROUNDS);
        LEAF_ALIASES.put("maxfixrounds", REPAIR_ROUNDS);
        LEAF_ALIASES.put("fixrounds", REPAIR_ROUNDS);
        LEAF_ALIASES.put("cache", CACHE_ENABLED);
        LEAF_ALIASES.put("cacheenabled", CACHE_ENABLED);
        LEAF_ALIASES.put("cachedir", CACHE_DIR);
        LEAF_ALIASES.put("cachedirectory", CACHE_DIR);
        LEAF_ALIASES.put("workdir", WORK_DIR);
        LEAF_ALIASES.put("workdirectory", WORK_DIR);
        LEAF_ALIASES.put("outputdir", WORK_DIR);
        LEAF_ALIASES.put("debugdir", WORK_DIR);
        LEAF_ALIASES.put("package", OUTPUT_PACKAGE);
        LEAF_ALIASES.put("outputpackage", OUTPUT_PACKAGE);
        LEAF_ALIASES.put("targetpackage", OUTPUT_PACKAGE);
        LEAF_ALIASES.put("classname", OUTPUT_CLASS_NAME);
        LEAF_ALIASES.put("outputclassname", OUTPUT_CLASS_NAME);
        LEAF_ALIASES.put("mainclass", OUTPUT_CLASS_NAME);
        LEAF_ALIASES.put("targetclass", OUTPUT_CLASS_NAME);
        LEAF_ALIASES.put("entryclass", OUTPUT_CLASS_NAME);
        LEAF_ALIASES.put("failonerror", FAIL_ON_ERROR);
        LEAF_ALIASES.put("failbuildonerror", FAIL_ON_ERROR);
        LEAF_ALIASES.put("strict", FAIL_ON_ERROR);
        LEAF_ALIASES.put("fallbackmode", FALLBACK);
        LEAF_ALIASES.put("mode", FALLBACK);
        LEAF_ALIASES.put("onerror", FALLBACK);
        LEAF_ALIASES.put("task", PROMPT);
        LEAF_ALIASES.put("requirement", PROMPT);
        LEAF_ALIASES.put("demand", PROMPT);
        LEAF_ALIASES.put("instruction", PROMPT);
        LEAF_ALIASES.put("systemprompt", SYSTEM_PROMPT);
        LEAF_ALIASES.put("roleprompt", SYSTEM_PROMPT);
        LEAF_ALIASES.put("customprompt", CUSTOM_PROMPT);
        LEAF_ALIASES.put("rawprompt", CUSTOM_PROMPT);
        LEAF_ALIASES.put("prompttemplate", CUSTOM_PROMPT);
        LEAF_ALIASES.put("aiprompt", CUSTOM_PROMPT);
        LEAF_ALIASES.put("aiiprompt", CUSTOM_PROMPT);
        LEAF_ALIASES.put("javadoc", JAVADOC_ENABLED);
        LEAF_ALIASES.put("javadocenabled", JAVADOC_ENABLED);
        LEAF_ALIASES.put("headercomment", JAVADOC_ENABLED);
        LEAF_ALIASES.put("enforceinterface", ENFORCE_INTERFACE);
        LEAF_ALIASES.put("acceptunverified", ACCEPT_UNVERIFIED);
        LEAF_ALIASES.put("keepunverified", ACCEPT_UNVERIFIED);
        LEAF_ALIASES.put("rawoutput", ACCEPT_UNVERIFIED);
        LEAF_ALIASES.put("tokenfield", TOKEN_FIELD);
        LEAF_ALIASES.put("maxtokensfield", TOKEN_FIELD);
        LEAF_ALIASES.put("anthropicversion", ANTHROPIC_VERSION);
        LEAF_ALIASES.put("compileclasspath", COMPILE_CLASSPATH);
        LEAF_ALIASES.put("checkclasspath", COMPILE_CLASSPATH);

        for (String key : CANONICAL_KEYS) {
            BY_NORMALIZED.put(normalize(key), key);
        }
        BY_NORMALIZED.put("retries", RETRY_ATTEMPTS);
        BY_NORMALIZED.put("selfhealrounds", REPAIR_ROUNDS);
        BY_NORMALIZED.put("cachedirectory", CACHE_DIR);
        BY_NORMALIZED.put("mainclass", OUTPUT_CLASS_NAME);
    }

    private ConfigKeys() {
    }

    /** 去掉分隔符并转小写：Base-URL / base_url / baseUrl 都会变成 baseurl。 */
    public static String normalize(String segment) {
        StringBuilder sb = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static boolean isRootPrefix(String segment) {
        return ROOT_PREFIXES.contains(normalize(segment));
    }

    /**
     * 把一条配置路径收敛成规范键。优先级：分组路径别名 &gt; 叶子别名 &gt; 归一化后的规范键自身。
     *
     * @param path 已经拆好并逐段 normalize 过的路径（不含根命名空间）
     */
    public static String canonicalize(List<String> path) {
        if (path.isEmpty()) {
            return "";
        }
        String leaf = path.get(path.size() - 1);
        if (path.size() > 1) {
            String parent = path.get(path.size() - 2);
            String hit = PATH_ALIASES.get(parent + "." + leaf);
            if (hit != null) {
                return hit;
            }
        }
        String hit = LEAF_ALIASES.get(leaf);
        if (hit != null) {
            return hit;
        }
        // baseUrl / base_url / base-url 归一化后都是 baseurl，指回同一个规范键
        String self = BY_NORMALIZED.get(leaf);
        return self != null ? self : leaf;
    }

    /** 是否是已识别的规范键（未识别的项只记日志，不影响构建）。 */
    public static boolean isKnown(String canonical) {
        return CANONICAL_KEYS.contains(canonical);
    }

    /** openai / anthropic 之外的写法（claude、gpt、deepseek、兼容网关等）在此归一。 */
    public static String normalizeProtocol(String provider) {
        String v = normalize(provider);
        if (v.contains("anthropic") || v.contains("claude") || v.contains("vertex")) {
            return "anthropic";
        }
        if (v.contains("openai") || v.contains("gpt") || v.contains("deepseek") || v.contains("qwen")
                || v.contains("moonshot") || v.contains("compatible") || v.contains("gateway") || v.isEmpty()) {
            return "openai";
        }
        return "openai";
    }

    /** 模板/示例里的占位密钥，视为未配置，避免拿它去发请求。 */
    public static boolean isPlaceholderApiKey(String key) {
        if (Utils.isBlank(key)) {
            return true;
        }
        String v = key.trim().toLowerCase(Locale.ROOT);
        return v.contains("xxxxxxxx")
                || v.startsWith("your-") || v.startsWith("your_")
                || v.equals("changeme") || v.equals("none") || v.equals("null")
                || v.equals("sk-") || v.equals("sk-xxxx") || v.matches("x+");
    }

    /** 日志脱敏。 */
    public static String mask(String key) {
        if (Utils.isBlank(key)) {
            return "(未配置)";
        }
        if (key.length() <= 8) {
            return "***";
        }
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
}
