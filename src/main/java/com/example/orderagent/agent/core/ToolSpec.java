package com.example.orderagent.agent.core;

import java.util.Map;

/** 一次待执行的工具调用（已通过白名单校验）。 */
public record ToolSpec(String callId, AgentTool tool, Map<String, Object> args) {
}