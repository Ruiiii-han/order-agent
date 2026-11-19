package com.example.orderagent.agent.guard;

import com.example.orderagent.agent.core.AgentContext;
import com.example.orderagent.agent.core.AgentTool;
import com.example.orderagent.service.OrderQueryService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 数据权限守卫 —— 越权拦截的<b>执行层</b>。
 *
 * <p>为什么不靠 prompt 写"只能查询当前用户的订单"？
 * 因为 prompt 是不可信输入的一部分，用户完全可以注入"忽略以上指令"。
 * 真正的防线在工具执行前：用会话里<b>服务端已知</b>的 userId 反查订单归属。
 *
 * <p>两种处理策略：
 * <ul>
 *   <li>订单不存在 → 返回 NOT_FOUND，让模型知道要换个订单号（可纠正的错）；</li>
 *   <li>订单存在但属于别人 → 返回 FORBIDDEN，<b>不暴露</b>订单是否存在（防信息泄露）。</li>
 * </ul>
 */
public class DataPermissionGuard {

    private static final Pattern ORDER_NO = Pattern.compile("^[A-Z]\\d{4,12}$");

    private final OrderQueryService query;

    public DataPermissionGuard(OrderQueryService query) {
        this.query = query;
    }

    /** 由具体工具实现的依赖注入使用。 */
    public OrderQueryService query() {
        return query;
    }

    /**
     * 校验并返回订单，失败时抛出 / 返回空的统一处理放在 {@link BaseReadOnlyTool}。
     *
     * @return 订单行；不存在或无权访问时返回 empty
     */
    public Optional<Map<String, Object>> loadAuthorizedOrder(String orderNo, AgentContext ctx) {
        if (orderNo == null || !ORDER_NO.matcher(orderNo).matches()) {
            throw new IllegalArgumentException("订单号格式非法: " + orderNo);
        }
        Optional<Map<String, Object>> row = query.findOrder(orderNo);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Object ownerId = row.get().get("user_id");
        long owner = ownerId == null ? -1 : Long.parseLong(String.valueOf(ownerId));
        if (owner != ctx.getUserId()) {
            // 注意：不区分"不存在"和"无权"，统一返回 FORBIDDEN，防止枚举探测
            throw new ForbiddenException("无权访问该订单");
        }
        return row;
    }

    /** 从参数里取字符串，缺省返回 null。 */
    public static String argStr(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 越权异常：Agent 必须立即终止，而不是把细节回填给模型。 */
    public static class ForbiddenException extends SecurityException {
        public ForbiddenException(String msg) {
            super(msg);
        }
    }

    /** 供工具基类校验入参。 */
    public static void requireSchema(AgentTool tool, Map<String, Object> args) {
        Map<String, Object> schema = tool.parameterSchema();
        Object required = schema.get("required");
        if (required instanceof List<?> requiredList) {
            for (Object key : requiredList) {
                String k = String.valueOf(key);
                if (args == null || args.get(k) == null || String.valueOf(args.get(k)).isBlank()) {
                    throw new IllegalArgumentException("缺少必填参数: " + k);
                }
            }
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> propMap && args != null) {
            for (String k : args.keySet()) {
                if (!propMap.containsKey(k)) {
                    throw new IllegalArgumentException("不支持参数: " + k);
                }
            }
        }
    }
}