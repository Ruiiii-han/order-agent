package com.example.orderagent.api;

import com.example.orderagent.common.ApiResult;
import com.example.orderagent.service.DemoDataSeeder;
import com.example.orderagent.service.FaultInjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 管理端：故障注入 + 演示数据查询。生产环境必须加权限控制（这里仅演示）。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final FaultInjectionService faultService;
    private final DemoDataSeeder seeder;

    /** 注入故障：target 可为工具名（queryLogistics）或订单号（A50001）。 */
    @PostMapping("/fault")
    public ApiResult<Map<String, String>> inject(@RequestParam String target,
                                                 @RequestParam String type) {
        faultService.set(target, FaultInjectionService.FaultType.valueOf(type.toUpperCase()));
        return ApiResult.ok(Map.of("target", target, "type", type.toUpperCase()));
    }

    @DeleteMapping("/fault")
    public ApiResult<String> clear() {
        faultService.clear();
        return ApiResult.ok("已清除全部故障注入");
    }

    @GetMapping("/fault")
    public ApiResult<Object> list() {
        return ApiResult.ok(Map.of(
                "current", faultService.current(),
                "history", faultService.history()
        ));
    }

    /** 列出某个场景的订单号，方便演示与评测取数。 */
    @GetMapping("/scenarios")
    public ApiResult<List<Map<String, Object>>> scenarios() {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (DemoDataSeeder.Scenario s : DemoDataSeeder.Scenario.values()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("scenario", s.name());
            m.put("label", s.label);
            m.put("orders", seeder.catalog().byScenario(s));
            m.put("expectedConclusion", s.expectedConclusion);
            list.add(m);
        }
        return ApiResult.ok(list);
    }

    @GetMapping("/scenarios/{scenario}")
    public ApiResult<List<String>> ordersOf(@PathVariable String scenario) {
        var s = DemoDataSeeder.Scenario.valueOf(scenario.toUpperCase());
        return ApiResult.ok(seeder.catalog().byScenario(s));
    }
}