# WolfMind 详细设计：Agent Runtime

本文细化 `DESIGN.md` §9（AI Agent 架构）与 §10（Agent Runtime）。核心约束是
`DESIGN.md` §12 与本文第 1 节：AI 不直接操作 Game Domain。

## 1. 定位与边界

`DESIGN.md` §9 的结构链在本设计中一一落地：

```
Player（领域实体，只有 controller 字段）
  │
  AIController（应用层适配器：回合派发 + 结果归一）
  │
  Agent Runtime（生命周期与执行编排）
  │
  Agent Instance（一次对局中的一个 AI 座位）
  │
  LangGraph4j（工作流执行）
  │
  LangChain4j（LLM 调用与工具绑定）
  │
  LLM
```

Agent Runtime 允许依赖：Agent 侧的模型（`AgentConfig`、`AgentContext`、`TurnRequest`、
`GameView`）、端口（`GameViewQueryPort`、`MemoryPort`、`ToolGatewayPort`、
`AgentTracePort`）、LangGraph4j 与 LangChain4j 的执行接口。

Agent Runtime 禁止依赖：

| 禁止项 | 原因 |
| --- | --- |
| `domain.game.model` 下的任何类型（`Game`、`Player`、`Action`、`Role`） | DEP-7，AI 与领域之间没有编译期依赖（C-5） |
| `GameRepository`、`EventStorePort`、Redis/SQLite 客户端 | 读写都经端口与工具网关，Runtime 不接触存储 |
| `SubmitActionApplicationService` 之外的写路径 | 工具网关是唯一写路径（[tool-gateway.md](tool-gateway.md)） |
| 完整事件流或上帝视图 | AI 只能看到自己角色可见的 `GameView`，否则等于作弊 |

`PlayerController` 的 `type` 只在领域里表达"这个座位由谁驱动"；
`AIController` 是应用层的调度适配器，不进入领域模型。

## 2. 组件

| 组件 | 职责 | 关键接口 |
| --- | --- | --- |
| `AgentRuntime` | 实例注册、回合执行入口、超时与降级 | `createAgent` / `destroyAgent` / `executeTurn` |
| `AgentRegistry` | `agentId + gameId + playerId` 到 `AgentInstance` 的映射，生命周期管理 | `register` / `unregister` / `get` |
| `AgentInstance` | 一个 AI 座位的执行上下文：配置、记忆句柄、工作流实例 | `execute(TurnRequest)` |
| `AIControllerAdapter` | 接收窗口任务的 `TurnSignal`，构造 `TurnRequest`，返回工具提交结果 | `dispatch(signal)` |
| `ContextAssembler` | 装配 `AgentContext`（角色可见视图 + 记忆 + 规则摘要） | `assemble(TurnRequest)` |
| `MemoryManager` | 短期与长期记忆的读写，经 `MemoryPort` | `loadShortTerm` / `saveLongTerm` |
| `WorkflowEngine` | LangGraph4j 工作流的加载与执行 | `run(graphId, state)` |
| `LlmClient` | LangChain4j 封装的模型调用（含工具声明） | `chat(request)` |
| `ToolGatewayClient` | 工具调用的唯一出口，含权限结果处理 | `invoke(tool, args)` |
| `BudgetGuard` | 回合预算：LLM 调用次数、工具调用次数、token、时长 | `tryConsume(budget)` |
| `TraceRecorder` | 写 `AGENT_` 决策事件 | `record(traceEvent)` |
| `FallbackPlanner` | LLM 不可用或超时时的规则兜底 | `plan(context)` |

## 3. 生命周期

对应 `DESIGN.md` §10 的 `createAgent()` / `destroyAgent()` / `executeTurn()`。

```java
public interface AgentRuntime {
    AgentHandle createAgent(CreateAgentCommand cmd);   // 开局：每个 AI 座位一个实例
    void        destroyAgent(AgentHandle handle);      // 对局结束、座位被真人接管
    TurnResult  executeTurn(TurnRequest request);      // 领取有效窗口任务后调用
}
```

| 时机 | 调用 | 说明 |
| --- | --- | --- |
| `GAME_STARTED` 后，座位 `controller.type == AI` | `createAgent` | 按 `role` 绑定策略集，加载 `AgentConfig` |
| 回合/阶段事件规划的有效 AI 任务 | `executeTurn` | 一窗口一次，重复消费不增加付费调用 |
| `GAME_FINISHED` / `GAME_ABORTED` | `destroyGame` | 关闭新回合入口，排空已接收回合后收尾轨迹并释放成本账本 |
| `PLAYER_CONTROLLER_CHANGED`（AI 被真人接管） | `destroyAgent` | 释放实例，座位转由人工驱动 |
| `PLAYER_CONTROLLER_CHANGED`（真人掉线、AI 补位） | `createAgent` + `executeTurn` | 支持 `DESIGN.md` §15 Phase 4 的 AI 补位 |

