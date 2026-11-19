package com.example.orderagent.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟数据生成器：造出真实履约场景的订单，其中多类是"陷阱题"。
 *
 * <p>数据分两批：
 * <ol>
 *   <li><b>v1 基础场景（8 类 × 20 单）</b>：单一根因，数据干净，用于验证基本能力；</li>
 *   <li><b>v2 难例场景（6 类 × 20 单）</b>：多因素并发、信息矛盾、边界模糊，
 *       用来检验模型在"真实混乱"下的判断力——这批数据才是 100% 能否站住脚的关键。</li>
 * </ol>
 *
 * <p>为什么要造数据而不是连真实系统？
 * <ol>
 *   <li>可复现：评测集需要固定订单号，数据每次启动都一样（固定随机种子）；</li>
 *   <li>可控：每类根因固定单量，评测集直接从里面抽样；</li>
 *   <li>可演示：管理端能直接报出"哪一号单是哪个场景"。</li>
 * </ol>
 */
@Slf4j
@Component
public class DemoDataSeeder {

    public static final int PER_SCENARIO = 20;

    /** 场景枚举 —— 与结论枚举对应，评测集按它组织。 */
    public enum Scenario {
        // ========== v1：基础场景（单一根因，数据干净） ==========
        STOCK_OUT("A1", "缺货", "STOCK_OUT", "v1"),
        PAY_TIMEOUT("A2", "支付超时", "PAY_TIMEOUT", "v1"),
        PAY_FAILING("A3", "支付失败", "PAY_FAILING", "v1"),
        RISK_BLOCKED("A4", "风控拦截", "RISK_BLOCKED", "v1"),
        LOGISTICS_EXCEPTION("A5", "物流异常", "LOGISTICS_EXCEPTION", "v1"),
        LOGISTICS_NOT_PICKED("A6", "仓库未揽收", "LOGISTICS_NOT_PICKED", "v1"),
        NORMAL_IN_TRANSIT("B1", "正常在途（陷阱）", "NORMAL_IN_TRANSIT", "v1"),
        DATA_MISSING("B2", "数据缺失（陷阱）", "NEED_MANUAL", "v1"),

        // ========== v2：难例场景（真实混乱） ==========
        /** 多商品订单，其中一件缺货、另一件有货——最容易误判为"整体缺货"或"整体正常"。 */
        MULTI_SKU_PARTIAL("C1", "多商品部分缺货", "STOCK_OUT", "v2"),
        /** 部分发货：一件已发且在途，另一件缺货卡住——用户问"为什么没收到"。 */
        PARTIAL_SHIPPED("C2", "部分发货一件缺货", "STOCK_OUT", "v2"),
        /** 已签收但用户问"没收到"——真相是已送达，考验模型不被用户话术带偏。 */
        DELIVERED_BUT_COMPLAINT("C3", "已签收仍在投诉", "NORMAL_IN_TRANSIT", "v2"),
        /** 退款已完成但用户以为没处理——真相是已退款，属正常终态。 */
        REFUNDED_NORMAL("C4", "已退款用户误以为未处理", "NORMAL_IN_TRANSIT", "v2"),
        /** 风控拦截 + 库存也异常：优先级判断，风控应胜出。 */
        RISK_AND_STOCK("C5", "风控拦截且库存异常", "RISK_BLOCKED", "v2"),
        /** 物流异常 + 支付失败：两个真问题并存，按优先级支付失败应先解决。 */
        PAY_FAIL_AND_LOGISTICS("C6", "支付失败且物流异常", "PAY_FAILING", "v2"),
        /** 全部数据缺失（订单查不到关联的支付/库存/物流）——必须 NEED_MANUAL。 */
        ALL_DATA_MISSING("C7", "全链路数据缺失", "NEED_MANUAL", "v2"),
        /** 信息自相矛盾：支付成功但订单显示未支付。 */
        CONTRADICTORY("C8", "数据自相矛盾", "NEED_MANUAL", "v2");

        public final String prefix;
        public final String label;
        public final String expectedConclusion;
        /** 数据集批次：v1 = 基础场景，v2 = 难例场景 */
        public final String batch;

