package com.blackshore.apt.processor;

import com.blackshore.apt.processor.code.AiCodeGenerator;
import com.blackshore.apt.processor.code.BuiltInSnippets;
import com.blackshore.apt.processor.code.GeneratedCode;
import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.config.ConfigKeys;
import com.blackshore.apt.processor.config.ConfigLoader;
import com.blackshore.apt.processor.prompt.PromptTemplates;
import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 编译期注解处理器：扫描 {@code @Entrance}，把 application.yml 里的需求交给 AI 生成
 * 一个实现 {@link AiAptConfig#ENTRANCE_INTERFACE} 的类，并用 Filer 写回本次编译。
 *
 * <p>处理器只依赖 JDK（javax.annotation.processing / java.net.http / 自带的 JSON 与 YAML 解析），
 * 因此可以被单独放进 {@code -processorpath}。</p>
 */
@SupportedAnnotationTypes(AiAptConfig.ENTRANCE_ANNOTATION)
public final class AiCodeProcessor extends AbstractProcessor {

    private final Set<String> generatedTypes = new LinkedHashSet<>();
    private AptLog log;
    private AiAptConfig config;
    private String release;
    private boolean initialized;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedOptions() {
        Set<String> options = new LinkedHashSet<>();
        options.add(ConfigLoader.OPTION_PREFIX + "config");
        options.add(ConfigLoader.OPTION_PREFIX + "project.dir");
        // javac 只接受合法标识符形式的选项名，所以对外用驼峰：-Aai.apt.baseUrl=... / -Aai.apt.maxTokens=...
        for (String key : ConfigKeys.CANONICAL_KEYS) {
            options.add(ConfigLoader.OPTION_PREFIX + toCamelCase(key));
        }
        return options;
    }

    private static String toCamelCase(String kebab) {
        StringBuilder sb = new StringBuilder(kebab.length());
        boolean upper = false;
        for (int i = 0; i < kebab.length(); i++) {
            char c = kebab.charAt(i);
            if (c == '-') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            return false;
        }
        try {
            return handle(round);
        } catch (RuntimeException e) {
            String message = "AI 代码生成过程中出现未预期的异常: " + e;
            if (config != null && config.failOnError()) {
                error(message);
            } else {
                log(Diagnostic.Kind.WARNING, message + "（已写出生成日志，可用 fallback 策略继续构建）");
            }
            return false;
        }
    }

    private boolean handle(RoundEnvironment round) {
        TypeElement entranceType = entranceType();
        if (entranceType == null) {
            return false;
        }
        Set<? extends Element> targets = round.getElementsAnnotatedWith(entranceType);
        if (targets.isEmpty()) {
            return false;
        }
        initialize();
        if (!config.enabled()) {
            log(Diagnostic.Kind.NOTE, "enabled=false，跳过 " + targets.size() + " 个 @Entrance 的 AI 代码生成");
            return true;
        }
        log(Diagnostic.Kind.NOTE, "AI 代码生成开始 | " + config.describe());
        for (Element target : targets) {
            generateFor(target);
        }
        return true;
    }

    // ------------------------------------------------------------------ 主流程

    private void generateFor(Element target) {
        String targetClass = ((TypeElement) target).getQualifiedName().toString();
        Map<String, Object> attributes = annotationAttributes(target);
        // 没有显式配置包名时，跟随被注解类所在的包，保证与 Main 反射加载的约定自然对齐
        String pkg = config.hasOutputPackage() ? config.outputPackage() : packageOf(target);
        String className = Utils.firstNonBlank(config.outputClassName(), readString(attributes, "className"));
        String fqn = pkg + "." + className;
        if (!generatedTypes.add(fqn)) {
            return;
        }
        if (fqn.equals(targetClass)) {
            error("@Entrance 标注在 " + targetClass + " 上，而生成目标也是它本身，请调整 output.class-name");
            return;
        }

        String requirement = Utils.firstNonBlank(config.prompt(), readString(attributes, "prompt"));
        if (Utils.isBlank(requirement)) {
            log(Diagnostic.Kind.WARNING, "未配置 prompt（需求），本次使用内置示例需求: "
                    + PromptTemplates.describeMissingRequirement());
            requirement = PromptTemplates.describeMissingRequirement();
        } else {
            log(Diagnostic.Kind.NOTE, "需求: " + PromptTemplates.brief(requirement) + "  ← " + targetClass);
        }

        long start = System.currentTimeMillis();
        try {
            AiCodeGenerator generator = new AiCodeGenerator(config, log, release);
            GeneratedCode code = generator.generate(requirement, pkg, className);
            write(fqn, code.source(), target);
            log(Diagnostic.Kind.NOTE, String.format("已生成 %s（%s，预编译校验=%s，AI 请求 %d 次，耗时 %d ms）",
                    fqn, code.originLabel(), code.verified() ? "通过" : "未通过", code.aiCalls(),
                    System.currentTimeMillis() - start));
        } catch (AiCodeGenerator.GenerationException e) {
            error("生成 " + fqn + " 失败: " + e.getMessage()
                    + "；可调整 application.yml 的 api-key/provider，或把 fallback 设为 demo/stub 让构建继续");
        } catch (FilerException e) {
            log(Diagnostic.Kind.NOTE, "跳过重复生成: " + e.getMessage());
        } catch (RuntimeException | IOException e) {
            log(Diagnostic.Kind.WARNING, "生成 " + fqn + " 失败: " + e);
            writeFallback(pkg, className, fqn, target, e.toString());
        }
    }

    private void writeFallback(String pkg, String className, String fqn, Element target, String reason) {
        if (config.fallback() == AiAptConfig.Fallback.ERROR || config.failOnError()) {
            error("无法为 " + fqn + " 写出兜底实现，因为 fallback=error 或 fail-on-error=true：" + reason);
            return;
        }
        String body = config.fallback() == AiAptConfig.Fallback.STUB
                ? BuiltInSnippets.stub(pkg, className, reason)
                : BuiltInSnippets.demo(pkg, className, config.prompt(), reason);
        try {
            write(fqn, body, target);
            log(Diagnostic.Kind.WARNING, "已写出兜底实现 " + fqn + "，构建可以继续，但入口不是 AI 生成的代码");
        } catch (IOException e) {
            error("写入兜底实现失败: " + e.getMessage());
        }
    }

    private void write(String fqn, String source, Element origin) throws IOException {
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, origin);
        try (Writer writer = file.openWriter()) {
            writer.write(source);
        }
    }

    // ------------------------------------------------------------------ 初始化

    private void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        log = new AptLog(processingEnv.getMessager());
        Map<String, String> options = processingEnv.getOptions() == null ? Map.of() : processingEnv.getOptions();
        config = ConfigLoader.load(options, log);
        try {
            Utils.mkdirs(config.workDir());
            log.attachFile(config.workDir().resolve("apt-compiler.log"));
        } catch (IOException e) {
            log(Diagnostic.Kind.NOTE, "工作目录不可用: " + e.getMessage());
        }
        release = sourceRelease();
    }

    private String sourceRelease() {
        SourceVersion version = processingEnv.getSourceVersion();
        if (version == null) {
            return String.valueOf(Runtime.version().feature());
        }
        String name = version.name();
        if (!name.startsWith("RELEASE_")) {
            return String.valueOf(Runtime.version().feature());
        }
        return name.substring("RELEASE_".length()).replace('_', '.');
    }

    private TypeElement entranceType() {
        return processingEnv.getElementUtils().getTypeElement(AiAptConfig.ENTRANCE_ANNOTATION);
    }

    private static String packageOf(Element element) {
        Element current = element;
        while (current != null && !(current instanceof PackageElement)) {
            current = current.getEnclosingElement();
        }
        return current == null ? ConfigKeys.DEFAULT_PACKAGE : ((PackageElement) current).getQualifiedName().toString();
    }

    /** SOURCE 保留级的注解只能靠 AnnotationMirror 读值。 */
    private static Map<String, Object> annotationAttributes(Element element) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (!AiAptConfig.ENTRANCE_ANNOTATION.equals(mirror.getAnnotationType().toString())) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : mirror.getElementValues().entrySet()) {
                values.put(entry.getKey().getSimpleName().toString(), entry.getValue().getValue());
            }
        }
        return values;
    }

    private static String readString(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void log(Diagnostic.Kind kind, String message) {
        if (log == null) {
            processingEnv.getMessager().printMessage(kind, "[AI-APT] " + message);
        } else if (kind == Diagnostic.Kind.ERROR) {
            log.error("%s", message);
        } else if (kind == Diagnostic.Kind.WARNING) {
            log.warn("%s", message);
        } else {
            log.info("%s", message);
        }
    }

    private void error(String message) {
        log(Diagnostic.Kind.ERROR, message);
    }
}
