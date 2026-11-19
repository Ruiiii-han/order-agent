package com.example.orderagent.eval;

import com.example.orderagent.diagnose.DiagnosisResult;

/**
 * 判分器 —— 三层判分的第一、二层都在这里（第三层是人工抽样，交给人）。
 *
 * <p><b>设计原则：确定性优先</b>。
 * 结论正确性用枚举精确匹配，证据用信号包含匹配，只有"理由与建议的可读性"才交给 LLM 裁判。
 * 这样即使裁判模型不稳定，主指标依然可信。
 */
public interface CaseJudge {

    /** 确定性判定结果。 */
    record Deterministic(
            boolean conclusionMatch,
            boolean evidenceMatch,
            boolean mustMentionOk,
            double score
    ) {
    }

    Deterministic deterministic(EvalCase c, DiagnosisResult result);

    /** LLM 裁判：0-100，给"理由合理性与建议可执行性"打分。关闭时返回 -1。 */
    int llmScore(EvalCase c, DiagnosisResult result);

    String name();
}