`createAgent` 是幂等的：同一 `(gameId, playerId)` 重复创建返回既有实例，
避免事件重放导致实例重复。

### 3.1 已实现的终局收尾（B5，2026-10-14）

`AgentRuntimeImpl` 按局登记已接收、尚未返回的回合数量。终局到达时，立即销毁/注销座位实例，
拒绝新回合；只在该局最后一个已接收回合返回后调用 `AgentTracePort.finishGame` 和
`LlmCostGuard.forget`。清理不等待网络调用、不占用对局事务，重复终局通知不重复收尾。
正在执行的调用沿用既有模型超时、回合预算及应用层过期命令/任务围栏校验，不切换引擎重跑。
真人接管只销毁该座位实例，保留本局累计费用及分析轨迹，其余座位继续运行。

拒绝的未注册/晚到回合返回失败结果并增加无标签指标，不向已经释放的每局缓存写新轨迹。
近期终局注册防护只保留最多 1000 个局 ID；派发适配器同时校验当前视图仍 RUNNING 且未被真人接管，
历史终局/旧 AI 信号不能通过惰性创建重新启动 Agent。

纯内存模式的终局分析缓存默认保留最近 20 局、最长 1 小时，每座位最多 2000 条轨迹；
在读写/终局及每 30 秒维护时执行淘汰，重复终局通知不会延长保留时间。活动对局不受终局缓存淘汰影响。
配置项为 `wolfmind.agent.trace.finished-max-games`（正整数）和
`wolfmind.agent.trace.finished-retention-ms`（正毫秒数）。

Redis 模式继续逐条双写 SQLite；终局校对当前内存轨迹与已有归档，幂等补存缺漏并请求统计补算，
成功后立即释放内存副本，分析读路径回落到 SQLite。比较前统一摘要截断与 JSON 数值形态，
不因原归档长字符串或 Long/Integer 差异重复写入同一条成本轨迹。SQLite/补算通知暂时失败时，
保留终局缓存，每 30 秒重试；成功行不重复累计。该缓存沿用数量/时长/每座位条数上限，
长期故障超过上限可能丢失尚未归档的分析摘要，记录 WARN 与 dropped-games 指标。
它是有界的尽力补存，不是持久消息队列；进程退出会丢失尚未补存的缓存。
已归档分析、对局事件、回放和未归档对局 PERSIST 策略不受该清理影响。

本项不实现短期记忆或策略经验的生成/保存；原表中的“写长期记忆后释放”不能视为已实现的调用路径。

## 4. 回合驱动

回合的发放在领域，AI 不自行开始回合：

```
领域 Game
  │ PHASE_CHANGED(DAY_DISCUSS) + TURN_GRANTED{playerId, phase, turnIndex, deadline}
  ▼
Redis Stream game:{id}:events
  │ 消费组 cg-agent（任务可靠登记后 ACK）
  ▼
TurnPlanner → TurnTaskStore → 有界执行器
  ▼
AIControllerAdapter
  │ 1. 读座位 controller.type：HUMAN → 忽略（等真人提交）
  │ 2. AI → 构造 TurnRequest
  ▼
AgentRuntime.executeTurn(request)
  │
  ├─ ContextAssembler.assemble（含 GameViewQueryPort 读取角色可见视图）
  ├─ WorkflowEngine.run（LangGraph4j）
  │     └─ 内部经 ToolGatewayClient 调用工具
  ├─ TraceRecorder.record（AGENT_ 决策事件）
  └─ 返回 TurnResult
  ▼
工具网关已完成写路径（SubmitVoteTool / SpeakTool 等）
  ▼
领域产生 PLAYER_SPOKE / PLAYER_VOTED / …
```

回合资格来自领域已提交的窗口事件：

1. AI 无法通过任何工具改变"轮到谁"，顺序由领域决定。
2. 发言/遗言/猎人按 `TURN_GRANTED` 派发。夜间、投票按 `PHASE_CHANGED` 开放资格；
   狼人提名保持现有顺序，`DAY_VOTE` 的待投票 AI 同时派发（见 §4.1）。
3. AI 卡死不会让对局停下：`deadline` 到期由超时看护提交 `SkipAction`。

### 4.1 事件调度与稳定任务身份（2026-10-13 所有者批准）

默认 `wolfmind.game.driver.mode=EVENT_DRIVEN`：`EventDrivenTurnScheduler` 消费已提交事件，
`TurnPlanner` 核对权威聚合并登记任务，独立有限线程池调用 Agent。正常回合不查询 RUNNING 对局列表。
消费线程不等待模型；狼提名完成唤醒下一位，投票并发，控制者变更取消旧任务并登记接管任务。
瞬时历史阶段不执行；终局排空消费后清理任务、定时器和 Agent。
旧 interfaces.rest.GameDriver、其 Bean 和专用投票线程池已移除；接口层不再提供回合驱动实现。
EVENT_DRIVEN 与 POLLING 均复用应用层调度器、规划器和超时用例。
归档/回放等同步测试使用仅位于 src/test 的 ControlledTurnDriver，复用规划器和超时用例，
不复制规则或阶段判断；异步派发、租约和并发由真实 EventDrivenTurnScheduler 测试覆盖。

