# WolfMind 可观测性

本文说明**看什么、怎么读、什么时候该动**。`AGENTS.md` §11 的 Phase 5 要求"可观测性"与
"模型成本可控"，两者共用同一条数据通路：`GET /api/v1/metrics`。

---

## 1. 为什么不引入指标框架

第一版只需要回答四个运维问题：

1. **对局在推进吗** —— `wolfmind.agent.turns`、`wolfmind.events.appended`
2. **事件在写吗** —— `wolfmind.events.appended`、`wolfmind.games.finished`
3. **模型花了多少钱** —— `wolfmind.llm.*` 与 `llmCost`
4. **有没有在降级** —— `wolfmind.agent.turns.fallback`、`wolfmind.llm.budget.rejections`

为此引入 Micrometer/Prometheus 会让"把三个数写清楚"变成"配一套采集链路"。
需要接 Prometheus 时，`/api/v1/metrics` 的 `counters` 与 `gauges` 是扁平的一层键值，
抓取器可以直接读——**不接任何框架不等于不能被采集**。

**无标签（no tags）**：按 `gameId` 打标签会让长跑实例的指标基数随对局数无界增长。
"哪一局慢"应该去查事件流与决策轨迹，不是查指标。

Redis 模式的归档指标（2026-10-14）：每次归档扫描更新
`wolfmind.archive.pending.games`（待归档或保留期更新未完成的终局数）和
`wolfmind.archive.failed.games`（本轮失败数），失败尝试累计为 `wolfmind.archive.failures`。
指标由 `/api/v1/metrics` 的 gauges/counters 返回，目录在 `/api/v1/metrics/names`。
未归档事件不自动过期，积压持续增加时应排查 SQLite 写入/核对或 Redis 保留期更新失败，
同时观察 Redis 内存。关闭归档调度时这些仪表不更新，不把缺失指标解释为积压为零。

四组接线后，新增三组在 `/api/v1/metrics` 暴露
`wolfmind.consumer.{cg-projection|cg-broadcast|cg-insight}.{pending|blocked|failures|read.failures}`。
pending 表示已投递未确认条数（统计组另含补算收据数），不等于全部未读取事件积压；blocked 是当前被坏事件阻塞的单局数。
暂时数据库/连接失败保留消息重试，不能按坏事件自动跳过；坏事件三次后隔离并阻塞该组该局。
`wolfmind.consumer.cg-insight.repair.failures` 是 SQLite 缺漏核对/补算通知失败次数。
目录与位点、租约、blocked/failures 各组独立，某组终局退出不删除其他组待办。

---

## 2. 读法

```bash
curl -fsS http://localhost:8080/api/v1/metrics | jq .
curl -fsS http://localhost:8080/api/v1/metrics/names | jq .
```

```json
{
  "since": 1791815096937,
  "uptimeMillis": 1514,
  "counters": {
    "wolfmind.events.appended": 1240,
    "wolfmind.games.finished": 8,
    "wolfmind.agent.turns": 214,
    "wolfmind.agent.turns.fallback": 6
  },
  "gauges": {},
  "timers": {
    "wolfmind.llm.latency": { "count": 96, "totalMs": 402113, "maxMs": 33012, "averageMs": 4188 }
  },
  "llmBudget": { "maxTokensPerGame": null, "trackedGames": 3, "blockedGames": 0 },
  "llmCost": {
    "calls": 96, "promptTokens": 812340, "completionTokens": 220118,
    "totalTokens": 1032458, "averageTokensPerCall": 10755,
    "failures": 2, "budgetRejections": 0
  }
}
```

### 2.1 指标目录

| 指标 | 类型 | 含义 | 什么时候该在意 |
| --- | --- | --- | --- |
| `wolfmind.events.appended` | counter | 已追加的领域事件总数 | 长时间不增长 = 没有对局在推进 |
| `wolfmind.games.finished` | counter | 进入 `GAME_FINISHED` 的对局数 | 与 `agent.turns` 一起看，判断"对局能不能打完" |
| `wolfmind.games.aborted` | counter | 进入 `GAME_ABORTED` 的对局数 | **持续增长说明有对局被中止**（人数不足或连续多轮无人出局） |
| `wolfmind.agent.turns` | counter | 已完成的 AI 回合数 | 对局推进的粒度指标 |
| `wolfmind.agent.turns.fallback` | counter | 其中由规则兜底完成的部分 | 占比突然升高 = 模型变慢/不可用/超预算 |
| `wolfmind.llm.calls` | counter | 模型调用次数 | 成本的第一分量 |
| `wolfmind.llm.failures` | counter | 返回失败的调用 | 持续非零 = 供应商侧或参数问题 |
| `wolfmind.llm.prompt.tokens` / `.completion.tokens` | counter | token 累计 | 单价 × token = 成本；见 §3 |
| `wolfmind.llm.latency` | timer | 调用耗时（count/total/max/average） | `max` 接近回合时限 = 该角色上下文需要压缩或换更快的模型 |
| `wolfmind.llm.budget.rejections` | counter | 因单局 token 上限被拒的调用 | 非零说明上限生效；若同时 `turns.fallback` 升高，是"省钱生效了" |
| `wolfmind.llm.budget.blocked.seats` | gauge | 当前被预算挡住的座位数 | 用于区分"模型慢"与"预算收紧" |

### 2.2 三个组合读法

