package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.ai.AiClient;
import com.blackshore.apt.processor.ai.AiClientFactory;
import com.blackshore.apt.processor.ai.AiException;
import com.blackshore.apt.processor.ai.ChatMessage;
import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.prompt.PromptTemplates;
import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译期 AI 代码生成的主流程：组 prompt → 调模型（可缓存）→ 抽取源码 → 内存预编译 →
 * 失败时把诊断回喂给模型自愈 → 仍失败则按 fallback 策略兜底。
 */
public final class AiCodeGenerator {

    /** 生成彻底失败且不允许兜底时抛出，由处理器转成编译错误。 */
    public static class GenerationException extends Exception {
        public GenerationException(String message) {
            super(message);
        }
    }

    private final AiAptConfig config;
    private final AptLog log;
    private final CompileValidator validator;
    private final String release;
    private final Path workDir;
    private final Path cacheDir;
    private final AiClient client;
    private final String unavailableReason;

    public AiCodeGenerator(AiAptConfig config, AptLog log, String release) {
        this.config = config;
        this.log = log;
        this.release = release;
        this.validator = new CompileValidator(config, log, AiAptConfig.ENTRANCE_INTERFACE, release);
        this.workDir = config.workDir();
        this.cacheDir = config.cacheDir();
        AiClient created = null;
        String reason = null;
        if (!config.hasApiKey()) {
            reason = "未配置有效的 api-key（当前值看起来是占位符），已跳过 AI 调用";
        } else {
            try {
                created = AiClientFactory.create(config, log);
                log.info("AI 客户端就绪: %s", created.target());
            } catch (RuntimeException e) {
                reason = "AI 客户端初始化失败: " + e.getMessage();
            }
        }
        this.client = created;
        this.unavailableReason = reason;
    }

    /**
     * @param requirement 需求文本（来自 @Entrance.value() 或 application.yml 的 prompt）
     * @param pkg         生成的包名
     * @param className   生成的类名
     */
    public GeneratedCode generate(String requirement, String pkg, String className) throws GenerationException {
        String fqn = pkg + "." + className;
        Map<String, String> context = PromptTemplates.context(pkg, className, AiAptConfig.ENTRANCE_INTERFACE, release, requirement);
        Map<String, String> variables = config.variables();
        String systemPrompt = PromptTemplates.render(
                Utils.isBlank(config.systemPrompt()) ? PromptTemplates.SYSTEM : config.systemPrompt(), context, variables);
        String userPrompt = PromptTemplates.render(
                Utils.isBlank(config.customPrompt()) ? PromptTemplates.USER : config.customPrompt(), context, variables);
        String repairTemplate = Utils.isBlank(config.repairPrompt()) ? PromptTemplates.REPAIR : config.repairPrompt();

        String cacheKey = cacheKey(systemPrompt, userPrompt, fqn);
        saveText(workDir.resolve(className + "-prompt.txt"),
                "===== SYSTEM =====\n" + systemPrompt + "\n===== USER =====\n" + userPrompt + "\n");

        if (client == null) {
            log.warn("%s", unavailableReason);
            return fallback(unavailableReason, requirement, pkg, className, fqn, 0);
        }

        GeneratedCode cached = fromCache(cacheKey, className, fqn, requirement);
        if (cached != null) {
            return cached;
        }

        List<String> notes = new ArrayList<>();
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user(userPrompt));
        int repairRounds = config.repairEnabled() ? Math.max(0, config.repairRounds()) : 0;
        String body = null;
        CompileValidator.Outcome outcome = null;
        int calls = 0;
        String failure = null;

