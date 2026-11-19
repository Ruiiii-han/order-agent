package com.example.orderagent.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条评测用例。
 *
 * <p>字段设计的三个要点：
 * <ul>
 *   <li>{@code expectedConclusion}：唯一的主指标，枚举精确匹配，不依赖 LLM 裁判；</li>
 *   <li>{@code expectedEvidence}：期望至少命中的一个信号，用于区分"猜对结论"与"真的查到了证据"；</li>
 *   <li>{@code category}：失败分析时按类别聚合，能一眼看出"物流异常场景掉点"。</li>
 * </ul>
 */
@Data
public class EvalCase {
    private Long id;
    private String datasetVersion;
    private String caseNo;
    private String orderNo;
    private String question;
    private String expectedConclusion;
    private List<String> expectedEvidence = new ArrayList<>();
    private List<String> mustMention = new ArrayList<>();
    private String category;
    private String notes;
}