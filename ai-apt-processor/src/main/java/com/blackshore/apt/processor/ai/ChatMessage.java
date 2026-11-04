package com.blackshore.apt.processor.ai;

/**
 * 一次对话消息。role 使用两种协议共同认可的 user / assistant。
 */
public record ChatMessage(String role, String content) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public static ChatMessage user(String content) {
        return new ChatMessage(USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ASSISTANT, content);
    }

    public boolean isUser() {
        return USER.equals(role);
    }

    @Override
    public String toString() {
        return role + "(" + (content == null ? 0 : content.length()) + " 字符)";
    }
}
