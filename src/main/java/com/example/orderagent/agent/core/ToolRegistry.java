package com.example.orderagent.agent.core;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册表 —— Agent 的安全边界之一。
 *
 * <p>模型只能在注册表里"找"工具，找不到就返回明确错误让它改口；
 * 绝不使用反射按名字实例化任意类（那样等于把 RCE 漏洞交给模型）。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<AgentTool> toolList) {
        for (AgentTool t : toolList) {
            if (tools.putIfAbsent(t.name(), t) != null) {
                throw new IllegalStateException("重复注册工具: " + t.name());
            }
        }
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<String> names() {
        return tools.keySet().stream().sorted().collect(Collectors.toList());
    }

    /** 转成 OpenAI / DeepSeek 兼容的 tools 数组。 */
    public List<Map<String, Object>> toOpenAiTools() {
        return tools.values().stream().map(t -> Map.<String, Object>of(
                "type", "function",
                "function", Map.of(
                        "name", t.name(),
                        "description", t.description(),
                        "parameters", t.parameterSchema()
                )
        )).collect(Collectors.toList());
    }
}