package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.ai.AiClient;
import com.blackshore.apt.processor.ai.AiClientFactory;
import com.blackshore.apt.processor.ai.AiException;
import com.blackshore.apt.processor.ai.ChatMessage;
import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.config.GenerationTarget;
import com.blackshore.apt.processor.prompt.PromptTemplates;
import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编译期 AI 代码生成的主流程：组 prompt → 调模型（可缓存）→ 抽取源码（可多文件、可子包）→ 内存预编译 →
 * 失败时把诊断回喂给模型自愈 → 仍失败则按 fallback 策略兜底。
 *
 * <p>形态完全由 {@link GenerationTarget} 决定：multi-file 开时模型可以一次输出多个编译单元，
 * sub-packages 开时辅助文件还可以住在目标包之下的子包里；入口文件永远落在目标包，
 * 写盘前会整体预编译一次，并逐个交给处理器的 {@link NameGuard} 查类型名与包名是否已被工程占用。</p>
 */
public final class AiCodeGenerator {

    /** 生成彻底失败且不允许兜底时抛出，由处理器转成编译错误。 */
    public static class GenerationException extends Exception {
        public GenerationException(String message) {
            super(message);
        }
    }

    /** 处理器提供的类型名可用性检查：返回冲突原因，{@code null} 表示可以用。 */
    public interface NameGuard {

        /** 只能用于辅助文件；入口类名的冲突由处理器自己判断。 */
        String conflictFor(String fqn);
    }

    /** 多文件缓存的目录清单：每行一个全限定名，第一行是入口文件。 */
    private static final String MANIFEST = "manifest.txt";

    private final AiAptConfig config;
    private final AptLog log;
    private final CompileValidator validator;
    private final String release;
    private final Path workDir;
    private final Path cacheDir;
    private final AiClient client;
    private final NameGuard nameGuard;
    private final String unavailableReason;

