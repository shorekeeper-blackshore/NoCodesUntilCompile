package com.blackshore.apt.processor;

import com.blackshore.apt.processor.code.AiCodeGenerator;
import com.blackshore.apt.processor.code.BuiltInSnippets;
import com.blackshore.apt.processor.code.GeneratedCode;
import com.blackshore.apt.processor.code.SourceFile;
import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.config.AnnotationOverlay;
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
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编译期注解处理器：扫描 {@code @Entrance}，把需求交给 AI 生成实现
 * {@link AiAptConfig#ENTRANCE_INTERFACE} 的类，并用 Filer 写回本次编译。
 *
 * <p>配置以 application.yml 为准：每个目标上 {@code @Entrance} 里显式写出的项（见
 * {@link AnnotationOverlay}）只作为该目标的默认值，只对配置文件没写的项生效，
 * 因此同一个工程可以让不同入口各走各的模型与 prompt，而改配置文件就能统一压住。</p>
 *
 * <p>{@code @Entrance} 也可以写在自定义注解上当组合注解用（类上只写 {@code @AiApp} 一行），
 * 直接写在类上的配置优先于继承来的。</p>
 *
 * <p>输出默认可以不止一个文件（{@code output.multi-file}，缺省开），也不止一层包
 * （{@code output.sub-packages}，缺省开，子包最深 2 级）：模型可以一次给出入口类文件加若干子包里的辅助类文件，
 * 入口类永远住在目标包（运行期反射加载的位置），辅助类按职责住进目标包之下的子包。
 * 它们一起预编译校验、由 {@link AiCodeGenerator.NameGuard} 逐个查类型名与包名是否已被工程占用，
 * 最后全部写回本次编译。</p>
 *
 * <p>处理器只依赖 JDK（javax.annotation.processing / java.net.http / 自带的 JSON 与 YAML 解析），
 * 因此可以被单独放进 {@code -processorpath}。</p>
 */
@SupportedAnnotationTypes(AiAptConfig.ENTRANCE_ANNOTATION)
public final class AiCodeProcessor extends AbstractProcessor {

    private final Set<String> generatedTypes = new LinkedHashSet<>();
    /** 本次编译里由我们新建的包；辅助文件只能住在目标包子树里，不能往工程已有的包里插。 */
    private final Set<String> generatedPackages = new LinkedHashSet<>();
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
        Set<Element> targets = targets(round, entranceType);
        if (targets.isEmpty()) {
            return false;
        }
        initialize();
        if (!config.enabled()) {
            log(Diagnostic.Kind.NOTE, "enabled=false，跳过 " + targets.size() + " 个 @Entrance 的 AI 代码生成");
            return true;
        }
        for (Element target : targets) {
            generateFor(target);
        }
        return true;
    }

    /**
     * 本轮要处理的入口类：直接标了 {@code @Entrance} 的，以及通过组合注解（自己也被 {@code @Entrance}
     * 标注的注解，类比 Spring 的 @SpringBootApplication）间接带上配置的。
     * 注解类型本身只是配置载体，不作为生成目标。
     */
    private Set<Element> targets(RoundEnvironment round, TypeElement entranceType) {
        Set<Element> targets = new LinkedHashSet<>();
        for (Element element : round.getElementsAnnotatedWith(entranceType)) {
            if (element.getKind() == ElementKind.ANNOTATION_TYPE) {
                log(Diagnostic.Kind.NOTE, element + " 只是配置载体（组合注解），不为它生成代码");
                continue;
            }
            targets.add(element);
        }
        for (Element element : round.getRootElements()) {
            if (element instanceof TypeElement type && type.getKind() == ElementKind.CLASS
                    && !type.getAnnotationMirrors().isEmpty() && AnnotationOverlay.carriesEntrance(element)) {
                targets.add(element);
            }
        }
        return targets;
    }

    // ------------------------------------------------------------------ 主流程

    private void generateFor(Element target) {
        String targetClass = ((TypeElement) target).getQualifiedName().toString();
        // 注解只作为当前目标的默认值：application.yml / 环境变量 / -A 写了非空值的项依旧以它们为准
        AnnotationOverlay overlay = AnnotationOverlay.read(target);
        AiAptConfig effective = config.withAnnotationDefaults(overlay);
        if (!effective.targetEnabled()) {
            log(Diagnostic.Kind.NOTE, "@Entrance(enabled = false)，跳过 " + targetClass);
            return;
        }
        log(Diagnostic.Kind.NOTE, "AI 代码生成 | " + effective.describe());
        if (!overlay.isEmpty()) {
            log(Diagnostic.Kind.NOTE, overlay.carrierLabel() + " 默认值 " + targetClass + ": " + overlay.describe());
            List<String> shadowed = config.shadowedKeys(overlay);
            if (!shadowed.isEmpty()) {
                log(Diagnostic.Kind.NOTE, "其中被 application.yml 等更高优先级来源压住（想让注解生效请删掉配置文件的对应项）: "
                        + String.join(", ", shadowed));
            }
        }
        // 没有显式配置包名时，跟随被注解类所在的包，保证与 Main 反射加载的约定自然对齐
        String pkg = effective.hasOutputPackage() ? effective.outputPackage() : packageOf(target);
        String className = effective.outputClassName();
        String fqn = pkg + "." + className;
        if (!generatedTypes.add(fqn)) {
            log(Diagnostic.Kind.WARNING, "生成目标 " + fqn + " 已被前面的 @Entrance 占用，跳过 " + targetClass
                    + "；给每个入口写上 className 或 pkg 就能各生成一个");
            return;
        }
        if (fqn.equals(targetClass)) {
            error("@Entrance 标注在 " + targetClass + " 上，而生成目标也是它本身，请调整 className 或 output.class-name");
            return;
        }
        log(Diagnostic.Kind.NOTE, "生成目标: " + fqn + "  ← " + targetClass);

        String requirement = effective.prompt();
        if (Utils.isBlank(requirement)) {
            log(Diagnostic.Kind.WARNING, "未配置 prompt（需求），本次使用内置示例需求: "
                    + PromptTemplates.describeMissingRequirement());
            requirement = PromptTemplates.describeMissingRequirement();
        } else {
            log(Diagnostic.Kind.NOTE, "需求: " + PromptTemplates.brief(requirement)
                    + (Utils.isBlank(config.prompt()) ? "（取自 @Entrance）" : "（取自 application.yml）"));
        }

        long start = System.currentTimeMillis();
        try {
            AiCodeGenerator generator = new AiCodeGenerator(effective, log, release,
                    generated -> nameConflict(generated, pkg));
            GeneratedCode code = generator.generate(requirement, pkg, className);
            writeAll(code, target);
            log(Diagnostic.Kind.NOTE, String.format("已生成 %d 个文件，入口 %s（%s，预编译校验=%s，AI 请求 %d 次，耗时 %d ms）",
                    code.fileCount(), fqn, code.originLabel(), code.verified() ? "通过" : "未通过", code.aiCalls(),
                    System.currentTimeMillis() - start));
            if (code.fileCount() > 1) {
                log(Diagnostic.Kind.NOTE, "包结构: " + code.structure());
            }
        } catch (WriteException e) {
            error(e.getMessage());
        } catch (AiCodeGenerator.GenerationException e) {
            error("生成 " + fqn + " 失败: " + e.getMessage()
                    + "；可调整 application.yml（或 @Entrance(model = @Model(...))）的 api-key/provider，"
                    + "或把 fallback 设为 demo/stub 让构建继续");
        } catch (FilerException e) {
            log(Diagnostic.Kind.NOTE, "跳过重复生成: " + e.getMessage());
        } catch (RuntimeException | IOException e) {
            log(Diagnostic.Kind.WARNING, "生成 " + fqn + " 失败: " + e);
            writeFallback(effective, pkg, className, fqn, target, e.toString(), requirement);
        }
    }

    /**
     * 入口文件排在第一个，辅助文件紧随其后；任何一个文件写不下去都必须报错，
     * 否则入口会引用不到辅助类。写成功的名字全部占位，后面的 @Entrance 不能再生成同名类型。
     */
    private void writeAll(GeneratedCode code, Element target) throws IOException, WriteException {
        for (SourceFile file : code.files()) {
            try {
                write(file.fqn(), file.source(), target);
            } catch (FilerException e) {
                throw new WriteException("写入 " + file.pathName() + " 失败，目标 " + file.fqn()
                        + " 在本次编译里已经被占用：请换个辅助类型名或子包名（都不能与工程已有的类型/包重名），"
                        + "也可以把 output.multi-file 设为 false 只生成入口类");
            }
            generatedTypes.add(file.fqn());
            generatedPackages.add(file.packageName());
        }
    }

    /** 生成好的文件写不回编译（通常是类型名与已有类撞车），需要让构建明明白白地失败。 */
    private static final class WriteException extends IOException {
        WriteException(String message) {
            super(message);
        }
    }

    /**
     * 生成文件能不能落到这个全限定名上：类型名不能与工程已有的类（含本次待编译的源码）撞车；
     * 子包也必须是新建的，不能把生成代码塞进工程已有的包里，否则事后很难分清谁写的。
     * 入口类自己不受子包规则约束（它永远住在目标包）。
     */
    private String nameConflict(String fqn, String rootPkg) {
        if (generatedTypes.contains(fqn)) {
            return "与本次编译中另一个生成目标同名";
        }
        int index = fqn.lastIndexOf('.');
        String pkg = index < 0 ? "" : fqn.substring(0, index);
        if (!pkg.equals(rootPkg) && !generatedPackages.contains(pkg)
                && processingEnv.getElementUtils().getPackageElement(pkg) != null) {
            return "包 " + pkg + " 已经存在于工程里，请换个新的子包名（子包必须开在 " + rootPkg + " 之下）";
        }
        TypeElement existing = processingEnv.getElementUtils().getTypeElement(fqn);
        return existing == null ? null : "工程里已经存在 " + fqn;
    }

    private void writeFallback(AiAptConfig effective, String pkg, String className, String fqn, Element target,
                               String reason, String requirement) {
        if (effective.fallback() == AiAptConfig.Fallback.ERROR || effective.failOnError()) {
            error("无法为 " + fqn + " 写出兜底实现，因为 fallback=error 或 fail-on-error=true：" + reason);
            return;
        }
        String body = effective.fallback() == AiAptConfig.Fallback.STUB
                ? BuiltInSnippets.stub(pkg, className, reason)
                : BuiltInSnippets.demo(pkg, className, requirement, reason);
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
