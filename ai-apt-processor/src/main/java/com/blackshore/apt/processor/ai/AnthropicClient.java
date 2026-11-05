package com.blackshore.apt.processor.ai;

import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.json.Json;
import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 协议客户端（api.anthropic.com 或任何兼容该协议的网关）。
 *
 * <p>与 OpenAI 协议的差异都在这里被吸收：system 提示是独立的顶层字段、鉴权用 x-api-key、
 * 必须显式给 max_tokens、响应正文是 content 块数组。</p>
 */
public final class AnthropicClient implements AiClient {

    /** max_tokens 在本协议里必填，“不限制”时给一个宽裕的上限（与思考预算联动）。 */
    private static final int UNLIMITED_MAX_TOKENS = 16384;

    private final AiAptConfig config;
    private final AptLog log;
    private final HttpTransport transport;
    private final String url;

    public AnthropicClient(AiAptConfig config, AptLog log) {
        this.config = config;
        this.log = log;
        this.transport = new HttpTransport(config, log);
        this.url = HttpTransport.resolveApiUrl(config.baseUrl(), "messages");
    }

    @Override
    public String protocol() {
        return "anthropic";
    }

    @Override
    public String target() {
        String effort = config.reasoningEffort();
        return "anthropic:" + config.model() + ("auto".equals(effort) ? "" : "[" + effort + "]") + " @ " + url;
    }

    @Override
    public String complete(List<ChatMessage> messages, String systemPrompt) throws AiException {
        List<Object> nodes = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, Object> node = Json.object();
            node.put("role", message.role());
            node.put("content", message.content() == null ? "" : message.content());
            nodes.add(node);
        }

        Map<String, Object> payload = Json.object();
        payload.put("model", config.model());
        payload.put("messages", nodes);

        // 思考等级→ Anthropic 的 thinking 块；开了思考时 max_tokens 必须大于预算，且 temperature 只能为 1
        String effort = config.reasoningEffort();
        int budget = budgetOf(effort);
        if (config.thinkingDisabled()) {
            payload.put("thinking", Map.of("type", "disabled"));
        } else if (budget > 0) {
            payload.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
        }
        int maxTokens = config.maxTokens() > 0 ? config.maxTokens() : UNLIMITED_MAX_TOKENS;
        if (budget > 0 && maxTokens <= budget) {
            maxTokens = budget + 4096;
        }
        // max_tokens 在 Anthropic 协议里是必填项（不配置时也满足“不限制”语义）
        payload.put("max_tokens", maxTokens);
        if (!Utils.isBlank(systemPrompt)) {
            payload.put("system", systemPrompt);
        }
        Double temperature = config.temperature();
        if (temperature != null && budget <= 0) {
            payload.put("temperature", temperature);
        } else if (temperature != null) {
            log.info("开了思考模式（%s），按协议要求不发送 temperature=%s", effort, temperature);
        }

        Map<String, String> headers = Map.of(
                "x-api-key", config.apiKey(),
                "anthropic-version", config.anthropicVersion());
        String response = transport.postJson(url, headers, Json.write(payload));
        return extractText(response);
    }

    /** 思考预算：auto/off 不下发 thinking 块（返 0），其余按强度给 tokens 预算。 */
    private static int budgetOf(String effort) {
        return switch (effort) {
            case "minimal" -> 1024;
            case "low" -> 2048;
            case "medium" -> 8192;
            case "high" -> 16384;
            default -> 0;
        };
    }

    private String extractText(String response) throws AiException {
        Object root;
        try {
            root = Json.parse(response);
        } catch (RuntimeException e) {
            throw new AiException("无法解析 AI 返回的 JSON: " + e.getMessage() + "，原文: " + Utils.truncate(response, 400));
        }
        String type = Json.text(root, "type");
        if ("error".equals(type) || !Utils.isBlank(Json.text(root, "error", "message"))) {
            throw new AiException("AI 返回错误: " + HttpTransport.describeError(200, response));
        }
        String content = joinTextBlocks(Json.at(root, "content"));
        log.info("AI(anthropic) 返回内容长度 %d, tokens: input=%s output=%s, stopReason=%s",
                content.length(),
                Utils.firstNonBlank("-", Json.text(root, "usage", "input_tokens")),
                Utils.firstNonBlank("-", Json.text(root, "usage", "output_tokens")),
                Utils.firstNonBlank("-", Json.text(root, "stop_reason")));
        if (Utils.isBlank(content)) {
            throw new AiException("AI 响应中没有 text 内容块，原始响应: " + Utils.truncate(response, 600));
        }
        return content;
    }

    /** content 既可能是块数组，也可能是个别网关直接返回的字符串。 */
    private static String joinTextBlocks(Object node) {
        if (node instanceof String s) {
            return s;
        }
        if (!(node instanceof List<?> list)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : list) {
            String text = Json.text(block, "text");
            if (!Utils.isBlank(text)) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }
}
