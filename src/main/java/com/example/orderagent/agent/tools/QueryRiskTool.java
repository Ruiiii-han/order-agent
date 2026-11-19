package com.example.orderagent.agent.tools;

import com.example.orderagent.agent.core.EvidenceSignals;
import com.example.orderagent.agent.guard.BaseReadOnlyTool;
import com.example.orderagent.agent.guard.DataPermissionGuard;
import com.example.orderagent.service.AgentProperties;
import com.example.orderagent.service.FaultInjectionService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 工具 5：查风控记录。 */
@Component
public class QueryRiskTool extends BaseReadOnlyTool {

    public QueryRiskTool(DataPermissionGuard guard, FaultInjectionService fault, AgentProperties props) {
        super(guard, fault, props);
    }

    @Override
    public String name() {
        return "queryRisk";
    }

    @Override
    public String description() {
        return "查询订单风控结果：风险等级与命中的规则名。用于判断订单是否被风控拦截导致无法履约。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return orderNoSchema();
    }

    @Override
    protected String query(Map<String, Object> order, Map<String, Object> raw) {
        String orderNo = str(order, "order_no");
        List<Map<String, Object>> risks = guard.query().findRisk(orderNo);
        raw.put("risks", risks);

        if (risks.isEmpty()) {
            return EvidenceSignals.summary(EvidenceSignals.DATA_MISSING, "未查询到风控记录");
        }
        Map<String, Object> r = risks.get(0);
        String level = str(r, "risk_level");
        String rule = str(r, "rule_name");
        if ("HIGH".equals(level)) {
            return EvidenceSignals.summary(EvidenceSignals.RISK_BLOCKED,
                    "风控等级 HIGH，命中规则[%s]，订单已被拦截".formatted(rule));
        }
        return EvidenceSignals.summary(null, "风控等级 %s，未命中拦截规则".formatted(level));
    }
}