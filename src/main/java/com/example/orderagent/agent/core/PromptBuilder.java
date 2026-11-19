package com.example.orderagent.agent.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System Prompt 构建器（带版本号）。
 *
 * <p>版本号不是形式主义：评测模块要对比"v2 的 prompt 和 v3 的 prompt 谁更准"，
 * 没有版本号就无法做 A/B 与回归定位。这是"可回归评测"最便宜的一次投资。
 *
 * <p>注意 prompt 里写的约束都是"软约束"，真正的硬约束在工具执行层（见 BaseReadOnlyTool），
 * 两者形成纵深防御。
 */
@Slf4j
@Component
public class PromptBuilder {

    /** 与 {@link PromptRepository#CURRENT} 保持一致。 */
    public static final String VERSION = PromptRepository.CURRENT;

    /** 根据版本号取 prompt，未知版本回落到当前版本。 */
    public String systemPrompt(String version) {
        if (version != null && !PromptRepository.VERSIONS.contains(version)) {
            log.warn("未知 prompt 版本 {}，使用 {}（下一次审查请补进 PromptRepository）", version, VERSION);
        }
        return PromptRepository.get(version);
    }

    public String systemPrompt() {
        return systemPrompt(VERSION);
    }

    public List<String> versions() {
        return PromptRepository.VERSIONS;
    }
}