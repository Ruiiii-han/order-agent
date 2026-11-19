package com.example.orderagent.eval;

import com.example.orderagent.service.DemoDataSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测集生成与读取。
 *
 * <p>构建方法（面试会问"评测集怎么来的"）：
 * <ol>
 *   <li><b>场景化造数</b>：14 类场景（8 类基础 + 6 类难例）各 20 单，固定种子可复现；</li>
 *   <li><b>一问多变体</b>：每个订单造「标准问法」和「口语化问法」，考察鲁棒性；</li>
 *   <li><b>答案靠数据推</b>：期望结论直接取自场景定义，避免人工标注主观漂移；</li>
 *   <li><b>留出陷阱</b>：正常在途、数据缺失、已签收仍投诉、已退款、信息矛盾等，
 *       专测"误报率"和"不被用户话术带偏"。</li>
 * </ol>
 *
 * <p>数据集版本：
 * <ul>
 *   <li>{@code v1}：基础场景（8 类 × 20 单），单一根因，数据干净，~200 条；</li>
 *   <li>{@code v2}：基础 + 难例（14 类 × 20 单），真实混乱，~340 条 —— <b>推荐用于简历</b>。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    public static final String DEFAULT_VERSION = "v2";
    public static final String BASIC_VERSION = "v1";

    private final JdbcTemplate jdbc;
    private final DemoDataSeeder seeder;
    private final ObjectMapper mapper;

    /** 标准问法（偏正式）。 */
    private static final List<String> FORMAL = List.of(
            "订单 %s 为什么一直没发货？",
            "请帮我排查订单 %s 的履约问题",
            "订单 %s 当前卡在哪个环节？",
            "麻烦查一下订单 %s 的状态"
    );

    /** 口语化问法（偏真实客服场景，带情绪、含糊、口语）。 */
    private static final List<String> CASUAL = List.of(
            "这单 %s 咋回事，我付了钱还没动静",
            "%s 到底发没发货啊",
            "我买的东西怎么一直没到，订单号 %s",
            "%s 这个单子是不是有问题？我都等好久了",
            "朋友帮我看看 %s，我急着要"
    );

    /** 幂等生成：同一版本已存在则跳过。 */
    public int buildIfAbsent(String version) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM eval_case WHERE dataset_version = ?",
                Integer.class, version);
        if (n != null && n > 0) {
            return n;
        }
        boolean includeHard = DEFAULT_VERSION.equals(version);
        int seq = 0;
        int inserted = 0;
        for (DemoDataSeeder.Scenario s : DemoDataSeeder.Scenario.values()) {
            if (s.isHard() && !includeHard) {
                continue;
            }
            List<String> orders = seeder.catalog().byScenario(s);
            for (int i = 0; i < orders.size(); i++) {
                String orderNo = orders.get(i);
                // 每 3 单里有 1 单用两条问法（标准 + 口语），其余一条，控制总规模
                int variants = (i % 3 == 0) ? 2 : 1;
                for (int v = 0; v < variants; v++) {
                    String question = v == 0
                            ? FORMAL.get(i % FORMAL.size()).formatted(orderNo)
                            : CASUAL.get(i % CASUAL.size()).formatted(orderNo);
                    jdbc.update("INSERT INTO eval_case(dataset_version,case_no,order_no,question,expected_conclusion,"
                                    + "expected_evidence,must_mention,category,notes) VALUES (?,?,?,?,?,?,?,?,?)",
                            version, "C" + String.format("%05d", ++seq), orderNo, question,
                            s.expectedConclusion, toJson(List.of(expectedEvidence(s))),
                            toJson(mustMention(s)), s.name(), s.label);
                    inserted++;
                }
            }
        }
        log.info("评测集 {} 生成完成，共 {} 条（{}）", version, inserted,
                includeHard ? "基础 + 难例场景" : "仅基础场景");
        return inserted;
    }

    private static String expectedEvidence(DemoDataSeeder.Scenario s) {
        return switch (s) {
            case STOCK_OUT, MULTI_SKU_PARTIAL, PARTIAL_SHIPPED -> "stock_out";
            case PAY_TIMEOUT -> "pay_timeout";
            case PAY_FAILING, PAY_FAIL_AND_LOGISTICS -> "pay_failing";
            case RISK_BLOCKED, RISK_AND_STOCK -> "risk_blocked";
            case LOGISTICS_EXCEPTION -> "logistics_exception";
            case LOGISTICS_NOT_PICKED -> "logistics_not_picked";
            case NORMAL_IN_TRANSIT, DELIVERED_BUT_COMPLAINT -> "in_transit";
            // 已退款属"正常终态"，工具给的是 normal 信号而非 in_transit。
            // 早期期望证据写错成 in_transit，导致结论判对却被证据匹配卡成失败（评测假阴性）。
            case REFUNDED_NORMAL -> "normal";
            case DATA_MISSING, ALL_DATA_MISSING, CONTRADICTORY -> "data_missing";
        };
    }

    private static List<String> mustMention(DemoDataSeeder.Scenario s) {
        return switch (s) {
            case STOCK_OUT, MULTI_SKU_PARTIAL, PARTIAL_SHIPPED -> List.of("库存");
            case PAY_TIMEOUT -> List.of("支付");
            case PAY_FAILING, PAY_FAIL_AND_LOGISTICS -> List.of("支付");
            case RISK_BLOCKED, RISK_AND_STOCK -> List.of("风控");
            case LOGISTICS_EXCEPTION -> List.of("物流");
            case LOGISTICS_NOT_PICKED -> List.of("揽收", "仓库", "发货");
            case NORMAL_IN_TRANSIT, DELIVERED_BUT_COMPLAINT, REFUNDED_NORMAL -> List.of("正常", "已", "送达");
            case DATA_MISSING, ALL_DATA_MISSING, CONTRADICTORY -> List.of("人工", "核实", "无法");
        };
    }

    @SuppressWarnings("unchecked")
    public List<EvalCase> load(String version, int limit) {
        buildIfAbsent(version);
        String sql = "SELECT * FROM eval_case WHERE dataset_version = ? ORDER BY id"
                + (limit > 0 ? " LIMIT " + limit : "");
        List<EvalCase> cases = new ArrayList<>();
        for (var row : jdbc.queryForList(sql, version)) {
            EvalCase c = new EvalCase();
            c.setId(Long.parseLong(String.valueOf(row.get("id"))));
            c.setDatasetVersion(String.valueOf(row.get("dataset_version")));
            c.setCaseNo(String.valueOf(row.get("case_no")));
            c.setOrderNo(String.valueOf(row.get("order_no")));
            c.setQuestion(String.valueOf(row.get("question")));
            c.setExpectedConclusion(String.valueOf(row.get("expected_conclusion")));
            c.setCategory(String.valueOf(row.get("category")));
            c.setNotes(row.get("notes") == null ? null : String.valueOf(row.get("notes")));
            c.setExpectedEvidence(readList(String.valueOf(row.get("expected_evidence"))));
            c.setMustMention(readList(String.valueOf(row.get("must_mention"))));
            cases.add(c);
        }
        return cases;
    }

    @SuppressWarnings("unchecked")
    private List<String> readList(String json) {
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<String> versions() {
        return jdbc.queryForList("SELECT DISTINCT dataset_version FROM eval_case", String.class);
    }

    /** 数据集概况：每个版本的用例数、场景分布，前端展示用。 */
    public List<java.util.Map<String, Object>> datasetSummary() {
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (String v : versions()) {
            var rows = jdbc.queryForList(
                    "SELECT category, COUNT(*) AS cnt FROM eval_case WHERE dataset_version = ? GROUP BY category", v);
            java.util.Map<String, Object> dist = new java.util.LinkedHashMap<>();
            for (var r : rows) {
                dist.put(String.valueOf(r.get("category")), r.get("cnt"));
            }
            Integer total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eval_case WHERE dataset_version = ?", Integer.class, v);
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("version", v);
            m.put("total", total);
            m.put("scenarios", dist.size());
            m.put("distribution", dist);
            list.add(m);
        }
        return list;
    }
}