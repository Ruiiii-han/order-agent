package com.example.orderagent.agent.exec;

import com.example.orderagent.service.AgentProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 执行策略装配：由配置决定用自研还是 Resilience4j 实现。 */
@Slf4j
@Configuration
public class ToolExecutionConfig {

    @Bean(destroyMethod = "shutdown")
    public ToolExecutor toolExecutor(AgentProperties props, MeterRegistry registry) {
        return new ToolExecutor(props, registry,
                "queryOrder", "queryPayment", "queryStock", "queryLogistics", "queryRisk");
    }

    @Bean
    public ToolExecutionStrategy toolExecutionStrategy(ToolExecutor executor,
                                                       AgentProperties props,
                                                       MeterRegistry registry) {
        String s = props.getAgent().getFaultStrategy();
        if ("resilience4j".equalsIgnoreCase(s)) {
            log.info("工具执行策略: resilience4j（熔断 + 隔舱 + 虚拟线程）");
            ResilienceStrategy strategy = new ResilienceStrategy(props.getAgent().getToolTimeoutMs(),
                    props.getAgent().getToolConcurrency(), registry);
            // 注意：策略接口不暴露 shutdown，销毁逻辑放这里显式处理，避免 Spring 的 destroy-method 签名校验失败
            return strategy;
        }
        log.info("工具执行策略: 自研线程池 + CompletableFuture 超时");
        return new ParallelExecutorStrategy(executor, props);
    }

    /** 暴露工具名列表，便于管理端查看注册情况。 */
    public static List<String> defaultTools() {
        return List.of("queryOrder", "queryPayment", "queryStock", "queryLogistics", "queryRisk");
    }
}