任务键为 `(gameId, windowId, kind, playerId, controllerGeneration)`。
`windowId` 是当前 `TURN_GRANTED` 或进入阶段 `PHASE_CHANGED` 的事件版本；控制者代次来自
最近一次该座位 `PLAYER_CONTROLLER_CHANGED` 版本。它们是内部身份，不是公开 phaseVersion。
首次登记的 `dispatchVersion` 保持不变，贯通 TurnSignal / TurnRequest / ToolSession / TurnKey；
并发投票与夜聊不能重置本座位预算。服务端写幂等键为 `task:{taskId}`，不同写工具共享该键。

`DAY_VOTE` 的全部待投票 AI 经全局有限线程池（`wolfmind.agent.max-concurrent-turns`，默认 8）
并发执行；真人未投不阻塞 AI。模型和维护执行器分别使用有限队列（driver.queue-size 默认 64）。
队列满时任务保留 READY；独立维护池仍处理截止。未提交者与真人一样到期记弃权。
各座位仍经工具网关独立提交，同一对局的 AI 提交在网关写路径上按对局排队（见 tool-gateway.md §6）。

任务状态为 READY / RUNNING / SUCCEEDED / WAITING_DEADLINE / EXPIRED / CANCELLED。
领取任务持久化 attempts、owner、token 和 leaseUntil；模型/工具调用前持久化预算尝试。
任务凭证只由服务端贯通，提交在对局写锁内再次核对当前窗口和租约；预览也受租约限制。
旧控制者、旧窗口、旧租约或截止后的结果不能提交。已成功行动以提交事件为准。
租约过期的 ACTION 转 WAITING_DEADLINE，不自动重跑付费模型；维护任务可重新领取。
截止任务调用 TimeoutApplicationService → Game.timeoutSkips → 逐条提交、发布，竞争时重新加载。

阶段推进与超时处理统一由 TimeoutApplicationService 执行：ADVANCE 任务调用 Game.advance，
TIMEOUT 任务核对截止时间后提交领域返回的 SkipAction。两者在对局锁内检查任务所属窗口，
提交成功后才发布；无事件不提交，旧窗口任务不再执行。
旧 AdvancePhaseApplicationService 已在所有者批准的 A3 清理中删除，其测试迁到正式用例。
当前没有人工强制推进接口，本次清理不新增管理端能力。

工作流整局等价验证见 WorkflowEngineFullMatchParityTest：同一 gameId/角色种子、逻辑时钟、
固定派发顺序及模型替身，分别使用 SEQUENTIAL / LANGGRAPH4J，逐条比较完整 EventEnvelope 和终态，
不删除或归一化时间戳、事件 ID、版本、可见性或 payload。覆盖规则基线、原生 A、原生 B、
非法提案后修复四类，各三个种子。此测试验证工作流调度等价，不比较异步投票执行的先后顺序，
也不等同于真实模型胜率实验。修复成功后 rawLlmOutput 同步更新为新提案，
避免图引擎返回 validate 时再次检查旧稿并额外触发表达重写。

Redis cg-agent 从 0-0 建组，先读 PEL 再读新消息；任务登记成功后单独 XACK。
登记失败不 ACK；登记成功而 ACK 失败由稳定 taskId 收敛。单实例固定消费者名支持重启读回 PEL，
其他消费者遗留条目闲置 60s 后接管。坏事件三次失败记录 DLQ 和 blocked 状态，不 ACK 后遗忘；
其他对局继续消费。阻塞状态需检查修复原流并清除 blocked 后恢复。
流目录由事件首次追加同一 Lua 登记；本地追加通知唤醒注册，5s 目录校对补偿通知丢失。
启动迁移旧流用 GameIdCatalog 一次补建目录。到期/就绪/运行租约索引每 500ms 补偿，
该检查不扫描游戏列表、不重新发放窗口；常规阅读停顿和截止仍登记一次性定时唤醒。

显式 POLLING 回退复用相同规划器和持久化任务，每 200ms 分页扫描 RUNNING 对局；
启动只激活一种触发方式。切换要求重启，`driver.enabled=false` 关闭两种调度。
首版支持单实例、多对局；memory 适配器不承诺跨进程保存。Redis 键定义见 redis-schema.md §16。

