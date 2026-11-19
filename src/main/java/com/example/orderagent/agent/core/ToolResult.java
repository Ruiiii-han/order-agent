package com.example.orderagent.agent.core;

import java.util.Map;

/**
 * 一次工具执行的结果。
 *
 * <p>三个设计点，都是面试考点：
 * <ol>
 *   <li>{@code summary}：给<b>模型</b>看的摘要，长度受限（控制 token）；</li>
 *   <li>{@code raw}：完整原始数据，只落库供回放，<b>绝不</b>回填给模型；</li>
 *   <li>{@code errorCode}：失败时的机器可读原因，模型据此自我纠正或降级。</li>
 * </ol>
 */
public record ToolResult(
        String toolName,
        boolean success,
        Map<String, Object> data,
        String summary,
        String errorCode,
        String errorMsg,
        long durationMs
) {
    public static ToolResult ok(String toolName, Map<String, Object> data, String summary, long durationMs) {
        return new ToolResult(toolName, true, data, summary, null, null, durationMs);
    }

    public static ToolResult fail(String toolName, String errorCode, String errorMsg, long durationMs) {
        return new ToolResult(toolName, false, Map.of(), errorMsg, errorCode, errorMsg, durationMs);
    }

    /** 回填给模型的内容：只给摘要，不给原始 JSON。 */
    public String toModelMessage() {
        if (!success) {
            return "{\"tool\":\"" + toolName + "\",\"ok\":false,\"error\":\"" + errorMsg
                    + "\",\"hint\":\"该数据源暂不可用，请基于其他证据判断，信息不足时结论给 NEED_MANUAL\"}";
        }
        return "{\"tool\":\"" + toolName + "\",\"ok\":true,\"summary\":\"" + escape(summary) + "\"}";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}