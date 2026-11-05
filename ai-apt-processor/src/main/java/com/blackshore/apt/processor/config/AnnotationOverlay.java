package com.blackshore.apt.processor.config;

import com.blackshore.apt.processor.util.Utils;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@code @Entrance} 里显式写出的配置读成一份「默认值」，键与 {@link ConfigKeys} 的规范键一致，
 * 由 {@link AiAptConfig#withAnnotationDefaults} 合在 application.yml 之下（配置文件写了非空值就压住注解）。
 *
 * <p>三条关键规则：</p>
 * <ul>
 *   <li>{@code AnnotationMirror.getElementValues()} 只包含显式写出的成员，默认值不在其中，
 *       所以嵌套分组里也只精确取写到的那几项；</li>
 *   <li>成员名沿用配置文件的书写习惯（model/provider、prompt/system、repair/rounds、cache/dir、
 *       output/class-name、fallback/mode、debug/work-dir…），收敛交给 {@link ConfigKeys} 的别名表，
 *       只有各分组的 {@code value()} 与顶层 {@code enabled} 需要在这里额外指定归属；</li>
 *   <li>同一个规范键可能有多种写法（value 与 task），用书写强度定序，不依赖注解成员的遍历顺序；</li>
 *   <li>支持组合注解（像 Spring 的 @SpringBootApplication 那样）：如果类上的某个注解自己又被
 *       {@code @Entrance} 标注，则该配置会继承给这个类；类上直接写的 {@code @Entrance} 优先于继承来的。</li>
 * </ul>
 */
public final class AnnotationOverlay {

    /** 分组路径 -&gt; 该分组 value() 成员对应的规范键；空路径就是 @Entrance 自身的 value()。 */
    private static final Map<String, String> PRIMARY = Map.of(
            "", ConfigKeys.PROMPT,
            "prompt", ConfigKeys.PROMPT,
            "model", ConfigKeys.MODEL,
            "fallback", ConfigKeys.FALLBACK
    );

    /** 必须改写归属的路径：顶层 enabled 是“只关这一个入口”，不能和全局开关撞车。 */
    private static final Map<String, String> FORCED = Map.of("enabled", ConfigKeys.TARGET_ENABLED);

    private static final Set<String> HEADER_MEMBERS = Set.of("headers", "extraheaders", "additionalheaders");
    private static final Set<String> VARIABLE_MEMBERS = Set.of("variables", "vars", "placeholders");

    /** 这些规范键是给人写的多行文本，数组元素按行拼接；其余数组用逗号连接。 */
    private static final Set<String> TEXT_KEYS = Set.of(
            ConfigKeys.PROMPT, ConfigKeys.SYSTEM_PROMPT, ConfigKeys.CUSTOM_PROMPT, ConfigKeys.REPAIR_PROMPT);

    private static final String PATH_SEPARATOR = System.getProperty("path.separator", ";");

    /** 来源层级：直接写在类上的 @Entrance 优先于组合注解里带的，嵌套越深优先级越低。 */
    private static final int LAYER_DIRECT = 2;
    private static final int MAX_META_DEPTH = 3;

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> variables = new LinkedHashMap<>();
    /** 规范键 -&gt; 写入强度：显式成员名（task/requirement）比简写的 value() 更明确。 */
    private final Map<String, Integer> strength = new LinkedHashMap<>();
    private final Set<String> touched = new LinkedHashSet<>();
    private final List<String> ignored = new ArrayList<>();
    /** 是否找到了 @Entrance（包括通过组合注解继承），与“写没写值”区分开。 */
    private boolean discovered;
    /** 贡献配置的注解名，用于日志区分直接写的与组合注解继承的。 */
    private final Set<String> carriers = new LinkedHashSet<>();

    private AnnotationOverlay() {
    }

    /** 读取元素上的 {@code @Entrance}，包括写在组合注解（元注解）上的那份。 */
    public static AnnotationOverlay read(Element target) {
        AnnotationOverlay overlay = new AnnotationOverlay();
        if (target != null) {
            collect(target.getAnnotationMirrors(), overlay, LAYER_DIRECT, 0);
        }
        return overlay;
    }

    /** 该元素是否直接或间接（通过组合注解）带着 {@code @Entrance}。 */
    public static boolean carriesEntrance(Element element) {
        return read(element).discovered();
    }

    /** 是否发现了 @Entrance（哪怕一个成员都没写）。 */
    public boolean discovered() {
        return discovered;
    }

    /** 配置来源标注，日志用：直接写的是 @Entrance，继承来的是 @AiApp 之类。 */
    public String carrierLabel() {
        return carriers.isEmpty() ? "@Entrance" : String.join(" + ", carriers);
    }

    private static void collect(Iterable<? extends AnnotationMirror> mirrors, AnnotationOverlay overlay,
                                int layer, int depth) {
        for (AnnotationMirror mirror : mirrors) {
            String type = mirror.getAnnotationType().toString();
            if (AiAptConfig.ENTRANCE_ANNOTATION.equals(type)) {
                overlay.discovered = true;
                if (layer >= LAYER_DIRECT) {
                    overlay.carriers.add("@Entrance");
                }
                overlay.addElementValues(List.of(), mirror, layer);
                continue;
            }
            if (depth >= MAX_META_DEPTH || type.startsWith("java.")) {
                continue;
            }
            Element declaration = mirror.getAnnotationType().asElement();
            if (declaration != null && declaration.getKind() == ElementKind.ANNOTATION_TYPE) {
                // 组合注解：它自己带的 @Entrance 以更低优先级继承给当前元素
                overlay.carriers.add("@" + declaration.getSimpleName());
                collect(declaration.getAnnotationMirrors(), overlay, layer - 2, depth + 1);
            }
        }
    }

    public Map<String, String> values() {
        return values;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, String> variables() {
        return variables;
    }

    public boolean isEmpty() {
        return values.isEmpty() && headers.isEmpty() && variables.isEmpty();
    }

    /** 日志用：注解写出了哪些默认值（api-key 脱敏，长文本压成单行并截断）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String key : touched) {
            if (values.containsKey(key)) {
                String value = values.get(key);
                append(sb).append(key).append('=')
                        .append(ConfigKeys.API_KEY.equals(key) ? ConfigKeys.mask(value) : flat(Utils.truncate(value, 60)));
            }
        }
        if (!headers.isEmpty()) {
            append(sb).append("headers").append(headers.keySet());
        }
        if (!variables.isEmpty()) {
            append(sb).append("variables").append(variables.keySet());
        }
        for (String path : ignored) {
            append(sb).append("忽略").append(path);
        }
        return sb.toString();
    }

    private static StringBuilder append(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        return sb;
    }

    private static String flat(String text) {
        return text.replace("\r", "").replace("\n", "↲");
    }

    // ------------------------------------------------------------------ 遍历

    private void addElementValues(List<String> path, AnnotationMirror mirror, int layer) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : mirror.getElementValues().entrySet()) {
            String member = entry.getKey().getSimpleName().toString();
            walkValue(append(path, member), entry.getValue(), layer);
        }
    }

    private void walkValue(List<String> path, AnnotationValue annotationValue, int layer) {
        Object raw = annotationValue.getValue();
        if (raw instanceof AnnotationMirror nested) {
            addElementValues(path, nested, layer);
            return;
        }
        if (raw instanceof List<?> items) {
            putArray(path, items, layer);
            return;
        }
        putLeaf(path, raw == null ? "" : String.valueOf(raw), layer);
    }

    private void putArray(List<String> path, List<?> items, int layer) {
        String leaf = ConfigKeys.normalize(path.get(path.size() - 1));
        if (HEADER_MEMBERS.contains(leaf) || VARIABLE_MEMBERS.contains(leaf)) {
            putPairs(HEADER_MEMBERS.contains(leaf) ? headers : variables, path, items,
                    HEADER_MEMBERS.contains(leaf), layer);
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Object item : items) {
            Object value = item instanceof AnnotationValue wrapped ? wrapped.getValue() : item;
            if (value instanceof AnnotationMirror nested) {
                addElementValues(path, nested, layer);
                continue;
            }
            String text = value == null ? "" : String.valueOf(value).trim();
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        String canonical = canonicalOf(path);
        String separator = canonical != null && TEXT_KEYS.contains(canonical) ? "\n"
                : ConfigKeys.COMPILE_CLASSPATH.equals(canonical) ? PATH_SEPARATOR : ",";
        putLeaf(path, String.join(separator, parts), layer);
    }

    /** headers / variables 写成 KEY=VALUE（headers 也允许 KEY: VALUE），逐项落到对应的映射表。 */
    private void putPairs(Map<String, String> sink, List<String> path, List<?> items, boolean headersGroup, int layer) {
        String kind = headersGroup ? "header:" : "var:";
        for (Object item : items) {
            Object value = item instanceof AnnotationValue wrapped ? wrapped.getValue() : item;
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isEmpty()) {
                continue;
            }
            int sep = text.indexOf('=');
            if (headersGroup && sep < 0) {
                sep = text.indexOf(':');
            }
            if (sep <= 0 || sep == text.length() - 1) {
                ignored.add(display(path) + "[" + Utils.truncate(text, 40) + "]");
                continue;
            }
            String name = text.substring(0, sep).trim();
            if (name.isEmpty() || weaker(kind + name, layer)) {
                continue;
            }
            strength.put(kind + name, layer);
            sink.put(headersGroup ? name.replace('_', '-') : name,
                    ConfigLoader.expand(text.substring(sep + 1).trim()));
        }
    }

    private void putLeaf(List<String> path, String text, int layer) {
        if (Utils.isBlank(text)) {
            // 空串视为“没写”，让配置文件的值继续生效
            return;
        }
        String canonical = canonicalOf(path);
        if (canonical == null || !ConfigKeys.isKnown(canonical)) {
            ignored.add(display(path));
            return;
        }
        // 先比来源层级（直接写的 > 组合注解继承的），同层级里再比写法（task 这类显式名 > value 简写），
        // 不依赖注解成员的遍历顺序
        int current = layer + (isValueShorthand(path) ? 0 : 1);
        if (weaker(canonical, current)) {
            return;
        }
        strength.put(canonical, current);
        values.put(canonical, ConfigLoader.expand(text.trim()));
        touched.add(canonical);
    }

    /** 已有的来源更强（或同强且已是显式写法）时，新的写入就要跳过。 */
    private boolean weaker(String canonical, int current) {
        Integer stored = strength.get(canonical);
        return stored != null && stored > current;
    }

    private static boolean isValueShorthand(List<String> path) {
        return ConfigKeys.normalize(path.get(path.size() - 1)).equals("value");
    }

    // ------------------------------------------------------------------ 键名收敛

    private static String canonicalOf(List<String> path) {
        List<String> normalized = path.stream().map(ConfigKeys::normalize).toList();
        String joined = String.join(".", normalized);
        String forced = FORCED.get(joined);
        if (forced != null) {
            return forced;
        }
        String leaf = normalized.get(normalized.size() - 1);
        if (leaf.equals("value")) {
            String group = String.join(".", normalized.subList(0, normalized.size() - 1));
            return PRIMARY.get(group);
        }
        return ConfigKeys.canonicalize(normalized);
    }

    private static List<String> append(List<String> path, String member) {
        List<String> next = new ArrayList<>(path);
        next.add(member);
        return next;
    }

    private static String display(List<String> path) {
        return String.join(".", path);
    }
}
