package com.example.orderagent.api;

import com.example.orderagent.common.ApiResult;
import com.example.orderagent.llm.LlmProperties;
import com.example.orderagent.sandbox.SandboxExecutor;
import com.example.orderagent.sandbox.SandboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模型设置接口 —— 只用于<b>本地演示</b>。
 *
 * <p>安全说明（面试会问）：允许通过 HTTP 传 API Key 是危险操作，
 * 这里做了三件事：① 默认只绑定 127.0.0.1 访问；② 不把 Key 回显给前端；
 * ③ 由 {@code order-agent.sandbox.allow-runtime-switch} 控制开关，生产环境必须关闭。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SandboxExecutor sandbox;
    private final LlmProperties llmProperties;
    private final SandboxProperties sandboxProperties;

    /** 当前模型状态（不含 Key）。 */
    @GetMapping("/llm")
    public ApiResult<Map<String, Object>> status() {
        return ApiResult.ok(sandbox.status());
    }

    /** 切换被测模型。 */
    @PostMapping("/llm")
    public ApiResult<Map<String, Object>> switchModel(@RequestBody SwitchRequest req) {
        if (!sandboxProperties.isAllowRuntimeSwitch()) {
            return ApiResult.fail(403, "运行时切换模型已关闭");
        }
        String provider = req.getProvider() == null ? llmProperties.getProvider() : req.getProvider();
        String baseUrl = req.getBaseUrl() == null ? llmProperties.getBaseUrl() : req.getBaseUrl();
        String apiKey = req.getApiKey() == null ? llmProperties.getApiKey() : req.getApiKey();
        String model = req.getModel() == null ? llmProperties.getModel() : req.getModel();
        return ApiResult.ok(sandbox.switchSubject(provider, baseUrl, apiKey, model));
    }

    @lombok.Data
    public static class SwitchRequest {
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String model;
    }
}