        for (int attempt = 0; attempt <= repairRounds; attempt++) {
            String raw;
            try {
                calls++;
                log.info("调用 AI 生成 %s（第 %d 次请求，%s）", fqn, calls, client.target());
                raw = client.complete(history, systemPrompt);
            } catch (AiException e) {
                failure = e.getMessage();
                log.warn("AI 调用失败: %s", Utils.truncate(e.getMessage(), 500));
                break;
            }
            saveText(workDir.resolve(className + "-raw-" + attempt + ".txt"), raw);

            String extracted;
            try {
                extracted = CodeExtractor.toCompilationUnit(raw, pkg, className, AiAptConfig.ENTRANCE_INTERFACE,
                        config.enforceInterface(), notes);
            } catch (CodeExtractor.ExtractionException e) {
                failure = "无法从 AI 输出中抽取 Java 源文件: " + e.getMessage();
                log.warn("%s", failure);
                if (attempt >= repairRounds) {
                    break;
                }
                history.add(ChatMessage.assistant(raw));
                history.add(ChatMessage.user("上一次的输出不能直接作为 Java 源文件（原因: " + e.getMessage()
                        + "）。请重新输出**一个完整的 Java 编译单元**，只包含 package " + pkg
                        + " 下的 public class " + className + "，用 ```java 代码块包裹，不要输出其它文字。"));
                continue;
            }
            body = extracted;
            outcome = validator.validate(className, extracted);
            if (!outcome.checked()) {
                notes.add("环境中没有可用的 javac，未做预编译校验");
                break;
            }
            if (outcome.success()) {
                log.info("预编译校验通过（第 %d 轮，共 %d 行代码）", attempt + 1,
                        extracted.split("\n").length);
                break;
            }
            failure = "生成的代码编译不通过:\n" + outcome.describe();
            saveText(workDir.resolve(className + "-errors-" + attempt + ".txt"), outcome.describe());
            log.warn("第 %d 轮生成的代码存在 %d 处编译错误", attempt + 1, outcome.errors().size());
            notes.add("第 " + (attempt + 1) + " 轮预编译失败");
            if (attempt >= repairRounds) {
                break;
            }
            Map<String, String> repairContext = new LinkedHashMap<>(context);
            repairContext.put("ERRORS", outcome.forModel());
            repairContext.put("CODE", extracted);
            history.add(ChatMessage.assistant(raw));
            history.add(ChatMessage.user(PromptTemplates.render(repairTemplate, repairContext, variables)));
            log.info("把 %d 条编译诊断回喂给模型，进行第 %d 轮自愈修复", outcome.errors().size(), attempt + 2);
        }

