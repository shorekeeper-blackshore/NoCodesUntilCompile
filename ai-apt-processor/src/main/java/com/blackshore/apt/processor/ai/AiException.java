package com.blackshore.apt.processor.ai;

/**
 * AI 调用链路上的统一异常，携带协议名等上下文，方便处理器输出可操作的提示。
 */
public class AiException extends Exception {

    private final int statusCode;

    public AiException(String message) {
        this(message, 0, null);
    }

    public AiException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public AiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP 状态码，非 HTTP 错误时为 0。 */
    public int statusCode() {
        return statusCode;
    }
}
