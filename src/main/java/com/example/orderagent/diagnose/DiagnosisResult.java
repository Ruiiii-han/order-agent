package com.example.orderagent.diagnose;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 结构化诊断结论 —— 结构化输出的落点。
 *
 * <p>为什么必须有这个东西？
 * <ol>
 *   <li><b>可判分</b>：评测靠 {@code conclusionCode} 做确定性比对，纯自然语言无法稳定判分；</li>
 *   <li><b>可展示</b>：前端能渲染成"结论 + 证据链 + 建议"三块卡片；</li>
 *   <li><b>可监控</b>：结论枚举可直接作为指标 tag，统计各根因分布。</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResult {

    public enum ConclusionCode {
        STOCK_OUT, PAY_TIMEOUT, PAY_FAILING, RISK_BLOCKED,
        LOGISTICS_EXCEPTION, LOGISTICS_NOT_PICKED, NORMAL_IN_TRANSIT, NEED_MANUAL;

        public static ConclusionCode of(String s) {
            if (s == null) {
                return null;
            }
            for (ConclusionCode c : values()) {
                if (c.name().equalsIgnoreCase(s.trim())) {
                    return c;
                }
            }
            return null;
        }

        public String label() {
            return switch (this) {
                case STOCK_OUT -> "缺货导致无法发货";
                case PAY_TIMEOUT -> "支付超时未回调";
                case PAY_FAILING -> "支付失败或重复支付";
                case RISK_BLOCKED -> "风控拦截";
                case LOGISTICS_EXCEPTION -> "物流异常滞留";
                case LOGISTICS_NOT_PICKED -> "仓库未揽收";
                case NORMAL_IN_TRANSIT -> "状态正常，包裹在途";
                case NEED_MANUAL -> "证据不足，需人工介入";
            };
        }
    }

    private ConclusionCode conclusionCode;
    private String conclusionLabel;
    private double confidence;
    private String reason;
    private String suggestion;

    /** 证据链：工具名 -> 摘要。来自工具执行结果，而不是模型编造。 */
    private List<Evidence> evidence;

    /** 原始模型输出（排查问题用）。 */
    private String rawContent;

    /** 是否经过降级（某工具失败 / 模型降级）。 */
    private boolean degraded;

    /** 多数表决：同一次评测跑的次数（1 = 未启用）。 */
    private int voteRounds = 1;

    /** 多数表决时各结论的票数分布，如 {"STOCK_OUT": 3}，用于分析稳定性。 */
    private java.util.Map<String, Integer> voteDistribution;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Evidence {
        private String tool;
        private String summary;
        private boolean success;
        private long durationMs;
    }
}