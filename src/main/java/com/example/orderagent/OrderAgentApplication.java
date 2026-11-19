package com.example.orderagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order-agent 启动类。
 *
 * <p>项目结构：
 * <ul>
 *   <li>{@code agent.core}  —— 自研 Agent 执行循环、工具注册表、记忆</li>
 *   <li>{@code agent.tools} —— 五个订单链路只读工具</li>
 *   <li>{@code agent.guard} —— 参数校验、越权拦截（工具执行层兜底）</li>
 *   <li>{@code llm}         —— 模型客户端（mock / deepseek / qwen）</li>
 *   <li>{@code eval}        —— 评测集、批量执行、三层判分、看板</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class OrderAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderAgentApplication.class, args);
    }
}