    public AiCodeGenerator(AiAptConfig config, AptLog log, String release, NameGuard nameGuard) {
        this.config = config;
        this.log = log;
        this.release = release;
        this.nameGuard = nameGuard == null ? fqn -> null : nameGuard;
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
     * @param pkg         目标包名（入口类就住在这里，子包只能开在它之下）
     * @param className   入口类的简单类名
     */
    public GeneratedCode generate(String requirement, String pkg, String className) throws GenerationException {
        GenerationTarget target = GenerationTarget.of(config, pkg, className);
        String fqn = target.entranceFqn();
        Map<String, String> context = PromptTemplates.context(target, release, requirement);
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

        CacheHit cached = fromCache(cacheKey, fqn);
        if (cached != null) {
            return built(fqn, cached.files(), GeneratedCode.Origin.CACHE, cached.checked(), 0,
                    new ArrayList<>(List.of(cached.note())), requirement, cacheKey);
        }

        List<String> notes = new ArrayList<>();
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user(userPrompt));
        int repairRounds = config.repairEnabled() ? Math.max(0, config.repairRounds()) : 0;
        List<SourceFile> units = null;
        CompileValidator.Outcome outcome = null;
        int calls = 0;
        String failure = null;
        // 最后一轮仍卡在类型名冲突上：没跑过预编译，不能当成「校验通过」写出去
        boolean blockedByName = false;

        for (int attempt = 0; attempt <= repairRounds; attempt++) {
            blockedByName = false;
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

            try {
                units = CodeExtractor.toCompilationUnits(raw, target, notes);
            } catch (CodeExtractor.ExtractionException e) {
                failure = "无法从 AI 输出中抽取 Java 源文件: " + e.getMessage();
                log.warn("%s", failure);
                if (attempt >= repairRounds) {
                    break;
                }
                Map<String, String> retryContext = new LinkedHashMap<>(context);
                retryContext.put("REASON", e.getMessage());
                history.add(ChatMessage.assistant(raw));
                history.add(ChatMessage.user(PromptTemplates.render(PromptTemplates.REEXTRACT, retryContext, variables)));
                continue;
            }
            saveUnits(className, attempt, units);
            String conflict = conflictFor(units, fqn);
            if (conflict != null) {
                failure = conflict;
                log.warn("%s", failure);
                if (attempt >= repairRounds) {
                    blockedByName = true;
                    break;
                }
                history.add(ChatMessage.assistant(raw));
                history.add(ChatMessage.user("上一轮生成的文件跟工程里已有的类型或包撞车（" + conflict + "），"
                        + "请换个类型名或子包名（子包必须开在 " + pkg + " 之下并且是新包），"
                        + "然后按原来的格式重新输出全部文件（不要只给改名的那一个）。"));
                continue;
            }
            outcome = validator.validate(units);
            if (!outcome.checked()) {
                notes.add("环境中没有可用的 javac，未做预编译校验");
                break;
            }
            if (outcome.success()) {
                log.info("预编译校验通过（第 %d 轮，%d 个文件共 %d 行代码）：%s", attempt + 1, units.size(),
                        CodeExtractor.totalLines(units), GeneratedCode.structureOf(units));
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
            repairContext.put("CODE", CodeExtractor.renderForRepair(units));
            history.add(ChatMessage.assistant(raw));
            history.add(ChatMessage.user(PromptTemplates.render(repairTemplate, repairContext, variables)));
            log.info("把 %d 条编译诊断回喂给模型，进行第 %d 轮自愈修复", outcome.errors().size(), attempt + 2);
        }

        if (units != null && !blockedByName) {
            if (outcome != null && !outcome.checked()) {
                // 环境中没有 javac：无法校验，按用户意愿直接采用模型产物
                toCache(cacheKey, units);
                return built(fqn, units, GeneratedCode.Origin.AI, false, calls, notes, requirement, cacheKey);
            }
            if (outcome == null || outcome.success()) {
                toCache(cacheKey, units);
                return built(fqn, units, GeneratedCode.Origin.AI, true, calls, notes, requirement, cacheKey);
            }
            if (config.acceptUnverified()) {
                log.warn("预编译仍未通过，但 accept-unverified=true，将原样写出最后一次生成结果（构建可能失败）");
                return built(fqn, units, GeneratedCode.Origin.UNVERIFIED_AI, false, calls, notes, requirement, cacheKey);
            }
        }
        return fallback(Utils.firstNonBlank("AI 生成未通过预编译校验", failure), requirement, pkg, className, fqn, calls);
    }

    /** 头部注释写好后的生成结果。 */
    private GeneratedCode built(String fqn, List<SourceFile> units, GeneratedCode.Origin origin, boolean verified,
                               int calls, List<String> notes, String requirement, String cacheKey) {
        return new GeneratedCode(fqn, decorateAll(units, origin, verified, calls, notes, requirement, cacheKey),
                origin, verified, calls, notes, cacheKey);
    }

    /** 辅助文件的类型名是否已经被工程里的类占用（入口类名由处理器另行校验）。 */
    private String conflictFor(List<SourceFile> units, String entranceFqn) {
        for (SourceFile unit : units) {
            if (unit.fqn().equals(entranceFqn)) {
                continue;
            }
            String reason = nameGuard.conflictFor(unit.fqn());
            if (reason != null) {
                return "生成的 " + unit.pathName() + "（" + unit.fqn() + "）不可用：" + reason;
            }
        }
        return null;
    }

    /** 把拆分结果落一份到工作目录，方便核对「哪个类型被写进了哪个包、哪个文件」。 */
    private void saveUnits(String className, int attempt, List<SourceFile> units) {
        StringBuilder sb = new StringBuilder();
        sb.append("结构: ").append(GeneratedCode.structureOf(units)).append("\n\n");
        for (int i = 0; i < units.size(); i++) {
            SourceFile unit = units.get(i);
            sb.append(i == 0 ? "[入口] " : "[辅助] ").append(unit.pathName())
                    .append("  (").append(unit.lines()).append(" 行)\n");
        }
        sb.append('\n');
        for (SourceFile unit : units) {
            sb.append("===== ").append(unit.fqn()).append(" =====\n").append(unit.source()).append('\n');
        }
        saveText(workDir.resolve(className + "-files-" + attempt + ".txt"), sb.toString());
    }

    // ------------------------------------------------------------------ 缓存

    /** 缓存命中时读回的文件集合 + 本次是否真的做得到预编译校验。 */
    private record CacheHit(List<SourceFile> files, boolean checked, String note) {
    }

    private String cacheKey(String systemPrompt, String userPrompt, String fqn) {
        return Utils.sha256(String.join("\u0001",
                config.protocol(), config.baseUrl(), config.model(), config.reasoningEffort(),
                String.valueOf(config.temperature()),
                String.valueOf(config.maxTokens()), systemPrompt, userPrompt, fqn, release));
    }

    private CacheHit fromCache(String cacheKey, String fqn) {
        if (!config.cacheEnabled()) {
            return null;
        }
        List<SourceFile> files = readCache(cacheKey, fqn);
        if (files.isEmpty()) {
            return null;
        }
        if (!fqn.equals(files.get(0).fqn())) {
            log.warn("缓存里的入口类型是 %s，与本次目标 %s 不一致，忽略缓存", files.get(0).fqn(), fqn);
            return null;
        }
        CompileValidator.Outcome outcome = validator.validate(files);
        if (!outcome.success()) {
            log.warn("缓存中的代码在当前 JDK/接口下已无法编译，忽略缓存重新生成");
            deleteCache(cacheKey);
            return null;
        }
        log.info("命中构建缓存，跳过 AI 调用: %s（%d 个文件）", fingerprint(cacheKey), files.size());
        return new CacheHit(files, outcome.checked(),
                "命中构建缓存: " + fingerprint(cacheKey) + "（" + files.size() + " 个文件）");
    }

    /** 先读新版目录缓存（manifest + 每个文件一份），读不到再兼容旧版的单文件缓存。 */
    private List<SourceFile> readCache(String cacheKey, String fqn) {
        Path dir = cacheDir.resolve(cacheKey);
        Path manifest = dir.resolve(MANIFEST);
        if (Files.isReadable(manifest)) {
            List<SourceFile> files = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                    String name = line.trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    // 子包形式优先（com/x/model/Order.java），兼容旧版的扫平存放（Order.java）
                    Path file = dir.resolve(name.replace('.', '/') + ".java");
                    if (!Files.isReadable(file)) {
                        file = dir.resolve(Utils.simpleName(name) + ".java");
                    }
                    if (!Files.isReadable(file)) {
                        log.warn("缓存条目缺失，整份缓存作废: %s", file);
                        return List.of();
                    }
                    files.add(new SourceFile(name, GeneratedCode.stripHeader(Utils.readString(file))));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("读取缓存失败: %s (%s)", manifest, e.getMessage());
                return List.of();
            }
            if (!files.isEmpty()) {
                return files;
            }
        }
        Path legacy = cacheDir.resolve(cacheKey + ".java");
        if (!Files.isReadable(legacy)) {
            return List.of();
        }
        try {
            return List.of(new SourceFile(fqn, GeneratedCode.stripHeader(Utils.readString(legacy))));
        } catch (IOException | RuntimeException e) {
            log.warn("读取缓存失败: %s (%s)", legacy, e.getMessage());
            return List.of();
        }
    }

