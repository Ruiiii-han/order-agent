package com.example.orderagent.eval;

import com.example.orderagent.diagnose.DiagnoseService;
import com.example.orderagent.diagnose.DiagnosisResult;
import com.example.orderagent.service.AgentProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 批量评测执行器 —— "可回归"的实现主体。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>走线上同一条链路</b>：复用 {@link DiagnoseService}，否则测的只是评测专用分支，没有意义；</li>
 *   <li><b>并发但受控</b>：固定并发数，避免打爆模型限流；单条超时不计分也不卡住整体；</li>
 *   <li><b>结果全量落库</b>：run 汇总 + item 明细，方便对比两次运行找回归用例；</li>
 *   <li><b>指标暴露</b>：最新准确率做成 Gauge，Grafana 直接画趋势。</li>
 * </ol>
 */
@Slf4j
@Service
public class EvalRunner {

    private final EvalDatasetService datasetService;
    private final DiagnoseService diagnoseService;
    private final CaseJudge judge;
    private final AgentProperties props;
    private final JdbcTemplate jdbc;
    private final com.example.orderagent.llm.LlmClient llmClient;
    private final com.example.orderagent.llm.LlmProperties llmProperties;
    private final com.example.orderagent.llm.LlmProperties judgeProps;
    private final com.example.orderagent.sandbox.SandboxExecutor sandbox;
    private final MajorityVoter voter;
    private final AtomicReference<Double> latestAccuracy = new AtomicReference<>(null);
    private final AtomicReference<String> running = new AtomicReference<>(null);

    public EvalRunner(EvalDatasetService datasetService, DiagnoseService diagnoseService, CaseJudge judge,
                      AgentProperties props, JdbcTemplate jdbc, MeterRegistry registry,
                      com.example.orderagent.llm.LlmClient llmClient,
                      com.example.orderagent.llm.LlmProperties llmProperties,
                      com.example.orderagent.sandbox.SandboxExecutor sandbox,
                      MajorityVoter voter) {
        this.datasetService = datasetService;
        this.diagnoseService = diagnoseService;
        this.judge = judge;
        this.props = props;
        this.jdbc = jdbc;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.judgeProps = llmProperties;
        this.sandbox = sandbox;
        this.voter = voter;
        Gauge.builder("eval_accuracy", latestAccuracy, ref -> {
                    Double v = ref.get();
                    return v == null ? 0 : v;
                })
                .description("最近一次评测的结论准确率")
                .register(registry);
    }

    /**
     * 评测请求。
     *
     * @param promptVersion 指定 prompt 版本做 A/B 对照（null = 当前版本）
     * @param useJudge      是否启用 LLM 裁判（false = 只用确定性判分，纯客观指标）
     * @param voteRounds    多数表决轮数（1 = 不投票；3 = 同一用例跑 3 次取众数，抗随机抖动）
     */
    public record EvalRunRequest(String datasetVersion, Integer limit, Boolean useJudge,
                                 String triggeredBy, String promptVersion, Integer voteRounds) {
        /** 向后兼容：旧调用只有前四个参数。 */
        public EvalRunRequest(String datasetVersion, Integer limit, Boolean useJudge, String triggeredBy) {
            this(datasetVersion, limit, useJudge, triggeredBy, null, null);
        }

        public EvalRunRequest(String datasetVersion, Integer limit, Boolean useJudge,
                              String triggeredBy, String promptVersion) {
            this(datasetVersion, limit, useJudge, triggeredBy, promptVersion, null);
        }
    }

    /** 同步执行（评测量不大时用）；返回 runNo。 */
    public String run(EvalRunRequest req) {
        String version = req.datasetVersion() == null ? EvalDatasetService.DEFAULT_VERSION : req.datasetVersion();
        int limit = req.limit() == null ? 0 : req.limit();
        String promptVersion = req.promptVersion();
        if (promptVersion != null && !com.example.orderagent.agent.core.PromptRepository.VERSIONS.contains(promptVersion)) {
            throw new IllegalArgumentException("未知 prompt 版本: " + promptVersion
                    + "，可选 " + com.example.orderagent.agent.core.PromptRepository.VERSIONS);
        }
        List<EvalCase> cases = datasetService.load(version, limit);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("评测集为空: " + version);
        }
        log.info("开始评测 dataset={} promptVersion={} useJudge={} limit={} concurrency={}",
                version, promptVersion == null ? "当前(v3)" : promptVersion,
                req.useJudge(), limit, props.getEval().getConcurrency());