        Scenario(String prefix, String label, String expectedConclusion, String batch) {
            this.prefix = prefix;
            this.label = label;
            this.expectedConclusion = expectedConclusion;
            this.batch = batch;
        }

        public boolean isHard() {
            return "v2".equals(batch);
        }
    }

    private final JdbcTemplate jdbc;
    private final DemoDataCatalog catalog = new DemoDataCatalog();

    public DemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void seed() {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM order_main", Integer.class);
        int expected = Scenario.values().length * PER_SCENARIO;
        if (existing != null && existing >= expected) {
            log.info("订单数据已存在 {} 条，跳过初始化", existing);
            return;
        }
        // 数据不完整（例如新增了场景）→ 清空重建，保证评测集与数据一致
        if (existing != null && existing > 0) {
            log.warn("订单数据不完整（{} / {}），清空重建", existing, expected);
            for (String t : List.of("order_main", "pay_record", "stock_record", "logistics_record",
                    "risk_record", "fault_injection", "eval_case", "eval_run", "eval_run_item")) {
                jdbc.update("DELETE FROM " + t);
            }
        }
        Random random = new Random(20250101L);   // 固定种子，保证每次启动数据一致
        List<String> inserted = new ArrayList<>();
        for (Scenario s : Scenario.values()) {
            for (int i = 1; i <= PER_SCENARIO; i++) {
                String orderNo = s.prefix + String.format("%04d", i);
                long userId = 10000L + (i % 5);
                long skuId = 5000L + random.nextInt(50);
                insertScenario(s, orderNo, userId, skuId, random);
                inserted.add(orderNo);
                catalog.register(orderNo, s);
            }
        }
        jdbc.update("INSERT INTO fault_injection(target, fault_type, enabled, created_at) VALUES (?,?,?,?)",
                "none", "NONE", 0, LocalDateTime.now());
        long v1 = Scenario.values().length - java.util.Arrays.stream(Scenario.values()).filter(Scenario::isHard).count();
        log.info("模拟数据初始化完成：基础场景 {} 类 + 难例场景 {} 类 = {} 单",
                v1, Scenario.values().length - v1, inserted.size());
    }