    private void toCache(String cacheKey, List<SourceFile> files) {
        if (!config.cacheEnabled()) {
            return;
        }
        try {
            Path dir = cacheDir.resolve(cacheKey);
            StringBuilder manifest = new StringBuilder();
            for (SourceFile file : files) {
                manifest.append(file.fqn()).append('\n');
                // 按包层级存，否则不同子包里的同名类型会在缓存目录里撞车
                Utils.writeString(dir.resolve(file.pathName()), file.source());
            }
            Utils.writeString(dir.resolve(MANIFEST), manifest.toString());
            Utils.writeString(cacheDir.resolve(cacheKey + ".meta"),
                    "generated-at=" + LocalDateTime.now() + System.lineSeparator()
                            + "protocol=" + config.protocol() + System.lineSeparator()
                            + "model=" + config.model() + System.lineSeparator()
                            + "base-url=" + config.baseUrl() + System.lineSeparator()
                            + "files=" + files.size() + System.lineSeparator());
        } catch (IOException | RuntimeException e) {
            log.warn("写入构建缓存失败: %s", e.getMessage());
        }
    }

    /** 缓存目录里现在会有子包目录，删除得递归。 */
    private void deleteCache(String cacheKey) {
        Path dir = cacheDir.resolve(cacheKey);
        try {
            if (Files.isDirectory(dir)) {
                try (var entries = Files.walk(dir)) {
                    for (Path file : entries.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
            Files.deleteIfExists(cacheDir.resolve(cacheKey + ".java"));
            Files.deleteIfExists(cacheDir.resolve(cacheKey + ".meta"));
        } catch (IOException e) {
            log.warn("清理失效缓存失败: %s", e.getMessage());
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
        return new GeneratedCode(fqn,
                List.of(new SourceFile(fqn, decorate(body, origin, true, calls, notes, requirement, "fallback", ""))),
                origin, true, calls, notes, "fallback");
    }

    /** 逐个文件写头部注释：入口文件写完整摘要，辅助文件只写关键信息。 */
    private List<SourceFile> decorateAll(List<SourceFile> units, GeneratedCode.Origin origin, boolean verified, int calls,
                                         List<String> notes, String requirement, String cacheKey) {
        // 多个文件时才把整棵结构写进头部注释，一眼就能看出哪个类型住在哪个子包
        String structure = units.size() > 1 ? GeneratedCode.structureOf(units) : "";
        List<SourceFile> decorated = new ArrayList<>(units.size());
        String entranceFqn = units.get(0).fqn();
        for (int i = 0; i < units.size(); i++) {
            SourceFile unit = units.get(i);
            String body = i == 0
                    ? decorate(unit.source(), origin, verified, calls, notes, requirement, cacheKey, structure)
                    : decorateAux(unit.source(), origin, verified, entranceFqn, cacheKey, structure);
            decorated.add(new SourceFile(unit.fqn(), body));
        }
        return decorated;
    }

    private String decorateAux(String body, GeneratedCode.Origin origin, boolean verified, String entranceFqn,
                               String cacheKey, String structure) {
        String text = body.endsWith("\n") ? body : body + "\n";
        if (!config.javadocEnabled()) {
            return text;
        }
        return "/*\n"
                + " * 本文件由 AI APT Compiler 在编译期自动生成，请勿手工编辑（下次编译会被覆盖）。\n"
                + " * 入口  : " + entranceFqn + "（与它一起生成、一起校验的辅助类）\n"
                + (structure.isBlank() ? "" : " * 结构  : " + structure + "\n")
                + " * 来源  : " + origin.name() + "  校验: "
                + (verified ? "通过（内存预编译）" : "未通过或未执行") + "\n"
                + " * 指纹  : " + fingerprint(cacheKey) + "\n"
                + " */\n\n" + GeneratedCode.stripHeader(text.stripLeading());
    }

    /** 头部注释：说明这是编译期生成的文件，并记录来源、校验结果与再生成方式。 */
    private String decorate(String body, GeneratedCode.Origin origin, boolean verified, int calls,
                            List<String> notes, String requirement, String cacheKey, String structure) {
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
        if (!structure.isBlank()) {
            sb.append(" * 结构  : ").append(structure).append('\n');
        }
        sb.append(" * 指纹  : ").append(fingerprint(cacheKey)).append('\n');
        Set<String> written = new LinkedHashSet<>();
        for (String note : notes) {
            // 自愈多轮会反复记下同样的修正（同一个包名改两次），头部注释里只保留一次
            if (written.add(note)) {
                sb.append(" * 修正  : ").append(note).append('\n');
            }
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
