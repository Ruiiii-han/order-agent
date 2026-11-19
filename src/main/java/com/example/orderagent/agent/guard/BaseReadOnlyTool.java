package com.example.orderagent.agent.guard;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.AgentTool;
import com.example.orderagent.agent.core.EvidenceSignals;
import com.example.orderagent.agent.core.ToolResult;
import com.example.orderagent.service.AgentProperties;
import com.example.orderagent.service.FaultInjectionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 只读工具基类：把"校验 → 故障注入 → 查数 → 摘要"这段重复流程固化下来。
 *
 * <p>子类只需关心两件事：怎么查数据（{@link #query}）和怎么把数据压成一句摘要（{@link #summarize}）。
 * 安全校验（{@link DataPermissionGuard}）在基类里强制执行，子类想跳过都跳不过。
 */
public abstract class BaseReadOnlyTool implements AgentTool {

    protected final DataPermissionGuard guard;
    protected final FaultInjectionService fault;
    protected final AgentProperties props;

    protected BaseReadOnlyTool(DataPermissionGuard guard, FaultInjectionService fault, AgentProperties props) {
        this.guard = guard;
        this.fault = fault;
        this.props = props;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        long start = System.currentTimeMillis();
        String orderNo = DataPermissionGuard.argStr(args, "orderNo");

        // ① 参数白名单校验（schema 层面）
        DataPermissionGuard.requireSchema(this, args);

        // ② 显式授权：订单必须属于当前会话用户
        Optional<Map<String, Object>> order = guard.loadAuthorizedOrder(orderNo, ctx);
        if (order.isEmpty()) {
            return ToolResult.fail(name(), "NOT_FOUND",
                    "订单不存在: " + orderNo, System.currentTimeMillis() - start);
        }

        // ③ 模拟下游延迟（让并发与超时的价值可被观测）
        if (props.getTools().getSimulatedLatencyMs() > 0) {
            sleep((long) (props.getTools().getSimulatedLatencyMs()
                    * (0.5 + Math.random())));
        }

        // ④ 故障注入
        fault.maybeInject(name(), orderNo);
        if (fault.isEmptyInjected(name(), orderNo)) {
            return ToolResult.ok(name(), Map.of(), EvidenceSignals.summary(EvidenceSignals.DATA_MISSING,
                    "下游返回空数据"), System.currentTimeMillis() - start);
        }

        // ⑤ 查数 + 摘要
        Map<String, Object> raw = new LinkedHashMap<>();
        String summary = query(order.get(), raw);
        summary = clip(summary);
        ctx.addEvidence(name(), summary);
        return ToolResult.ok(name(), raw, summary, System.currentTimeMillis() - start);
    }

    /** 查数据，写入 raw，返回一句话摘要（用 {@link EvidenceSignals#summary} 包装信号）。 */
    protected abstract String query(Map<String, Object> order, Map<String, Object> raw);

    private String clip(String s) {
        int max = props.getAgent().getSummaryMaxChars();
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 参数 schema：所有工具都只有一个 orderNo。 */
    protected static Map<String, Object> orderNoSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of(
                                "type", "string",
                                "description", "订单号，格式为大写字母开头加数字，例如 A10001",
                                "pattern", "^[A-Z]\\d{4,12}$"
                        )
                ),
                "required", java.util.List.of("orderNo")
        );
    }

    /** 从 JDBC 行里安全取值。 */
    protected static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}