超时看护读取加载版本以内已提交的截止时间：发言、遗言、猎人使用
`TURN_GRANTED.deadline`；投票与夜间阶段使用最新 `PHASE_CHANGED.deadline`。
夜间提名、查验、用药的阶段窗口取该局 `rules.turnDeadlineMs`，投票取
`rules.voteDeadlineMs`。旧事件没有夜间 deadline 时，以事件 `occurredAt`
加该局保存的规则时长恢复窗口，不在每次调度时重新计时。
HUMAN、AI 失败和去重出口共用同一超时路径；到期前不跳过任何座位。

**发言阅读停顿**（2026-10-13 所有者批准）：`DAY_DISCUSS` / `LAST_WORDS` 中轮到 AI 座位时，
规划器从已提交事件里找最近一条 `PLAYER_SPOKE`，登记一次性唤醒，等到
`occurredAt + min(maxMs, baseMs + perCharMs × 字数)` 之后才派发，给真人留出读完上一条发言的时间。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `wolfmind.game.speech-pacing.enabled` | `true` | 停顿配置位于应用层，TurnPlannerPacingTest 覆盖就绪时间、截止时间和不适用场景 |
| `wolfmind.game.speech-pacing.base-ms` | 1500 | 每条发言的固定停顿 |
| `wolfmind.game.speech-pacing.per-char-ms` | 120 | 约每秒 8 字 |
| `wolfmind.game.speech-pacing.max-ms` | 25000 | 必须明显小于 `agentTurnDeadlineMs`（60 s），否则留给模型的时间不够 |

- **不改变规则与事件：** 回合顺序、`TURN_GRANTED.deadline` 和事件都不变，重放不受影响。停顿占用的是下一位 AI 自己的回合窗口。
- **重启不重新计时：** 停顿的起点取自已提交事件的时间。
- **哪些座位不停顿：** 真人座位不停顿（打字本身需要时间）；`g_lab_` 开头的实验室批量对局没有读者，也不停顿。
- **前端配合：** 对局页与观战页在下一位开口前继续显示上一条发言。
- **等待期间仍会超时：** 处于停顿中也照常检查领域超时。

## 5. 回合契约

### 5.1 TurnRequest

| 字段 | 说明 |
| --- | --- |
| `gameId, playerId, seatNo` | 身份 |
| `role, camp` | 由 `GameView.me` 提供，不在 Runtime 内推导 |
| `phase, round, turnIndex, version` | 回合定位 |
| `deadline` | 来自 `TURN_GRANTED`，Runtime 必须在此之前提交 |
| `gameView` | 角色可见视图，`PRIVATE` / `WOLF` / `JUDGE` 已按可见性矩阵过滤 |
| `availableTools` | 由角色与阶段决定（[tool-gateway.md §4](tool-gateway.md)） |
| `budget` | `{maxLlmCalls, maxToolCalls, maxTokens, softDeadlineMs}` |
| `agentConfig` | 模型、温度、策略档位、人格与语气配置 |

### 5.2 TurnResult

| 字段 | 说明 |
| --- | --- |
| `status` | `SUBMITTED` / `FALLBACK` / `TIMEOUT` / `FAILED` |
| `submittedAction` | `{actionType, params}`，已由工具网关提交成功时填写 |
| `submittedEventId` / `version` | 提交成功后的领域事件与版本 |
| `candidateSet` | 策略层给出的候选与打分（用于分析与调试） |
| `chosen` | `{actionType, targetId, speech, confidence, reasonCode}` |
| `toolCalls, llmCalls` | 本次回合的调用明细 |
| `tokens` | `{prompt, completion}` |
| `latencyMs` | 端到端耗时 |
| `fallbackUsed` | 是否走了规则兜底 |
| `errorCode` | 失败时的结构化原因；`FALLBACK` 时为触发兜底的原因（模型失败码、校验失败码、`SOFT_DEADLINE`、`BUDGET_LLM`、`SUBMIT_WINDOW` 或网关拒绝码，可能为空）；`SUBMITTED` 时为空 |

`TurnResult` 只描述"这一回合做了什么"，它不修改任何状态；状态变化只能由工具网关
提交后的领域事件产生（C-4）。

## 6. 上下文装配

`ContextAssembler` 把一个回合需要的信息分成五个区，每区有独立预算：

| 区 | 内容 | 来源 | 预算占比 |
| --- | --- | --- | --- |
| 固定区 | 规则摘要、角色能力、硬约束清单、输出契约 | 代码常量 + `GAME_STARTED.rules` | 15% |
| 对局区 | 公开事件时间线、自身私有事件、当前阶段与回合 | `GameViewQueryPort`（角色可见） | 45% |
| 记忆区 | 本局摘要、跨局策略摘录、最近反思 | `MemoryPort` | 10% |
| 提案区 | Strategy Layer 的候选动作集与打分 | 工作流内部产出 | 12% |
| 预留区 | 输出空间 | 无 | 18% |

三条装配规则：

1. 对局区只放角色可见内容。`JUDGE` 事件（`PLAYER_KILLED` 的原始记录等）
   绝不进入上下文。
