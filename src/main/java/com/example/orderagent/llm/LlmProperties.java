package com.example.orderagent.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "order-agent.llm")
public class LlmProperties {
    private String provider = "mock";
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    private double temperature = 0.2;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 60000;
    /** 元 / 1M tokens */
    private double priceInPerMTokens = 1.0;
    private double priceOutPerMTokens = 2.0;
    /** 真实模型失败时：fallback（回落 mock）| abort（直接失败，评测更严格） */
    private String fallback = "fallback";

    /** 可重试错误（429 限流 / 5xx）的最大重试次数。 */
    private int maxRetries = 3;

    /** 两次 LLM 调用之间的最小间隔（毫秒），用于 TPM 限流的主动节流；0 表示不限。 */
    private long minIntervalMs = 0;

    private Judge judge = new Judge();

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 估算成本（元）。 */
    public double cost(long promptTokens, long completionTokens) {
        return promptTokens / 1_000_000.0 * priceInPerMTokens
                + completionTokens / 1_000_000.0 * priceOutPerMTokens;
    }

    @Data
    public static class Judge {
        private boolean enabled = true;
        private String provider = "mock";
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "mock";
        private double weightDeterministic = 0.6;
        private double weightJudge = 0.4;
    }
}