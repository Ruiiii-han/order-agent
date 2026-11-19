package com.example.orderagent.common;

import com.example.orderagent.agent.core.AgentTrippedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> onBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail(e.getCode(), e.getMessage()));
    }

    /** Agent 熔断/超出步数/累计错误过多：返回 429，前端展示“已终止，请转人工”。 */
    @ExceptionHandler(AgentTrippedException.class)
    public ResponseEntity<ApiResult<Void>> onTripped(AgentTrippedException e) {
        log.warn("agent tripped: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResult.fail(429, e.getMessage()));
    }

    /**
     * 越权访问：统一返回 403，且不向调用方透露"订单是否存在"。
     * 注意这里要<b>吞掉</b>异常细节，只返回通用文案，防止被用来枚举他人订单。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResult<Void>> onForbidden(SecurityException e) {
        log.warn("越权访问被拦截: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.fail(403, "无权访问该订单"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> onValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResult.fail(400, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> onOther(Exception e) {
        // SecurityException 可能被包装（如 CompletableFuture 里），这里兜底识别
        if (e.getCause() instanceof SecurityException || e instanceof SecurityException) {
            return onForbidden(new SecurityException("无权访问"));
        }
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(500, "服务异常: " + e.getClass().getSimpleName()));
    }
}