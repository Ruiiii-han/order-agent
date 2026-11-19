package com.example.orderagent.diagnose;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.ToolResult;
import com.example.orderagent.llm.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型输出 → 结构化结论的解析器。
 *
 * <p>模型不一定听话（尤其接真实模型的 v1 阶段），所以这里是"防幻觉的最后一道闸"：
 * <ul>
 *   <li>从可能夹杂 markdown 的文本里抽取 JSON；</li>
 *   <li>结论枚举必须命中白名单，否则降级为 NEED_MANUAL；</li>
 *   <li>证据链<b>用工具的原始摘要覆盖</b>模型声称的证据，杜绝编造；</li>
 *   <li>有任何工具失败时，置信度强制打折。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisParser {

    private final ObjectMapper mapper;

    public DiagnosisResult parse(String content, AgentContext ctx, List<ToolResult> toolResults,
                                 long promptTokens, long completionTokens) {
        JsonNode node = extractJson(content);
        DiagnosisResult.ConclusionCode code = DiagnosisResult.ConclusionCode.NEED_MANUAL;
        double confidence = 0.4;
        String reason = "模型未返回可解析的结构化结论";
        String suggestion = "请转人工核实";

        if (node != null) {
            DiagnosisResult.ConclusionCode c = DiagnosisResult.ConclusionCode.of(node.path("conclusionCode").asText(null));
            if (c != null) {
                code = c;
            }
            if (node.hasNonNull("confidence")) {
                confidence = clamp(node.path("confidence").asDouble(0.5));
            }
            reason = node.path("reason").asText(reason);
            suggestion = node.path("suggestion").asText(suggestion);
        }

        // 证据链不信任模型，直接用工具结果重建
        List<DiagnosisResult.Evidence> evidence = toolResults.stream()
                .map(r -> DiagnosisResult.Evidence.builder()
                        .tool(r.toolName())
                        .summary(r.success() ? r.summary() : "[" + r.errorCode() + "] " + r.errorMsg())
                        .success(r.success())
                        .durationMs(r.durationMs())
                        .build())
                .toList();

        boolean degraded = toolResults.stream().anyMatch(r -> !r.success());
        if (degraded) {
            // 有工具失败：置信度打折，最多 0.6
            confidence = Math.min(confidence, 0.6);
        }

        return DiagnosisResult.builder()
                .conclusionCode(code)
                .conclusionLabel(code.label())
                .confidence(confidence)
                .reason(reason)
                .suggestion(suggestion)
                .evidence(evidence)
                .rawContent(content)
                .degraded(degraded)
                .build();
    }

    /** 从文本里抽取第一个完整的 JSON 对象（兼容 ```json 代码块）。 */
    JsonNode extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    inStr = false;
                }
                continue;
            }
            if (ch == '"') {
                inStr = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    String json = content.substring(start, i + 1);
                    try {
                        return mapper.readTree(json);
                    } catch (Exception e) {
                        log.debug("JSON 解析失败: {}", e.getMessage());
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}