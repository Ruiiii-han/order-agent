package com.example.orderagent.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 诊断请求。用户身份由 Header 模拟（真实项目是 SecurityContext）。 */
public class DiagnoseRequest {

    @NotBlank(message = "订单号不能为空")
    @Pattern(regexp = "^[A-Z]\\d{4,12}$", message = "订单号格式非法")
    private String orderNo;

    @NotBlank(message = "问题描述不能为空")
    private String question;

    /** 续聊时传会话 ID。 */
    private String sessionId;

    /** 模拟登录用户，默认 10000。 */
    private Long userId;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public long resolveUserId() {
        return userId == null ? 10000L : userId;
    }
}