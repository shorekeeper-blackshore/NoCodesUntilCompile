package com.blackshore.apt.processor.ai;

import com.blackshore.apt.processor.config.AiAptConfig;
import com.blackshore.apt.processor.util.AptLog;

/**
 * 按配置里的 provider/protocol 选择协议实现。两种协议之外的厂商名一律归一到
 * OpenAI 兼容协议（业界绝大多数网关都兼容它）。
 */
public final class AiClientFactory {

    private AiClientFactory() {
    }

    public static AiClient create(AiAptConfig config, AptLog log) {
        if (config.isAnthropic()) {
            return new AnthropicClient(config, log);
        }
        if (!"openai".equalsIgnoreCase(config.provider())) {
            log.info("provider=%s 不是两种标准协议之一，已按 OpenAI 兼容协议访问 %s", config.provider(), config.baseUrl());
        }
        return new OpenAiClient(config, log);
    }
}
