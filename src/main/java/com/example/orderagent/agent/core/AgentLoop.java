package com.example.orderagent.agent.core;

import com.example.orderagent.agent.core.AgentMemory.LlmMessage;
import com.example.orderagent.agent.exec.ToolExecutionStrategy;
import com.example.orderagent.llm.LlmClient;
import com.example.orderagent.llm.LlmException;
import com.example.orderagent.service.AgentProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agent 主循环 —— 整个项目的心脏，也是最值得在面试里讲清楚的两百行。
 *
 * <pre>
 *   1. 组装 messages(system + user)
 *   2. 调模型（带 tools）
 *   3. 返回 tool_calls ?
 *        ├─ 是 → 白名单/参数校验 → 并发执行 → 摘要回填 → 回到 2
 *        └─ 否 → 输出结构化结论，结束
 *   4. 步数超限 → 撤掉 tools 强制收敛；连续失败超限 → 终止
 * </pre>
 *
 * <p>为什么不用现成框架：并发、超时、越权、步数收敛这些分支都需要精细控制并可观测，
 * 框架的默认行为反而不好干预；自研之后每一行都能讲清楚。
 */
@Slf4j
@Component
public class AgentLoop {

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ToolExecutionStrategy strategy;
    private final PromptBuilder promptBuilder;
    private final AgentProperties props;
    /** 延迟注入：避免 AgentLoop → SandboxExecutor 的循环依赖 */
    private final org.springframework.context.ApplicationContext ctxHolder;
    private final MeterRegistry meterRegistry;

    private final Counter sessions;
    private final Counter fallbacks;
    private final DistributionSummary stepsSummary;
    private final Timer sessionTimer;

    /** 一次完整诊断的产物。 */
    public record AgentRunResult(
            String content,
            AgentContext context,
            List<ToolResult> toolResults,
            String promptVersion,
            String model,
            String finishReason
    ) {
    }

    public AgentLoop(LlmClient llm,
                     ToolRegistry registry,
                     ToolExecutionStrategy strategy,
                     PromptBuilder promptBuilder,
                     AgentProperties props,
                     MeterRegistry meterRegistry,
                     org.springframework.context.ApplicationContext ctxHolder) {
        this.llm = llm;
        this.registry = registry;
        this.strategy = strategy;
        this.promptBuilder = promptBuilder;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.ctxHolder = ctxHolder;
        this.sessions = Counter.builder("agent_sessions_total").register(meterRegistry);
        this.fallbacks = Counter.builder("agent_fallback_total").register(meterRegistry);
        this.stepsSummary = DistributionSummary.builder("agent_steps").register(meterRegistry);
        this.sessionTimer = Timer.builder("agent_session_latency_seconds")
                .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
    }

    public AgentRunResult run(AgentContext ctx) {
        return run(ctx, null);
    }

    /**
     * @param promptVersion 指定 prompt 版本（评测做 A/B 对比用），null 表示当前版本
     */
    public AgentRunResult run(AgentContext ctx, String promptVersion) {
        long startNs = System.nanoTime();
        sessions.increment();
        String version = promptVersion == null ? PromptBuilder.VERSION : promptVersion;
        List<ToolResult> allResults = new ArrayList<>();

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(promptBuilder.systemPrompt(version)));
        messages.add(LlmMessage.user(buildUserMessage(ctx)));