| 现象 | 组合 | 结论 |
| --- | --- | --- |
| 对局不动 | `events.appended` 平 + `agent.turns` 平 | 驱动没在跑，或没有 RUNNING 对局（看日志的"扫描未找到运行中的对局"） |
| 对局打不完 | `games.finished` 平 + `games.aborted` 涨 | 触发了 `stalledRoundsLimit`：连续多轮无人出局，规则判定中止。这是**被设计的行为**，但意味着 AI 打得不好 |
| 花了很多钱但没打好 | `llm.calls` 高 + `llm.failures` 高 + `turns.fallback` 高 | 模型输出大量被校验/守卫拒绝，白烧 token。查决策轨迹的 `AGENT_OUTPUT_REJECTED` |

---

## 3. 模型成本控制

### 3.1 三层防线

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 1. 单回合时限 | `GameRules.agentTurnDeadlineMs`（默认 60 s，含最多 25 s 发言阅读停顿） | 挡住"一个回合永远不结束" |
| 2. 软截止与提交窗口 | `BudgetGuard`（`agent-runtime.md` §13） | **不起**注定赶不上的模型调用，省掉整次调用 |
| 3. 单局 token 上限 | `LlmCostGuard`（`wolfmind.llm.max-tokens-per-game`） | 超过后该座位转规则兜底，对局仍正常结束 |

**为什么上限默认是 0（不限）**：一个没被验证过的默认上限会在真实对局里悄悄改变行为，
而那种变化看起来像"模型变笨了"，排查方向会完全跑偏。要省钱必须显式配置，
并且能在指标上看到它生效。

**为什么超限是降级而不是报错**：抛异常会让对局卡在一个没有后续动作的回合上。
规则基线产出的是合法动作，因此"不再调用模型"只是让 AI 退回规则——多花一次钱比卡住整局轻。

### 3.2 不换算金额

终局最后一个已接收回合返回后释放临时成本账本，因此 `llmBudget.trackedGames` / `blockedGames`
不继续计入已结束对局；真人接管一个座位不会重置整局额度。历史成本仍来自 SQLite 决策轨迹和全局累计指标。

轨迹缓存相关指标（均无 gameId 标签）：

| 名称 | 含义 |
| --- | --- |
| `wolfmind.agent.trace.live-events` | 当前内存轨迹总条数，含活动与待保留终局 |
| `wolfmind.agent.trace.finished-games` | 内存中保留的终局数量 |
| `wolfmind.agent.trace.evicted-games` | 因数量或时长上限淘汰的终局累计数 |
| `wolfmind.agent.trace.archive.pending-games` | 待补存或待发送统计补算通知的终局数，纯内存模式为 0 |
| `wolfmind.agent.trace.archive.failures` | 决策轨迹归档/补算通知失败次数 |
| `wolfmind.agent.trace.archive.dropped-games` | Redis 模式下超保留上限淘汰的待补存终局累计数 |
| `wolfmind.agent.turn.unregistered` | 拒绝的未注册/已释放座位回合数 |

`archive.dropped-games` 增长时检查 SQLite/Redis 可用性及缓存保留配置；可能有部分分析摘要未保存，
不能把缺少轨迹的模型用量声称为已测量的零费用。对局事实流与已归档记录未被此缓存淘汰删除。

`llmCost` 只给 token 数，**不给金额**。价格随供应商、模型、缓存与批量折扣变化，
把它写死在代码里，一个季度后就会显示一个错误金额——而错误金额会被当成事实引用。
换算交给运维侧的报价单：

```
成本 ≈ promptTokens × prompt单价 + completionTokens × completion单价
```

### 3.3 实验室的成本

实验室批次的成本可以直接从 `agent_memory` 的决策痕迹重算，`GET /lab/comparisons` 的每一行
都带 `llmCalls / tokens / averageLlmLatencyMs / fallbackTurns`。
**样本量小于 20 局时差异不具统计意义**——这条写在接口的 `provenance` 里，
因为它容易被忘记，而忘了它就会把噪声当成结论。

---

## 4. 日志

- 编码固定 UTF-8（`application.properties` 的 `logging.charset.*`）。Windows 下 JVM 默认 GBK，
  IDEA 控制台按 UTF-8 解码，不显式设置会让中文日志变乱码。
- 每个 REST 响应都带 `X-Trace-Id`；错误体里有 `traceId`，它就是事件的 `correlationId`，
  可以直接拿去对事件流。
- **日志不含密钥**：`LlmSettings.describeForLog()` 只报告 `(set)`。
- 旧 `GameDriver` 的行动提交日志已随实现删除。应用层调度日志用于事件/任务失败定位，
  不输出完整 prompt；行动结构摘要由 AgentWorkflow 的 Trace 记录，私有上下文不作公开日志载荷。

---

## 5. 建议的告警（按优先级）

| 优先级 | 条件 | 含义 |
| --- | --- | --- |
| P0 | `/api/v1/health` 返回 HTTP 503 / `status == DOWN` | 必需依赖不可用（事件存储），健康探针可据状态码识别 |
| P0 | `games.aborted` 在 10 分钟内增长 | 有对局被中止，需要看 `GAME_ABORTED.reason` |
| P1 | `llm.failures / llm.calls > 0.2`（5 分钟窗口） | 供应商侧或参数问题 |
| P1 | `llm.latency.max > agentTurnDeadlineMs` | 有调用注定赶不上提交窗口，正在白烧 token |
| P2 | `agent.turns.fallback / agent.turns > 0.3` | 大量回合退回规则，AI 质量下降 |
| P2 | `llm.budget.rejections` 从 0 变正 | 成本上限生效；确认这是预期的收紧而不是误配 |

**不要**对 `wolfmind.events.appended` 设绝对阈值：它随对局数量线性变化，
而在没有对局时保持不动是正常状态。
