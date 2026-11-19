package com.example.orderagent.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "order-agent")
public class AgentProperties {

    private Llm llm = new Llm();
    private Agent agent = new Agent();
    private Tools tools = new Tools();
    private Eval eval = new Eval();

    @Data
    public static class Llm {
        private String provider = "mock";
    }

    @Data
    public static class Agent {
        private int maxSteps = 6;
        private int maxErrors = 2;
        private int toolConcurrency = 5;
        private long toolTimeoutMs = 1500;
        private int summaryMaxChars = 600;
        private int memoryMaxTurns = 6;
        private String faultStrategy = "executor";
    }

    @Data
    public static class Tools {
        private long simulatedLatencyMs = 80;
    }

    @Data
    public static class Eval {
        private int concurrency = 4;
        private String dailyRegressionSchedule = "0 0 3 * * *";
        private boolean dailyRegressionEnabled = false;
    }
}