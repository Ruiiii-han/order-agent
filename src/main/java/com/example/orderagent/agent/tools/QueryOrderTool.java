package com.example.orderagent.agent.tools;

import com.example.orderagent.agent.core.EvidenceSignals;
import com.example.orderagent.agent.guard.BaseReadOnlyTool;
import com.example.orderagent.agent.guard.DataPermissionGuard;
import com.example.orderagent.service.AgentProperties;
import com.example.orderagent.service.FaultInjectionService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 工具 1：查订单主信息。 */
@Component
public class QueryOrderTool extends BaseReadOnlyTool {

    public QueryOrderTool(DataPermissionGuard guard, FaultInjectionService fault, AgentProperties props) {
        super(guard, fault, props);
    }

    @Override
    public String name() {
        return "queryOrder";
    }

    @Override
    public String description() {
        return "查询订单主信息：订单状态、支付状态、库存锁定状态、金额、下单时间。排查任何异常都应先调用它。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return orderNoSchema();
    }

    @Override
    protected String query(Map<String, Object> order, Map<String, Object> raw) {
        raw.putAll(order);
        String status = str(order, "status");
        String pay = str(order, "pay_status");
        String stock = str(order, "stock_status");
        String amount = str(order, "amount");

        String signal = null;
        String text = "订单状态=%s，支付状态=%s，库存状态=%s，金额=%s元".formatted(status, pay, stock, amount);

        // 优先级必须与风控工具一致：订单被 CLOSED 不一定就是风控拦截。
        // 早期实现看到 CLOSED 就报 risk_blocked，导致"已退款正常关闭"的订单被误判为风控拦截。
        // 正确的风控信号由 QueryRiskTool 依据 risk_level 给出，这里不再抢答。
        if ("OUT_OF_STOCK".equals(stock)) {
            signal = EvidenceSignals.STOCK_OUT;
        } else if ("REFUNDED".equals(pay)) {
            // 已退款是正常终态，不是支付超时。
            // 注意要给正向信号 normal：只给中性描述会让模型拿不到"没问题"的证据，从而保守判 NEED_MANUAL
            return EvidenceSignals.summary(EvidenceSignals.NORMAL, text + "（订单已退款关闭，属正常终态）");
        } else if ("UNPAID".equals(pay) || "PAYING".equals(pay)) {
            signal = EvidenceSignals.PAY_TIMEOUT;
        } else if ("FAILING".equals(pay)) {
            signal = EvidenceSignals.PAY_FAILING;
        }
        return EvidenceSignals.summary(signal, text);
    }
}