2. 截断按"关键事件优先，其次最近事件，最后旧事件摘要化"三级处理。
   关键事件指 `GAME_STARTED`、`ROLE_ASSIGNED`（自己）、`DEATH_ANNOUNCED`、
   `VOTE_TALLY_RESOLVED`、`GAME_FINISHED` 与自己的技能结果。
3. 旧事件摘要使用结构化模板拼接（阶段、轮次、死亡、投票分布），
   第一阶段不额外调用 LLM 做摘要，避免成本与不确定性；需要 LLM 摘要时由
   实验室开关控制。

上下文装配是纯函数式的读取，任何"顺手写回"的状态都被禁止，
否则同一次回合执行会因为执行次数不同而产生不同结果。

## 7. 记忆

| 层 | 存储 | 生命周期 | 内容 |
| --- | --- | --- | --- |
| 短期 | Redis `game:{id}:agent:{playerId}:session`（Hash）+ 事件派生上下文 | 对局内 | 当前怀疑度排序、发言计划、已用技能、本局发言摘要 |
| 长期 | SQLite `agent_memory`（`EPISODE` / `FACT` / `STRATEGY` / `REFLECTION`） | 跨局 | 策略偏好、角色心得、复盘结论、匿名化统计 |
| 轨迹 | SQLite `agent_memory` 的 `DECISION_TRACE` | 跨局（分析用） | `AGENT_` 决策事件归档 |

写入规则：

1. 短期记忆由 `MemoryUpdater`（`cg-agent` 消费者的一部分）在事件到达时更新，
   Runtime 只读，不直接写 Redis（`MemoryPort` 之后才是 `RedisAgentMemoryAdapter`）。
2. 长期记忆在 `GAME_FINISHED` 后由 Runtime 生成 `REFLECTION` 并写入。
   写入前经过隐私过滤：跨局记忆只保留策略性内容与匿名化统计，
   不保留具体对局的隐藏信息（例如"第 3 号是预言家"）。
   否则一条记忆会在下一局变成跨局信息泄漏。
3. LLM 没有写记忆的工具。记忆写入是 Runtime 的职责，模型只能通过输出文本影响它。
4. 记忆召回按 `agentId + role + phase` 过滤，召回条数上限由 `agentConfig` 给定。

## 8. 工作流执行（LangGraph4j）

工作流节点与边见 [strategy-layer.md §6](strategy-layer.md)。Runtime 的职责只有四项：

| 职责 | 说明 |
| --- | --- |
| 初始化状态 | 把 `AgentContext` 作为图的初始 state，字段命名与图的 input schema 一致 |
| 驱动执行 | 单线程执行一个回合，不做并行分支的共享状态写入 |
| 中断与超时 | `softDeadlineMs` 到达时终止图执行，转入兜底 |
| 收敛结果 | 从终止节点的 state 取 `ActionProposal`，交给工具网关提交 |

### 8.0 引擎选择与故障处理（2026-10-13，所有者批准）

正式 Spring Boot 服务默认 `wolfmind.agent.workflow-engine=LANGGRAPH4J`，环境变量
`WOLFMIND_AGENT_WORKFLOW_ENGINE`。`SEQUENTIAL` 是显式回退；大小写不敏感，未知值启动失败。
选择在 Bean 创建时固定，修改配置需重启；它与外层 `game.driver.mode` 的事件/轮询选择独立。

AgentRuntimeImpl 以真实 AgentWorkflow.nodeHandlers 初始化一次探测引擎，启动校验全部必需
节点和图连接，只构建/编译，不执行节点、不读取对局、不调用模型、不提交行动。
图无效时终止启动并输出原因，不静默切换引擎。启动日志记录引擎和校验成功，
每次 AGENT_TURN_STARTED 的内部 Trace 增加 engine 字段。

每个回合只创建/执行所选引擎一次。模型或提案失败仍走同一套修复、规则 fallback 与预算；
未完成行动的严重引擎异常由调度器保留为 WAITING_DEADLINE，独立超时服务依法跳过。
已提交行动后的异常保留提交结果，不再次执行工作流或另一引擎，不新增付费调用或行动。
顺序引擎不会在图运行中接管，也不会从头重跑。若图出现持续问题，可配置 SEQUENTIAL
并重启；持久化任务与截止时间沿用现有恢复政策，中断模型尝试不自动重跑。

两种引擎共用节点处理器。生产默认装配和 standalone Phase3AgentAssembly 均已接入图引擎；
便捷测试构造器未传工厂时仍使用顺序引擎。B 已由后续独立改造接入，详见 §8.2；引擎选择与发言模式独立。

