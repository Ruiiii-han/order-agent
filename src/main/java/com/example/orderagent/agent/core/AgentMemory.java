package com.example.orderagent.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆：滑动窗口 + 摘要压缩。
 *
 * <p>不做无限累积，规则：
 * <ul>
 *   <li>最多保留 {@code maxMessages} 条 user/assistant 消息；</li>
 *   <li>超出的部分只保留"上一轮结论"作为系统提示，避免上下文全丢；</li>
 *   <li>tool 消息<b>不回放</b>（工具结果只在当轮有效，回放既贵又容易过期）。</li>
 * </ul>
 */
public class AgentMemory {

    private final int maxMessages;
    private final List<LlmMessage> turns = new ArrayList<>();
    private String lastConclusionSummary;

    public AgentMemory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void addUser(String text) {
        turns.add(LlmMessage.user(text));
        trim();
    }

    public void addAssistant(String text) {
        turns.add(LlmMessage.assistant(text, null));
        trim();
    }

    /** 压缩：超窗口时丢掉最旧的问答，把结论摘要保存下来。 */
    private void trim() {
        if (turns.size() <= maxMessages) {
            return;
        }
        // 至少保留最近一轮完整问答
        while (turns.size() > maxMessages) {
            LlmMessage removed = turns.remove(0);
            if ("assistant".equals(removed.role()) && removed.content() != null) {
                lastConclusionSummary = clip(removed.content(), 200);
            }
        }
    }

    /** 供下一轮拼 prompt 使用。 */
    public List<LlmMessage> snapshot() {
        return List.copyOf(turns);
    }

    public String lastConclusionSummary() {
        return lastConclusionSummary;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 模型消息（OpenAI 兼容格式的最小建模）。 */
    public record LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {

        public static LlmMessage system(String c) {
            return new LlmMessage("system", c, null, null, null);
        }

        public static LlmMessage user(String c) {
            return new LlmMessage("user", c, null, null, null);
        }

        public static LlmMessage assistant(String c, List<ToolCall> calls) {
            return new LlmMessage("assistant", c, calls, null, null);
        }

        public static LlmMessage tool(String toolCallId, String name, String content) {
            return new LlmMessage("tool", content, null, toolCallId, name);
        }
    }

    /** 模型返回的工具调用请求。 */
    public record ToolCall(String id, String functionName, Map<String, Object> arguments) {
    }
}