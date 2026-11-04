package com.blackshore.apt.processor.config;

import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定位并加载 application.yml，再按「配置文件 &lt; 环境变量 &lt; -D 系统属性 &lt; javac -A 参数」
 * 的优先级合并成 {@link AiAptConfig}。
 */
public final class ConfigLoader {

    /** javac -A / -D 使用的键前缀。 */
    public static final String OPTION_PREFIX = "ai.apt.";
    private static final String ENV_PREFIX = "AI_APT_";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final String[] CLASSPATH_CANDIDATES = {
            "application.yml", "application.yaml", "config/application.yml", "ai-apt.yml", "config/ai-apt.yml"
    };
    private static final String[] FILE_CANDIDATES = {
            "src/main/resources/application.yml", "src/main/resources/application.yaml",
            "src/main/resources/config/application.yml", "src/main/resources/ai-apt.yml",
            "application.yml", "config/application.yml"
    };
    private static final List<String> HEADER_GROUPS = List.of("headers", "extraheaders", "additionalheaders");
    private static final List<String> VARIABLE_GROUPS = List.of("variables", "vars", "placeholders");

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> variables = new LinkedHashMap<>();
    private final Map<String, String> unknown = new LinkedHashMap<>();
    private final AptLog log;

    private ConfigLoader(AptLog log) {
        this.log = log;
    }

    /**
     * @param options javac 传入的 -A 参数（processingEnv.getOptions()）
     */
    public static AiAptConfig load(Map<String, String> options, AptLog log) {
        ConfigLoader loader = new ConfigLoader(log);
        return loader.doLoad(options == null ? Map.of() : options);
    }

    private AiAptConfig doLoad(Map<String, String> options) {
        Path projectDir = resolveProjectDir(options);
        Source source = locate(options, projectDir);
        if (source != null) {
            parseInto(source);
        } else {
            log.warn("未找到 application.yml，全部使用默认配置（工作目录: %s）", projectDir);
        }
        applyEnvironment();
        applySystemProperties();
        applyOptions(options);

        Map<String, String> resolved = interpolate(values);
        Map<String, String> resolvedHeaders = interpolate(headers);
        Map<String, String> resolvedVariables = interpolate(variables);
        for (String key : unknown.keySet()) {
            log.info("忽略无法识别的配置项: %s=%s", key, ConfigKeys.mask(unknown.get(key)));
        }
        return new AiAptConfig(resolved, resolvedHeaders, resolvedVariables, projectDir,
                source == null ? null : source.description(), source == null ? null : source.kind());
    }

    // ------------------------------------------------------------------ 定位

    private Path resolveProjectDir(Map<String, String> options) {
        String configured = options.get(OPTION_PREFIX + "project.dir");
        if (!Utils.isBlank(configured)) {
            Path path = Path.of(configured.trim());
            if (Files.isDirectory(path)) {
                return path.toAbsolutePath().normalize();
            }
            log.warn("-A%sproject.dir 指向的目录不存在: %s", OPTION_PREFIX, configured);
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private record Source(String kind, String description, String content) {
    }

    private Source locate(Map<String, String> options, Path projectDir) {
        String explicit = options.get(OPTION_PREFIX + "config");
        if (!Utils.isBlank(explicit)) {
            Path path = projectDir.resolve(explicit.trim()).normalize();
            if (Files.isReadable(path)) {
                return readFile(path);
            }
            Path raw = Path.of(explicit.trim());
            if (Files.isReadable(raw)) {
                return readFile(raw.toAbsolutePath().normalize());
            }
            log.warn("-A%sonfig 指定的文件不可读，改为继续自动搜索: %s", OPTION_PREFIX, explicit);
        }
        for (String candidate : CLASSPATH_CANDIDATES) {
            Source source = readClasspath(candidate);
            if (source != null) {
                return source;
            }
        }
        List<Path> roots = new ArrayList<>(List.of(projectDir, Path.of("").toAbsolutePath().normalize()));
        for (Path root : roots) {
            for (String candidate : FILE_CANDIDATES) {
                Path path = root.resolve(candidate).normalize();
                if (Files.isReadable(path)) {
                    return readFile(path);
                }
            }
        }
        return null;
    }

    private Source readFile(Path path) {
        try {
            return new Source("file", path.toString(), Utils.readString(path));
        } catch (IOException e) {
            log.warn("读取配置文件失败: %s (%s)", path, e.getMessage());
            return null;
        }
    }

    private Source readClasspath(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader() == null
                ? ConfigLoader.class.getClassLoader()
                : Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new Source("classpath", "classpath:" + resource, Utils.readAll(in));
        } catch (IOException e) {
            log.warn("读取 classpath 配置失败: %s (%s)", resource, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 解析

    private void parseInto(Source source) {
        Map<String, Object> tree;
        try {
            tree = YamlLite.parse(source.content());
        } catch (RuntimeException e) {
            log.error("配置文件 %s 解析失败: %s", source.description(), e.getMessage());
            return;
        }
        walk(tree, List.of());
        log.info("已加载配置: %s（%d 项）", source.description(), values.size() + headers.size() + variables.size());
    }

    /** 递归遍历 YAML 树，把叶子按规范键收集起来。 */
    private void walk(Map<String, Object> node, List<String> path) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            List<String> next = new ArrayList<>(path);
            next.addAll(splitKey(entry.getKey()));
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) child;
                if (casted.isEmpty()) {
                    store(next, "");
                } else {
                    walk(casted, next);
                }
            } else if (value instanceof List<?> list) {
                List<String> items = new ArrayList<>();
                for (Object item : list) {
                    items.add(item == null ? "" : String.valueOf(item));
                }
                store(next, String.join(",", items));
            } else {
                store(next, value == null ? "" : String.valueOf(value));
            }
        }
    }

