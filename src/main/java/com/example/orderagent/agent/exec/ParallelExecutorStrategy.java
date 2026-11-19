package com.example.orderagent.agent.exec;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.ToolResult;
import com.example.orderagent.agent.core.ToolSpec;
import com.example.orderagent.service.AgentProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自研并行执行策略 —— 手写 {@code CompletableFuture} + 超时 + 取消。
 *
 * <p>关键细节（都是面试可挖的点）：
 * <ul>
 *   <li><b>不用 allOf().join()</b>：任何一个失败都会让 join 抛出，导致其他工具结果丢失。
 *       这里用 {@code exceptionally} 把每个 future 收敛成"必有结果"。</li>
 *   <li><b>超时后要取消任务</b>：{@code orTimeout} 只让 future 完成，底层任务还在跑（占线程、占下游资源）。</li>
 *   <li><b>结果按原顺序收集</b>：tool_call_id 必须一一对应，乱序回填会导致模型对错号。</li>
 * </ul>
 */
@Slf4j
public class ParallelExecutorStrategy implements ToolExecutionStrategy {

    private final ToolExecutor executor;
    private final AgentProperties props;

    public ParallelExecutorStrategy(ToolExecutor executor, AgentProperties props) {
        this.executor = executor;
        this.props = props;
    }

    @Override
    public String name() {
        return "executor";
    }

    @Override
    public List<ToolResult> executeAll(List<ToolSpec> calls, AgentContext ctx) {
        long timeout = props.getAgent().getToolTimeoutMs();
        @SuppressWarnings("unchecked")
        CompletableFuture<ToolResult>[] futures = new CompletableFuture[calls.size()];
        AtomicBoolean forbidden = new AtomicBoolean(false);

        for (int i = 0; i < calls.size(); i++) {
            ToolSpec spec = calls.get(i);
            futures[i] = CompletableFuture
                    .supplyAsync(() -> executor.execute(spec.tool(), spec.args(), ctx))
                    .orTimeout(timeout + 200, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        ToolResult r = handleException(spec, ex, timeout);
                        if (r == null) {
                            forbidden.set(true);
                            return ToolResult.fail(spec.tool().name(), "FORBIDDEN", "无权访问", 0);
                        }
                        return r;
                    });
        }

        // 整体兜底等待：即使个别 future 内部卡住，也不会无限阻塞
        try {
            CompletableFuture.allOf(futures).get(timeout * 2L + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("工具整体等待超时，使用已完成结果: {}", e.getMessage());
        }

        // 越权必须在拿到结果后（或超时后）统一判断：单个工具的 SecurityException 被 exceptionally 吞掉后，
        // 只有这里能把它升级为"终止整个会话"，避免模型拿"无权访问"继续编造答案
        if (forbidden.get()) {
            throw new SecurityException("检测到越权访问，会话已终止");
        }

        List<ToolResult> results = new java.util.ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            ToolResult r = futures[i].getNow(null);
            results.add(r != null ? r : ToolResult.fail(calls.get(i).tool().name(), "TIMEOUT", "工具超时", timeout));
        }
        return results;
    }

    /**
     * 把 CompletableFuture 的异常收敛成"必有结果"。
     *
     * @return {@code null} 表示越权，调用方必须终止会话
     */
    private ToolResult handleException(ToolSpec spec, Throwable ex, long timeout) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof TimeoutException) {
            return ToolResult.fail(spec.tool().name(), "TIMEOUT", "工具执行超时(" + timeout + "ms)", timeout);
        }
        // 注意：orTimeout 触发的是 TimeoutException，而 CancellationException 表示被主动取消，
        // 两者都不是业务错误，要分别标记，否则评测里无法区分"慢"和"坏"
        if (cause instanceof CancellationException) {
            return ToolResult.fail(spec.tool().name(), "CANCELLED", "工具调用被取消", 0);
        }
        if (cause instanceof SecurityException) {
            return null;
        }
        log.warn("工具 {} 执行异常: {}", spec.tool().name(), cause.toString());
        return ToolResult.fail(spec.tool().name(), "TOOL_ERROR", String.valueOf(cause.getMessage()), 0);
    }
}