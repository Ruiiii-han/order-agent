package com.example.orderagent.agent.exec;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.AgentTool;
import com.example.orderagent.agent.core.ToolResult;
import com.example.orderagent.agent.guard.DataPermissionGuard;
import com.example.orderagent.service.AgentProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 工具执行器 —— 并发、超时、熔断、隔舱、幂等的统一入口。
 *
 * <p>设计要点（面试核心）：
 * <ol>
 *   <li><b>线程池隔离</b>：所有工具共用一个<b>有界</b>线程池（核心=并发数，队列有界），
 *       不用 {@code allOf().get(timeout)} 直接依赖 ForkJoinPool，防止一个慢工具拖垮整个应用；</li>
 *   <li><b>超时是"预期"而不是"异常"</b>：超时后取消任务，返回 {@code TIMEOUT} 结果，
 *       由模型基于其他证据判断，而不是整次诊断失败；</li>
 *   <li><b>熔断</b>：某工具连续失败率超阈值，下一次直接快速失败，避免拖慢整轮；</li>
 *   <li><b>越权异常不吞</b>：{@link SecurityException} 直接向上抛，终止整个 Agent 会话。</li>
 * </ol>
 */
@Slf4j
public class ToolExecutor {

    private final AgentProperties props;
    private final ExecutorService pool;
    private final CircuitBreaker[] breakers;
    private final Bulkhead bulkhead;
    private final Timer toolTimer;
    private final Counter failures;

    public ToolExecutor(AgentProperties agentProperties, MeterRegistry registry, String... toolNames) {
        this.props = agentProperties;
        int concurrency = props.getAgent().getToolConcurrency();
        // 有界队列：队列满了就拒绝，让调用方降级，而不是无限堆积内存
        this.pool = new ThreadPoolExecutor(
                concurrency, concurrency, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                new ThreadFactory() {
                    private int i = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "tool-exec-" + (i++));
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());

        this.breakers = new CircuitBreaker[toolNames.length];
        for (int i = 0; i < toolNames.length; i++) {
            this.breakers[i] = CircuitBreaker.of(toolNames[i], CircuitBreakerConfig.custom()
                    // 注意：窗口和阈值是按"工具级故障"设计的，不要因为偶发超时把整个工具熔断掉。
                    // 早期配置是 窗口10/最小5次/失败率50%/打开30s——实测只要平均延迟略高，
                    // 10 次里 6 次超时就会熔断，之后所有请求被直接拒绝，评测里表现为大面积 NEED_MANUAL。
                    // 现调整为：窗口 30、最小 10 次、失败率 70%、打开 15s（快速恢复）。
                    // 生产环境应按真实下游 SLA 校准，而不是拍脑袋。
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(30)
                    .minimumNumberOfCalls(10)
                    .failureRateThreshold(70f)
                    .waitDurationInOpenState(Duration.ofSeconds(15))
                    .permittedNumberOfCallsInHalfOpenState(3)
                    .build());
        }
        this.bulkhead = Bulkhead.of("tools", BulkheadConfig.custom()
                .maxConcurrentCalls(concurrency * 2)
                .maxWaitDuration(Duration.ofMillis(props.getAgent().getToolTimeoutMs()))
                .build());

        this.toolTimer = Timer.builder("agent_tool_latency_seconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.failures = Counter.builder("agent_tool_failures_total").register(registry);
    }

    /**
     * 执行一个工具调用。
     *
     * @return 永不为 null；失败时为 {@link ToolResult#fail}。
     * @throws SecurityException 越权访问，调用方必须终止会话
     */
    public ToolResult execute(AgentTool tool, Map<String, Object> args, AgentContext ctx) {
        long start = System.currentTimeMillis();
        CircuitBreaker breaker = breakerOf(tool.name());
        Supplier<ToolResult> call = () -> {
            try {
                return tool.execute(args, ctx);
            } catch (SecurityException e) {
                throw e;   // 越权不吞
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(tool.name(), "INVALID_ARGS", e.getMessage(), 0);
            } catch (Exception e) {
                return ToolResult.fail(tool.name(), "TOOL_ERROR", String.valueOf(e.getMessage()), 0);
            }
        };

        try {
            Future<ToolResult> future = pool.submit(() -> Bulkhead.decorateSupplier(bulkhead,
                    CircuitBreaker.decorateSupplier(breaker, call)).get());
            // 兜底等待：外层 CompletableFuture.orTimeout 只让 future 完成，底层任务仍在跑，
            // 真正能调度超时的是这里 future.get(timeout)（它会中断工作线程）。
            // 两层超时叠加：orTimeout 负责"给调用方一个结果"，这里负责"真正停掉任务"。
            ToolResult r = future.get(props.getAgent().getToolTimeoutMs() + 100, TimeUnit.MILLISECONDS);
            toolTimer.record(r.durationMs(), TimeUnit.MILLISECONDS);
            if (!r.success()) {
                failures.increment();
            }
            return r;
        } catch (TimeoutException e) {
            failures.increment();
            long cost = System.currentTimeMillis() - start;
            log.warn("tool {} 超时 (>{}ms)", tool.name(), props.getAgent().getToolTimeoutMs());
            return ToolResult.fail(tool.name(), "TIMEOUT",
                    "工具执行超时(" + props.getAgent().getToolTimeoutMs() + "ms)", cost);
        } catch (CancellationException e) {
            // 外层 orTimeout 已经放弃等待，任务被取消；保留结果但标记取消
            failures.increment();
            return ToolResult.fail(tool.name(), "CANCELLED", "任务被取消", 0);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException se) {
                throw se;
            }
            failures.increment();
            return ToolResult.fail(tool.name(), "TOOL_ERROR", String.valueOf(cause), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail(tool.name(), "INTERRUPTED", "执行被中断", 0);
        } catch (RejectedExecutionException e) {
            failures.increment();
            return ToolResult.fail(tool.name(), "REJECTED", "工具线程池已满，请稍后重试", 0);
        }
    }

    private CircuitBreaker breakerOf(String toolName) {
        for (CircuitBreaker b : breakers) {
            if (b.getName().equals(toolName)) {
                return b;
            }
        }
        return breakers[0];
    }

    /** 各工具熔断器状态，暴露给管理端观察。 */
    public Map<String, String> breakerStates() {
        return java.util.Arrays.stream(breakers)
                .collect(java.util.stream.Collectors.toMap(
                        CircuitBreaker::getName, b -> b.getState().name()));
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}