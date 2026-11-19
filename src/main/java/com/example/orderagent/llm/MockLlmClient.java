package com.example.orderagent.llm;

import com.example.orderagent.agent.core.AgentMemory.LlmMessage;
import com.example.orderagent.agent.core.AgentMemory.ToolCall;
import com.example.orderagent.agent.core.EvidenceSignals;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地确定性"模型"——没有 API Key 也能把整条链路跑通。
 *
 * <p>它不是玩具，而是一个 <b>策略模型</b>，行为完全确定，因此：
 * <ul>
 *   <li>能在无外网 / 无 Key 的机器上演示、跑评测回归；</li>
 *   <li>可以作为评测的 <b>基线（baseline）</b>，真实模型的效果要和它对比才有说服力；</li>
 *   <li>便于验证 Agent 防御逻辑（白名单、越权、步数限制）而不受模型随机性干扰。</li>
 * </ul>
 *
 * <p>策略：还没调过工具 → 并发发起 5 个只读工具的调用；已有工具结果 → 按信号优先级输出结构化结论。
 */
@Slf4j
public class MockLlmClient implements LlmClient {

    private static final List<String> ALL_TOOLS =
            List.of("queryOrder", "queryPayment", "queryStock", "queryLogistics", "queryRisk");

    @Override
    public String modelName() {
        return "mock-v1";
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<Map<String, Object>> tools, String toolChoice) {
        long start = System.currentTimeMillis();
        int toolMsgCount = 0;
        String lastUser = null;
        for (LlmMessage m : messages) {
            if ("tool".equals(m.role())) {
                toolMsgCount++;
            }
            if ("user".equals(m.role())) {
                lastUser = m.content();
            }
        }
        long promptTokens = estimateTokens(messages);

        // 第 1 段：还没查数据 → 发起工具调用（只发起注册表里真实存在的）
        if (toolMsgCount == 0 && tools != null && !tools.isEmpty()) {
            java.util.Set<String> available = new java.util.HashSet<>();
            for (Map<String, Object> t : tools) {
                Object fn = t.get("function");
                if (fn instanceof Map<?, ?> m) {
                    available.add(String.valueOf(m.get("name")));
                }
            }
            String orderNo = extractOrderNo(lastUser);
            List<ToolCall> calls = new ArrayList<>();
            int i = 0;
            for (String name : ALL_TOOLS) {
                if (!available.contains(name)) {
                    continue;
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("orderNo", orderNo);
                calls.add(new ToolCall("call_" + (++i), name, args));
            }
            return new LlmResponse(null, calls, promptTokens, 40, "tool_calls",
                    System.currentTimeMillis() - start);
        }

        // 第 2 阶段：基于工具结果下结论
        // 用与 PromptRepository 一致的优先级规则，而不是"字符串谁先出现"
        String toolContents = EvidenceSignals.collectToolContents(messages);
        String signal = EvidenceSignals.firstSignal(toolContents);
        String conclusion = toolContents.isBlank() ? "NEED_MANUAL"
                : (signal == null ? "NEED_MANUAL" : toConclusion(signal));
        String answer = buildAnswer(conclusion, toolContents);
        long completion = Math.max(20, answer.length() / 2L);

        return new LlmResponse(answer, List.of(), promptTokens, completion,
                "stop", System.currentTimeMillis() - start);
    }

    private static String toConclusion(String signal) {
        return switch (signal) {
            case EvidenceSignals.STOCK_OUT -> "STOCK_OUT";
            case EvidenceSignals.PAY_TIMEOUT -> "PAY_TIMEOUT";
            case EvidenceSignals.PAY_FAILING -> "PAY_FAILING";
            case EvidenceSignals.RISK_BLOCKED -> "RISK_BLOCKED";
            case EvidenceSignals.LOGISTICS_EXCEPTION -> "LOGISTICS_EXCEPTION";
            case EvidenceSignals.LOGISTICS_NOT_PICKED -> "LOGISTICS_NOT_PICKED";
            case EvidenceSignals.IN_TRANSIT -> "NORMAL_IN_TRANSIT";
            default -> "NEED_MANUAL";
        };
    }

    private static String buildAnswer(String conclusion, String evidenceText) {
        String reason;
        String suggestion;
        switch (conclusion) {
            case "STOCK_OUT" -> {
                reason = "订单已支付，但商品可用库存为 0 或库存锁定失败，履约暂停。";
                suggestion = "建议联系仓库确认补货周期；可引导用户改拍同款或由客服发起退款。";
            }
            case "PAY_TIMEOUT" -> {
                reason = "订单创建后未查询到成功支付流水，支付处于超时未回调状态。";
                suggestion = "建议核对第三方支付账单是否已扣款；若已扣款未回调，走支付补偿/对账流程。";
            }
            case "PAY_FAILING" -> {
                reason = "支付流水显示连续失败，可能存在重复支付或支付渠道异常。";
                suggestion = "建议核查同一订单的多笔支付流水，若存在重复扣款立即发起自动退款。";
            }
            case "RISK_BLOCKED" -> {
                reason = "订单命中风控规则被拦截，履约流程被暂停。";
                suggestion = "建议转风控人工复核；确认无风险后手动放行，否则通知用户取消并退款。";
            }
            case "LOGISTICS_EXCEPTION" -> {
                reason = "物流状态异常，包裹在承运商处滞留。";
                suggestion = "建议联系承运商查询滞留原因，超时未解决可发起补发或退款。";
            }
            case "LOGISTICS_NOT_PICKED" -> {
                reason = "库存已锁定，但仓库超过 24 小时仍未揽收发货。";
                suggestion = "建议催办仓库拣货发货；若为预售后订单，向用户同步预计发货时间。";
            }
            case "NORMAL_IN_TRANSIT" -> {
                reason = "订单支付、库存、物流各环节状态正常，包裹正在运输途中。";
                suggestion = "建议向用户同步物流单号与预计到达时间，无需额外干预。";
            }
            default -> {
                reason = "关键环节数据缺失或部分数据源不可用，无法确定根因。";
                suggestion = "建议转人工客服核单，或待数据源恢复后重新发起诊断。";
            }
        }
        return """
                {"conclusionCode":"%s","confidence":%s,"reason":"%s","suggestion":"%s","evidence":"%s"}
                """.formatted(conclusion, "NEED_MANUAL".equals(conclusion) ? "0.45" : "0.9",
                reason, suggestion, evidenceText.replace("\"", "'").replace("\n", " "));
    }

    private static String extractOrderNo(String text) {
        if (text == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Z]\\d{4,12}").matcher(text);
        return m.find() ? m.group() : "";
    }

    /** 粗略估算 token：中文约 1 token / 2 字符。仅 mock 使用，真实模型读响应里的 usage。 */
    public static long estimateTokens(List<LlmMessage> messages) {
        long chars = 0;
        for (LlmMessage m : messages) {
            if (m.content() != null) {
                chars += m.content().length();
            }
        }
        return Math.max(1, chars / 2);
    }
}