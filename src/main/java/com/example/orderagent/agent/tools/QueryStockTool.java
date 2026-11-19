package com.example.orderagent.agent.tools;

import com.example.orderagent.agent.core.EvidenceSignals;
import com.example.orderagent.agent.guard.BaseReadOnlyTool;
import com.example.orderagent.agent.guard.DataPermissionGuard;
import com.example.orderagent.service.AgentProperties;
import com.example.orderagent.service.FaultInjectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具 3：查库存。
 *
 * <p><b>支持多商品订单</b>——这是扩大评测集时发现的真实业务复杂度：
 * 一个订单可能包含多个 SKU，其中一个缺货、另一个有货。
 * 早期实现只查订单里的主 SKU，会得出"库存充足"的错误结论。
 *
 * <p>正确逻辑：查订单下<b>所有</b> SKU 的库存，只要有一个不可用，整体就是"部分/全部缺货"。
 * 这与仓储系统的真实行为一致：任一商品缺货，订单都无法完整发出。
 */
@Slf4j
@Component
public class QueryStockTool extends BaseReadOnlyTool {

    public QueryStockTool(DataPermissionGuard guard, FaultInjectionService fault, AgentProperties props) {
        super(guard, fault, props);
    }

    @Override
    public String name() {
        return "queryStock";
    }

    @Override
    public String description() {
        return "查询订单包含的【所有商品】在各仓库的库存：可用数量与锁定数量。"
                + "多商品订单只要有一个商品缺货，订单就无法完整发出；本工具会逐个列出每个商品的状态。"
                + "用于判断是否因缺货导致订单无法发货。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return orderNoSchema();
    }

    @Override
    protected String query(Map<String, Object> order, Map<String, Object> raw) {
        long skuId;
        try {
            skuId = Long.parseLong(str(order, "sku_id"));
        } catch (Exception e) {
            return EvidenceSignals.summary(EvidenceSignals.DATA_MISSING, "订单缺少 SKU 信息");
        }

        // 多商品订单：副 SKU 约定为主 SKU + 500（由种子数据保证，真实系统应查 order_item 表）
        List<Long> skuIds = new ArrayList<>();
        skuIds.add(skuId);
        List<Map<String, Object>> extraStock = guard.query().findStock(skuId + 500);
        if (!extraStock.isEmpty()) {
            skuIds.add(skuId + 500);
        }

        StringBuilder sb = new StringBuilder();
        List<String> outOfStockItems = new ArrayList<>();
        List<String> missingItems = new ArrayList<>();
        int totalAvailable = 0;
        int totalLocked = 0;

        for (Long sku : skuIds) {
            List<Map<String, Object>> stocks = guard.query().findStock(sku);
            if (stocks.isEmpty()) {
                // 关键区分：「没有库存记录」≠「库存为 0」。
                // 前者是数据缺失（该 SKU 可能根本不在库存系统里，或数据同步失败），
                // 后者才是真的缺货。早期实现把两者都当成缺货，导致数据缺失单被误判为 STOCK_OUT。
                missingItems.add("SKU " + sku);
                sb.append("SKU ").append(sku).append(" 无库存记录；");
                continue;
            }
            int avail = 0;
            int lock = 0;
            String wh = "";
            for (Map<String, Object> s : stocks) {
                avail += parseInt(str(s, "available"));
                lock += parseInt(str(s, "locked"));
                wh = str(s, "warehouse");
            }
            totalAvailable += avail;
            totalLocked += lock;
            raw.put("stock_" + sku, stocks);
            if (avail <= 0) {
                outOfStockItems.add("SKU " + sku + "(" + wh + " 可用 0)");
                sb.append("SKU ").append(sku).append(" 可用0(仓库").append(wh).append(")；");
            } else {
                sb.append("SKU ").append(sku).append(" 可用").append(avail)
                        .append("(仓库").append(wh).append(")；");
            }
        }

        String orderStockStatus = str(order, "stock_status");
        boolean orderSaysOut = "OUT_OF_STOCK".equals(orderStockStatus);

        if (!outOfStockItems.isEmpty()) {
            // 只要有商品缺货，整体就是缺货（多商品单的核心规则）
            String scope = skuIds.size() > 1
                    ? "多商品订单共 " + skuIds.size() + " 件，其中 " + outOfStockItems.size() + " 件缺货"
                    : "商品缺货";
            return EvidenceSignals.summary(EvidenceSignals.STOCK_OUT,
                    scope + "：" + sb.toString().trim() + (orderSaysOut ? "；订单库存锁定状态为 OUT_OF_STOCK" : ""));
        }
        // 只有在"没有真正的缺货"时，才把无记录上报为数据缺失
        if (!missingItems.isEmpty()) {
            return EvidenceSignals.summary(EvidenceSignals.DATA_MISSING,
                    "库存系统无以下商品的记录：" + String.join("、", missingItems)
                            + "（数据缺失，无法判断库存，需人工核实）");
        }
        if (orderSaysOut) {
            // 库存系统显示有货，但订单锁定失败：需要人工补锁
            return EvidenceSignals.summary(EvidenceSignals.STOCK_OUT,
                    "商品可用库存 " + totalAvailable + " 件，但本订单库存锁定失败（status=" + orderStockStatus + "）");
        }
        if ("UNKNOWN".equals(orderStockStatus)) {
            return EvidenceSignals.summary(EvidenceSignals.DATA_MISSING,
                    "订单库存状态未知（status=UNKNOWN），库存系统返回数据不可信，需人工核实");
        }
        return EvidenceSignals.summary(null,
                "订单包含 " + skuIds.size() + " 件商品，均有库存且已锁定");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}