        String runNo = "R" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        running.set(runNo);
        int concurrency = Math.max(1, props.getEval().getConcurrency());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long start = System.currentTimeMillis();

        int voteRounds = req.voteRounds() == null ? 1 : Math.max(1, req.voteRounds());
        try {
            List<Future<ItemResult>> futures = new ArrayList<>(cases.size());
            for (EvalCase c : cases) {
                futures.add(pool.submit(() -> evalOne(runNo, c, promptVersion, voteRounds)));
            }
            List<ItemResult> results = new ArrayList<>(cases.size());
            for (Future<ItemResult> f : futures) {
                try {
                    results.add(f.get(180, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    f.cancel(true);
                    log.warn("评测用例超时被取消");
                } catch (ExecutionException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            aggregateAndSave(runNo, version, promptVersion, results, System.currentTimeMillis() - start);
            return runNo;
        } finally {
            pool.shutdownNow();
            running.set(null);
        }
    }

    private ItemResult evalOne(String runNo, EvalCase c, String promptVersion, int voteRounds) {
        long start = System.currentTimeMillis();
        DiagnosisResult result = null;
        long tokens = 0;
        try {
            if (voteRounds > 1) {
                // 多数表决：抗随机抖动，成本 ×N
                var vote = voter.diagnose(c.getOrderNo(), ownerOf(c.getOrderNo()), c.getQuestion(),
                        voteRounds, promptVersion);
                result = vote.result();
                tokens = vote.tokens();
            } else {
                // 注意：评测用 persist=false 可减少写库量
                var outcome = diagnoseService.diagnose(c.getOrderNo(), ownerOf(c.getOrderNo()), c.getQuestion(),
                        false, promptVersion);
                result = outcome.result();
                tokens = outcome.tokens();
            }
        } catch (Exception e) {
            log.warn("用例 {} 执行失败: {}", c.getCaseNo(), e.getMessage());
        }
        long latency = System.currentTimeMillis() - start;

        CaseJudge.Deterministic d = judge.deterministic(c, result);
        int llmScore = judge.llmScore(c, result);
        double total = computeTotal(d.score(), llmScore);
        boolean passed = d.conclusionMatch() && total >= 0.6;

        jdbc.update("INSERT INTO eval_run_item(run_no,case_id,case_no,question,expected_conclusion,actual_conclusion,"
                        + "conclusion_match,evidence_match,judge_score,judge_reason,passed,latency_ms,tokens) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runNo, c.getId(), c.getCaseNo(), c.getQuestion(), c.getExpectedConclusion(),
                result == null || result.getConclusionCode() == null ? "NULL" : result.getConclusionCode().name(),
                d.conclusionMatch() ? 1 : 0, d.evidenceMatch() ? 1 : 0, llmScore, null, passed ? 1 : 0,
                (int) latency, (int) tokens);

        return new ItemResult(c, result, d, llmScore, total, passed, latency, tokens);
    }

    /**
     * 总分 = 确定性权重 × 确定性分 + 裁判权重 × 裁判分/100
     * 裁判关闭（-1）时退化为纯确定性分，保证评测在无 Key 环境也能跑。
     */
    private double computeTotal(double deterministicScore, int llmScore) {
        var j = judgeProps.getJudge();
        if (llmScore < 0) {
            return deterministicScore;
        }
        return j.getWeightDeterministic() * deterministicScore + j.getWeightJudge() * (llmScore / 100.0);
    }

    private void aggregateAndSave(String runNo, String version, String promptVersion,
                                   List<ItemResult> results, long elapsedMs) {
        if (results.isEmpty()) {
            return;
        }
        int total = results.size();
        int passed = (int) results.stream().filter(ItemResult::passed).count();
        int evidence = (int) results.stream().filter(r -> r.deterministic().evidenceMatch()).count();
        List<Integer> judgeScores = results.stream().map(ItemResult::llmScore).filter(s -> s >= 0).toList();
        double judgeAvg = judgeScores.isEmpty() ? -1 : judgeScores.stream().mapToInt(i -> i).average().orElse(0);
        List<Long> latencies = results.stream().map(ItemResult::latencyMs).sorted().toList();
        long p99 = latencies.get(Math.min(total - 1, (int) Math.floor(total * 0.99)));
        long avgLatency = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long tokens = results.stream().mapToLong(ItemResult::tokens).sum();
        double accuracy = round((double) passed / total);

        Double prev = latestAccuracy.get();
        // 成本按模型单价折算：mock 模型单价为 0，真实模型按配置的价格计算
        double cost = llmProperties.cost(tokens, 0);
        String model = currentModel();
        jdbc.update("INSERT INTO eval_run(run_no,dataset_version,prompt_version,model,total,passed,accuracy,evidence_rate,"
                        + "judge_avg,avg_latency_ms,p99_latency_ms,total_tokens,total_cost,triggered_by,started_at,finished_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runNo, version, promptVersion == null
                        ? com.example.orderagent.agent.core.PromptBuilder.VERSION : promptVersion,
                model, total, passed, accuracy, round((double) evidence / total),
                judgeAvg < 0 ? null : judgeAvg, avgLatency, p99, tokens, cost, "MANUAL",
                LocalDateTime.now().minusNanos(elapsedMs * 1_000_000), LocalDateTime.now());
        latestAccuracy.set(accuracy);

        log.info("评测 {} 完成：{} 条，通过 {} 条，准确率 {}%，证据命中率 {}%，耗时 {}ms，较上次 {}",
                runNo, total, passed, accuracy * 100, round((double) evidence / total) * 100, elapsedMs,
                prev == null ? "首次" : String.format("%+.2f%%", (accuracy - prev) * 100));
    }

    private String currentModel() {
        try {
            return sandbox.client(com.example.orderagent.sandbox.SandboxExecutor.Role.SUBJECT).modelName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 评测时以数据的所有者身份查询。演示数据 userId = 10000 + (i % 5)。 */
    private long ownerOf(String orderNo) {
        List<Long> ids = jdbc.queryForList("SELECT user_id FROM order_main WHERE order_no = ?", Long.class, orderNo);
        return ids.isEmpty() ? 10000L : ids.get(0);
    }

    public String runningRunNo() {
        return running.get();
    }

    public List<Map<String, Object>> listRuns(int limit) {
        return jdbc.queryForList("SELECT * FROM eval_run ORDER BY id DESC LIMIT " + limit);
    }

    public Map<String, Object> runDetail(String runNo) {
        var run = jdbc.queryForList("SELECT * FROM eval_run WHERE run_no = ?", runNo);
        var items = jdbc.queryForList("SELECT * FROM eval_run_item WHERE run_no = ? ORDER BY id", runNo);
        // 失败用例按类别聚合，直接给出"哪类场景掉点"
        Map<String, Long> failedByCategory = items.stream()
                .filter(i -> "0".equals(String.valueOf(i.get("passed"))))
                .collect(java.util.stream.Collectors.groupingBy(
                        i -> String.valueOf(i.get("expected_conclusion")),
                        java.util.stream.Collectors.counting()));
        return Map.of(
                "run", run.isEmpty() ? Map.of() : run.get(0),
                "items", items,
                "failedByExpectedConclusion", failedByCategory.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (a, b) -> a, java.util.LinkedHashMap::new))
        );
    }

    record ItemResult(EvalCase c, DiagnosisResult result, CaseJudge.Deterministic deterministic,
                      int llmScore, double total, boolean passed, long latencyMs, long tokens) {
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}