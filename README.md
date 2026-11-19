# order-agent · 订单异常排查智能体

> 一个面向电商客服的 **Java + LLM Agent** 项目：输入订单号和一句自然语言问题，
> 自动并发排查订单 / 支付 / 库存 / 物流 / 风控五条链路，输出**结构化根因结论 + 证据链 + 处理建议**，
> 并附带一条**可回归的评测流水线**来量化它到底准不准。

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-orange?logo=openjdk">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.3-green?logo=springboot">
  <img alt="Tests" src="https://img.shields.io/badge/tests-10%20passed-brightgreen">
  <img alt="Accuracy" src="https://img.shields.io/badge/accuracy-99.54%25-blue">
</p>

---

## 它解决什么问题

客服遇到"订单为什么还不发货"，传统做法是打开 4~5 个后台系统逐个查，平均耗时约 8 分钟，且依赖经验。
本项目让 Agent 自动编排这些查询：

```
用户: "订单 A10001 为什么一直没发货？"
  ↓
Agent: 并发调用 [queryOrder, queryPayment, queryStock, queryLogistics, queryRisk]
  ↓
结论: STOCK_OUT（缺货）
证据: "SKU 500900 在仓库 WH-SH-01 可用库存为 0；支付已成功；风控 LOW"
建议: "联系仓库确认补货周期；可引导用户改拍同款或由客服发起退款"
耗时: 6~8 秒
```

---

## 核心指标

| 指标 | 数值 |
|---|---|
| 结论准确率 | **99.54%**（430/432，DeepSeek-V4.1-Flash） |
| 评测集 | 432 条 / 16 个场景 |
| 单次诊断延迟 | 平均 6~8 秒 |
| 单次 token | 约 3.8K（工具摘要抽取后） |
| 场景覆盖 | 缺货、支付异常、风控拦截、物流异常、多商品部分缺货、已签收仍投诉、信息矛盾等 |
| 单测 & 集成测试 | 10 / 10 通过 |

---

## 快速开始

### 环境要求

- **JDK 21**（Spring Boot 3 需要 17+，本项目用 21 以支持虚拟线程）
- Maven 3.8+
- 无需 Docker / MySQL / Redis（默认用 H2 内存库）

### 构建与运行

```bash
# 1. 构建（含测试）
mvn clean package

# 2. 启动（默认 mock 模型，零依赖、零成本）
java -jar target/order-agent-1.0.0.jar

# 3. 健康检查
curl http://localhost:8080/actuator/health
```

### 接入真实大模型（可选）

```bash
# 复制密钥模板并填入你的 Key
cp config/local-secrets.example.yml config/local-secrets.yml

# 启动时加载
java -jar target/order-agent-1.0.0.jar \
  --spring.config.additional-location=optional:file:./config/local-secrets.yml

# 确认模型已生效（不返回 Key）
curl http://localhost:8080/api/settings/llm
```

> 支持任何 **OpenAI 兼容协议**的网关：硅基流动、DeepSeek 官方、阿里通义、各类中转服务。

---

## 三条核心命令

```bash
# ① 诊断（阻塞版）
curl -X POST http://localhost:8080/api/agent/diagnose \
  -H "Content-Type: application/json" \
  -d '{"orderNo":"A10001","question":"为什么一直没发货","userId":10001}'

# ② 诊断（SSE 流式版，前端可展示"正在查库存…"）
curl -N -X POST http://localhost:8080/api/agent/diagnose/stream \
  -H "Content-Type: application/json" \
  -d '{"orderNo":"A10001","question":"为什么一直没发货","userId":10001}'

# ③ 跑一次全量评测（432 条）
curl -X POST http://localhost:8080/api/eval/run \
  -H "Content-Type: application/json" \
  -d '{"datasetVersion":"v2"}'
```

### 演示降级能力（面试最有说服力的一段）

```bash
# 让物流服务"挂掉"
curl -X POST "http://localhost:8080/api/admin/fault?target=queryLogistics&type=ERROR"

# 再查一次：结论仍然正确，但 degraded=true、置信度被强制压到 0.6
curl -X POST http://localhost:8080/api/agent/diagnose \
  -H "Content-Type: application/json" \
  -d '{"orderNo":"A10001","question":"为什么一直没发货","userId":10001}'

# 清除故障
curl -X DELETE http://localhost:8080/api/admin/fault
```

---

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│                        API 层（REST + SSE）                    │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                      AgentLoop（自研执行循环）                  │
│  ① 组装 messages  ② 调模型  ③ 解析 tool_calls                  │
│  ④ 白名单校验   ⑤ 并发执行   ⑥ 结果回填  ⑦ 步数收敛            │
└──────┬──────────────────────┬───────────────────────┬─────────┘
       │                      │                       │
