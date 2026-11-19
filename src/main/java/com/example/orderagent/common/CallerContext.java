package com.example.orderagent.common;

/** 会话发起人上下文。真实项目从 Spring Security 的 SecurityContext 里取。 */
public record CallerContext(long userId) {
}