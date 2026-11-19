package com.example.orderagent.agent.core;

import com.example.orderagent.agent.core.AgentMemory.LlmMessage;

/**
 * 从证据摘要里提取"信号"。
 *
 * <p>这是 Mock 模型和规则判分的<b>共享语汇</b>：MockLlmClient 用这些信号做决策，
 * 评测模块用这些信号做确定性比对。信号 = 英文键 + 中文描述，必须与工具输出格式保持一致。
 */
public final class EvidenceSignals {

    public static final String STOCK_OUT = "stock_out";
    public static final String PAY_TIMEOUT = "pay_timeout";
    public static final String PAY_FAILING = "pay_failing";
    public static final String RISK_BLOCKED = "risk_blocked";
    public static final String LOGISTICS_EXCEPTION = "logistics_exception";
    public static final String LOGISTICS_NOT_PICKED = "logistics_not_picked";
    public static final String IN_TRANSIT = "in_transit";
    public static final String DATA_MISSING = "data_missing";

    /**
     * 正向信号：明确表示"这一环节已完成且正常"。
     *
     * <p>为什么需要这个信号（扩大评测集时踩的坑）：
     * 早期只设计了"异常信号"，已退款订单的每个环节都只给出<b>中性</b>描述
     * （"订单已退款关闭""未进入履约环节"），模型拿不到任何正向证据，
     * 只能保守地判 NEED_MANUAL。加了 {@code normal} 之后模型才知道"这是正常终态"。
     *
     * <p>面试价值：这说明 <b>工具不仅要报问题，还要报"没问题"</b>——
     * 沉默不等于健康，模型需要明确的正面证据才能收敛。
     */
    public static final String NORMAL = "normal";

    /** 预置信号词表：按出现顺序判断，前面的优先级更高。 */
    public static final String[] ALL = {
            STOCK_OUT, PAY_TIMEOUT, PAY_FAILING, RISK_BLOCKED,
            LOGISTICS_EXCEPTION, LOGISTICS_NOT_PICKED, DATA_MISSING, IN_TRANSIT, NORMAL
    };

    private EvidenceSignals() {
    }

    /** 从一批消息（role=tool 的内容）里找第一个出现的信号，找不到返回 null。 */
    public static String firstSignal(CharSequence haystack) {
        if (haystack == null) {
            return null;
        }
        for (String s : ALL) {
            if (haystack.toString().contains(s)) {
                return s;
            }
        }
        return null;
    }

    /** 判断一段文本里是否包含某个信号。 */
    public static boolean has(String haystack, String signal) {
        return haystack != null && haystack.contains(signal);
    }

    /**
     * 解析工具返回的 summary 字符串里的信号。
     * 工具的 summary 统一以 {@code [signal:xxx]} 开头（无信号时不带）。
     */
    public static String signalOfSummary(String summary) {
        if (summary == null) {
            return null;
        }
        int i = summary.indexOf("[signal:");
        if (i < 0) {
            return null;
        }
        int end = summary.indexOf(']', i);
        return end > i ? summary.substring(i + 8, end) : null;
    }

    /** 工具摘要的统一格式：{@code [signal:xxx] 中文描述 ...} */
    public static String summary(String signal, String text) {
        return signal == null ? text : "[signal:" + signal + "] " + text;
    }

    /**
     * 结构化信号提取：把"各工具摘要"翻译成"根因判定"，而不是靠字符串里谁先出现。
     *
     * <p>这是接真实模型后发现的第一个真问题：
     * 早期版本用 {@link #firstSignal} 按固定顺序匹配字符串，结果"物流查不到"的 `data_missing`
     * 排在 `stock_out` 前后会导致误判。更本质的问题是——<b>证据缺失 ≠ 缺货</b>。
     *
     * <p>规则（与 PromptRepository v3 的判定优先级严格一致，保证 prompt 与代码不打架）：
     * <pre>
     *   risk_blocked > stock_out > pay_timeout > pay_failing
     *   > logistics_exception > logistics_not_picked > in_transit > data_missing/默认
     * </pre>
     * 且：只有当【订单/支付/库存】这三类"可判定环节"全部缺失时才给 data_missing。
     */
    public static final class Snapshot {
        private final java.util.Map<String, java.util.List<String>> byTool = new java.util.LinkedHashMap<>();

        public void accept(String toolName, String summary) {
            String sig = signalOfSummary(summary);
            if (sig != null) {
                byTool.computeIfAbsent(sig, k -> new java.util.ArrayList<>()).add(toolName);
            }
        }

        public boolean has(String signal) {
            return byTool.containsKey(signal);
        }

        public java.util.List<String> toolsOf(String signal) {
            return byTool.getOrDefault(signal, java.util.List.of());
        }

        public java.util.Map<String, java.util.List<String>> asMap() {
            return byTool;
        }

        /** 依据优先级规则给出结论枚举（与 PromptRepository.SIGNAL_TO_CONCLUSION 一致）。 */
        public java.util.Optional<String> conclusion() {
            for (String signal : ALL) {
                if (signal.equals(DATA_MISSING) || signal.equals(IN_TRANSIT) || signal.equals(NORMAL)) {
                    continue;
                }
                if (has(signal)) {
                    return java.util.Optional.of(SIGNAL_TO_CONCLUSION.get(signal));
                }
            }
            if (has(IN_TRANSIT) || has(NORMAL)) {
                return java.util.Optional.of("NORMAL_IN_TRANSIT");
            }
            return java.util.Optional.empty();
        }
    }

    /** 信号 → 结论枚举 的唯一真值表，prompt 里写的规则必须与这里一致。 */
    public static final java.util.Map<String, String> SIGNAL_TO_CONCLUSION = java.util.Map.of(
            STOCK_OUT, "STOCK_OUT",
            PAY_TIMEOUT, "PAY_TIMEOUT",
            PAY_FAILING, "PAY_FAILING",
            RISK_BLOCKED, "RISK_BLOCKED",
            LOGISTICS_EXCEPTION, "LOGISTICS_EXCEPTION",
            LOGISTICS_NOT_PICKED, "LOGISTICS_NOT_PICKED",
            IN_TRANSIT, "NORMAL_IN_TRANSIT",
            NORMAL, "NORMAL_IN_TRANSIT"
    );

    /** 目标枚举 → 判定依据的信号（评测里做"信号是否命中期望"用）。 */
    public static String signalOfConclusion(String conclusion) {
        for (var e : SIGNAL_TO_CONCLUSION.entrySet()) {
            if (e.getValue().equals(conclusion)) {
                return e.getKey();
            }
        }
        return DATA_MISSING;
    }

    /** 把一批 tool 消息解析成快照。 */
    public static Snapshot snapshot(java.util.List<String> toolNames, java.util.List<String> summaries) {
        Snapshot s = new Snapshot();
        for (int i = 0; i < toolNames.size(); i++) {
            s.accept(toolNames.get(i), summaries.get(i));
        }
        return s;
    }

    /** 拼接所有 role=tool 消息内容，供 MockLlmClient 与规则判分使用。 */
    public static String collectToolContents(java.util.List<LlmMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : messages) {
            if ("tool".equals(m.role()) && m.content() != null) {
                sb.append(m.content()).append('\n');
            }
        }
        return sb.toString();
    }
}