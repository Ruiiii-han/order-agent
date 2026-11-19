package com.example.orderagent.agent.exec;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.ToolResult;
import com.example.orderagent.agent.core.ToolSpec;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Resilience4j 版本的执行策略（与自研版对拍用）。
 *
 * <p>与 {@link ParallelExecutorStrategy} 的差异对比，正好是一道好的面试题：
 * <table>
 *   <tr><th>能力</th><th>自研</th><th>Resilience4j</th></tr>
 *   <tr><td>并发</td><td>自控线程池</td><td>虚拟线程 / 号志</td></tr>
 *   <tr><td>超时</td><td>future.orTimeout + cancel</td><td>TimeLimiter</td></tr>
 *   <tr><td>熔断</td><td>需要自己写滑动窗口</td><td>开箱即用</td></tr>
 *   <tr><td>隔舱</td><td>线程池大小</td><td>Bulkhead</td></tr>
 *   <tr><td>可观测</td><td>自己打点</td><td>集成 Micrometer</td></tr>
 * </table>
 */
@Slf4j
public class ResilienceStrategy implements ToolExecutionStrategy {

    private final long timeoutMs;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Bulkhead bulkhead;
    private final ExecutorService workers;
    private final Timer timer;

    public ResilienceStrategy(long timeoutMs, int concurrency, MeterRegistry registry) {
        this.timeoutMs = timeoutMs;
        this.bulkhead = Bulkhead.of("r4j-tools", BulkheadConfig.custom()
                .maxConcurrentCalls(concurrency)
                .maxWaitDuration(Duration.ofMillis(timeoutMs))
                .build());
        // JDK21 虚拟线程：IO 密集型任务不需要调优线程数，每个任务一个虚拟线程
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.timer = Timer.builder("agent_tool_latency_seconds").tag("strategy", "resilience4j")
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }

    @Override
    public String name() {
        return "resilience4j";
    }

    @Override
    public List<ToolResult> executeAll(List<ToolSpec> calls, AgentContext ctx) {
        List<Future<ToolResult>> futures = new ArrayList<>(calls.size());
        for (ToolSpec spec : calls) {
            futures.add(workers.submit(() -> runOne(spec, ctx)));
        }
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            try {
                results.add(futures.get(i).get(timeoutMs + 300, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                results.add(ToolResult.fail(calls.get(i).tool().name(), "TIMEOUT", "工具执行超时", timeoutMs));
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof SecurityException se) {
                    throw se;
                }
                results.add(ToolResult.fail(calls.get(i).tool().name(), "TOOL_ERROR", String.valueOf(c), 0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(ToolResult.fail(calls.get(i).tool().name(), "INTERRUPTED", "被中断", 0));
            }
        }
        return results;
    }

    private ToolResult runOne(ToolSpec spec, AgentContext ctx) {
        CircuitBreaker cb = breakers.computeIfAbsent(spec.tool().name(), n -> CircuitBreaker.of(n,
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50f)
                        .waitDurationInOpenState(Duration.ofSeconds(30)).build()));
        long start = System.currentTimeMillis();
        try {
            ToolResult r = Bulkhead.decorateSupplier(bulkhead,
                    CircuitBreaker.decorateSupplier(cb, () -> {
                        try {
                            return spec.tool().execute(spec.args(), ctx);
                        } catch (SecurityException se) {
                            throw se;
                        } catch (IllegalArgumentException ie) {
                            return ToolResult.fail(spec.tool().name(), "INVALID_ARGS", ie.getMessage(), 0);
                        } catch (Exception ex) {
                            return ToolResult.fail(spec.tool().name(), "TOOL_ERROR", String.valueOf(ex.getMessage()), 0);
                        }
                    })).get();
            timer.record(r.durationMs(), TimeUnit.MILLISECONDS);
            return r;
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            return ToolResult.fail(spec.tool().name(), "CIRCUIT_OPEN", "熔断器打开，快速失败", 0);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException e) {
            return ToolResult.fail(spec.tool().name(), "BULKHEAD_FULL", "并发已满，拒绝执行", 0);
        } catch (RuntimeException e) {
            if (e instanceof SecurityException se) {
                throw se;
            }
            return ToolResult.fail(spec.tool().name(), "TOOL_ERROR", String.valueOf(e.getMessage()),
                    System.currentTimeMillis() - start);
        }
    }

    public void shutdown() {
        workers.shutdownNow();
    }
}