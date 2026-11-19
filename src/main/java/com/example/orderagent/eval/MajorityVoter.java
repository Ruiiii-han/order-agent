package com.example.orderagent.eval;

import com.example.orderagent.diagnose.DiagnoseService;
import com.example.orderagent.diagnose.DiagnosisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多数表决器 —— 用「同一问题问 3 次取众数」换取稳定性。
 *
 * <p>为什么需要它（扩大评测集后发现的真实问题）：
 * DeepSeek-V4.1-Flash 在 temperature=0.2 下仍有约 1.4% 的随机抖动——
 * 同一个订单、同一个问题，重跑结果可能一次 STOCK_OUT、一次 NEED_MANUAL。
 * 单次评测得到的分数因此不可复现。
 *
 * <p>实现取舍：
 * <ul>
 *   <li>只用<b>结论枚举</b>投票，不投内容（内容是自由文本，投票没有意义）；</li>
 *   <li>票数相同选置信度高的那个，再相同取第一轮（deterministic tie-break）；</li>
 *   <li>同时记录票分布，用于后续分析「哪些场景模型不稳定」；</li>
 *   <li>成本 ×N，所以只在关键场景或离线评测启用，<b>线上实时诊断默认不启用</b>。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MajorityVoter {

    private final DiagnoseService diagnoseService;

    /** 单轮结果 + 投票信息。 */
    public record VoteOutcome(
            DiagnosisResult result,
            long latencyMs,
            long tokens,
            int rounds,
            Map<String, Integer> distribution,
            int unanimous
    ) {
    }

    /**
     * @param orderNo    订单号
     * @param userId     发起人
     * @param question   问题
     * @param rounds     投票轮数（1 = 不投票）
     * @param promptVersion prompt 版本（可为 null）
     */
    public VoteOutcome diagnose(String orderNo, long userId, String question,
                                int rounds, String promptVersion) {
        int n = Math.max(1, rounds);
        List<DiagnoseService.DiagnoseOutcome> outs = new ArrayList<>(n);
        long totalLatency = 0;
        long totalTokens = 0;

        for (int i = 0; i < n; i++) {
            try {
                var o = diagnoseService.diagnose(orderNo, userId, question, false, promptVersion);
                outs.add(o);
                totalLatency += o.latencyMs();
                totalTokens += o.tokens();
            } catch (RuntimeException e) {
                log.warn("第 {} 轮诊断失败: {}", i + 1, e.getMessage());
            }
        }
        if (outs.isEmpty()) {
            throw new IllegalStateException("全部轮次均失败");
        }

        // 统计票数
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (var o : outs) {
            String key = o.result() == null || o.result().getConclusionCode() == null
                    ? "NULL" : o.result().getConclusionCode().name();
            dist.merge(key, 1, Integer::sum);
        }

        // 选票数最多的；平票时取置信度最高的
        String winner = null;
        int maxVotes = -1;
        double bestConf = -1;
        for (var e : dist.entrySet()) {
            DiagnoseService.DiagnoseOutcome sample = outs.stream()
                    .filter(o -> o.result() != null && o.result().getConclusionCode() != null
                            && o.result().getConclusionCode().name().equals(e.getKey()))
                    .findFirst().orElse(null);
            double conf = sample != null ? sample.result().getConfidence() : 0;
            if (e.getValue() > maxVotes || (e.getValue() == maxVotes && conf > bestConf)) {
                maxVotes = e.getValue();
                bestConf = conf;
                winner = e.getKey();
            }
        }

        // 取票数最多的那轮作为代表结果（保留其完整理由与证据）
        final String w = winner;
        DiagnoseService.DiagnoseOutcome chosen = outs.stream()
                .filter(o -> o.result() != null && o.result().getConclusionCode() != null
                        && o.result().getConclusionCode().name().equals(w))
                .findFirst().orElse(outs.get(0));

        DiagnosisResult r = chosen.result();
        r.setVoteRounds(n);
        r.setVoteDistribution(dist);

        int unanimous = (dist.size() == 1) ? n : 0;
        return new VoteOutcome(r, totalLatency / Math.max(1, outs.size()), totalTokens,
                n, dist, unanimous);
    }
}