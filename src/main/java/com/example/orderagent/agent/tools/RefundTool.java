package com.example.orderagent.agent.tools;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.AgentTool;
import com.example.orderagent.agent.core.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 写操作工具（示例）：发起退款。
 *
 * <p><b>它永远不会被模型直接执行</b>：
 * <ul>
 *   <li>{@code readOnly() == false}，Agent 执行器检查到只读工具未注册该工具；</li>
 *   <li>即使注册，执行器也会在 {@code requiresConfirm} 时改走"建议 + 人工确认"通道。</li>
 * </ul>
 * 面试时这一点很值钱：把"AI 能做什么、不能做什么"的边界做进代码，而不是写在提示词里。
 */
@Component
public class RefundTool implements AgentTool {

    @Override
    public String name() {
        return "initiateRefund";
    }

    @Override
    public String description() {
        return "【写操作，需人工确认】为订单发起退款。AI 只能提出建议，不能直接调用。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of("type", "string", "description", "订单号"),
                        "reason", Map.of("type", "string", "description", "退款原因")
                ),
                "required", List.of("orderNo", "reason")
        );
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        // 这里只做"建议"，真正执行需要人工在后台确认，因此永远返回不可执行
        return ToolResult.fail(name(), "REQUIRES_HUMAN_CONFIRM",
                "退款属于写操作，需人工确认后执行", 0);
    }
}