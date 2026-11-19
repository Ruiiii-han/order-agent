package com.example.orderagent.eval;

import com.example.orderagent.diagnose.DiagnosisResult;
import com.example.orderagent.llm.LlmClient;
import com.example.orderagent.llm.LlmException;
import com.example.orderagent.llm.LlmProperties;
import com.example.orderagent.sandbox.SandboxExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 三层判分的实现。
 *
 * <pre>
 *  分数 = 0.6 × 确定性分 + 0.4 × LLM 裁判分
 *  确定性分 = 结论枚举匹配(0.5) + 证据信号命中(0.3) + 关键词命中(0.2)
 *  及格线   = 结论匹配 且 总分 ≥ 0.6
 * </pre>
 *
 * <p>为什么权重这么分：结论对但证据错，说明是"蒙对"，不能算过；
 * 证据对但结论错，说明模型看到了却没想明白，是 prompt 的问题。两者的失败类型不同，
 * 用分数分层就能自动分类，面试时能讲"我的失败用例是分类的，不是一堆混杂的个案"。
 */
@Slf4j
@Component
public class RuleAndLlmJudge implements CaseJudge {

    private final SandboxExecutor sandbox;
    private final LlmProperties props;
    private final ObjectMapper mapper;

    public RuleAndLlmJudge(SandboxExecutor sandbox, LlmProperties props, ObjectMapper mapper) {
        this.sandbox = sandbox;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "rule+llm";
    }

    @Override
    public Deterministic deterministic(EvalCase c, DiagnosisResult result) {
        boolean conclusionMatch = result != null
                && result.getConclusionCode() != null
                && c.getExpectedConclusion().equals(result.getConclusionCode().name());

        // 证据匹配：期望信号是否出现在任一成功工具的摘要里
        String evidenceText = result == null || result.getEvidence() == null ? "" :
                result.getEvidence().stream().map(DiagnosisResult.Evidence::getSummary)
                        .filter(java.util.Objects::nonNull).reduce("", (a, b) -> a + "\n" + b);
        boolean evidenceMatch = c.getExpectedEvidence() == null || c.getExpectedEvidence().isEmpty()
                || c.getExpectedEvidence().stream().anyMatch(evidenceText::contains);

        // 关键词命中：理由/建议里是否提到关键领域词（防止"结论对但理由答非所问"）
        // 注意：mustMention 匹配范围不只看模型说的话，还包括工具证据原文——
        // 模型说"已送达"、工具说"已签收"，语义等价但用词不同；
        // 早期只匹配模型输出，导致一条判对结论的用例被关键词卡成失败（评测假阳性）。
        // 这与"LLM-as-judge 应该看全部上下文"的原则一致。
        String answerText = (result == null ? "" : result.getReason() + " " + result.getSuggestion())
                + " " + evidenceText;
        boolean mustMentionOk = c.getMustMention() == null || c.getMustMention().isEmpty()
                || c.getMustMention().stream().anyMatch(answerText::contains);

        double score = (conclusionMatch ? 0.5 : 0)
                + (evidenceMatch ? 0.3 : 0)
                + (mustMentionOk ? 0.2 : 0);
        return new Deterministic(conclusionMatch, evidenceMatch, mustMentionOk, round(score));
    }

    /** LLM 裁判：用固定 rubric 让模型打分，输出 JSON。失败返回 -1（不影响主指标）。 */
    @Override
    public int llmScore(EvalCase c, DiagnosisResult result) {
        if (!props.getJudge().isEnabled()) {
            return -1;
        }
        if (result == null) {
            return 0;
        }
        String prompt = """
                你是一名严格的电商客服质检员。请对下面这条"订单异常诊断"打分（0-100 整数）。

                【标准答案】根因类别：%s
                【用户问题】%s
                【模型结论】%s
                【模型理由】%s
                【模型建议】%s
                【模型引用的工具证据】
                %s

                打分标准：
                - 理由是否与标准答案的根因一致、是否引用了具体数据（60 分）
                - 建议是否具体可执行、是否符合客服操作规范（40 分）
                只输出 JSON：{"score": 85, "reason": "简要点评"}
                """.formatted(c.getExpectedConclusion(), c.getQuestion(),
                result.getConclusionCode(), result.getReason(), result.getSuggestion(),
                evidenceText(result));

        try {
            LlmClient.LlmResponse resp = sandbox.judgeClient().chat(
                    List.of(com.example.orderagent.agent.core.AgentMemory.LlmMessage.user(prompt)),
                    List.of(), "none");
            JsonNode node = extractJson(resp.content());
            if (node == null) {
                return -1;
            }
            return Math.max(0, Math.min(100, node.path("score").asInt(0)));
        } catch (LlmException e) {
            log.warn("LLM 裁判失败，本条跳过裁判分: {}", e.getMessage());
            return -1;
        }
    }

    private String evidenceText(DiagnosisResult r) {
        if (r.getEvidence() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DiagnosisResult.Evidence e : r.getEvidence()) {
            sb.append("- ").append(e.getTool()).append(": ").append(e.getSummary()).append('\n');
        }
        return sb.toString();
    }

    private JsonNode extractJson(String s) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf('{'), j = s.lastIndexOf('}');
        if (i < 0 || j <= i) {
            return null;
        }
        try {
            return mapper.readTree(s.substring(i, j + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}