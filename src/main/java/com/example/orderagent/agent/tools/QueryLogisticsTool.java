package com.example.orderagent.agent.tools;

import com.example.orderagent.agent.core.EvidenceSignals;
import com.example.orderagent.agent.guard.BaseReadOnlyTool;
import com.example.orderagent.agent.guard.DataPermissionGuard;
import com.example.orderagent.service.AgentProperties;
import com.example.orderagent.service.FaultInjectionService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工具 4：查物流轨迹。
 *
 * <p><b>这里踩过一个很真实的坑</b>（接真实模型评测暴露）：
 * 早期实现"没有物流记录"就一律返回 `data_missing`，导致缺货单、未支付单
 * 也被标记成"数据缺失"，模型据此乱判。
 * 正确做法是<b>结合订单状态区分三种"没有物流"</b>：
 * <ul>
 *   <li>订单未支付 / 已关闭 → 压根不该有物流，返回中性信息（正确路径）；</li>
 *   <li>已支付且库存已锁定但无物流 → 仓库还没发货（提示性信息，不是异常）；</li>
 *   <li>其余未知 → 才标记为 data_missing。</li>
 * </ul>
 * 这就是"让工具返回事实，让模型做判断，但事实要携带正确的上下文"。
 */
@Component
public class QueryLogisticsTool extends BaseReadOnlyTool {

    public QueryLogisticsTool(DataPermissionGuard guard, FaultInjectionService fault, AgentProperties props) {
        super(guard, fault, props);
    }

    @Override
    public String name() {
        return "queryLogistics";
    }

    @Override
    public String description() {
        return "【必须与其他工具一起调用】查询物流状态：承运商、运单号、最新节点与更新时间。"
                + "用于判断包裹是否异常滞留、仓库是否迟迟未揽收。"
                + "注意：物流正常不代表订单履约正常——必须先确认订单状态、支付状态与库存状态，"
                + "否则可能把'未支付''缺货'误判为'在途正常'。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return orderNoSchema();
    }

    @Override
    protected String query(Map<String, Object> order, Map<String, Object> raw) {
        String orderNo = str(order, "order_no");
        String orderStatus = str(order, "status");
        String payStatus = str(order, "pay_status");
        String stockStatus = str(order, "stock_status");

        List<Map<String, Object>> records = guard.query().findLogistics(orderNo);
        raw.put("logistics", records);

        if (records.isEmpty()) {
            return noLogistics(orderStatus, payStatus, stockStatus);
        }
        // 同一订单可能有多条轨迹，取最新一条判断
        Map<String, Object> r = records.get(0);
        String status = str(r, "latest_status");
        String carrier = str(r, "carrier");
        String tracking = str(r, "tracking_no");
        LocalDateTime latest = r.get("latest_time") == null ? null
                : ((java.sql.Timestamp) r.get("latest_time")).toLocalDateTime();
        long stuckHours = latest == null ? 0 : Duration.between(latest, LocalDateTime.now()).toHours();

        return switch (status) {
            case "EXCEPTION" -> EvidenceSignals.summary(EvidenceSignals.LOGISTICS_EXCEPTION,
                    "承运商 %s 运单 %s 状态异常：%s，滞留 %d 小时%s".formatted(
                            carrier, tracking, str(r, "exception_desc"), stuckHours,
                            stuckHours > 72 ? "，已超过 72 小时" : ""));
            case "NOT_PICKED" -> EvidenceSignals.summary(EvidenceSignals.LOGISTICS_NOT_PICKED,
                    "承运商 %s 已接单但仓库未揽收，已等待 %d 小时".formatted(carrier, stuckHours));
            case "IN_TRANSIT" -> EvidenceSignals.summary(EvidenceSignals.IN_TRANSIT,
                    "运单 %s 正常运输中，最近更新 %d 小时前".formatted(tracking, stuckHours));
            case "DELIVERED" -> EvidenceSignals.summary(EvidenceSignals.IN_TRANSIT,
                    "运单 %s 已签收送达（客户可能未亲自收到，但物流已完成投递）".formatted(tracking));
            default -> EvidenceSignals.summary(null, "物流状态未知: " + status);
        };
    }

    /**
     * 无物流记录时的三分类判定 —— 这是修复误判的核心。
     * 关键点：返回 <b>null 信号</b>（中性事实），而不是 data_missing，
     * 让模型基于订单/支付/库存的真实状态去判断，而不是被"数据缺失"带偏。
     */
    private String noLogistics(String orderStatus, String payStatus, String stockStatus) {
        // ① 未支付 / 已关闭：流程还没走到发货，无物流是完全正常的
        if ("CREATED".equals(orderStatus) && !"SUCCESS".equals(payStatus)) {
            return EvidenceSignals.summary(EvidenceSignals.NORMAL, "订单尚未支付完成，物流环节未开始（属正常，非异常）");
        }
        if ("CLOSED".equals(orderStatus)) {
            return EvidenceSignals.summary(EvidenceSignals.NORMAL, "订单已关闭，未进入履约环节（属正常终态）");
        }
        // ② 已支付但库存未锁定成功：缺货导致未发货
        if ("OUT_OF_STOCK".equals(stockStatus)) {
            return EvidenceSignals.summary(null, "订单未发货：库存不足，未进入揽收环节");
        }
        // ③ 已支付且已锁定库存，但还没有物流单
        //    "没有物流记录"与"承运商已接单但未揽收（NOT_PICKED）"是两件事：
        //    后者有明确的 NOT_PICKED 状态记录，前者说明履约数据链路断了。
        //    实测这里若不标记 data_missing，模型会把"下游数据缺失"误判成"正常在途"，
        //    所以必须如实报告"该环节数据缺失"，由模型据此给 NEED_MANUAL。
        //
        //    但注意：如果**其他环节已经给出明确异常信号**（支付失败等），
        //    这里的 data_missing 就不应成为主因——履约数据缺失往往只是
        //    "因为支付失败所以根本没发货"的结果，而不是独立问题。
        //    所以给一个提示性的 data_missing，并在文案里说明"可能是上游未完成导致"。
        if ("LOCKED".equals(stockStatus)) {
            String upstream = upstreamProblemHint(payStatus);
            return EvidenceSignals.summary(EvidenceSignals.DATA_MISSING,
                    "订单已支付且库存已锁定，但物流系统无任何运单记录（履约数据缺失，需人工核实是否已实际发货）"
                            + upstream);
        }
        // ④ 其余未知情况：才是真正的数据缺失
        return EvidenceSignals.summary(EvidenceSignals.DATA_MISSING,
                "未查询到物流记录，且订单状态不一致（status=%s, pay=%s, stock=%s）".formatted(
                        orderStatus, payStatus, stockStatus));
    }

    /**
     * 上游环节是否已有明确异常 —— 用于说明"物流无记录"可能是上游问题的<b>结果</b>而非独立根因。
     *
     * <p>这是接真实模型后发现的信号冲突问题：支付失败的订单本来就发不了货，
     * 物流自然没有记录。如果两个信号都当作"异常"上报，模型会在两者间摇摆，
     * 有时判 PAY_FAILING、有时判 NEED_MANUAL（实测 PAY_FAILING 场景掉了 3 条）。
     */
    private static String upstreamProblemHint(String payStatus) {
        if ("FAILING".equals(payStatus)) {
            return "；注意：该订单支付状态为 FAILING，物流无记录很可能是因为支付未成功、订单未进入发货环节，"
                    + "根因应优先按支付失败处理";
        }
        return "";
    }
}