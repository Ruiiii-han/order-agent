package com.example.orderagent.eval;

import com.example.orderagent.service.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 每日自动回归 —— 让"评测"从一次性的动作变成基础设施。
 *
 * <p>面试话术：把评测接进定时任务后，每次改 prompt / 换模型，第二天就能在 Grafana 上看到
 * 准确率是涨还是跌，而不是上线后靠用户投诉发现。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EvalScheduler {

    private final EvalRunner runner;
    private final AgentProperties props;

    @Scheduled(cron = "${order-agent.eval.daily-regression-schedule:0 0 3 * * *}")
    public void dailyRegression() {
        if (!props.getEval().isDailyRegressionEnabled()) {
            return;
        }
        if (runner.runningRunNo() != null) {
            log.warn("已有评测在运行({})，本次定时回归跳过", runner.runningRunNo());
            return;
        }
        try {
            log.info("开始每日自动回归");
            String runNo = runner.run(new EvalRunner.EvalRunRequest("v1", null, null, "SCHEDULE"));
            log.info("每日回归完成: {}", runNo);
        } catch (Exception e) {
            log.error("每日回归失败", e);
        }
    }
}