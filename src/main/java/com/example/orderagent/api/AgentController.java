package com.example.orderagent.api;

import com.example.orderagent.common.ApiResult;
import com.example.orderagent.diagnose.DiagnoseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 诊断接口。
 *
 * <p>技术点：
 * <ul>
 *   <li>SSE 流式：先推 {@code step} 事件（让用户看到"正在查库存"），再推 {@code result}；</li>
 *   <li>SSE 在独立线程里跑，避免占用 Tomcat 工作线程；</li>
 *   <li>用户身份从 Header {@code X-User-Id} 取（真实项目用 Spring Security）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final DiagnoseService diagnoseService;
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 阻塞版：评测与 curl 调试用。 */
    @PostMapping("/diagnose")
    public ApiResult<DiagnoseService.DiagnoseOutcome> diagnose(@Valid @RequestBody DiagnoseRequest req) {
        return ApiResult.ok(diagnoseService.diagnose(req.getOrderNo(), req.resolveUserId(),
                req.getQuestion(), true));
    }

    /** SSE 流式版：前端用 EventSource / fetch 消费。 */
    @PostMapping(value = "/diagnose/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter diagnoseStream(@Valid @RequestBody DiagnoseRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        sseExecutor.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("step")
                        .data(Map.of("stage", "start", "msg", "正在解析问题并规划查询步骤")));
                var outcome = diagnoseService.diagnose(req.getOrderNo(), req.resolveUserId(),
                        req.getQuestion(), true);
                emitter.send(SseEmitter.event().name("step")
                        .data(Map.of("stage", "tools_done", "msg", "已完成数据采集")));
                emitter.send(SseEmitter.event().name("result").data(outcome));
                emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("sessionId", outcome.sessionId(), "tokens", outcome.tokens(),
                                "cost", outcome.cost(), "latencyMs", outcome.latencyMs())));
                emitter.complete();
            } catch (SecurityException e) {
                sendError(emitter, 403, "无权访问该订单");
            } catch (Exception e) {
                log.warn("SSE 诊断失败: {}", e.getMessage());
                sendError(emitter, 429, e.getMessage());
            }
        });
        return emitter;
    }

    /** 查会话详情（含工具调用链，用于回放）。 */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResult<Object>> session(@PathVariable String sessionId,
                                                     @RequestHeader(value = "X-User-Id", defaultValue = "10000") long userId) {
        if (!diagnoseService.owns(sessionId, userId)) {
            // 统一 403，不区分"不存在"与"无权"，防止枚举
            return ResponseEntity.status(403).body(ApiResult.fail(403, "无权访问"));
        }
        var outcome = diagnoseService.find(sessionId);
        return ResponseEntity.ok(ApiResult.ok(Map.of(
                "outcome", outcome.orElse(null),
                "toolCalls", diagnoseService.toolCalls(sessionId)
        )));
    }

    private void sendError(SseEmitter emitter, int code, String msg) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("code", code, "msg", msg)));
        } catch (IOException ignored) {
            // 客户端已断开
        } finally {
            emitter.complete();
        }
    }

    /** 便于测试关闭线程池。 */
    public void shutdown() {
        sseExecutor.shutdownNow();
    }

    /** 工具链查询的辅助方法（评测失败定位用）。 */
    public List<Map<String, Object>> toolCallsOf(String sessionId) {
        return diagnoseService.toolCalls(sessionId);
    }
}