        if (body != null && (outcome == null || outcome.success())) {
            toCache(cacheKey, body);
            return new GeneratedCode(fqn, decorate(body, GeneratedCode.Origin.AI, true, calls, notes, requirement, cacheKey),
                    GeneratedCode.Origin.AI, true, calls, notes, cacheKey);
        }
        if (body != null && outcome != null && !outcome.checked()) {
            // 无法校验时按用户意愿直接采用模型产物
            toCache(cacheKey, body);
            return new GeneratedCode(fqn, decorate(body, GeneratedCode.Origin.AI, false, calls, notes, requirement, cacheKey),
                    GeneratedCode.Origin.AI, false, calls, notes, cacheKey);
        }
        if (body != null && config.acceptUnverified()) {
            log.warn("预编译仍未通过，但 accept-unverified=true，将原样写出最后一次生成结果（构建可能失败）");
            return new GeneratedCode(fqn, decorate(body, GeneratedCode.Origin.UNVERIFIED_AI, false, calls, notes, requirement, cacheKey),
                    GeneratedCode.Origin.UNVERIFIED_AI, false, calls, notes, cacheKey);
        }
        return fallback(Utils.firstNonBlank("AI 生成未通过预编译校验", failure), requirement, pkg, className, fqn, calls);
    }

    // ------------------------------------------------------------------ 缓存

    private String cacheKey(String systemPrompt, String userPrompt, String fqn) {
        return Utils.sha256(String.join("\u0001",
                config.protocol(), config.baseUrl(), config.model(), config.reasoningEffort(),
                String.valueOf(config.temperature()),
                String.valueOf(config.maxTokens()), systemPrompt, userPrompt, fqn, release));
    }

    private GeneratedCode fromCache(String cacheKey, String className, String fqn, String requirement) {
        if (!config.cacheEnabled()) {
            return null;
        }
        Path file = cacheDir.resolve(cacheKey + ".java");
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            String body = GeneratedCode.stripHeader(Utils.readString(file));
            CompileValidator.Outcome outcome = validator.validate(className, body);
            if (!outcome.success()) {
                log.warn("缓存中的代码在当前 JDK/接口下已无法编译，忽略缓存重新生成");
                Files.deleteIfExists(file);
                return null;
            }
            List<String> notes = new ArrayList<>(List.of("命中构建缓存: " + file.getFileName()));
            log.info("命中构建缓存，跳过 AI 调用: %s", file.getFileName());
            return new GeneratedCode(fqn, decorate(body, GeneratedCode.Origin.CACHE, outcome.checked(), 0, notes,
                    requirement, cacheKey), GeneratedCode.Origin.CACHE, outcome.checked(), 0, notes, cacheKey);
        } catch (IOException | RuntimeException e) {
            log.warn("读取缓存失败: %s (%s)", file, e.getMessage());
            return null;
        }
    }

    private void toCache(String cacheKey, String body) {
        if (!config.cacheEnabled()) {
            return;
        }
        try {
            saveText(cacheDir.resolve(cacheKey + ".java"), body);
            saveText(cacheDir.resolve(cacheKey + ".meta"),
                    "generated-at=" + LocalDateTime.now() + System.lineSeparator()
                            + "protocol=" + config.protocol() + System.lineSeparator()
                            + "model=" + config.model() + System.lineSeparator()
                            + "base-url=" + config.baseUrl() + System.lineSeparator());
        } catch (RuntimeException e) {
            log.warn("写入构建缓存失败: %s", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 兜底与装饰

    private GeneratedCode fallback(String reason, String requirement, String pkg, String className, String fqn, int calls)
            throws GenerationException {
        String fullReason = reason + "（协议=" + config.protocol() + "，模型=" + config.model()
                + "，思考=" + config.reasoningEffort() + "）";
        if (config.fallback() == AiAptConfig.Fallback.ERROR || config.failOnError()) {
            throw new GenerationException(fullReason);
        }
        List<String> notes = new ArrayList<>(List.of("兜底原因: " + fullReason));
        GeneratedCode.Origin origin;
        String body;
        if (config.fallback() == AiAptConfig.Fallback.STUB) {
            origin = GeneratedCode.Origin.FALLBACK_STUB;
            body = BuiltInSnippets.stub(pkg, className, fullReason);
        } else {
            origin = GeneratedCode.Origin.FALLBACK_DEMO;
            body = BuiltInSnippets.demo(pkg, className, requirement, fullReason);
        }
        log.warn("使用 %s 生成 %s，构建继续", origin.name(), fqn);
        return new GeneratedCode(fqn, decorate(body, origin, true, calls, notes, requirement, "fallback"), origin, true, calls, notes, "fallback");
    }

    /** 头部注释：说明这是编译期生成的文件，并记录来源、校验结果与再生成方式。 */
    private String decorate(String body, GeneratedCode.Origin origin, boolean verified, int calls,
                            List<String> notes, String requirement, String cacheKey) {
        if (!config.javadocEnabled()) {
            return body.endsWith("\n") ? body : body + "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("/*\n");
        sb.append(" * 本文件由 AI APT Compiler 在编译期自动生成，请勿手工编辑（下次编译会被覆盖）。\n");
        sb.append(" * 需求  : ").append(PromptTemplates.brief(requirement)).append('\n');
        sb.append(" * 来源  : ").append(origin.name()).append("（")
                .append(PromptTemplates.brief(reasonOf(origin))).append("）\n");
        sb.append(" * 协议  : ").append(config.protocol()).append("  模型: ").append(config.model())
                .append("  思考: ").append(config.reasoningEffort())
                .append("  输出上限: ").append(config.maxTokens() > 0 ? config.maxTokens() : "不限制")
                .append("  端点: ").append(config.baseUrl()).append('\n');
        sb.append(" * 校验  : ").append(verified ? "通过（内存预编译）" : "未通过或未执行").append("  AI 请求次数: ").append(calls).append('\n');
        sb.append(" * 指纹  : ").append(fingerprint(cacheKey)).append('\n');
        for (String note : notes) {
            sb.append(" * 修正  : ").append(note).append('\n');
        }
        sb.append(" * 生成于: ").append(LocalDateTime.now()).append('\n');
        sb.append(" * 重新生成: 修改 application.yml 的 prompt（或把它留空后改 @Entrance 的需求）后执行 mvn clean compile（增量构建可能不会重新触发注解处理）。\n");
        sb.append(" */\n\n");
        return sb + GeneratedCode.stripHeader(body.stripLeading());
    }

    private String reasonOf(GeneratedCode.Origin origin) {
        return switch (origin) {
            case AI -> "调用模型并逐轮自愈";
            case CACHE -> "构建缓存命中，未产生费用";
            case FALLBACK_DEMO -> "内置示例实现";
            case FALLBACK_STUB -> "占位实现";
            case UNVERIFIED_AI -> "accept-unverified=true";
        };
    }

    private static String fingerprint(String cacheKey) {
        if (Utils.isBlank(cacheKey)) {
            return "-";
        }
        return cacheKey.length() <= 16 ? cacheKey : cacheKey.substring(0, 16);
    }

    private void saveText(Path file, String content) {
        try {
            Utils.mkdirs(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("调试产物写入失败: %s (%s)", file, e.getMessage());
        }
    }
}