┌──────▼──────┐    ┌──────────▼─────────┐   ┌─────────▼────────┐
│ ToolRegistry│    │ ToolExecutionStrgy │   │  PromptRepository │
│  （白名单）  │    │ 自研 / Resilience4j│   │   （版本管理）     │
└──────┬──────┘    └──────────┬─────────┘   └──────────────────┘
       │                      │
┌──────▼──────────────────────▼─────────────────────────────────┐
│              BaseReadOnlyTool（基类，强制安全校验）              │
│  DataPermissionGuard：按会话 userId 反查订单归属 → 越权即终止   │
└──────┬───────────────────────────────────────────────────────┘
       │
┌──────▼───────────────────────────────────────────────────────┐
│  queryOrder · queryPayment · queryStock · queryLogistics      │
│  queryRisk  （5 个只读工具 + 1 个写操作工具，写操作需人工确认）    │
└──────────────────────────────────────────────────────────────┘
```

### 包结构

```
com.example.orderagent
├── agent/core      Agent 执行循环、工具注册表、Prompt 版本仓库
├── agent/exec      工具并发执行（自研 CompletableFuture / Resilience4j 双实现）
├── agent/guard     越权校验、参数白名单、工具基类
├── agent/tools     5 个只读工具 + 1 个写操作示例
├── llm             模型客户端（OpenAI 兼容 / Mock 基线）
├── sandbox         被测与裁判模型角色分离
├── diagnose        结构化结论解析（防幻觉最后一道闸）
├── eval            评测集、批量执行、三层判分、多数表决
└── service         演示数据（16 场景）、故障注入
```

---

## 关键设计取舍

### 1. 为什么自研 Agent 循环，不用 LangChain4j / Spring AI？

并发、超时、越权、步数收敛这些分支都需要**精细控制并可观测**，框架的默认行为反而不好干预。
自研约 200 行，每一行都能讲清楚；也更清楚 Function Calling 的本质是一个状态机。

### 2. 安全边界为什么做在工具执行层？

因为 **prompt 是不可信输入的一部分**。用户完全可以注入"忽略以上指令"。
所以真正的防线在执行层：

```java
// BaseReadOnlyTool 强制校验，子类无法跳过
Optional<Map<String, Object>> order = guard.loadAuthorizedOrder(orderNo, ctx);
if (order.isEmpty()) {
    return ToolResult.fail(name(), "NOT_FOUND", "订单不存在", 0);
}
// DataPermissionGuard 内部：按会话 userId 校验归属，不一致抛 SecurityException
```

越权直接终止整个会话，且**统一返回 403 不泄露订单是否存在**（防枚举）。

### 3. 两层超时：外层给结果，内层真正中断

踩过的一个真实坑：

```java
// 外层 orTimeout 只让 future 完成，底层任务还在跑
.supplyAsync(() -> executor.execute(...))
.orTimeout(timeout + 200, MILLISECONDS)

// 内层 Future.get(timeout) 才真正中断工作线程
ToolResult r = future.get(toolTimeoutMs + 100, MILLISECONDS);
```

两层叠加：`orTimeout` 负责"给调用方一个结果"，`future.get` 负责"真正停掉任务"。

### 4. 工具不仅要报问题，还要报"没问题"

这是扩大评测集时踩的坑：已退款订单每个工具只给中性描述，模型拿不到任何正向证据，
只能保守地判 `NEED_MANUAL`。

解法：新增 `[signal:normal]` 正向信号——**沉默不等于健康，模型需要正面证据才能收敛**。

---

## 评测体系

### 数据集

| 版本 | 场景数 | 用例数 | 说明 |
|---|---|---|---|
| `v1` | 8 | 200 | 基础场景：单一根因、干净数据 |
| **`v2`** | **16** | **432** | 基础 + 8 个难例：多商品、部分发货、已签收投诉、已退款、信息矛盾等 |

难例场景示例：

| 场景 | 数据设计 | 期望 | 考察 |
|---|---|---|---|
| 多商品部分缺货 | A 有货、B 缺货 | STOCK_OUT | 不能因"部分有货"判正常 |
| 已签收仍投诉 | 物流已签收，用户说没收到 | NORMAL_IN_TRANSIT | **不被用户话术带偏** |
| 信息矛盾 | 订单显示未支付，但有成功流水 | NEED_MANUAL | 矛盾必须转人工 |
| 风控+库存异常 | 两个信号并存 | RISK_BLOCKED | 优先级判断 |

### 三层判分

```
总分 = 0.6 × 确定性分 + 0.4 × LLM 裁判分
确定性分 = 结论枚举匹配(0.5) + 证据信号命中(0.3) + 关键词命中(0.2)
及格 = 结论匹配 且 总分 ≥ 0.6
```

**主指标只看确定性分**（结论枚举精确匹配），不依赖 LLM 裁判的主观判断。

### 多数表决（抗随机抖动）

```bash
# 同一问题跑 3 次取众数，消除 1.4% 的随机抖动
curl -X POST http://localhost:8080/api/eval/run \
  -H "Content-Type: application/json" \
  -d '{"datasetVersion":"v2","voteRounds":3}'
