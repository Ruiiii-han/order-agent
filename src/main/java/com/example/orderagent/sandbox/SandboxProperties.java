package com.example.orderagent.sandbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 沙箱侧配置（与 llm.* 区分，避免两个 @ConfigurationProperties 抢同一个前缀）。 */
@Data
@Component
@ConfigurationProperties(prefix = "order-agent.sandbox")
public class SandboxProperties {
    /** 是否允许前端通过接口切换模型（演示用，生产应关闭） */
    private boolean allowRuntimeSwitch = true;
    /** 评测时是否校验"被测模型必须是真实模型"，为 true 时 mock 结果不计入正式数据 */
    private boolean requireRealModelForEval = false;
}