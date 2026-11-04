package com.blackshore.apt.processor.ai;

import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.json.Json;
import com.blackshore.apt.processor.util.AptLog;
import com.blackshore.apt.processor.util.Utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 JDK HttpClient 的 JSON POST 传输层，负责超时、重试与退避，供两种协议客户端共用。
 * 编译期不允许引入第三方依赖，因此这里只用 JDK 能力。
 */
public final class HttpTransport {

    private final AiAptConfig config;
    private final AptLog log;
    private final HttpClient client;

    public HttpTransport(AiAptConfig config, AptLog log) {
        this.config = config;
        this.log = log;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @param url    完整接口地址
     * @param headers 协议自身的鉴权/版本头
     * @param json   请求体
     * @return 响应体文本
     */
    public String postJson(String url, Map<String, String> headers, String json) throws AiException {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putAll(config.headers());
        merged.putIfAbsent("Content-Type", "application/json; charset=utf-8");
        merged.putIfAbsent("Accept", "application/json");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        merged.forEach(builder::header);
        HttpRequest request = builder.build();

        int attempts = config.retryAttempts();
        String lastError = "";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                String body = response.body() == null ? "" : response.body();
                if (status >= 200 && status < 300) {
                    return body;
                }
                lastError = describeError(status, body);
                if (!retryable(status)) {
                    throw new AiException(lastError, status, null);
                }
                log.warn("AI 接口返回 %d（第 %d/%d 次尝试）: %s", status, attempt, attempts, Utils.truncate(lastError, 300));
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("AI 接口网络异常（第 %d/%d 次尝试）: %s", attempt, attempts, lastError);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiException("请求被中断: " + url, e);
            }
            if (attempt < attempts) {
                sleep(Math.max(200L, config.retryBackoffMillis()) * attempt);
            }
        }
        throw new AiException("调用 AI 接口失败（" + url + "），已重试 " + attempts + " 次，最后一次错误: " + lastError);
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    /** 兼容 OpenAI({"error":{"message"}}) 与 Anthropic({"error":{"message","type"},"type":"error"}) 的错误体。 */
    static String describeError(int status, String body) {
        StringBuilder sb = new StringBuilder("HTTP ").append(status).append(" ");
        try {
            Object root = Json.parse(body);
            String message = Utils.firstNonBlank("",
                    Json.text(root, "error", "message"),
                    Json.text(root, "message"),
                    Json.text(root, "errmsg"),
                    Json.text(root, "error", "type"));
            if (!message.isEmpty()) {
                sb.append(message);
                String type = Json.text(root, "error", "type");
                if (!Utils.isBlank(type)) {
                    sb.append(" [").append(type).append(']');
                }
            } else {
                sb.append(Utils.truncate(body, 400));
            }
        } catch (RuntimeException e) {
            sb.append(Utils.truncate(body, 400));
        }
        return sb.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 拼接接口地址：base-url 可以是根域名（自动补 /v1/）、带版本号的地址，或已经写全的完整地址。
     *
     * @param resource 协议资源路径，如 chat/completions、messages
     */
    public static String resolveApiUrl(String baseUrl, String resource) {
        String base = Utils.stripTrailingSlashes(baseUrl);
        if (base.isEmpty()) {
            throw new IllegalArgumentException("base-url 未配置");
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/" + resource)) {
            return base;
        }
        boolean versioned = lower.matches(".*/v\\d+$");
        return versioned ? base + "/" + resource : base + "/v1/" + resource;
    }
}
