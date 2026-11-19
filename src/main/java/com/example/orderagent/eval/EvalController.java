package com.example.orderagent.eval;

import com.example.orderagent.common.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 评测接口：发起评测、查趋势、查失败明细。 */
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalRunner runner;
    private final EvalDatasetService datasetService;

    /** 发起一次评测（同步执行，小数据集几百条在几十秒内完成）。 */
    @PostMapping("/run")
    public ApiResult<Map<String, Object>> run(@RequestBody(required = false) EvalRunner.EvalRunRequest req) {
        var r = req == null ? new EvalRunner.EvalRunRequest(null, null, null, "MANUAL") : req;
        String runNo = runner.run(r);
        return ApiResult.ok(runner.runDetail(runNo));
    }

    @GetMapping("/runs")
    public ApiResult<List<Map<String, Object>>> runs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(runner.listRuns(limit));
    }

    @GetMapping("/runs/{runNo}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String runNo) {
        return ApiResult.ok(runner.runDetail(runNo));
    }

    /** 趋势数据：准确率随运行次数的变化，前端画折线。 */
    @GetMapping("/trend")
    public ApiResult<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "30") int limit) {
        return ApiResult.ok(runner.listRuns(limit).stream()
                .map(m -> Map.<String, Object>of(
                        "runNo", m.get("run_no"),
                        "accuracy", m.get("accuracy"),
                        "evidenceRate", m.get("evidence_rate"),
                        "model", String.valueOf(m.get("model")),
                        "promptVersion", String.valueOf(m.get("prompt_version")),
                        "finishedAt", String.valueOf(m.get("finished_at"))))
                .toList());
    }

    @GetMapping("/datasets")
    public ApiResult<List<Map<String, Object>>> datasets() {
        return ApiResult.ok(datasetService.datasetSummary());
    }

    @PostMapping("/datasets/build")
    public ApiResult<Integer> build(@RequestParam(defaultValue = "v1") String version) {
        return ApiResult.ok(datasetService.buildIfAbsent(version));
    }

    @GetMapping("/running")
    public ApiResult<String> running() {
        return ApiResult.ok(runner.runningRunNo());
    }
}