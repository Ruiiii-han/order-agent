package com.example.orderagent.diagnose;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.AgentLoop;
import com.example.orderagent.agent.core.AgentTrippedException;
import com.example.orderagent.agent.core.ToolResult;
import com.example.orderagent.llm.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 诊断编排服务：串起「建会话 → 跑 Agent → 解析 → 落库」。
 *
 * <p>Controller 只做参数校验与 SSE 转发，业务全部在这里，方便评测复用（评测也要走这条链路，
 * 否则测的就不是线上真实路径了）。
 */
@Slf4j
@Service
public class DiagnoseService {

    private final AgentLoop agentLoop;
    private final DiagnosisParser parser;
    private final DiagSessionRepository repository;
    private final LlmProperties llmProperties;
    private final ObjectMapper mapper;

    public DiagnoseService(AgentLoop agentLoop, DiagnosisParser parser, DiagSessionRepository repository,
                           LlmProperties llmProperties, ObjectMapper mapper) {
        this.agentLoop = agentLoop;
        this.parser = parser;
        this.repository = repository;
        this.llmProperties = llmProperties;
        this.mapper = mapper;
    }

    /** 一次诊断的全部产物（含耗时、成本，供 SSE 与评测使用）。 */
    public record DiagnoseOutcome(
            String sessionId,
            DiagnosisResult result,
            long latencyMs,
            long tokens,
            double cost,
            String model
    ) {
    }

    public DiagnoseOutcome diagnose(String orderNo, long userId, String question, boolean persist) {
        return diagnose(orderNo, userId, question, persist, null);
    }

    /**
     * @param promptVersion 指定 prompt 版本（评测做 A/B 对照用），null 表示当前版本
     */
    public DiagnoseOutcome diagnose(String orderNo, long userId, String question, boolean persist,
                                    String promptVersion) {
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long start = System.currentTimeMillis();

        repository.insertRunning(sessionId, orderNo, userId, question,
                promptVersion == null ? com.example.orderagent.agent.core.PromptBuilder.VERSION : promptVersion,
                "pending");

        AgentContext ctx = new AgentContext(sessionId, userId, orderNo, question);
        try {
            AgentLoop.AgentRunResult run = agentLoop.run(ctx, promptVersion);
            List<ToolResult> results = run.toolResults();
            DiagnosisResult result = parser.parse(run.content(), ctx, results,
                    ctx.getTotalPromptTokens(), ctx.getTotalCompletionTokens());

            long latency = System.currentTimeMillis() - start;
            long tokens = ctx.getTotalPromptTokens() + ctx.getTotalCompletionTokens();
            double cost = llmProperties.cost(ctx.getTotalPromptTokens(), ctx.getTotalCompletionTokens());

            if (persist) {
                List<ToolResult> forLog = results;
                forLog.forEach(r -> repository.saveToolCall(sessionId, 1, r.toolName(),
                        safeWrite(r.data()), safeWrite(r.data()), r.summary(), r.success(), r.errorMsg(), r.durationMs()));
                repository.finish(sessionId, result, (int) tokens, cost, (int) latency);
            }
            return new DiagnoseOutcome(sessionId, result, latency, tokens, cost, run.model());
        } catch (AgentTrippedException | SecurityException e) {
            repository.markFailed(sessionId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            repository.markFailed(sessionId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    public Optional<DiagnoseOutcome> find(String sessionId) {
        return repository.find(sessionId).map(row -> {
            String answer = row.get("answer") == null ? null : String.valueOf(row.get("answer"));
            DiagnosisResult parsed = null;
            if (answer != null) {
                try {
                    DiagnosisParser p = parser;
                    DiagnosisResult.ConclusionCode code =
                            DiagnosisResult.ConclusionCode.of(String.valueOf(row.get("conclusion_code")));
                    parsed = DiagnosisResult.builder()
                            .conclusionCode(code)
                            .conclusionLabel(code == null ? "未知" : code.label())
                            .confidence(row.get("confidence") == null ? 0 : Double.parseDouble(String.valueOf(row.get("confidence"))))
                            .reason(answer)
                            .rawContent(answer)
                            .build();
                } catch (Exception ignored) {
                    // 历史数据格式异常不影响主流程
                }
            }
            return new DiagnoseOutcome(String.valueOf(row.get("session_id")), parsed, 0,
                    row.get("total_tokens") == null ? 0 : Long.parseLong(String.valueOf(row.get("total_tokens"))),
                    row.get("cost") == null ? 0 : Double.parseDouble(String.valueOf(row.get("cost"))),
                    String.valueOf(row.get("model")));
        });
    }

    public boolean owns(String sessionId, long userId) {
        return repository.owns(sessionId, userId);
    }

    public List<java.util.Map<String, Object>> toolCalls(String sessionId) {
        return repository.toolCalls(sessionId);
    }

    private String safeWrite(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}