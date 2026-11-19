package com.example.orderagent.agent.core;

/** Agent 被主动终止（超出步数、连续错误超限）。由全局异常处理器转 429。 */
public class AgentTrippedException extends RuntimeException {
    public AgentTrippedException(String message) {
        super(message);
    }
}