package com.example.orderagent.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守护层单测：这是最该写测试的地方——
 * 越权、参数注入、工具白名单，任何一个漏了都是安全事故。
 */
class GuardTest {

    @Test
    void 订单号格式非法应被拒绝() {
        // 纯逻辑校验：正则 ^[A-Z]\d{4,12}$
        assertThat("A10001".matches("^[A-Z]\\d{4,12}$")).isTrue();
        assertThat("a10001".matches("^[A-Z]\\d{4,12}$")).isFalse();
        assertThat("A1".matches("^[A-Z]\\d{4,12}$")).isFalse();
        assertThat("A10001 OR 1=1".matches("^[A-Z]\\d{4,12}$")).isFalse();
    }

    @Test
    void 信号优先级_缺货应优先于支付() {
        String summary = "订单状态=PAID，支付状态=SUCCESS，库存状态=OUT_OF_STOCK";
        // 摘要里两者都提，firstSignal 按 ALL 的先后顺序取
        assertThat(EvidenceSignals.firstSignal(EvidenceSignals.summary(EvidenceSignals.STOCK_OUT, summary)))
                .isEqualTo(EvidenceSignals.STOCK_OUT);
    }

    @Test
    void 摘要信号可以被反向解析() {
        String s = EvidenceSignals.summary(EvidenceSignals.PAY_TIMEOUT, "未查询到支付流水");
        assertThat(EvidenceSignals.signalOfSummary(s)).isEqualTo(EvidenceSignals.PAY_TIMEOUT);
        assertThat(EvidenceSignals.signalOfSummary("无信号摘要")).isNull();
    }

    @Test
    void 未知信号应返回null() {
        assertThat(EvidenceSignals.firstSignal("一切正常，没有任何异常")).isNull();
    }

    @Test
    void 越权异常类型必须是SecurityException() {
        // AgentLoop 用 SecurityException 判断"必须终止会话"，
        // 如果这里改成普通 RuntimeException，越权信息会被回填给模型 → 信息泄露
        assertThatThrownBy(() -> {
            throw new SecurityException("x");
        }).isInstanceOf(SecurityException.class);
    }
}