package com.blackshore.apt.processor.ai;

import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.json.Json;
import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI Chat Completions 协议客户端，同时兼容所有声称支持 OpenAI 协议的网关
 * （DeepSeek、Qwen、Moonshot、vLLM、OneAPI 等），区别只在 base-url 与 model。
 */
public final class OpenAiClient implements AiClient {

    private final AiAptConfig config;
    private final AptLog log;
    private final HttpTransport transport;
    private final String url;

    public OpenAiClient(AiAptConfig config, AptLog log) {
        this.config = config;
        this.log = log;
        this.transport = new HttpTransport(config, log);
        this.url = HttpTransport.resolveApiUrl(config.baseUrl(), "chat/completions");
    }

    @Override
    public String protocol() {
        return "openai";
    }

    @Override
    public String target() {
        return "openai:" + config.model() + " @ " + url;
    }

    @Override
    public String complete(List<ChatMessage> messages, String systemPrompt) throws AiException {
        List<Object> nodes = new ArrayList<>();
        if (!Utils.isBlank(systemPrompt)) {
            nodes.add(message("system", systemPrompt));
        }
        for (ChatMessage message : messages) {
            nodes.add(message(message.role(), message.content()));
        }

        Map<String, Object> payload = Json.object();
        payload.put("model", config.model());
        payload.put("messages", nodes);
        payload.put("stream", false);
        Double temperature = config.temperature();
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (config.maxTokens() > 0) {
            payload.put(tokenField(), config.maxTokens());
        }

        String response = transport.postJson(url, Map.of("Authorization", "Bearer " + config.apiKey()), Json.write(payload));
        return extractText(response);
    }

    /** gpt-4o 之后新模型改用 max_completion_tokens，auto 按模型名判断。 */
    private String tokenField() {
        String configured = config.tokenField();
        if (!"auto".equals(configured)) {
            return configured;
        }
        String model = config.model().toLowerCase(Locale.ROOT);
        boolean newer = model.startsWith("gpt-4o") || model.startsWith("gpt-4.1") || model.startsWith("gpt-5")
                || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4");
        return newer ? "max_completion_tokens" : "max_tokens";
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> node = Json.object();
        node.put("role", role);
        node.put("content", content == null ? "" : content);
        return node;
    }

    private String extractText(String response) throws AiException {
        Object root;
        try {
            root = Json.parse(response);
        } catch (RuntimeException e) {
            throw new AiException("无法解析 AI 返回的 JSON: " + e.getMessage() + "，原文: " + Utils.truncate(response, 400));
        }
        String errorMessage = Json.text(root, "error", "message");
        if (!Utils.isBlank(errorMessage)) {
            throw new AiException("AI 返回错误: " + errorMessage);
        }
        String content = Json.text(root, "choices", 0, "message", "content");
        if (Utils.isBlank(content)) {
            content = joinParts(Json.at(root, "choices", 0, "message", "content"));
        }
        if (Utils.isBlank(content)) {
            content = Json.text(root, "choices", 0, "text");
        }
        log.info("AI(openai) 返回内容长度 %s, tokens: prompt=%s completion=%s, finishReason=%s",
                String.valueOf(content == null ? 0 : content.length()),
                Utils.firstNonBlank("-", Json.text(root, "usage", "prompt_tokens")),
                Utils.firstNonBlank("-", Json.text(root, "usage", "completion_tokens")),
                Utils.firstNonBlank("-", Json.text(root, "choices", 0, "finish_reason")));
        if (Utils.isBlank(content)) {
            throw new AiException("AI 响应中没有可用的代码文本，原始响应: " + Utils.truncate(response, 600));
        }
        return content;
    }

    /** 少数网关会把 content 返回为分段数组（[{type:text,text:...}]）。 */
    private static String joinParts(Object node) {
        if (!(node instanceof List<?> list)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (item instanceof String s) {
                sb.append(s);
            } else {
                String text = Json.text(item, "text");
                if (!Utils.isBlank(text)) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }
}