        String finish = "stop";
        try {
            while (true) {
                LlmClient.LlmResponse resp = chat(messages, registry.toOpenAiTools(), "auto");
                ctx.addTokens(resp.promptTokens(), resp.completionTokens());

                // 情况 A：模型要求调用工具
                if (resp.hasToolCalls()) {
                    // 【关键编排约束】首轮必须完成"全量取证"
                    // 小模型（如 Qwen2.5-7B）常犯的错：只调一个 queryLogistics 看到"在途"就下结论，
                    // 漏掉订单/支付/库存，于是把"未支付""缺货"误判成"正常在途"。
                    // 这是模型能力问题，prompt 约束不住，必须由编排层（代码）兜底。
                    //
                    // 注意：判断基准是"本轮即将调用的 + 历史上已调用的"，
                    // 否则模型已经全量调了 5 个工具，这里还会再补 5 个（实测出现 9 条证据的 bug）。
                    java.util.Set<String> willCall = new java.util.HashSet<>(ctx.calledTools());
                    resp.toolCalls().forEach(tc -> willCall.add(tc.functionName()));
                    java.util.List<String> missing = requiredTools().stream()
                            .filter(t -> !willCall.contains(t))
                            .toList();
                    if (!missing.isEmpty() && ctx.getStepNo() < props.getAgent().getMaxSteps() - 1) {
                        List<AgentMemory.ToolCall> augmented = new java.util.ArrayList<>(resp.toolCalls());
                        int seq = 100;
                        for (String toolName : missing) {
                            if (registry.find(toolName).isPresent()) {
                                Map<String, Object> args = new java.util.HashMap<>();
                                args.put("orderNo", ctx.getOrderNo());
                                augmented.add(new AgentMemory.ToolCall("auto_" + (seq++), toolName, args));
                            }
                        }
                        log.info("session {} 模型漏查 {}，编排层补齐", ctx.getSessionId(), missing);
                        resp = new com.example.orderagent.llm.LlmClient.LlmResponse(
                                resp.content(), augmented, resp.promptTokens(), resp.completionTokens(),
                                resp.finishReason(), resp.latencyMs());
                    }
                }
                if (resp.hasToolCalls()) {
                    if (ctx.getStepNo() >= props.getAgent().getMaxSteps()) {
                        log.warn("session {} 达到最大步数 {}，强制收敛", ctx.getSessionId(), props.getAgent().getMaxSteps());
                        messages.add(LlmMessage.user(
                                "工具调用次数已达上限。请立即基于已获得的证据给出结论；证据不足则给 NEED_MANUAL。"));
                        LlmClient.LlmResponse forced = chat(messages, List.of(), "none");
                        ctx.addTokens(forced.promptTokens(), forced.completionTokens());
                        return finish(ctx, forced, allResults, version, "forced_converge");
                    }
                    int step = ctx.nextStep();
                    List<ToolSpec> specs = validateToolCalls(ctx, resp.toolCalls(), messages);
                    if (specs.isEmpty()) {
                        // 全部不合法（模型编造了工具或参数），错误已回填，让它改口
                        if (ctx.getErrorCount() >= props.getAgent().getMaxErrors()) {
                            throw new AgentTrippedException("连续 " + ctx.getErrorCount()
                                    + " 次无效工具调用，已终止并建议转人工");
                        }
                        continue;
                    }
                    // 关键：assistant 的 tool_calls 消息必须原样保留，否则后续协议校验失败
                    messages.add(LlmMessage.assistant(resp.content(), resp.toolCalls()));

                    // 执行前登记本轮计划调用的工具（用于下一轮判断"是否已全量取证"）
                    specs.forEach(s -> ctx.markPlanned(s.tool().name()));

                    List<ToolResult> results = strategy.executeAll(specs, ctx);
                    failureLoopProtection(ctx, results);
                    for (int i = 0; i < specs.size(); i++) {
                        ToolResult r = results.get(i);
                        messages.add(LlmMessage.tool(specs.get(i).callId(), specs.get(i).tool().name(),
                                r.toModelMessage()));
                    }
                    allResults.addAll(results);
                    log.info("session {} step {}: 调用 {} 个工具，成功 {} 个", ctx.getSessionId(), step,
                            results.size(), results.stream().filter(ToolResult::success).count());
                    continue;
                }

                // 情况 B：模型给出最终答案
                return finish(ctx, resp, allResults, version, resp.finishReason());
            }
        } finally {
            sessionTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /** 统一出口：优先走沙箱（真实模型按角色/降级配置），沙箱不可用时直连 LlmClient。 */
    private LlmClient.LlmResponse chat(List<LlmMessage> messages, List<Map<String, Object>> tools, String choice) {
        var sandbox = sandbox();
        if (sandbox != null) {
            return sandbox.chat(messages, tools, choice);
        }
        try {
            return llm.chat(messages, tools, choice);
        } catch (LlmException e) {
            fallbacks.increment();
            throw new AgentTrippedException("模型服务不可用: " + e.getMessage());
        }
    }

    private com.example.orderagent.sandbox.SandboxExecutor sandbox() {
        try {
            return ctxHolder.getBean(com.example.orderagent.sandbox.SandboxExecutor.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 白名单校验 + 参数校验：所有防线的最外层。
     * 不合法的调用把错误作为 tool 消息回填，让模型自己纠正。
     */
    private List<ToolSpec> validateToolCalls(AgentContext ctx, List<AgentMemory.ToolCall> calls,
                                             List<LlmMessage> messages) {
        List<ToolSpec> specs = new ArrayList<>(calls.size());
        for (AgentMemory.ToolCall c : calls) {
            var tool = registry.find(c.functionName());
            if (tool.isEmpty()) {
                messages.add(LlmMessage.tool(c.id(), c.functionName(),
                        "{\"ok\":false,\"error\":\"UNKNOWN_TOOL\",\"hint\":\"可用工具: " + registry.names() + "\"}"));
                ctx.setErrorCount(ctx.getErrorCount() + 1);
                continue;
            }
            if (!tool.get().readOnly()) {
                messages.add(LlmMessage.tool(c.id(), c.functionName(),
                        "{\"ok\":false,\"error\":\"REQUIRES_HUMAN_CONFIRM\",\"hint\":\"写操作需人工确认，请仅在建议中说明\"}"));
                continue;
            }
            if (c.arguments() != null && c.arguments().containsKey("__parse_error")) {
                messages.add(LlmMessage.tool(c.id(), c.functionName(),
                        "{\"ok\":false,\"error\":\"ARG_PARSE_ERROR\",\"hint\":\"参数不是合法 JSON，请重新生成\"}"));
                ctx.setErrorCount(ctx.getErrorCount() + 1);
                continue;
            }
            specs.add(new ToolSpec(c.id(), tool.get(), c.arguments() == null ? Map.of() : c.arguments()));
        }
        return specs;
    }

    /** 连续错误保护：工具全失败且连续多轮 → 终止，避免死循环烧钱。 */
    private void failureLoopProtection(AgentContext ctx, List<ToolResult> results) {
        boolean allFailed = !results.isEmpty() && results.stream().noneMatch(ToolResult::success);
        if (allFailed) {
            ctx.setHadToolFailure(true);
            ctx.setErrorCount(ctx.getErrorCount() + 1);
        } else {
            ctx.setErrorCount(0);
        }
        if (ctx.getErrorCount() >= props.getAgent().getMaxErrors()) {
            throw new AgentTrippedException("连续 " + ctx.getErrorCount() + " 轮工具调用失败，已终止并建议转人工");
        }
    }

    private AgentRunResult finish(AgentContext ctx, LlmClient.LlmResponse resp,
                                  List<ToolResult> results, String version, String finish) {
        stepsSummary.record(ctx.getStepNo());
        log.info("session {} 完成：step={} tokens={}+{} model={}", ctx.getSessionId(), ctx.getStepNo(),
                ctx.getTotalPromptTokens(), ctx.getTotalCompletionTokens(), llm.modelName());
        return new AgentRunResult(resp.content(), ctx, results, version, llm.modelName(), finish);
    }

    private String buildUserMessage(AgentContext ctx) {
        return "订单号：%s\n用户描述：%s\n请排查该订单的履约问题。".formatted(ctx.getOrderNo(), ctx.getQuestion());
    }

    /**
     * 判定根因所必需的取证工具（缺一不可）。
     *
     * <p>为什么必须由代码兜底：这几个工具的结果互相"证伪"——
     * 只看物流会漏掉未支付/缺货；只看订单会漏掉物流异常。
     * 小模型没有这种"元认知"，靠 prompt 写规则它也会偷懒，所以放在编排层强制补齐。
     */
    private java.util.List<String> requiredTools() {
        return java.util.List.of("queryOrder", "queryPayment", "queryStock", "queryLogistics");
    }
}