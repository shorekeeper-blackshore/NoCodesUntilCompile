package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 JDK 自带的 javac 在内存里预校验 AI 生成的代码。
 *
 * <p>这样做的意义：AI 产出的编译错误在写进 Filer 之前就能拿到诊断，从而把错误回喂给模型自愈，
 * 而不是让整个构建挂掉。</p>
 */
public final class CompileValidator {

    private static final int MAX_REPORTED_ERRORS = 15;

    /** checked=false 表示环境里拿不到 javac，只能跳过校验。 */
    public record Outcome(boolean checked, boolean success, List<String> errors) {

        public static Outcome skipped(String reason) {
            return new Outcome(false, true, List.of(reason));
        }

        public String describe() {
            return String.join(System.lineSeparator(), errors);
        }

        /** 给模型看的紧凑诊断文本。 */
        public String forModel() {
            List<String> shown = errors.size() <= MAX_REPORTED_ERRORS
                    ? errors : errors.subList(0, MAX_REPORTED_ERRORS);
            StringBuilder sb = new StringBuilder();
            for (String error : shown) {
                sb.append("- ").append(error).append('\n');
            }
            if (errors.size() > MAX_REPORTED_ERRORS) {
                sb.append("- ...(其余 ").append(errors.size() - MAX_REPORTED_ERRORS).append(" 条已省略)\n");
            }
            return sb.toString();
        }
    }

    private final AiAptConfig config;
    private final AptLog log;
    private final String interfaceFqn;
    private final String release;
    private final Path workDir;
    private final JavaCompiler compiler;
    private String cachedInterfaceSource;
    private int round;

    public CompileValidator(AiAptConfig config, AptLog log, String interfaceFqn, String release) {
        this.config = config;
        this.log = log;
        this.interfaceFqn = interfaceFqn;
        this.release = release;
        this.workDir = config.workDir().resolve("checks");
        this.compiler = ToolProvider.getSystemJavaCompiler();
    }

    public Outcome validate(String simpleClassName, String source) {
        if (compiler == null) {
            return Outcome.skipped("运行当前构建的 JVM 中没有 javac（可能是 JRE），已跳过生成代码的预编译校验");
        }
        round++;
        Path outDir = workDir.resolve("round-" + round);
        try {
            Utils.mkdirs(outDir);
        } catch (IOException e) {
            return Outcome.skipped("无法创建校验输出目录: " + e.getMessage());
        }

        List<JavaFileObject> units = new ArrayList<>();
        units.add(new InMemorySource(simpleClassName + ".java", source));
        units.add(new InMemorySource(Utils.simpleName(interfaceFqn) + ".java", interfaceSource()));

        List<String> options = new ArrayList<>(List.of(
                "-proc:none",
                "-encoding", "UTF-8",
                "-nowarn",
                "-d", outDir.toString(),
                "-classpath", config.compileClasspath()));
        if (!Utils.isBlank(release)) {
            options.add("--release");
            options.add(release);
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8);
        try {
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, collector, options, List.of(), units);
            boolean success = Boolean.TRUE.equals(task.call());
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(String.format("%s:%d:%d %s",
                            sourceName(diagnostic),
                            diagnostic.getLineNumber(), diagnostic.getColumnNumber(),
                            diagnostic.getMessage(null)));
                }
            }
            if (!success && errors.isEmpty()) {
                errors.add("javac 返回失败但没有给出具体诊断");
            }
            return new Outcome(true, success, errors);
        } finally {
            try {
                fileManager.close();
            } catch (IOException ignored) {
                // 关闭失败不影响结果
            }
        }
    }

    /** 只保留文件名，让回喂给模型的诊断更短。 */
    private static String sourceName(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject source = diagnostic.getSource();
        if (source == null) {
            return "<unknown>";
        }
        String name = source.getName().replace('\\', '/');
        int index = name.lastIndexOf('/');
        return index < 0 ? name : name.substring(index + 1);
    }

    /** 优先用工程里真实的接口源码，保证校验签名与真实契约一致。 */
    private String interfaceSource() {
        if (cachedInterfaceSource != null) {
            return cachedInterfaceSource;
        }
        String relative = "src/main/java/" + interfaceFqn.replace('.', '/') + ".java";
        List<Path> candidates = new ArrayList<>();
        if (config.projectDir() != null) {
            candidates.add(config.projectDir().resolve(relative));
        }
        candidates.add(Path.of(relative));
        for (Path candidate : candidates) {
            if (Files.isReadable(candidate)) {
                try {
                    cachedInterfaceSource = Utils.readString(candidate);
                    log.info("编译校验使用真实接口源码: %s", candidate);
                    return cachedInterfaceSource;
                } catch (IOException e) {
                    log.warn("读取接口源码失败: %s (%s)", candidate, e.getMessage());
                }
            }
        }
        cachedInterfaceSource = stubInterfaceSource();
        log.warn("未找到 %s 的源码，编译校验改用等价的桩接口定义", interfaceFqn);
        return cachedInterfaceSource;
    }

    private String stubInterfaceSource() {
        int index = interfaceFqn.lastIndexOf('.');
        String pkg = index < 0 ? "" : interfaceFqn.substring(0, index);
        String simple = interfaceFqn.substring(index + 1);
        return (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n")
                + "public interface " + simple + " {\n    void run(String[] args);\n}\n";
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String content;

        InMemorySource(String fileName, String content) {
            super(URI.create("mem:///ai-apt/" + fileName), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
