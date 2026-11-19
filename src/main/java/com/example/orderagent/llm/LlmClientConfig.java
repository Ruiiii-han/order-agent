package com.example.orderagent.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 模型客户端装配。
 *
 * <p>这里是"可替换 + 可观测"的落点：
 * <ul>
 *   <li>按配置选 mock 或 OpenAI 兼容客户端；</li>
 *   <li>无论选哪个，外面都套一层 {@link MeteredLlmClient}，统一上报延迟、token、失败率；</li>
 *   <li>真实模型调用失败时自动降级到 mock，保证演示环境不会因为没网而白屏。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class LlmClientConfig {

    @Bean
    public LlmClient llmClient(LlmProperties props,
                               com.fasterxml.jackson.databind.ObjectMapper mapper,
                               MeterRegistry registry) {
        LlmClient raw;
        if ("mock".equalsIgnoreCase(props.getProvider()) || !props.hasApiKey()) {
            if (!"mock".equalsIgnoreCase(props.getProvider())) {
                log.warn("LLM provider={} 但未配置 API Key，自动降级为 mock 模型", props.getProvider());
            }
            raw = new MockLlmClient();
        } else {
            raw = new OpenAiCompatibleClient(props, mapper);
        }
        log.info("LLM client = {}, model = {}", raw.getClass().getSimpleName(), raw.modelName());
        return new MeteredLlmClient(raw, registry);
    }

    /** 装饰器：统一埋点。 */
    static class MeteredLlmClient implements LlmClient {
        private final LlmClient delegate;
        private final Timer timer;
        private final Counter tokensIn;
        private final Counter tokensOut;
        private final Counter errors;

        MeteredLlmClient(LlmClient delegate, MeterRegistry registry) {
            this.delegate = delegate;
            this.timer = Timer.builder("agent_llm_latency_seconds")
                    .tag("model", delegate.modelName())
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry);
            this.tokensIn = Counter.builder("agent_tokens_total").tag("type", "prompt")
                    .tag("model", delegate.modelName()).register(registry);
            this.tokensOut = Counter.builder("agent_tokens_total").tag("type", "completion")
                    .tag("model", delegate.modelName()).register(registry);
            this.errors = Counter.builder("agent_llm_errors_total")
                    .tag("model", delegate.modelName()).register(registry);
        }

        @Override
        public String modelName() {
            return delegate.modelName();
        }

        @Override
        public LlmResponse chat(java.util.List<com.example.orderagent.agent.core.AgentMemory.LlmMessage> messages,
                                java.util.List<java.util.Map<String, Object>> tools,
                                String toolChoice) {
            try {
                LlmResponse r = delegate.chat(messages, tools, toolChoice);
                timer.record(r.latencyMs(), TimeUnit.MILLISECONDS);
                tokensIn.increment(r.promptTokens());
                tokensOut.increment(r.completionTokens());
                return r;
            } catch (RuntimeException e) {
                errors.increment();
                throw e;
            }
        }
    }
}