```

只在离线评测启用，**线上实时诊断默认不启用**（成本 ×3）。

---

## 迭代记录（11 个真实缺陷）

开发过程中通过评测失败归因定位并修复的问题：

| # | 缺陷 | 性质 |
|---|---|---|
| 1 | 场景间 SKU 串号，缺货单查到正库存 | 测试数据 bug |
| 2 | 物流"无记录"一律报 data_missing | 工具语义错误 |
| 3 | 429 TPM 限流未重试 | 工程缺失 |
| 4 | 小模型只调 1 个工具就下结论 | 编排层缺失 |
| 5 | 强制取证补齐逻辑重复触发 | 自己引入的回归 |
| 6 | 订单 `CLOSED` 就报 risk_blocked | 已退款单误判风控 |
| 7 | 退款单被报 pay_timeout | 缺少 REFUNDED 分支 |
| 8 | **无库存记录 = 缺货** | 数据缺失误判缺货 |
| 9 | **缺少正向 normal 信号** | 正常单被判转人工 |
| 10 | **信号冲突** | 支付失败+物流无记录 → 模型摇摆 |
| 11 | **熔断器误杀** | 工具超时 1.5s 太短，累积触发熔断 |

完整定位过程见 [`docs/真实模型接入与优化记录.md`](docs/真实模型接入与优化记录.md)。

### 最有价值的一条

> **准确率突然暴跌时，先区分"模型判错"和"根本没跑成功"。**
>
> 我有两次准确率从 83% 掉到 53%、从 99% 掉到 19%，第一反应都是"代码改坏了"。
> 但看明细发现失败用例的结论列是 `NULL`——它们根本没跑成功。
> 一次是 TPM 限流 429，一次是账户余额不足 402。
> 所以评测明细里专门保留 `NULL` 标记，就是为了让这两类失败一眼可分。

---

## 成本控制

评测是 AI 项目最容易失控的地方，实测数据：

| 配置 | 单轮 432 条 | 三票表决 |
|---|---|---|
| DeepSeek-V4.1-Flash | ~203 万 token（**≈2.2 元**） | ~612 万 token（**≈6.6 元**） |
| mock 模型 | **0 元** | 0 元 |

三条实践：
1. **日常调试用 mock** —— 确定性策略模型，0 成本验证编排逻辑与评测流水线
2. **先抽样再全量** —— `{"limit":50}` 先跑 50 条（约 0.25 元）
3. **投票按需开** —— `voteRounds:3` 只用于最终定稿

---

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/diagnose` | 阻塞版诊断 |
| POST | `/api/agent/diagnose/stream` | SSE 流式诊断 |
| GET | `/api/agent/sessions/{id}` | 会话详情（含工具调用链） |
| POST | `/api/eval/run` | 发起评测 |
| GET | `/api/eval/trend` | 准确率趋势 |
| GET | `/api/eval/runs/{runNo}` | 单次评测明细（按期望结论聚合失败） |
| POST | `/api/admin/fault` | 故障注入（演示降级用） |
| GET | `/api/settings/llm` | 当前模型状态（不含 Key） |
| GET | `/actuator/prometheus` | Prometheus 指标 |

---

## 已知边界

1. **H2 换 MySQL**：改 `datasource.url` 即可，`schema.sql` 已是 MySQL 兼容写法
2. **会话记忆在进程内**：多实例部署前需迁到 Redis
3. **写操作只做建议**：`RefundTool` 永远返回 `REQUIRES_HUMAN_CONFIRM`，Agent 无法直接执行
4. **`/api/settings/llm` 仅用于本地演示**：允许 HTTP 传 API Key 是危险操作，生产必须设置
   `order-agent.sandbox.allow-runtime-switch=false`
5. **评测集为构造数据**：8 类基础 + 8 类难例，真实线上 badcase 回流机制尚未实现

---

## 文档

| 文档 | 内容 |
|---|---|
| README.md（本文） | 启动、命令、架构、设计取舍 |
| [docs/真实模型接入与优化记录.md](docs/真实模型接入与优化记录.md) | **4 轮评测对比 + 11 个缺陷的定位过程** |
| [docs/14天冲刺计划与面试稿.md](docs/14天冲刺计划与面试稿.md) | 逐日计划 + 高频面试题口述答案 |

---

## License

MIT