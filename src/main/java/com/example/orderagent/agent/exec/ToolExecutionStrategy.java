package com.example.orderagent.agent.exec;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.ToolResult;
import com.example.orderagent.agent.core.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * 工具执行策略：把"怎么执行工具"从 Agent 主循环里抽出来。
 *
 * <p>为什么要抽？两条实现代表了两种工程思路：
 * <ul>
 *   <li>{@link ParallelExecutorStrategy}：自研，自控线程池与超时——能讲清每一行；</li>
 *   <li>{@link ResilienceStrategy}        ：复用 Resilience4j——生产可维护。</li>
 * </ul>
 * 通过 {@code order-agent.agent.fault-strategy} 切换，面试时可以两种都摆出来讲取舍。
 */
public interface ToolExecutionStrategy {

    /**
     * 并发执行同一轮的全部工具调用。
     *
     * @return 与入参<b>同序</b>的结果列表（保证回填消息的顺序与 tool_call_id 对齐）
     * @throws SecurityException 越权，必须终止会话
     */
    List<ToolResult> executeAll(List<ToolSpec> calls, AgentContext ctx);

    String name();
}