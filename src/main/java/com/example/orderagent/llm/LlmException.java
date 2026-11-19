package com.example.orderagent.llm;

/** 模型调用异常：超时、HTTP 非 200、返回内容无法解析。 */
public class LlmException extends RuntimeException {

    /** HTTP 状态码，-1 表示非 HTTP 错误（IO / 解析）。 */
    private final int statusCode;

    /** 是否可重试（429 限流 / 5xx 服务端错误）。 */
    private final boolean retryable;

    /** 服务端建议的等待毫秒数（来自 Retry-After），-1 表示未提供。 */
    private final long retryAfterMs;

    public LlmException(String msg) {
        this(msg, -1, false, -1, null);
    }

    public LlmException(String msg, Throwable cause) {
        this(msg, -1, false, -1, cause);
    }

    public LlmException(String msg, int statusCode, boolean retryable, long retryAfterMs, Throwable cause) {
        super(msg, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    /** 429 限流（TPM/RPM 超限）——最容易在免费额度上遇到，必须单独识别。 */
    public boolean isRateLimited() {
        return statusCode == 429;
    }
}