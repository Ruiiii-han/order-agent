package com.example.orderagent.agent.core;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 执行上下文：一次诊断请求的全部可变状态。
 *
 * <p>为什么不用 ThreadLocal？因为工具是<b>并发</b>执行的，
 * 跨线程传递上下文要么用 ThreadLocal 继承（容易被线程池污染），
 * 要么显式传递一个不可变/受控的可变对象。这里选择显式传递，更安全。
 */
@Data
public class AgentContext {

    /** 会话 ID，一次诊断一个。 */
    private final String sessionId;

    /** 发起人，用于越权校验，**整个链路不可变**。 */
    private final long userId;

    /** 被诊断的订单号。 */
    private final String orderNo;

    /** 用户原始问题。 */
    private final String question;

    /** 工具步骤计数，每发生一轮工具调用 +1。 */
    private int stepNo;

    /** 连续工具错误次数，达到阈值直接终止。 */
    private int errorCount;

    /** 单次诊断内累计 token。 */
    private long totalPromptTokens;
    private long totalCompletionTokens;

    /** 是否发生过工具失败（决定最终置信度降级）。 */
    private boolean hadToolFailure;

    /** 本次诊断调用过的工具 -> 结果摘要，供"证据链"使用。 */
    private final Map<String, String> evidence = new LinkedHashMap<>();

    /** 调用过的工具名集合（编排层判断"是否已全量取证"用）。 */
    private final java.util.Set<String> calledTools = new java.util.LinkedHashSet<>();

    public java.util.Set<String> calledTools() {
        return calledTools;
    }

    /**
     * 记录本次真正要执行的工具（在<b>执行前</b>调用）。
     *
     * <p>为什么必须在执行前记录：编排层补齐全量取证时，判断的是"这一轮要调哪些"，
     * 如果用"执行完后才写入"的 {@link #calledTools}，下一轮就会把已补的工具再加一遍，
     * 造成重复取证（实测一个订单查出 9 条证据）。这是接真实模型抓到的第二个真实 bug。
     */
    public void markPlanned(String toolName) {
        calledTools.add(toolName);
    }

    public AgentContext(String sessionId, long userId, String orderNo, String question) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.orderNo = orderNo;
        this.question = question;
        this.stepNo = 0;
        this.errorCount = 0;
        this.totalPromptTokens = 0;
        this.totalCompletionTokens = 0;
    }

    public int nextStep() {
        return ++stepNo;
    }

    public void addTokens(long prompt, long completion) {
        this.totalPromptTokens += prompt;
        this.totalCompletionTokens += completion;
    }

    public void addEvidence(String toolName, String summary) {
        // 同一工具多次调用只保留最后一次，避免证据链冗余
        this.evidence.put(toolName, summary);
        this.calledTools.add(toolName);
    }
}