`LangChain4j` 使用底层 ChatModel / StreamingChatModel API 调用模型并发送原生工具声明。
工具声明由网关端口导出，来源为 ToolCatalog 与 RolePhaseMatrix；SDK 不自动执行工具。
模型响应携带工具调用 ID、名称和原始参数，Runtime 负责结果续接与有界循环。
工具的真实执行发生在 ToolGatewayPort；行动先转成 ActionProposal 并完成校验，之后才提交。

### 8.1 原生工具调用（2026-10-13，所有者批准）

默认交互模式为 `FUNCTION_CALLING`。`LlmClient` 的应用层消息支持 system、user、
assistant 工具调用和与 callId 配对的 tool 结果；成功响应支持正文为空而工具调用非空。
供应商续接信息由不透明的 Continuation 保存，禁止写入日志、Trace、分析或回放。
DeepSeek 同步与 SSE 的 reasoning_content 续接已用当前 LangChain4j 1.20.0 的本机线路测试验证。

`decide` 内最多执行两轮查询循环。一个响应允许多个只读调用（顺序执行），
或一个行动调用；查询与行动混合、多行动批次在执行前整体拒绝。
缺失/重复 callId、未知工具或坏参数进入修复/兜底。正文 JSON 在此模式下不能提交行动。
查询结果逐一以 tool 消息回传，按座位可见性过滤；授权视图纳入本回合事实集合。
原始参数最长 32768 字符，callId 最长 128 字符，批次最多六个调用；
网关结果最多 24000 字符，Runtime 再限制为 `max(256, maxTokens / 2)` 字符，
超限返回缩小查询范围的结构化提示，不截断 JSON。

行动成功后结束回合，不再调用模型确认。修复最多一次；表达重写锁定原动作、目标和工具。
提交被拒绝且仍有额度时，可回传拒绝原因修复一次；失败进入确定性兜底。
ToolSession 绑定原阶段与轮次，版本刷新不得把旧轮次行动重新绑定到新阶段。
写入后的审计或 finalize 失败不得抹掉成功事实或触发另一行动提交；记录不含私有载荷的告警。

部署可用 `WOLFMIND_LLM_INTERACTION_MODE=JSON_PROPOSAL` 回退，系统属性为
`wolfmind.llm.interactionMode`，属性文件键为 `interactionMode`。同一回合不混用协议。

### 8.2 B 两阶段发言（2026-10-13，所有者批准）

`AgentConfig.mode=B` 且 TurnRequest.expectsSpeech 时，`decide` 先制定并校验内部 SpeechPlan，
再经 `express` 生成公开表达。图引擎共 14 个必需节点，A 路径跳过 express；顺序引擎使用同样的门控。
完整字段、公开证据边界、修复政策见 strategy-layer.md §6.1。

第二阶段采用全新的模型消息，仅公开背景、服务器验证的计划与人格；不沿用私人上下文或工具查询结果。
计划内容不进公共事件、流式预览或 Trace，Trace 仅记录 SPEECH_PLAN / SPEECH_EXPRESSION 阶段及用量/拒绝码。
表达修复和重写同样新建公开上下文、锁定计划；最终通过检查后经原网关提交一次。
查询、两阶段及修复共用预算，不增加回合截止时间；第一阶段预留表达调用与时间。
无效/失败计划、无可用表达或预算不足走规则兜底；严重引擎异常仍由独立超时处理。

部署 `wolfmind.agent.speech-mode`（环境变量 WOLFMIND_AGENT_SPEECH_MODE）：CONFIG（默认，遵循座位 mode）、
A、B。未知值启动失败；在注册 Agent 时固定实际模式，修改配置需重启，运行中的付费尝试不重跑。
未配置模型时即使选 B 仍使用规则兜底。公共实验室表单没有新增模式字段。

## 9. 预算、超时与幂等

| 项 | 规则 |
| --- | --- |
| `deadline` | 来自 `TURN_GRANTED`；Runtime 在 `deadline - 500 ms` 前必须完成提交 |
| `softDeadlineMs` | 默认 `deadline - now - 1500 ms`，到期终止推理进入兜底 |
| LLM 调用 | FUNCTION_CALLING 默认最多 4 次；JSON_PROPOSAL 默认 2 次。查询、决策、修复与重写共用上限，超限兜底 |
| 单次调用时长 | 流式与阻塞调用都最多等到 `deadline - now - 500 ms`，到期返回 `TIMEOUT` 并兜底；SDK 自身的连接超时与重试不能把回合拖过截止时间 |
| 工具调用 | 默认每回合最多 6 次，原生模式查询尝试最多占用 4 次，预留行动与兜底各 1 次。原始参数拒绝也计 Runtime 尝试额度，整批拒绝计 1 次协议尝试 |
| 输出修复 | 校验失败最多重试 1 次（`agent.maxRepairRounds`） |
| 提交幂等 | `idempotencyKey = agent:{gameId}:{version}:{playerId}`，同一回合重复提交只生效一次 |
| 事件重放 | 重启后 `TURN_GRANTED` 可能被重新消费，幂等键保证不会产生两条行动事件 |

