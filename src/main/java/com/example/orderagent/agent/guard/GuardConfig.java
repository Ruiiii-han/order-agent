package com.example.orderagent.agent.guard;

import com.example.orderagent.service.OrderQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 守卫层装配。单独成类，保证越权校验是"显式依赖"而不是随手 new 出来的。 */
@Configuration
public class GuardConfig {

    @Bean
    public DataPermissionGuard dataPermissionGuard(OrderQueryService orderQueryService) {
        return new DataPermissionGuard(orderQueryService);
    }
}