package com.example.orderagent.sandbox;

import com.example.orderagent.llm.LlmClient;
import com.example.orderagent.llm.LlmException;
import com.example.orderagent.llm.LlmProperties;
import com.example.orderagent.llm.OpenAiCompatibleClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱执行器 —— 让<b>一个真实模型</b>分别扮演"被测"和"裁判"两个角色。
 *
 * <p>解决两个问题：
 * <ol>
 *   <li><b>角色分离</b>：被测模型和裁判模型可以配成不同的 provider / model / key，
 *       避免"自己评自己"；</li>
 *   <li><b>失败可观测</b>：真实模型调用失败时按配置回落 mock，并记录降级次数，
 *       避免"降级结果被当成真实模型结果"污染评测数据。</li>
 * </ol>
 */
@Slf4j
@Component
public class SandboxExecutor {

    public enum Role {
        SUBJECT, JUDGE
    }

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final LlmClient mock = new com.example.orderagent.llm.MockLlmClient();
    private final SandboxProperties sandboxProps;

    private volatile LlmClient subjectClient;
    private volatile LlmClient judgeClient;
    /** 限流重试计数：429 是免费额度最常见的问题，必须可观测。 */
    private final io.micrometer.core.instrument.Counter rateLimitRetries;

    public SandboxExecutor(LlmProperties props, ObjectMapper mapper, SandboxProperties sandboxProps,
                           io.micrometer.core.instrument.MeterRegistry registry) {
        this.props = props;
        this.mapper = mapper;
        this.sandboxProps = sandboxProps;
        this.rateLimitRetries = io.micrometer.core.instrument.Counter
                .builder("agent_llm_rate_limit_retries_total").register(registry);
        this.subjectClient = build(props.getProvider(), props.getBaseUrl(), props.getApiKey(),
                props.getModel(), "subject");
        this.judgeClient = build(props.getJudge().getProvider(), props.getJudge().getBaseUrl(),
                props.getJudge().getApiKey(), props.getJudge().getModel(), "judge");
        log.info("沙箱就绪：subject={} judge={} fallback={}",
                subjectClient.modelName(), judgeClient.modelName(), props.getFallback());
    }

    public LlmClient client(Role role) {
        LlmClient c = role == Role.JUDGE ? judgeClient : subjectClient;
        return c == null ? mock : c;
    }

    /** 裁判模型客户端（评测打分用）。 */
    public LlmClient judgeClient() {
        return client(Role.JUDGE);
    }

    /** 被测模型客户端（诊断用）。 */
    public LlmClient subjectClient() {
        return client(Role.SUBJECT);
    }

    /** 是否在用真实模型（评测报告里用来标注数据来源，防止把 mock 数字当真实结果）。 */
    public boolean isReal(Role role) {
        return client(role) != mock;
    }

    /** AgentLoop 的统一入口：失败重试（指数退避）→ 仍失败按配置降级。 */
    public LlmClient.LlmResponse chat(List<com.example.orderagent.agent.core.AgentMemory.LlmMessage> messages,
                                      List<Map<String, Object>> tools, String toolChoice) {
        LlmClient c = client(Role.SUBJECT);
        int attempts = Math.max(1, props.getMaxRetries() + 1);
        LlmException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return c.chat(messages, tools, toolChoice);
            } catch (LlmException e) {
                last = e;
                if (!e.isRetryable() || i == attempts - 1) {
                    break;
                }
                long wait = e.getRetryAfterMs() > 0 ? e.getRetryAfterMs() : backoffMs(i);
                rateLimitRetries.increment();
                log.warn("模型调用失败（第 {} 次），{}ms 后重试: {}", i + 1, wait, e.getMessage());
                sleep(wait);
            }
        }
        if ("abort".equalsIgnoreCase(props.getFallback())) {
            throw last;
        }
        log.warn("被测模型最终失败，降级 mock: {}", last == null ? "unknown" : last.getMessage());
        return mock.chat(messages, tools, toolChoice);
    }

    /** 指数退避 + 抖动，避免多个并发任务同时重试造成二次拥塞。 */
    private long backoffMs(int attempt) {
        long base = Math.min(30_000L, 1000L * (1L << attempt));
        return base + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, base / 2 + 1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 运行时切换被测模型（前端"模型设置"用，不用重启服务）。 */
    public synchronized Map<String, Object> switchSubject(String provider, String baseUrl,
                                                          String apiKey, String model) {
        this.subjectClient = build(provider, baseUrl, apiKey, model, "runtime");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", subjectClient.modelName());
        r.put("real", isReal(Role.SUBJECT));
        log.info("已切换被测模型: {}", r);
        return r;
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("subjectModel", subjectClient.modelName());
        s.put("subjectReal", isReal(Role.SUBJECT));
        s.put("judgeModel", judgeClient.modelName());
        s.put("judgeReal", isReal(Role.JUDGE));
        s.put("provider", props.getProvider());
        s.put("fallback", props.getFallback());
        return s;
    }

    private LlmClient build(String provider, String baseUrl, String apiKey, String model, String tag) {
        if (provider == null || "mock".equalsIgnoreCase(provider)
                || apiKey == null || apiKey.isBlank()) {
            if (!"mock".equalsIgnoreCase(provider)) {
                log.warn("[{}] provider={} 未配置 API Key，降级 mock", tag, provider);
            }
            return mock;
        }
        LlmProperties p = copy(props);
        if (baseUrl != null && !baseUrl.isBlank()) {
            p.setBaseUrl(baseUrl);
        }
        p.setApiKey(apiKey);
        p.setModel(model);
        log.info("[{}] 真实模型: {} model={}", tag, p.getBaseUrl(), p.getModel());
        return new OpenAiCompatibleClient(p, mapper);
    }

    private static LlmProperties copy(LlmProperties s) {
        LlmProperties c = new LlmProperties();
        c.setProvider(s.getProvider());
        c.setBaseUrl(s.getBaseUrl());
        c.setApiKey(s.getApiKey());
        c.setModel(s.getModel());
        c.setTemperature(s.getTemperature());
        c.setConnectTimeoutMs(s.getConnectTimeoutMs());
        c.setReadTimeoutMs(s.getReadTimeoutMs());
        c.setPriceInPerMTokens(s.getPriceInPerMTokens());
        c.setPriceOutPerMTokens(s.getPriceOutPerMTokens());
        c.setFallback(s.getFallback());
        c.setMaxRetries(s.getMaxRetries());
        c.setMinIntervalMs(s.getMinIntervalMs());
        return c;
    }
}