    private static List<String> splitKey(String key) {
        List<String> segments = new ArrayList<>();
        for (String part : key.split("\\.")) {
            if (!part.isBlank()) {
                segments.add(part.trim());
            }
        }
        return segments;
    }

    private void store(List<String> rawPath, String value) {
        List<String> path = new ArrayList<>(rawPath);
        if (!path.isEmpty() && ConfigKeys.isRootPrefix(path.get(0))) {
            path.remove(0);
        }
        if (path.isEmpty()) {
            return;
        }
        String head = ConfigKeys.normalize(path.get(0));
        if (path.size() > 1 && HEADER_GROUPS.contains(head)) {
            headers.put(String.join("-", path.subList(1, path.size())), value);
            return;
        }
        if (path.size() > 1 && VARIABLE_GROUPS.contains(head)) {
            variables.put(String.join("-", path.subList(1, path.size())), value);
            return;
        }
        List<String> normalized = path.stream().map(ConfigKeys::normalize).toList();
        String canonical = ConfigKeys.canonicalize(normalized);
        if (ConfigKeys.isKnown(canonical)) {
            values.put(canonical, value);
        } else {
            unknown.put(String.join(".", path), value);
        }
    }

    private void applyEnvironment() {
        Map<String, String> env = System.getenv();
        List<String> keys = new ArrayList<>(env.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (!key.startsWith(ENV_PREFIX) || key.length() == ENV_PREFIX.length()) {
                continue;
            }
            putOverride(List.of(key.substring(ENV_PREFIX.length())), env.get(key), "环境变量 " + key);
        }
    }

    private void applySystemProperties() {
        List<String> names = new ArrayList<>(System.getProperties().stringPropertyNames());
        names.sort(String::compareTo);
        for (String name : names) {
            if (name.startsWith(OPTION_PREFIX)) {
                putOverride(splitKey(name.substring(OPTION_PREFIX.length())), System.getProperty(name),
                        "系统属性 " + name);
            }
        }
    }

    private void applyOptions(Map<String, String> options) {
        List<String> names = new ArrayList<>(options.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            if (!name.startsWith(OPTION_PREFIX) || name.equals(OPTION_PREFIX + "config")
                    || name.equals(OPTION_PREFIX + "project.dir")) {
                continue;
            }
            putOverride(splitKey(name.substring(OPTION_PREFIX.length())), options.get(name), "编译参数 -A" + name);
        }
    }

    /** 覆盖优先级：后写入者胜出，空值视为“未配置”，不参与覆盖。 */
    private void putOverride(List<String> rawPath, String value, String origin) {
        if (value == null || value.isBlank()) {
            return;
        }
        List<String> path = new ArrayList<>(rawPath);
        if (!path.isEmpty() && ConfigKeys.isRootPrefix(path.get(0))) {
            path.remove(0);
        }
        if (path.isEmpty()) {
            return;
        }
        String head = ConfigKeys.normalize(path.get(0));
        if (head.equals("config") || head.startsWith("project")) {
            return;
        }
        if (path.size() > 1 && HEADER_GROUPS.contains(head)) {
            headers.put(String.join("-", path.subList(1, path.size())), value);
            return;
        }
        if (path.size() > 1 && VARIABLE_GROUPS.contains(head)) {
            variables.put(String.join("-", path.subList(1, path.size())), value);
            return;
        }
        String canonical = ConfigKeys.canonicalize(path.stream().map(ConfigKeys::normalize).toList());
        if (!ConfigKeys.isKnown(canonical)) {
            unknown.put(String.join(".", path), value);
            return;
        }
        values.put(canonical, value);
        log.info("配置 %s 被%s覆盖为 %s", canonical, origin,
                canonical.equals(ConfigKeys.API_KEY) ? ConfigKeys.mask(value) : Utils.truncate(value, 120));
    }

    // ------------------------------------------------------------------ 占位符

    /** 展开 ${ENV} / ${ENV:default}，让密钥不必写死在配置文件里。 */
    private Map<String, String> interpolate(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            result.put(entry.getKey(), expand(entry.getValue()));
        }
        return result;
    }

    private static String expand(String text) {
        if (text == null || text.indexOf("${") < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String defaultValue = matcher.group(2);
            String resolved = System.getenv(name);
            if (Utils.isBlank(resolved)) {
                resolved = System.getProperty(name);
            }
            if (Utils.isBlank(resolved)) {
                resolved = defaultValue == null ? "" : defaultValue;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