幂等键包含 `version`，因此"同一回合的重复执行"被收敛，而"下一个回合"必然是新键。

默认预算由 `TurnBudget.defaultFor(interactionMode)` 统一选择。房间开局/重启补建、
实验室配置转换与 `Phase3AgentAssembly.defaultConfigFor` 均在创建模型座位时调用该入口；
原生工具调用模式为 4 次，JSON 模式为 2 次，其他预算字段沿用现有默认值。
规则基线仍不调用模型。已显式提供的 `AgentConfig.budget` 不在 Runtime 注册或回合派发时
自动扩容；实验室当前公开配置不提供预算字段，因此转换时使用此默认选择。
预算是上限，查询、内部计划、公开表达、修复和重写共同计数；不延长截止时间或提高单局费用上限。

## 10. 失败与降级

| 故障 | 检测 | 处理 | 结果 |
| --- | --- | --- | --- |
| LLM 超时 | 调用超时 | `FallbackPlanner` 产出规则 Top-1 动作 | `status=FALLBACK`，`fallbackUsed=true` |
| LLM 返回无法解析 | 输出校验失败 | 修复 1 次；仍失败则兜底 | `AGENT_OUTPUT_REJECTED` |
| 工具被拒绝（越权或阶段不符） | 网关结构化拒绝 | 修复 1 次（按拒绝原因调整）；仍失败则兜底 | `AGENT_TOOL_CALLED{decision:DENIED}` |
| 预算耗尽 | `BudgetGuard` | 立即兜底 | `AGENT_TURN_FINISHED{fallbackUsed:true}` |
| 兜底也失败 | 兜底是纯规则，只在无合法动作时失败 | 提交 `SKIP` | `AGENT_TURN_TIMEOUT` |
| 进程崩溃 | 重启后位点回退 | 由幂等键收敛；超时则 `SkipAction` | `GAME_RECOVERED` + 事件 |
| 全 LLM 不可用 | 连续失败计数 | 全局降级：整局使用规则兜底 | `DESIGN.md` §15 Phase 3 仍能跑完 9 AI 对局 |

降级的设计底线：兜底动作是合法 `Action`，走同一领域校验与同一事件化路径。
AI 出问题不会让对局出现"没有事件的状态变化"。

## 11. 并发模型

| 维度 | 约束 |
| --- | --- |
| 同一 `gameId` | 同一时刻只有一个回合窗口，AI 回合天然串行；`DAY_VOTE` 的 AI 座位并发派发，提交在网关按对局排队 |
| 同一 `AgentInstance` | 单线程执行，禁止并发 `executeTurn` |
| 不同 `gameId` | 可并行；受 LLM 并发上限（配置 `agent.maxConcurrentTurns`）约束 |
| 消费者分片 | `cg-agent` 按 `gameId` 分片，同一对局只在一个实例上消费 |
| 实例数量 | `AgentInstance` 与"AI 座位"一一对应，9 个座位最多 9 个实例 |

## 12. 可观测性

Runtime 产生 `AGENT_` 前缀的决策事件（[event-design.md §7.4](../architecture/event-design.md)），
写入 `game:{id}:agent:{playerId}` 并归档到 SQLite `agent_memory`：

| 事件 | 记录内容 |
| --- | --- |
| `AGENT_TURN_STARTED` | 回合定位、deadline、预算 |
| `AGENT_CONTEXT_BUILT` | 事件区间、token 估算、记忆命中条数、截断层级 |
| `AGENT_STRATEGY_DECIDED` | 候选集、打分、命中的硬约束 |
| `AGENT_LLM_INVOKED` | 模型、token 数、延迟 |
| `AGENT_OUTPUT_REJECTED` | 拒绝阶段（解析 / 守卫 / 网关）、原因 |
| `AGENT_TOOL_CALLED` | 工具名、参数摘要、允许/拒绝、结果事件 id |
| `AGENT_TURN_FINISHED` | 最终动作、提交事件 id、是否兜底 |
| `AGENT_TURN_TIMEOUT` / `AGENT_TURN_FAILED` | 触发点与错误码 |

这些事件是 `DESIGN.md` §14 "AI 分析页"与 AI 实验室的数据来源，
它们**不进入** `game:{id}:events`，因此不会影响对局状态（C-4、C-5）。
日志中不记录完整 prompt 与私有事件 payload，避免日志成为隐藏信息泄漏渠道。
唯一记录模型原文的是复读拦截的 `draft`（strategy-layer.md §8.3）：它已通过 LeakGuard，本就是准备公开说的话，
且只在对局结束后经分析接口返回；泄密拦截的原稿从不记录。

