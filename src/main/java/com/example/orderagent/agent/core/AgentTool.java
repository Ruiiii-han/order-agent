package com.example.orderagent.agent.core;

import java.util.Map;

/**
 * 一个可被大模型调用的工具。
 *
 * <p><b>安全第一原则</b>：约束不写在 prompt 里，而是写在工具的执行层。
 * 即使模型被 prompt 注入，它只能调用本接口声明的工具、传白名单参数，
 * 且实现内部必须按当前 {@link AgentContext#getUserId()} 做数据权限过滤。
 */
public interface AgentTool {

    /** 工具名，模型看到的 function name，必须全局唯一。 */
    String name();

    /** 给模型看的功能描述。 */
    String description();

    /** 参数的 JSON Schema（OpenAI function calling 格式）。用于：① 告诉模型怎么传参；② 本地校验。 */
    Map<String, Object> parameterSchema();

    /** 是否只读。写操作必须为 false，执行前需人工确认，模型无法直接触发。 */
    default boolean readOnly() {
        return true;
    }

    /**
     * 执行工具。
     *
     * @throws IllegalArgumentException 参数不合法（会被转成 ERROR 结果回填给模型）
     * @throws SecurityException        越权访问（直接终止，不向模型暴露细节）
     */
    ToolResult execute(Map<String, Object> args, AgentContext ctx);
}