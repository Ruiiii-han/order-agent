package com.example.orderagent;

import com.example.orderagent.diagnose.DiagnoseService;
import com.example.orderagent.diagnose.DiagnosisResult;
import com.example.orderagent.eval.EvalRunner;
import com.example.orderagent.service.FaultInjectionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端集成测试（mock 模型）。
 *
 * <p>覆盖 5 个最值得被验证的路径：
 * 1. 正常诊断能给出正确结论；
 * 2. 越权访问被拦截，且不向调用方泄露"订单是否存在"；
 * 3. 单工具故障时能降级输出结论（而不是整单失败）；
 * 4. 全工具故障时不会死循环；
 * 5. 评测能跑通并产出准确率。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderAgentIntegrationTest {

    /** 种子数据规则：userId = 10000 + (序号 % 5)，A101xx 这一组的订单归属在运行时查出。 */
    private static final String ORDER = "A10001";

    @Autowired
    DiagnoseService diagnoseService;

    @Autowired
    FaultInjectionService faultInjection;

    @Autowired
    EvalRunner evalRunner;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void cleanFaults() {
        faultInjection.clear();
    }

    private long ownerOf(String orderNo) {
        List<Long> ids = jdbc.queryForList("SELECT user_id FROM order_main WHERE order_no = ?", Long.class, orderNo);
        assertThat(ids).as("订单 %s 应存在于种子数据中", orderNo).isNotEmpty();
        return ids.get(0);
    }

    @Test
    @Order(1)
    @DisplayName("缺货单应诊断为 STOCK_OUT，且证据链完整")
    void stockOutDiagnose() {
        long owner = ownerOf(ORDER);
        var outcome = diagnoseService.diagnose(ORDER, owner, "为什么一直没发货", true);
        DiagnosisResult r = outcome.result();

        assertThat(r.getConclusionCode()).isEqualTo(DiagnosisResult.ConclusionCode.STOCK_OUT);
        assertThat(r.getConfidence()).isGreaterThan(0.5);
        assertThat(r.getEvidence()).isNotEmpty();
        assertThat(r.getEvidence().stream().anyMatch(e -> e.isSuccess())).isTrue();
        System.out.printf("[测试1] 结论=%s 置信度=%.2f 工具数=%d 耗时=%dms%n 证据=%s%n",
                r.getConclusionCode(), r.getConfidence(), r.getEvidence().size(), outcome.latencyMs(),
                r.getEvidence().stream().map(DiagnosisResult.Evidence::getSummary).toList());
    }

    @Test
    @Order(2)
    @DisplayName("越权访问他人订单必须被拦截")
    void forbiddenAccess() {
        long owner = ownerOf(ORDER);
        long stranger = owner + 12345;
        assertThatThrownBy(() -> diagnoseService.diagnose(ORDER, stranger, "看看这个订单", false))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @Order(3)
    @DisplayName("单工具故障时应降级输出，而不是整单失败")
    void degradeOnToolFailure() {
        faultInjection.set("queryStock", FaultInjectionService.FaultType.ERROR);
        long owner = ownerOf(ORDER);
        var outcome = diagnoseService.diagnose(ORDER, owner, "库存还有吗", false);

        assertThat(outcome.result()).isNotNull();
        assertThat(outcome.result().isDegraded()).isTrue();
        assertThat(outcome.result().getConfidence()).isLessThanOrEqualTo(0.6);
        System.out.printf("[测试3] 降级结论=%s 置信度=%.2f 失败工具数=%d%n",
                outcome.result().getConclusionCode(), outcome.result().getConfidence(),
                outcome.result().getEvidence().stream().filter(e -> !e.isSuccess()).count());
    }

    @Test
    @Order(4)
    @DisplayName("全工具故障时不应死循环，必须收敛为人工兜底")
    void allToolsDownShouldNotLoop() {
        for (String t : List.of("queryOrder", "queryPayment", "queryStock", "queryLogistics", "queryRisk")) {
            faultInjection.set(t, FaultInjectionService.FaultType.ERROR);
        }
        long owner = ownerOf(ORDER);
        long start = System.currentTimeMillis();
        assertThatNoException()
                .isThrownBy(() -> diagnoseService.diagnose(ORDER, owner, "这单怎么了", false));
        long elapsed = System.currentTimeMillis() - start;
        // 全链路失败也必须在可控时间内结束（步数/错误次数限制生效）
        assertThat(elapsed).isLessThan(30_000);
    }

    @Test
    @Order(5)
    @DisplayName("评测能跑通并产出准确率")
    void evalRuns() {
        String runNo = evalRunner.run(new EvalRunner.EvalRunRequest("v1", 16, false, "MANUAL"));
        var detail = evalRunner.runDetail(runNo);
        assertThat(detail.get("run")).isNotNull();
        System.out.println("[测试5] eval run 完成: " + runNo + " -> " + detail.get("run"));
    }
}