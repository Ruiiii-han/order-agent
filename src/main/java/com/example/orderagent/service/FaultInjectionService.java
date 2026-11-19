package com.example.orderagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障注入：演示与评测构造"下游不可用"场景。
 *
 * <p>面试价值点：这是"可复现降级演示"的基础设施——关掉物流服务后，
 * Agent 仍能给出 NEED_MANUAL 或基于其他证据的结论，并自动降低置信度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaultInjectionService {

    public enum FaultType {
        /** 正常 */
        NONE,
        /** 慢响应（超过工具超时） */
        SLOW,
        /** 直接抛异常 */
        ERROR,
        /** 返回空数据 */
        EMPTY
    }

    private final JdbcTemplate jdbc;
    private final AgentProperties props;
    /** key: toolName 或 orderNo；value: 故障类型 */
    private final Map<String, FaultType> faults = new ConcurrentHashMap<>();

    /** 在工具执行前调用：若命中故障则按类型生效。 */
    public void maybeInject(String toolName, String orderNo) {
        FaultType t = faults.getOrDefault(orderNo, faults.getOrDefault(toolName, FaultType.NONE));
        switch (t) {
            case SLOW -> sleep(props.getAgent().getToolTimeoutMs() + 800);
            case ERROR -> throw new IllegalStateException("下游服务不可用(故障注入): " + toolName);
            case EMPTY -> { /* 由工具实现检查 isEmptyInjected 返回空 */ }
            default -> { }
        }
    }

    public boolean isEmptyInjected(String toolName, String orderNo) {
        FaultType t = faults.getOrDefault(orderNo, faults.getOrDefault(toolName, FaultType.NONE));
        return t == FaultType.EMPTY;
    }

    public void set(String target, FaultType type) {
        if (type == FaultType.NONE) {
            faults.remove(target);
        } else {
            faults.put(target, type);
        }
        jdbc.update("UPDATE fault_injection SET enabled=0");
        jdbc.update("INSERT INTO fault_injection(target, fault_type, enabled, created_at) VALUES (?,?,?,?)",
                target, type.name(), 1, LocalDateTime.now());
        log.info("故障注入: target={} type={}", target, type);
    }

    public Map<String, FaultType> current() {
        return Map.copyOf(faults);
    }

    public void clear() {
        faults.clear();
        jdbc.update("DELETE FROM fault_injection");
    }

    public List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM fault_injection ORDER BY id DESC");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}