## 13. 配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `agent.maxConcurrentTurns` | 8 | 跨对局并行 AI 回合上限 |
| `wolfmind.agent.speech-mode` | CONFIG | CONFIG / A / B，注册时固定，独立于图/顺序引擎 |
| `agent.maxLlmCallsPerTurn` | 4 / 2 | 原生模式默认 4，旧 JSON 模式默认 2；座位已有显式预算仍生效 |
| `agent.maxToolCallsPerTurn` | 6 | 含查询 |
| `agent.maxRepairRounds` | 1 | 输出修复轮数 |
| `agent.context.maxTokens` | 8000 | 上下文总预算 |
| `agent.memory.recallLimit` | 8 | 长期记忆召回条数 |
| `agent.deadlineReserveMs` | 500 | 提交前的预留时间 |
| `agent.softDeadlineReserveMs` | 1500 | 终止推理的预留时间 |
| `agent.fallback.enabled` | true | 规则兜底开关 |
| `agent.trace.enabled` | true | 决策轨迹记录开关 |

每个座位的模型、温度、策略档位来自 `AgentConfig`（AI 实验室的配置对象），
一局内同一座位的模型不切换，保证对局可复现。

**模型推理模式**（2026-10-12 所有者批准）：连接参数 `thinking`（环境变量 `WOLFMIND_LLM_THINKING`、
系统属性 `wolfmind.llm.thinking`、`wolfmind-llm.properties` 的 `thinking` 键），只对支持
`thinking` 请求字段的供应商（DeepSeek）有效：

| 取值 | 行为 |
| --- | --- |
| 留空 | 不发送该字段，沿用供应商默认（DeepSeek 默认开启推理） |
| `enabled` | 所有请求发送 `thinking: {type: enabled}` |
| `disabled` | 所有请求发送 `thinking: {type: disabled}` |
| `speech-off` | 发言回合（`DAY_DISCUSS`、`LAST_WORDS`，含其修复与重写调用）关闭推理；夜间行动、投票、开枪保留默认推理 |
| `speech-low` | 发言回合发送 `thinking: {type: enabled}` 与 `reasoning_effort: low`；其余回合沿用默认推理 |

`speech-off` 实测（对局 `g_r_mv563ji9_1`）发言平均 2.3 s，但多名座位整段复述前一位发言，所有者改选 `speech-low` 复跑比较。

关闭推理的请求同时发送 `temperature` 与 `max_tokens`（`LlmRequest.maxTokens`）；推理开启的请求不发送二者——
推理模式下 temperature 被忽略，而 `max_tokens` 会把推理 token 计入，可能截断推理留下空正文。
无法识别的取值按留空处理，启动摘要显示为 `thinking=default(unrecognized:…)`。供应商若拒绝该字段，
对应回合以 `reason=BAD_REQUEST` 走兜底。

## 14. 测试清单

| 测试 | 覆盖 |
| --- | --- |
| 生命周期 | `createAgent` 幂等；`destroyAgent` 后不再响应 `TURN_GRANTED` |
| 回合串行 | 同一 `gameId` 并发派发两个 `TURN_GRANTED` 时只执行一次提交 |
| 投票并发 | `DAY_VOTE` 并发派发待投票 AI，真人未投不挡 AI；真实调度器整局和并发/截止隔离由 EventDrivenTurnSchedulerTest 覆盖 |
| 幂等 | 同一 `version` 重复执行 `executeTurn` 只产生一条行动事件 |
| 超时 | `deadline` 前未完成时提交兜底或 `SKIP`，且不产生第二条事件 |
| 兜底 | LLM 全失败时 9 个 AI 能完成一整局，事件流完整 |
| 可见性 | 上下文不含 `JUDGE` 事件与他人 `PRIVATE` 事件 |
| 依赖边界 | 静态检查：Agent 侧代码无 `domain.game.model` 引用（DEP-7） |
| 记忆隐私 | 跨局记忆不含具体对局的隐藏信息 |
| 预算 | 超过 LLM/工具预算时进入兜底且记录原因 |
| 恢复 | 进程重启后由 `TURN_GRANTED` 重放恢复，无重复行动 |

## 15. 禁止事项

| 禁止 | 原因 |
| --- | --- |
| Runtime 直接调用 `SubmitActionApplicationService` 之外的状态写入 | 绕过工具网关的权限校验（C-5） |
| Runtime 直接读写 Redis / SQLite | 破坏端口边界，且让记忆与归档耦合 |
| 把完整事件流或 `JUDGE` 事件放进上下文 | 信息泄漏，实验结论失效 |
| 让 LLM 决定回合顺序、死亡判定、票数统计 | 这些是规则，属于领域（`DESIGN.md` §2） |
| 让 LLM 直接写记忆 | 记忆污染，且跨局泄漏风险 |
| 在 AI 回合内持有 `game:{id}:lock` | 锁会被 LLM 延迟拖到超时，阻塞真人与其他 AI |
| 把 `AGENT_` 事件写入 `game:{id}:events` | 污染对局事件流，违反 C-4 的语义分层 |
