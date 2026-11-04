package com.blackshore.apt.processor.ai;

import java.util.List;

/**
 * AI 对话客户端抽象：OpenAI 与 Anthropic 协议各自实现，对上层只暴露“多轮对话取回文本”。
 */
public interface AiClient {

    /** 协议标识，openai / anthropic。 */
    String protocol();

    /** 人类可读的调用目标，用于日志：protocol:model@baseUrl。 */
    String target();

    /**
     * 发起一次补全。
     *
     * @param messages    对话历史（user / assistant 交替）
     * @param systemPrompt 系统提示，可为空
     */
    String complete(List<ChatMessage> messages, String systemPrompt) throws AiException;
}
