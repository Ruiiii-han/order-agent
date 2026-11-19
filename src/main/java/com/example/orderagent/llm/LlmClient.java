package com.example.orderagent.llm;

import com.example.orderagent.agent.core.AgentMemory.LlmMessage;
import com.example.orderagent.agent.core.AgentMemory.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * 大模型客户端抽象。
 *
 * <p>三个实现：
 * <ul>
 *   <li>{@link OpenAiCompatibleClient} —— DeepSeek / 通义 / 任何 OpenAI 兼容网关（真实调用）</li>
 *   <li>{@link MockLlmClient}          —— 本地确定性策略，无需 API Key 即可跑通全链路与评测</li>
 * </ul>
 * 通过 {@code order-agent.llm.provider} 切换，评测时可以直接跑 mock 基线。
 */
public interface LlmClient {

    /**
     * 一次对话补全。
     *
     * @param messages  对话消息
     * @param tools     可用的工具定义（OpenAI function 格式），可为空表示不带工具
     * @param toolChoice "auto" | "none"
     */
    LlmResponse chat(List<LlmMessage> messages, List<Map<String, Object>> tools, String toolChoice);

    /** 模型名，用于落库和评测对比。 */
    String modelName();

    record LlmResponse(
            String content,
            List<ToolCall> toolCalls,
            long promptTokens,
            long completionTokens,
            String finishReason,
            long latencyMs
    ) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}