    private void insertScenario(Scenario s, String orderNo, long userId, long skuId, Random r) {
        LocalDateTime now = LocalDateTime.now();
        double amount = 99 + r.nextInt(900);

        // 难例场景走独立分支，逻辑更复杂
        if (s.isHard()) {
            insertHardScenario(s, orderNo, userId, skuId, amount, now, r);
            return;
        }

        // 默认：订单已支付
        String orderStatus = "PAID";
        String payStatus = "SUCCESS";
        String stockStatus = "LOCKED";
        LocalDateTime createTime = now.minusHours(6);

        switch (s) {
            case STOCK_OUT -> stockStatus = "OUT_OF_STOCK";
            case PAY_TIMEOUT -> {
                orderStatus = "CREATED";
                payStatus = "UNPAID";
                stockStatus = "NONE";
            }
            case PAY_FAILING -> payStatus = "FAILING";
            case RISK_BLOCKED -> {
                orderStatus = "CLOSED";
                stockStatus = "NONE";
            }
            case LOGISTICS_NOT_PICKED, LOGISTICS_EXCEPTION -> {
                // 仅影响物流表
            }
            default -> {
            }
        }

        jdbc.update("INSERT INTO order_main(order_no,user_id,sku_id,amount,status,pay_status,stock_status,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                orderNo, userId, skuId, amount, orderStatus, payStatus, stockStatus, createTime, now);

        if (!"UNPAID".equals(payStatus)) {
            if ("FAILING".equals(payStatus)) {
                jdbc.update("INSERT INTO pay_record(order_no,pay_channel,trade_no,status,fail_count,paid_at) VALUES (?,?,?,?,?,?)",
                        orderNo, "ALIPAY", null, "FAILING", 3, null);
            } else {
                jdbc.update("INSERT INTO pay_record(order_no,pay_channel,trade_no,status,fail_count,paid_at) VALUES (?,?,?,?,?,?)",
                        orderNo, "WECHAT", "T" + orderNo, "SUCCESS", 0, createTime.plusMinutes(2));
            }
        }

        // 库存：每个场景独立 SKU，避免与其他订单串号
        // 注意：真实模型评测暴露过一次数据串号 bug——多个场景复用同一 SKU 时，
        //       缺货单可能查到别的场景留下的正库存，导致误判。这里改为按场景独立分配。
        long scenarioSku = skuId * 100 + s.ordinal();
        if ("LOCKED".equals(stockStatus)) {
            jdbc.update("INSERT INTO stock_record(sku_id,warehouse,available,locked) VALUES (?,?,?,?)",
                    scenarioSku, "WH-SH-01", 50, 1);
        } else if ("OUT_OF_STOCK".equals(stockStatus)) {
            jdbc.update("INSERT INTO stock_record(sku_id,warehouse,available,locked) VALUES (?,?,?,?)",
                    scenarioSku, "WH-SH-01", 0, 0);
        }
        // 订单表中保存的就是场景独立 SKU，工具按订单里的 sku_id 查询，天然隔离
        jdbc.update("UPDATE order_main SET sku_id = ? WHERE order_no = ?", scenarioSku, orderNo);

        // 物流（注意：DATA_MISSING 场景的订单号前缀是 B2，与 A5 物流异常单不冲突；
        //       早期版本曾因场景枚举顺序导致同一个 orderNo 被写入两次，这里按场景强隔离）
        switch (s) {
            case LOGISTICS_EXCEPTION -> jdbc.update(
                    "INSERT INTO logistics_record(order_no,carrier,tracking_no,latest_status,latest_time,exception_desc) VALUES (?,?,?,?,?,?)",
                    orderNo, "SF", "SF" + orderNo, "EXCEPTION", now.minusHours(80), "包裹滞留分拨中心，疑似破损");
            case LOGISTICS_NOT_PICKED -> jdbc.update(
                    "INSERT INTO logistics_record(order_no,carrier,tracking_no,latest_status,latest_time,exception_desc) VALUES (?,?,?,?,?,?)",
                    orderNo, "JD", "JD" + orderNo, "NOT_PICKED", now.minusHours(30), null);
            case NORMAL_IN_TRANSIT -> jdbc.update(
                    "INSERT INTO logistics_record(order_no,carrier,tracking_no,latest_status,latest_time,exception_desc) VALUES (?,?,?,?,?,?)",
                    orderNo, "SF", "SF" + orderNo, "IN_TRANSIT", now.minusHours(3), null);
            case DATA_MISSING -> { /* 故意不写物流，模拟下游系统数据缺失 */ }
            default -> { /* 缺货/支付类场景尚未发货，无物流记录 */ }
        }

        // 风控
        if (s == Scenario.RISK_BLOCKED) {
            jdbc.update("INSERT INTO risk_record(order_no,risk_level,rule_name,created_at) VALUES (?,?,?,?)",
                    orderNo, "HIGH", "疑似刷单-同IP多账号", now.minusHours(5));
        } else {
            jdbc.update("INSERT INTO risk_record(order_no,risk_level,rule_name,created_at) VALUES (?,?,?,?)",
                    orderNo, "LOW", "无", now.minusHours(5));
        }
    }

    public DemoDataCatalog catalog() {
        return catalog;
    }

    // =====================================================================
    //  难例场景（v2）：真实世界的数据是混乱的——多因素并发、信息矛盾、边界模糊
    // =====================================================================

    /**
     * 难例场景生成。
     *
     * <p>设计原则：每个场景都要能"骗过"只做表面匹配的实现。
     * 例如「已签收仍然投诉」：只看用户说什么会判成异常，只看物流才判对；
     * 「风控+缺货」：两个信号同时存在，必须按业务优先级选风控。
     */
    private void insertHardScenario(Scenario s, String orderNo, long userId, long skuId,
                                    double amount, LocalDateTime now, Random r) {
        long skuA = skuId * 100 + s.ordinal();                 // 主商品
        long skuB = skuId * 100 + s.ordinal() + 500;           // 副商品（多商品单）
        LocalDateTime createTime = now.minusHours(30);

        switch (s) {
            // C1 多商品部分缺货：A 有货、B 缺货 → 整体仍判缺货
            case MULTI_SKU_PARTIAL -> {
                insertOrder(orderNo, userId, skuA, amount, "PAID", "SUCCESS", "OUT_OF_STOCK", createTime, now);
                insertSuccessPay(orderNo, createTime);
                insertStock(skuA, "WH-SH-01", 50, 1, "有货");
                insertStock(skuB, "WH-GZ-02", 0, 0, "缺货");
                insertRisk(orderNo, "LOW", "无", now);
            }
            // C2 部分发货：A 已发且在途，B 缺货卡住 —— 用户问"为什么没收到"
            case PARTIAL_SHIPPED -> {
                insertOrder(orderNo, userId, skuA, amount, "PARTIAL_SHIPPED", "SUCCESS", "OUT_OF_STOCK", createTime, now);
                insertSuccessPay(orderNo, createTime);
                insertStock(skuA, "WH-SH-01", 30, 1, "有货");
                insertStock(skuB, "WH-GZ-02", 0, 0, "缺货");
                jdbc.update("INSERT INTO logistics_record(order_no,carrier,tracking_no,latest_status,latest_time,exception_desc) "
                                + "VALUES (?,?,?,?,?,?)",
                        orderNo, "SF", "SF" + orderNo, "IN_TRANSIT", now.minusHours(6), null);
                insertRisk(orderNo, "LOW", "无", now);
            }
            // C3 已签收但用户说没收到 —— 真相是已送达，考验不被用户话术带偏
            case DELIVERED_BUT_COMPLAINT -> {
                insertOrder(orderNo, userId, skuA, amount, "SHIPPED", "SUCCESS", "LOCKED", createTime, now);
                insertSuccessPay(orderNo, createTime);
                insertStock(skuA, "WH-SH-01", 40, 1, "有货");
                jdbc.update("INSERT INTO logistics_record(order_no,carrier,tracking_no,latest_status,latest_time,exception_desc) "
                                + "VALUES (?,?,?,?,?,?)",
                        orderNo, "SF", "SF" + orderNo, "DELIVERED", now.minusHours(20), null);
                insertRisk(orderNo, "LOW", "无", now);
            }
            // C4 已退款属正常终态 —— 用户误以为没处理
            case REFUNDED_NORMAL -> {
                insertOrder(orderNo, userId, skuA, amount, "CLOSED", "REFUNDED", "NONE", createTime, now);
                jdbc.update("INSERT INTO pay_record(order_no,pay_channel,trade_no,status,fail_count,paid_at) "
                                + "VALUES (?,?,?,?,?,?)",
                        orderNo, "WECHAT", "T" + orderNo, "REFUNDED", 0, createTime.plusMinutes(2));
                insertStock(skuA, "WH-SH-01", 40, 0, "有货");
                insertRisk(orderNo, "LOW", "无", now);
            }
            // C5 风控拦截 + 库存也异常 —— 按业务优先级应判风控
            case RISK_AND_STOCK -> {
                insertOrder(orderNo, userId, skuA, amount, "CLOSED", "SUCCESS", "OUT_OF_STOCK", createTime, now);
                insertSuccessPay(orderNo, createTime);
                insertStock(skuA, "WH-SH-01", 0, 0, "缺货");
                insertRisk(orderNo, "HIGH", "疑似刷单-设备指纹重复", now);
            }
            // C6 支付失败 + 物流异常 —— 支付是前置问题，应先解决
            case PAY_FAIL_AND_LOGISTICS -> {
                insertOrder(orderNo, userId, skuA, amount, "PAID", "FAILING", "LOCKED", createTime, now);
                jdbc.update("INSERT INTO pay_record(order_no,pay_channel,trade_no,status,fail_count,paid_at) "
                                + "VALUES (?,?,?,?,?,?)",
                        orderNo, "ALIPAY", null, "FAILING", 4, null);
                insertStock(skuA, "WH-SH-01", 20, 1, "有货");
                jdbc.update("INSERT INTO logistics_record(order_no,carrier,tracking_no,latest_status,latest_time,exception_desc) "
                                + "VALUES (?,?,?,?,?,?)",
                        orderNo, "YTO", "YT" + orderNo, "EXCEPTION", now.minusHours(50), "超区无法派送");
                insertRisk(orderNo, "LOW", "无", now);
            }
            // C7 全链路数据缺失：订单在，但支付/库存/物流记录都没有
            case ALL_DATA_MISSING -> {
                insertOrder(orderNo, userId, skuA, amount, "PAID", "UNKNOWN", "UNKNOWN", createTime, now);
                // 故意不写任何下游记录
                insertRisk(orderNo, "LOW", "无", now);
            }
            // C8 数据自相矛盾：订单显示未支付，但有成功支付流水
            case CONTRADICTORY -> {
                insertOrder(orderNo, userId, skuA, amount, "CREATED", "UNPAID", "NONE", createTime, now);
                insertSuccessPay(orderNo, createTime.plusMinutes(3));
                insertStock(skuA, "WH-SH-01", 25, 0, "有货");
                insertRisk(orderNo, "LOW", "无", now);
            }
            default -> insertOrder(orderNo, userId, skuA, amount, "PAID", "SUCCESS", "LOCKED", createTime, now);
        }
    }

    private void insertOrder(String orderNo, long userId, long skuId, double amount,
                             String status, String payStatus, String stockStatus,
                             LocalDateTime createTime, LocalDateTime now) {
        jdbc.update("INSERT INTO order_main(order_no,user_id,sku_id,amount,status,pay_status,stock_status,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                orderNo, userId, skuId, amount, status, payStatus, stockStatus, createTime, now);
    }

    private void insertSuccessPay(String orderNo, LocalDateTime paidAt) {
        jdbc.update("INSERT INTO pay_record(order_no,pay_channel,trade_no,status,fail_count,paid_at) VALUES (?,?,?,?,?,?)",
                orderNo, "WECHAT", "T" + orderNo, "SUCCESS", 0, paidAt.plusMinutes(2));
    }

    /** @param note 写入仓库名，便于在证据链里看出"哪个商品有货/缺货" */
    private void insertStock(long skuId, String warehouse, int available, int locked, String note) {
        jdbc.update("INSERT INTO stock_record(sku_id,warehouse,available,locked) VALUES (?,?,?,?)",
                skuId, warehouse, available, locked);
    }

    private void insertRisk(String orderNo, String level, String rule, LocalDateTime now) {
        jdbc.update("INSERT INTO risk_record(order_no,risk_level,rule_name,created_at) VALUES (?,?,?,?)",
                orderNo, level, rule, now.minusHours(5));
    }

    /** 生成"多商品"订单：把第二个 SKU 也挂到同一订单上（用 stock_record 体现，order_main 只存主 SKU）。 */
    public java.util.List<Long> skusOf(String orderNo) {
        Long main = jdbc.queryForObject("SELECT sku_id FROM order_main WHERE order_no = ?", Long.class, orderNo);
        if (main == null) {
            return java.util.List.of();
        }
        // 多商品场景的副 SKU = 主 SKU + 500（见 insertHardScenario 的约定）
        var extra = jdbc.queryForList("SELECT sku_id FROM stock_record WHERE sku_id = ?", Long.class, main + 500);
        var all = new java.util.ArrayList<Long>();
        all.add(main);
        all.addAll(extra);
        return all;
    }

    /** 订单号 -> 场景，供评测集与演示提示使用。 */
    public static class DemoDataCatalog {
        private final java.util.Map<String, Scenario> map = new java.util.concurrent.ConcurrentHashMap<>();

        public void register(String orderNo, Scenario s) {
            map.put(orderNo, s);
        }

        public Scenario of(String orderNo) {
            return map.get(orderNo);
        }

        public java.util.List<String> byScenario(Scenario s) {
            return map.entrySet().stream().filter(e -> e.getValue() == s)
                    .map(java.util.Map.Entry::getKey).sorted().toList();
        }
    }
}