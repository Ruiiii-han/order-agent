package com.example.orderagent.diagnose;

import com.example.orderagent.service.OrderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/** 诊断会话落库：会话、工具调用链。工具链是回放与评测的数据基础。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagSessionRepository {

    private final JdbcTemplate jdbc;
    private final OrderQueryService orderQuery;

    public void insertRunning(String sessionId, String orderNo, long userId, String question,
                              String promptVersion, String model) {
        jdbc.update("INSERT INTO diag_session(session_id,order_no,user_id,question,prompt_version,model,status,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                sessionId, orderNo, userId, question, promptVersion, model, "RUNNING", LocalDateTime.now());
    }

    public void finish(String sessionId, DiagnosisResult result, int tokens, double cost, int latencyMs) {
        jdbc.update("UPDATE diag_session SET answer=?, conclusion_code=?, confidence=?, total_tokens=?, cost=?, "
                        + "latency_ms=?, status=? WHERE session_id=?",
                result.getRawContent(),
                result.getConclusionCode() == null ? "NEED_MANUAL" : result.getConclusionCode().name(),
                result.getConfidence(), tokens, cost, latencyMs, "DONE", sessionId);
    }

    public void markFailed(String sessionId, String errorMsg) {
        jdbc.update("UPDATE diag_session SET status=?, answer=? WHERE session_id=?",
                "FAILED", errorMsg, sessionId);
    }

    public void saveToolCall(String sessionId, int stepNo, String tool, String args, String result,
                             String summary, boolean success, String errorMsg, long durationMs) {
        jdbc.update("INSERT INTO tool_call_log(session_id,step_no,tool_name,arguments,result,summary,success,error_msg,duration_ms,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                sessionId, stepNo, tool, args, result, summary, success ? 1 : 0, errorMsg, durationMs,
                LocalDateTime.now());
    }

    public Optional<Map<String, Object>> find(String sessionId) {
        var rows = jdbc.queryForList("SELECT * FROM diag_session WHERE session_id = ?", sessionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public java.util.List<Map<String, Object>> toolCalls(String sessionId) {
        return jdbc.queryForList("SELECT * FROM tool_call_log WHERE session_id = ? ORDER BY id", sessionId);
    }

    /** 越权校验：历史记录的订单必须属于当前用户。 */
    public boolean owns(String sessionId, long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM diag_session WHERE session_id = ? AND user_id = ?", Integer.class,
                sessionId, userId);
        return n != null && n > 0;
    }
}