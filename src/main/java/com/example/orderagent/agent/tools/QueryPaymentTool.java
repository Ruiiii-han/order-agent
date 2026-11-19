package com.example.orderagent.agent.tools;

import com.example.orderagent.agent.core.EvidenceSignals;
import com.example.orderagent.agent.guard.BaseReadOnlyTool;
import com.example.orderagent.agent.guard.DataPermissionGuard;
import com.example.orderagent.service.AgentProperties;
import com.example.orderagent.service.FaultInjectionService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 工具 2：查支付流水（可能多笔，用于识别重复支付 / 连续失败）。 */
@Component
public class QueryPaymentTool extends BaseReadOnlyTool {

    public QueryPaymentTool(DataPermissionGuard guard, FaultInjectionService fault, AgentProperties props) {
        super(guard, fault, props);
    }

    @Override
    public String name() {
        return "queryPayment";
    }

    @Override
    public String description() {
        return "查询订单的支付流水：支付渠道、支付状态、支付时间、失败次数。用于判断是否支付超时、重复支付或渠道失败。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return orderNoSchema();
    }

    @Override
    protected String query(Map<String, Object> order, Map<String, Object> raw) {
        String orderNo = str(order, "order_no");
        List<Map<String, Object>> payments = guard.query().findPayments(orderNo);
        raw.put("payments", payments);

        if (payments.isEmpty()) {
            // 关键区分：没有支付流水 ≠ 支付超时。
            // 已退款订单、数据缺失订单都没有"成功流水"，但它们不是"用户没付钱"。
            String orderPayStatus = str(order, "pay_status");
            if ("REFUNDED".equals(orderPayStatus)) {
                return EvidenceSignals.summary(EvidenceSignals.NORMAL, "订单已退款，无待处理的支付流水（属正常终态）");
            }
            if ("UNKNOWN".equals(orderPayStatus)) {
                return EvidenceSignals.summary(EvidenceSignals.DATA_MISSING,
                        "支付系统无该订单的任何流水记录（数据缺失，需人工核实支付系统）");
            }
            return EvidenceSignals.summary(EvidenceSignals.PAY_TIMEOUT, "未查询到任何支付流水，用户可能未完成支付");
        }

        long success = payments.stream().filter(p -> "SUCCESS".equals(str(p, "status"))).count();
        long failing = payments.stream().filter(p -> "FAILING".equals(str(p, "status"))).count();
        long refunded = payments.stream().filter(p -> "REFUNDED".equals(str(p, "status"))).count();
        int failCount = payments.stream().mapToInt(p -> {
            try {
                return Integer.parseInt(str(p, "fail_count"));
            } catch (Exception e) {
                return 0;
            }
        }).sum();
        String channel = str(payments.get(0), "pay_channel");

        // 已退款：正常终态，不是支付失败。必须在"连续失败"判断之前处理
        if (refunded > 0 && success == 0) {
            return EvidenceSignals.summary(EvidenceSignals.NORMAL,
                    "支付渠道 %s 共 %d 笔流水，状态均为已退款（属正常退款终态，无需干预）".formatted(channel, refunded));
        }
        if (failing > 0 && success == 0) {
            return EvidenceSignals.summary(EvidenceSignals.PAY_FAILING,
                    "支付渠道 %s 连续失败 %d 次，无成功流水".formatted(channel, failCount));
        }
        if (success > 1) {
            return EvidenceSignals.summary(EvidenceSignals.PAY_FAILING,
                    "存在 %d 笔成功支付流水，疑似重复支付，需退款".formatted(success));
        }
        if (success == 1) {
            return EvidenceSignals.summary(null,
                    "支付渠道 %s 已成功，共 %d 笔成功流水".formatted(channel, success));
        }
        return EvidenceSignals.summary(EvidenceSignals.PAY_TIMEOUT, "支付流水状态异常，无成功记录");
    }
}