# 审计B：AI 子系统（Agent Runtime / Strategy Layer / Tool Gateway / LLM 接入）与设计文档的偏差

审计范围：`backend/server/src/main/java/com/wolfmind/application/agent/**`、`infrastructure/agent/**`、
`infrastructure/ai/**`，以及它们在生产装配中的接线点（`WolfMindConfiguration`、
`StartRoomGameApplicationService`、`GameDriver`、`LabBatchRunner`）。

对照文档：`AGENTS.md` §8（AI 子系统边界与隐藏信息保护）、`docs/ai/agent-runtime.md`、
`docs/ai/strategy-layer.md`、`docs/ai/tool-gateway.md`、`docs/ai/function-calling-migration.md`；
`TASKS.md` 仅用于判断"是否留痕"，不作为"已实现"的证据。

方法：只读 grep + read，逐条给出「文档出处（章节）→ 代码证据（file:line）→ 是否符合 → 严重度 → 是否留痕 → 影响」。
本次**没有运行测试**（最终 `mvn test` 由 Lead 负责），因此"是否符合"只基于静态代码路径；
凡是静态无法判定的都进 §4「未判定」。所有行号基于当前工作区内容。

---

## 0. 结论摘要

| 严重度 | 数量 | 说明 |
| --- | --- | --- |
| P0 | 0 | 未发现"AI 直接改规则/改状态"或"确认可被利用的跨座位私有信息泄漏" |
| P1 | 3 | 生产路径未使用 LangGraph4j；FUNCTION_CALLING 的 4 次模型预算在生产不生效；对局结束/座位接管的生命周期未接线 |
| P2 | 5 | 记忆链路整体缺失；`WorkflowMode.B` 两阶段模式未实现；文档 §13/§11 的配置与并发上限无落地；一批"文档要求但从未被调用"的实现；驱动/成本守卫/轨迹的 per-game 资源不释放 |
| P3 | 8 | 幂等键前缀两份文档互相矛盾；配置键名不一致；未记载的错误码/能力；上下文预算占比只实现两项；流式参数预览与迁移文档表述不一致；`scope` 未知取值 fail-open；`dispatch` 的按座位配置参数生产恒为 null；prompt 版本未进轨迹 |

**最严重的三条**

1. **P1-B02**：生产（Spring 装配）里 `AgentRuntimeImpl` 的 `workflowEngineFactory` 传 `null`，
   `AgentWorkflow` 退回 `SequentialWorkflowEngine`；`AGENTS.md` §8 / `agent-runtime.md` §8 要求的
   `… → LangGraph4j workflow → …` 在真实部署路径上没有被执行，`TASKS.md:303/312` 的 DONE 与实际不符。
2. **P1-B11**：默认 `FUNCTION_CALLING` 模式下文档承诺"每回合最多 4 次模型调用"，但生产建座位的两条路径
   （REST 开局的 `StartRoomGameApplicationService`、实验室 `LabBatchRunner`）都复制 `AgentConfig.ruleBaseline`
   的 `TurnBudget.DEFAULT`（2 次），只有生产未使用的 `Phase3AgentAssembly` 会给 4。
3. **P1-B03**：`agent-runtime.md` §3 的 `GAME_FINISHED/GAME_ABORTED → destroyAgent`、
   `PLAYER_CONTROLLER_CHANGED → destroyAgent` 没有任何生产调用者（`AIControllerAdapter.onGameFinished` /
   `onSeatTakenOverByHuman` 全仓零调用），Agent 注册表与相关 per-game 状态在长跑实例上只增不减。

**隐藏信息保护结论（`AGENTS.md` §8）**：静态核对**未发现确认的跨座位私有信息泄漏**。
`JUDGE` 事件在 `VisibilityFilter`/`ViewerContext` 层对所有参与者一律不可见
（`VisibilityFilter.java:41-50`、`ViewerContext.java:69-77`），
`AgentViewAdapter` 只把"本人私有事实 + 投影已给出的狼队友 + 女巫当夜刀口"这三类折进 `PrivateFact`
（`AgentViewAdapter.java:118-208`），非女巫视图拿不到 `NIGHT_KILL`、非狼视图拿不到 `WOLF_TEAMMATE`
（测试：`AgentViewAdapterTest.java:35-94`）。唯一的"超出文档授权"面是
**`QueryGameStateTool.reduce` 对未知 `scope` 降级为 SELF 全量私有视图**（B-33，fail-open，当前被 G2 枚举挡住，
不是跨座位泄漏）。另外发现 1 处**已留痕的定向披露**：女巫拿 `WOLF_KILL_SELECTED`
（`AgentViewAdapter.java:181-208`），与 `tool-gateway.md` §2.1.1 一致，属设计而非缺陷。

---

## 1. 逐项核对表

| 编号 | 设计要求（出处） | 实现现状（file:line） | 符合? | 严重度 | 已留痕 | 影响 |
| --- | --- | --- | --- | --- | --- | --- |
| B-01 | 调用链 `AiController → Agent Runtime → Strategy Layer → LangGraph4j → LangChain4j → 结构化提案 → 应用层校验`（`AGENTS.md` §8；`agent-runtime.md` §1 结构图） | `GameDriver.java:295,397` → `AIControllerAdapter.java:63-96` → `AgentRuntimeImpl.java:182,186` → `AgentWorkflow.java:394-409` → `PromptBuilder.java:196-206` → `LlmInvoker.java:81-115` → `ToolGatewayAdapter.java:193` → `WriteActionHandler.java:118-133` → `SubmitActionApplicationService`（应用层）。LangGraph4j 环节缺失：见 B-02 | 部分 | P1 | 部分（`Phase3AgentAssembly.java:105-107` 注释承认可退回顺序引擎） | 文档的架构链在生产上有一环不成立，见 B-02 |
| B-02 | 工作流执行必须由 LangGraph4j 承载（`AGENTS.md` §3 技术基线、§8；`agent-runtime.md` §2 `WorkflowEngine`=LangGraph4j、§8 标题"工作流执行（LangGraph4j）"） | 生产 Spring 装配把第 9 个实参传 `null`：`WolfMindConfiguration.java:253-265`；8 参构造把工厂置空：`AgentRuntimeImpl.java:56`；空工厂 → `SequentialWorkflowEngine`：`AgentWorkflow.java:382-386`。唯一注入 LangGraph4j 的装配 `Phase3AgentAssembly.java:108-110` 在 `main` 无调用者（仅测试引用 `defaultConfigFor`，`LangChain4jFunctionCallingTest.java:125`）。九 AI 整局 E2E 也走 8 参构造：`NineAiGameRunner.java:119-120` | 否 | P1 | 无（`TASKS.md:303,312` 反而声称"九人局跑在 LangGraph4j 图引擎上（Phase3AgentAssembly 默认注入）"） | 批准的基线与文档描述的运行时引擎在真实部署里未被使用；两条引擎一致性只被单元测试覆盖（`LangGraph4jWorkflowEngineTest.java`、`FunctionCallingWorkflowTest.java:39`），端到端证据缺图引擎 |
| B-03 | 生命周期：`GAME_STARTED`→`createAgent`；`TURN_GRANTED`→`executeTurn`；`GAME_FINISHED/ABORTED`→`destroyAgent`；AI↔真人接管双向切换（`agent-runtime.md` §3 表；`AGENTS.md` §8 "owns per-match/per-seat agent lifecycle"） | `createAgent`/`executeTurn` 有生产调用者（`AIControllerAdapter.java:77,95`）；`AIControllerAdapter.onGameFinished:111`、`onSeatTakenOverByHuman:115` **全仓零调用**（grep `onGameFinished|destroyGame|destroyAgent` 仅命中定义、测试、`LabBatchRunner.java:226`）；`AgentRuntimeImpl.destroyGame:155-160` 因此不在生产触发 | 否 | P1 | 无 | 注册表 `AgentRegistry`（`AgentRuntimeImpl.java:34,139-160`）、成本账本、短期记忆、轨迹都不释放；长跑实例随对局数持续增长（详见 B-16） |
| B-04 | 回合由领域/驱动发放，AI 不自行开始回合；`TURN_GRANTED` 是唯一发放者；`DAY_VOTE` 并发派发（`agent-runtime.md` §4、§4.1） | `GameDriver.java:295`（逐个派发）、`370-404`（投票并发派发，有界池）、`AIControllerAdapter.java:64-67`（真人座不派发）；`TurnSignal.drivesAgent()` | 是 | — | — | 符合；`GameDriver` 自选行动者一节已在 §4.1 留痕 |
| B-05 | `TurnRequest` 字段：身份/角色/阶段/轮次/版本/deadline/gameView/availableTools/budget/agentConfig（`agent-runtime.md` §5.1） | `TurnRequest.java:26-40` 逐字段一致；`toolSession()` 由 Runtime 注入身份（`:59-62`） | 是 | — | — | — |
| B-06 | `TurnResult` 字段与状态：`SUBMITTED/FALLBACK/TIMEOUT/FAILED`；`FALLBACK` 时 errorCode 取"模型失败码/校验失败码/SOFT_DEADLINE/BUDGET_LLM/SUBMIT_WINDOW/网关拒绝码"（`agent-runtime.md` §5.2） | `TurnResult.java:29-56` 一致；errorCode 取值见 `AgentWorkflow.java:484,489,498,851,870,926-928` | 部分 | P3 | 无 | 代码还产出文档未列的 `BUDGET_TOKENS`（`AgentWorkflow.java:498`）与 `NOT_SUBMITTED`（`:1027`），分析/回放按码分类时会出现文档外取值 |
| B-07 | 上下文五区与预算占比 15%/45%/10%/12%/18%（`agent-runtime.md` §6） | `ContextAssembler.java:60-102`（GAME_ZONE_RATIO=0.45、CONTEXT:PRIVATE=6:4、MEMORY_RATIO=0.10，`:51-54`）；`renderSystem`/`renderTask` 不受任何预算约束（`:105-155,394-422`） | 部分 | P3 | 无 | 只有"对局区/记忆区"两项预算落地；固定区与提案区/预留区无预算，长 prompt 挤压模型输出空间的风险无界 |
| B-08 | 记忆三层：短期 Redis Hash、长期 SQLite `agent_memory`、`MemoryUpdater` 消费者更新短期、`GAME_FINISHED` 后写 `REFLECTION`、召回按 agent+role+phase、含隐私过滤（`agent-runtime.md` §7；`AGENTS.md` §7/§8） | 端口 `MemoryPort.java:15-42`；生产唯一实现是内存版 `InMemoryMemoryPort`（`WolfMindConfiguration.java:257`、`Phase3AgentAssembly.java:123`）；`AgentWorkflow.recall:421-430` **只调 `loadLongTerm`**；`saveLongTerm`/`loadShortTerm`/`appendShortTerm`/`clearShortTerm` 在 `main` 零调用者；无 `MemoryUpdater`、无 `REFLECTION` 生成 | 否 | P2 | 部分（`TASKS.md:361` "Redis/SQLite 版本属于后续阶段"；`TASKS.md:708-709` "四类仍无写入者"） | 长期记忆永远为空（`ContextAssembler.java:386-391` 渲染为"本局暂无召回的跨局记忆"），短期记忆从不写入/读取；§7 四条写入规则里只有隐私过滤（`InMemoryMemoryPort.java:100-129`）有实现 |
| B-09 | 13 个节点（perceive/recall/summarize/analyze/strategize/decide/validate/repair/leakCheck/rewrite/submit/fallback/finalize），仅 decide/repair/rewrite 调模型（`strategy-layer.md` §6） | `WorkflowEngine.java:38-51,77-106`；`AgentWorkflow.java:394-409`；`agent-runtime.md` §6 的"条件 summarize"在代码里是无条件空节点（`AgentWorkflow.java:432-436`） | 是 | — | — | 节点语义一致；`summarize` 的条件边只体现在文档图上，代码里恒执行（空实现，无副作用） |
| B-10 | 原生工具调用：`ToolSpec` 声明来自网关输出、`callId` 配对、续接信息不透明且不入日志、`decide` 内最多两轮查询、多个只读顺序执行、查询与行动混批整体拒绝（`agent-runtime.md` §8.1） | `PromptBuilder.java:196-206`；`ModelToolCodec.java:131-164`；`AgentWorkflow.java:536-636`（`readsAllowed` 门 `:551-553`、批次判定 `:582-593`、callId 去重 `:573-580`）；`LlmClient.Continuation:65-70`（`toString` 打码） | 是 | — | — | 与迁移文档 §3/§5 的协议描述一致（测试：`FunctionCallingWorkflowTest.java:66-95,137-149`） |
| B-11 | `FUNCTION_CALLING` 默认每回合最多 4 次模型调用（`agent-runtime.md` §9/§13；`function-calling-migration.md` §5） | `TurnBudget.java:23-24` 定义 4；但生产两条建座位路径都取 `ruleBaseline` 的 `budget()`=DEFAULT(2)：`StartRoomGameApplicationService.java:207-213,226-242`、`LabBatchRunner.java:284-301`；唯一给 4 的是生产未调用的 `Phase3AgentAssembly.java:157-159`（测试断言 4：`LangChain4jFunctionCallingTest.java:125`） | 否 | P1 | 无（`TASKS.md:22` 仍写"默认 FUNCTION_CALLING 为 4 次模型/6 次工具"） | 真实部署下 `readsAllowed` 要求 `llmCalls < max-1`（`AgentWorkflow.java:553`），"2 轮查询 + 1 行动 + 1 修复/重写"退化为"最多 1 轮查询 + 1 行动"；提交被拒后的修复需要 `tryConsumeLlmCall`（`:875`），额度已尽 → 直接确定性兜底 |
| B-12 | `deadline - 500ms` 前提交、`deadline - 1500ms` 软截止终止推理、单次调用等到 `deadline - now - 500ms`（`agent-runtime.md` §9） | `BudgetGuard.java:62-71`；`AgentWorkflow.java:481-490,848-853`；`LlmInvoker.java:81-82`；`TurnBudget.java:51-58` | 是 | — | — | — |
| B-13 | 预算维度：LLM 次数、工具次数、token、时长（`agent-runtime.md` §2 的 `BudgetGuard` 行、§9） | LLM 次数/时长由 `BudgetGuard.java:52-76` 与 G11（`PermissionChecker.java:375-391`）实现；**"工具调用次数"无 `BudgetGuard` 实现**（`tryConsumeToolCall:57` 零调用）；token 由文档未记载的 `LlmCostGuard` 承担（`LlmCostGuard.java:30-45`） | 部分 | P2 | 无 | 文档列的四个预算维度里一个（工具次数）在 Runtime 侧是死代码，实际靠网关 `TurnBudgetLedger`（`PermissionChecker.java:381-389`）——两处记账语义不同（网关按 `(gameId,playerId,version)` 累加），见 `TASKS.md:1365` 记录过的历史坑 |
| B-14 | 失败与降级：LLM 超时/不可解析/被拒/预算耗尽/兜底失败/进程崩溃/全 LLM 不可用（连续失败计数→全局降级）（`agent-runtime.md` §10） | 前六行有实现（`AgentWorkflow.java:461-532,690-725,769-834,896-930`）；"连续失败计数 + 全局降级"**无任何代码**（grep `consecutive|failureCount` 在 agent 侧零命中） | 部分 | P2 | 无 | 文档描述的是"整局切换到规则兜底"的全局降级；实现是"每回合独立兜底"，功能上仍能跑完 9 AI（§10 底线成立），但连续故障下的成本/日志行为与文档不符 |
| B-15 | 并发：同一 gameId 串行、`DAY_VOTE` 并发、不同 gameId 可并行但受 `agent.maxConcurrentTurns` 限制、`AgentInstance` 单线程（`agent-runtime.md` §11/§13） | 投票并发受有界池约束（`WolfMindConfiguration.java:495-501`，属性 `wolfmind.agent.max-concurrent-turns`）；**跨对局 LLM 并发无信号量/上限**（`LangChain4jLlmClient.java:49` 用无界虚拟线程执行器）；`AgentInstance` 串行见 `AgentInstance.java` | 部分 | P2 | 部分（`TASKS.md:920` 只谈派发去重） | 多局并行时模型调用并发无上限，文档承诺的"LLM 并发上限"未落地；成本与供应商限流风险 |
| B-16 | `AGENT_` 事件写入 `game:{id}:agent:{playerId}`、不进入对局事件流、日志不记完整 prompt/私有 payload、泄密原稿从不记录（`agent-runtime.md` §12） | `AgentTracePort.java:29-40,71-75`；`InMemoryAgentTrace.java:26-42,85-97`（payload 截断 200 字符）；`AgentWorkflow.java:734-747` 泄密只记 kind、复读才记 draft；`AnalysisQueryService.java:514,517-520` 仅对 ECHO 阶段回传 draft | 是 | — | — | 与 §12 一致 |
| B-17 | 配置表 `agent.maxConcurrentTurns / agent.maxLlmCallsPerTurn / agent.maxToolCallsPerTurn / agent.maxRepairRounds / agent.context.maxTokens / agent.memory.recallLimit / agent.deadlineReserveMs / agent.softDeadlineReserveMs / agent.fallback.enabled / agent.trace.enabled`（`agent-runtime.md` §13） | 全仓只读取两个属性：`wolfmind.agent.max-concurrent-turns`（`WolfMindConfiguration.java:499`）与 `wolfmind.agent.force-rule`（`StartRoomGameApplicationService.java:181`）。其余均为硬编码常量：`TurnBudget.java:23-24`、`ToolGatewayConfig.java:32`、`AgentConfig.java:83-84,109-125`；`fallbackEnabled()` 恒 `true`（`AgentConfig.java:133-135`） | 否 | P2 | 无 | §13 的配置契约不成立：改这些键不会生效；`agent.fallback.enabled=false`、`agent.trace.enabled=false` 无法实现 |
| B-18 | 策略层职责分工：能不能做/候选集与打分由代码，取舍与表达由 LLM（`strategy-layer.md` §1、§2、§10；`AGENTS.md` §8） | `WolfMindStrategy.java:57-138` 装配 `FactExtractor`/`ThreatModel`/`SuspicionModel`/`CandidateGenerator`/`DecisionPolicy`/`OutputValidator`/`LeakGuard`/`EchoGuard`/`FallbackPlanner`/`ContextAssembler`；写路径只经 `gateway` | 是 | — | — | — |
| B-19 | `ThreatModel` 五因子/`SuspicionModel` 五因子与权重区间（`strategy-layer.md` §4.1/§4.2） | 两模型存在且为纯函数、用 `TieBreak` 排序（`ThreatModel.java`、`SuspicionModel.java:51,112`） | 未判定 | — | — | 未逐因子核对权重区间（见 §4 未判定-1） |
| B-20 | `score = w1·target + w2·consistency − w3·exposure + w4·infoGain + w5·pressure`；三档档位来自 `agentConfig.strategyProfile`（`strategy-layer.md` §4.3） | `DecisionPolicy.java:127-131` 完全一致；三档权重 `:63-69`（文档只给结构未给数值，代码注释已说明定案依据 `:28-36`）；档位来自 `AgentConfig.StrategyProfile`（`AgentConfig.java:48-52`） | 是 | — | — | — |
| B-21 | 角色硬约束与候选集：狼刀排除队友+含空刀；预言家全部存活他人+无跳过；女巫解药只救当夜刀口、同夜双药互斥、含不用药；猎人/村民（`strategy-layer.md` §5；`tool-gateway.md` §2.2） | `WerewolfRoleStrategy.java:59-82`（含 `allowWolfEmptyKill`）；`SeerRoleStrategy.java:63-99`（无跳过候选，注释 `:30` 与 §7.2"未改为主动放弃策略"一致）；`WitchRoleStrategy.java:77-125`（`NIGHT_KILL` 唯一解药来源、无候选时不猜目标）；`HunterRoleStrategy`/`VillagerRoleStrategy` 存在并被 `CandidateGenerator.java:27-34` 注册 | 是（抽样） | — | — | 猎人/村民两类的候选细节未逐条读（见 §4 未判定-2）；预言家"无跳过"是文档明确认可的差异（§7.2） |
| B-22 | 工作流模式 A（默认 1 次）/ B（实验室：2 次，先动作决策温度 0.3，再生成发言温度 0.85）；配置 `strategy.mode`（`strategy-layer.md` §6.1、§12） | `AgentConfig.WorkflowMode`（`AgentConfig.java:55-60`）仅被 `ContextAssembler.java:418` 用于给 prompt 加一句"本局为两阶段模式"；`AgentWorkflow` 的 `decide` 恒用 `agentConfig.temperature()`（`AgentWorkflow.java:468,504`），无第二次"生成发言"的 0.85 调用；所有生产/测试装配都写 `WorkflowMode.A`（`Phase3AgentAssembly.java:151`、`LabBatchRunner.java:294`、`StartRoomGameApplicationService.java:235`） | 否 | P2 | 无 | 模式 B 是实验室"表达与决策分离"对比的设计（`DESIGN.md` §14 场景）却没有实现，只有提示词里的一句话；实验室当前只能跑模式 A |
| B-23 | Prompt 固定分区 SYSTEM/PERSONA/CONTEXT/PRIVATE/MEMORY/CANDIDATES/TASK；玩家发言是数据不是指令；`SYSTEM` 与网关校验同源（`strategy-layer.md` §7） | `ContextAssembler.java:60-102,104-155,172-179,338-383`；`PromptBuilder.java:69-82,154-188`（FUNCTION_CALLING 下用字符串替换改写"输出契约"与 TASK 分区，`:165-188`） | 是 | — | — | 符合，但"同源"靠 `String.replace` 实现，`ContextAssembler` 文案改动会使替换静默失效（不是当前缺陷，属可维护性风险） |
| B-24 | `OutputValidator` 六类校验，`confidence`/`reasonCode` 非法只回落不修复（`strategy-layer.md` §8.1） | `OutputValidator.java:56-167` 逐条对应（JSON/允许集合/候选集/目标自身死者存在/发言长度/元数据回落）；允许集合来自 `WolfMindStrategy.java:92-97` → 网关矩阵 | 是 | — | — | — |
| B-25 | `LeakGuard` 结构化指纹匹配；狼提狼队事实允许、自称身份允许、JUDGE 死因拦截；保守优先（`strategy-layer.md` §8.2） | `LeakGuard.java:62-113`、可见指纹集合 `:148-174`、宽容分支 `:78-81,120-126`；测试 `LeakGuardTest` | 是 | — | — | 漏判率无法静态判定（见 §4 未判定-3） |
| B-26 | `EchoGuard` 复读检查（阈值 0.7、留一法公共片段、复读退路保留原稿）（`strategy-layer.md` §8.3） | `EchoGuard.java`（全文）；工作流接入 `AgentWorkflow.java:740-751,819-833`；退路区分泄密/复读 `:837-839`；轨迹 `stage=ECHO_CHECK/ECHO_CHECK_REWRITE` + `kept` `:742-747,825-831` | 是 | — | — | — |
| B-27 | `FallbackPlanner`：候选集最高分 / 空候选→SKIP / 结构化模板 / 标记 FALLBACK（`strategy-layer.md` §9） | `FallbackPlanner.java:51-75`；工作流 `AgentWorkflow.java:896-930`；模板句式 `:26-31` 与文档逐字一致 | 是 | — | — | — |
| B-28 | 策略层只产出 `ActionProposal`，映射与校验在网关（`strategy-layer.md` §10） | `WolfMindStrategy` 不持有仓储/事件流；映射 `ToolCatalog.java:300-410`；校验 `PermissionChecker.java` | 是 | — | — | — |
| B-29 | 平局按 `TieBreak(gameId+轮次+用途+座位)`，禁止随机数与座位号升序（`strategy-layer.md` §13 末两条） | `TieBreak.java:25-33`；使用者 `DecisionPolicy.java:77-88`、`ThreatModel`/`SuspicionModel`/`SeerRoleStrategy.java:79` | 是 | — | — | — |
| B-30 | G1..G11 顺序固定、失败即结构化拒绝且零事件（`tool-gateway.md` §4/§5） | `PermissionChecker.java:83-190`（G1 `:92-95`、G2 `:104-108`、G3 `:111-125`、G4 `:128-132`、G5 `:138-142`、G6 `:145-151`、G7 `:154-158`、G8 `:161-165`、G9 `:169-173`、G10 `:176-180`、G11 `:184-189`）；测试 `PermissionCheckerTest.java:48-207` | 是 | — | — | — |
| B-31 | G12 幂等（命中返回首次结果）、G13 提交（版本冲突刷新重试，`gateway.versionRetry` 默认 2） | `ToolGatewayAdapter.java:211-223`（G12）；`WriteActionHandler.java:106-152`（G13，重试上限 `:142`）；并发分段锁只包提交 `:97-104` | 是 | — | — | — |
| B-32 | 六个工具、无 `GOD` scope、观战者无工具（`tool-gateway.md` §2/§3/§8） | `ToolCatalog.java:34-57`（六工具、SCOPES 三项）、`:155-199`（schema）；拒绝 `GOD`：`ToolGatewayReadTest.java:96-100`、`ToolArgsValidatorTest.java:31` | 是 | — | — | — |
| B-33 | 只读工具语义：`QueryGameStateTool` 按 scope 裁剪、`PUBLIC⊆MY_CAMP⊆SELF`、`MY_CAMP` 的狼刀经历史查询取得（`tool-gateway.md` §2.1） | `QueryGameStateTool.java:44-100`；**`reduce` 的 `default` 分支返回 SELF 全量**（`:75-79`）——未知 scope 不是拒绝而是降级为本人全量私有视图（fail-open） | 部分 | P3 | 部分（`QueryGameStateTool.java:17-19` 记录了"单调包含"裁决） | 经网关不可达（G2 枚举 `ToolCatalog.java:52,157` 只放三个值）；但 `ToolGatewayPort.queryGameState(session, scope)` 是公开端口方法（`ToolGatewayPort.java:34`），任何绕过 G2 的调用者（应用层新用例/跨进程实现）传未知 scope 会拿到 SELF 视图而不是报错，与 `tool-gateway.md` §13"不提供 GOD 视图"的失败方向相反 |
| B-34 | 女巫当夜刀口是唯一的定向披露：只给女巫、只给当夜、不进事件流（`tool-gateway.md` §2.1.1） | `AgentViewAdapter.java:63-65`（role==WITCH 才加）、`:181-208`（god 读取 + 只取当前轮 `WOLF_KILL_SELECTED` + 空刀不产生事实）、`ViewerContexts.java:87-95`；测试 `AgentViewAdapterTest.java:35-94`、`ToolGatewayWriteTest.java:213-216` | 是 | — | — | 定向披露范围与文档三条边界一致；`nightKillReader` 是包内可见且只在该文件使用 |
| B-35 | `UseSkillTool` 七技能矩阵（角色×阶段×次数×目标约束）（`tool-gateway.md` §2.2） | `RolePhaseMatrix.java:126-139`；`ToolCatalog.java:55-57`；`SEER_SKIP`/`HUNTER_GIVE_UP` 别名归一 `ToolCatalog.java:239-274`；不进入候选集 `RolePhaseMatrix.java:181-190` | 是 | — | — | — |
| B-36 | 权限矩阵是 `availableTools` 与网关校验的唯一来源（`tool-gateway.md` §3；`strategy-layer.md` §7 约束1） | `RolePhaseMatrix.java:107-146`（唯一 GRANTS）；`toolsFor:155-165`、`actionTypesFor:181-190`、`toolAllowed:211-226`；策略层经端口复用 `WolfMindStrategy.java:74-89` | 是 | — | — | 但策略层另有一份离线兜底矩阵 `DefensiveActionMatrix.java:23-93`（无端口时启用），属"第二份词汇"，见 §3-2 |
| B-37 | G2 参数 Schema/未知字段拒绝；G3 身份由 Runtime 注入、模型提供身份即拒（`tool-gateway.md` §4、§8） | `ToolArgsValidator.java:24-25,91-142`；`IdentityGuard.java:43-101`；`ModelToolCodec.java:44-51`（原始参数先扫身份字段）；测试 `ModelToolCodecTest.java:41-42`、`PermissionCheckerTest.java:98-119` | 是 | — | — | — |
| B-38 | 结构化拒绝含 `code/message/tool/retryable/allowedAlternatives/traceId`（`tool-gateway.md` §5） | `ToolGatewayPort.ToolResult.Denied:126-150` 与 `Denial.java:20-37` 有前五项，**无 `traceId` 字段**；traceId 只进审计轨迹（`ToolAuditRecorder.java:80-81`） | 部分 | P3 | 无 | 模型/策略层拿不到文档承诺的可回溯 id；排查时只能靠审计表反查 |
| B-39 | 幂等：`idempotencyKey = tool:{gameId}:{version}:{playerId}:{toolName}`，存储保留 10 分钟（`tool-gateway.md` §6；`agent-runtime.md` §9 写作 `agent:{gameId}:{version}:{playerId}`） | `ToolSession.java:59-61` 用 `tool:` 前缀（与 tool-gateway 一致）；`ToolGatewayAdapter.java:213` | 是（对 tool-gateway） | P3 | 无 | 两份文档对同一键给了两个前缀，代码只与其中一份一致；`agent-runtime.md` §9 的键名是错的 |
| B-40 | 审计：每次调用一条 `AGENT_TOOL_CALLED`，含 tool/argsDigest/decision/denialCode/resultingEventId/version/latency/phase/round/turnIndex/traceId，不存发言全文（`tool-gateway.md` §7） | `ToolAuditRecorder.java:42-83,102-115`（只读工具也记录 `ToolGatewayAdapter.java:160-161,186`）；审计写失败不抹掉成功事实 `:315-333` | 是 | — | — | — |
| B-41 | 安全表：身份伪造/越权/参数注入/任意写/目标越界/资源滥用/提示注入/隐私/观测作弊/重放（`tool-gateway.md` §8） | 逐条对应见 B-30~B-40；无批量写工具、无 SQL/Redis 句柄（`ToolGatewayPort.java` 全接口） | 是 | — | — | — |
| B-42 | 网关配置六项 `gateway.maxToolCallsPerTurn/queryHistory.maxLimit/idempotency.ttlMinutes/versionRetry/audit.enabled/denyUnknownArgs`（`tool-gateway.md` §11） | `ToolGatewayConfig.java:32` 定义默认值，`from(Map):58-67` 可从配置表构造，但 **`from` 全仓零调用**；生产恒用 `ToolGatewayAdapter.java:83` 的 DEFAULT 构造 | 否 | P2 | 无 | 与 B-17 同类：§11 的配置契约没有读取路径；`denyUnknownArgs` 无法在生产关闭（native 入口本来也强制拒绝，见 `tool-gateway.md` §2.0） |
| B-43 | 迁移协议：完整 schema 确实发出、空正文+工具调用可解析、assistant/tool 配对、`reasoning_content` 续接、非 strict 省略 `additionalProperties`（`function-calling-migration.md` §2/§3.1/§6；`agent-runtime.md` §8.1） | `LangChain4jLlmClient.java:126-129,282-298,253-263`；`LangChain4jFunctionCallingTest.java:80-110`（同步与流式各一）、`:96`（省略 `additionalProperties`）、`:103`（reasoning_content 回传）、`:113-119`（旧模式不发 tools） | 是 | — | — | 与迁移文档声称的协议一致；未对真实 DeepSeek 端点复验（见 §4 未判定-4） |
| B-44 | 流式：正文与工具参数片段分开、按调用 index/ID 隔离、只在确认后提取发言、迟到片段丢弃（`function-calling-migration.md` §4 阶段4、§6） | 客户端实现了片段事件（`LangChain4jLlmClient.java:203-214`、`ScriptedLlmClient`），但工作流不消费：`AgentWorkflow.java:560-561` 传 `ignored -> {}`，只在拿到**完整**提案后用 `preview.accept(prepared.proposalJson())` 触发预览（`:609-613`），`SpeechFieldExtractor` 只由 `SpeechPreviewStream.java:29` 在此时使用 | 部分 | P3 | 部分（`strategy-layer.md` §7.2 明说"当前版本在完整调用响应确认后发送预览"；`function-calling-migration.md` §4/§6 表述为已实现参数流隔离缓存） | 两份文档对同一交付物表述不一致；生产不消费参数增量流，"未完成参数不得执行"是天然成立而非被校验 |
| B-45 | 实验室可按座位指定配置、`dispatch` 接受 `configOf`（`agent-runtime.md` §4.1、§13） | `AIControllerAdapter.java:63-71` 支持；但生产唯一调用点恒传 `seat -> null`：`GameDriver.java:295,397`；实验室改为在开局注册配置（`LabBatchRunner.java:272-275`） | 部分 | P3 | 无 | 参数能力存在但生产未使用；按座位在派发时覆盖配置的路径没有真实调用者 |
| B-46 | 测试清单：生命周期/回合串行/投票并发/幂等/超时/兜底/可见性/依赖边界/记忆隐私/预算/恢复（`agent-runtime.md` §14；`tool-gateway.md` §12；`AGENTS.md` §10） | 有对应测试：`PermissionCheckerTest`、`ToolGatewayWriteTest/ReadTest/AuditTest`、`ModelToolCodecTest`、`ArgsValidatorTest`、`RolePhaseMatrixTest`、`AgentViewAdapterTest`、`FunctionCallingWorkflowTest`、`AgentWorkflow{Repair,Echo,SpeechPreview}Test`、`LangGraph4jWorkflowEngineTest`、`NineAiFunctionCallingTest`、`NineAiGameE2ETest`、`GatewayBoundaryTest`；**"记忆隐私"只测内存实现的过滤**（`InMemoryMemoryPort`），因为 §7 写入链不存在 | 部分 | P2 | 部分（`TASKS.md:361,708`） | 清单里的"生命周期（destroyAgent 后不再响应）"只有单元级（`AgentInstance`/`AgentRegistry`），没有"对局结束真的会 destroy"的生产级断言 → 正是 B-03 漏掉的地方 |

---

## 2. 文档要求但代码缺失（或从未被调用）

### 2.1 P1 级

1. **LangGraph4j 工作流引擎未进入生产路径**（B-02）
   - 文档：`AGENTS.md` §3 技术基线（AI: LangChain4j, LangGraph4j）、§8 架构路径；`agent-runtime.md` §2（`WorkflowEngine` = "LangGraph4j 工作流的加载与执行"）、§8 标题。
   - 代码：`WolfMindConfiguration.java:253-265` 第 9 实参 `null` → `AgentRuntimeImpl.java:56` → `AgentWorkflow.java:382-386` 用 `SequentialWorkflowEngine`。
   - 佐证：`Phase3AgentAssembly.java:108-110` 是唯一注入 `LangGraph4jWorkflowEngine::new` 的地方，`main` 无调用者；九 AI 整局跑在 `NineAiGameRunner.java:119-120`（8 参构造）。
   - 与 TASKS 的冲突：`TASKS.md:303` "LangGraph4j 图调度（默认引擎）DONE"、`TASKS.md:312` "九人局跑在 LangGraph4j 图引擎上（Phase3AgentAssembly 默认注入）"——两者的实际执行路径都不是生产装配。
   - 需要什么才能确认影响：跑一次 Spring 上下文（memory 模式）开一局，断言 `AgentWorkflow` 使用的引擎类型——目前没有任何测试覆盖生产装配。

2. **FUNCTION_CALLING 的 4 次模型调用预算在生产不生效**（B-11）
   - 文档：`agent-runtime.md` §9 "FUNCTION_CALLING 默认最多 4 次"、§13；`function-calling-migration.md` §5 "每回合最多 4 次模型调用，可分配为 2 轮查询、1 轮行动、1 轮修复或重写"。
   - 代码：`StartRoomGameApplicationService.java:226-242` 的 `withModel` 保留 `base.budget()`（`AgentConfig.java:109-125` → `TurnBudget.DEFAULT`=2，`TurnBudget.java:23`）；`LabBatchRunner.java:300` 同样。
   - 后果链：`AgentWorkflow.java:551-553`（查询额度）与 `:875`（提交被拒后的修复额度）。

3. **对局结束 / 座位接管的生命周期未接线**（B-03）
   - 文档：`agent-runtime.md` §3 表（`GAME_FINISHED/GAME_ABORTED → destroyAgent`、`PLAYER_CONTROLLER_CHANGED → destroyAgent`、真人掉线 → `createAgent + executeTurn`）；`AGENTS.md` §8。
   - 代码：`AIControllerAdapter.java:105-117` 两个方法零调用；`AgentRuntimeImpl.java:155-160` 不被触发。
   - 连带：`MemoryPort.clearShortTerm`（`InMemoryMemoryPort.java:86`）在 `main` 零调用，§7 规则 3/4 的"对局结束清理"缺失。

### 2.2 P2 级

4. **记忆链路的写入者与召回者都不存在**（B-08）：`agent-runtime.md` §7 三层记忆（短期 Redis Hash、长期 SQLite `agent_memory` 四类、`MemoryUpdater` 消费者、`GAME_FINISHED` 写 `REFLECTION`）；代码只有端口 + 内存实现 + 一次 `loadLongTerm`（`AgentWorkflow.java:421-430`）。留痕在 `TASKS.md:361,708-709`，但 `agent-runtime.md` §7 未标注"后续阶段"。

5. **`WorkflowMode.B`（两阶段：动作 0.3 + 发言 0.85）未实现**（B-22）：`strategy-layer.md` §6.1/§12 把它当成实验室对比能力；代码只有 `ContextAssembler.java:418` 的一句提示词，`decide` 恒用主温度。

6. **配置契约不成立**（B-17、B-42）：`agent-runtime.md` §13 十个键与 `tool-gateway.md` §11 六个键里，只有 `wolfmind.agent.max-concurrent-turns` 与 `wolfmind.agent.force-rule` 有读取路径；`ToolGatewayConfig.from`（`ToolGatewayConfig.java:58`）零调用；`AgentConfig.fallbackEnabled()` 恒 true（`AgentConfig.java:133-135`）。

7. **`agent-runtime.md` §10 的"连续失败计数 → 全局降级"与 §11 的跨对局并发上限缺失**（B-14、B-15）。

8. **"文档要求但从未被调用"的实现清单（死代码）**：
   - `BudgetGuard.shouldFallbackNow`（`BudgetGuard.java:74`）、`tryConsumeToolCall`（`:57`）——零调用者，`agent-runtime.md` §2/§9 的预算维度之一落空。
   - `ToolGatewayConfig.from`（`ToolGatewayConfig.java:58`）——零调用者。
   - `WolfMindStrategy.safeSpeech`（`WolfMindStrategy.java:146`）、`traceOnlyObservations`（`:155`）——生产零调用者；`strategy-layer.md` §8.2 "与候选集无关的额外承诺…记录到 Trace 供复盘"因此没有实现（`AgentWorkflow.safeRewrite:1050-1062` 用自带模板，不复用 `FallbackPlanner.safeSpeech:39-43`）。
   - `AgentViewQueryPort.isSeatInGame`（`AgentViewQueryPort.java:34`）——只有测试调用。
   - `GameDriver.forget`（`GameDriver.java:560-567`）——零调用者（含测试）；`agent-runtime.md` §4.1 "每局只保留最近一次派发标识，`forget(gameId)` 同时清理派发与诊断记录"在运行时没有发生。
   - `LlmCostGuard.forget`（`LlmCostGuard.java:74`）——零生产调用者，而类注释 `:73` 明确要求对局结束释放。
   - `InMemoryAgentTrace.clearGame`（`InMemoryAgentTrace.java:79`）——零调用者（注释写"对局结束归档后调用"）。
   - `Phase3AgentAssembly`（`create`/`createDefault`）——生产零调用者（唯一被测试引用的是 `defaultConfigFor`）。
   - `LlmConnectivityCheck`（`infrastructure/ai/LlmConnectivityCheck.java:24`）——只在 `NineAiLiveGame.java:540`（手工实况）里调用；而 `WolfMindConfiguration.java:64` 的注释宣称"真正的可达性由**启动自检**（`LlmConnectivityCheck`）回答"，生产启动路径没有这个自检。
   - `NotificationPort.onGameFinished` 的唯一实现 `InMemoryNotification.onGameFinished`（`InMemoryNotification.java:31`）——`main` 里既没有 Bean 注册也没有调用者。

9. **per-game 资源不释放**（B-03 的连带面）：`GameDriver` 的 8 张按 gameId 的 map（`GameDriver.java:61-98`）无清理入口调用；`LlmCostGuard.byGame`、`InMemoryAgentTrace.byKey`、`AgentRegistry` 同样只增不减。单局容量都有上限（轨迹每座位 2000 条），但**对局数**维度无上限。

### 2.3 P3 级（细节缺失）

10. 结构化拒绝缺 `traceId` 字段（B-38）；`TurnResult.errorCode` 缺 `BUDGET_TOKENS`/`NOT_SUBMITTED` 的文档登记（B-06）。
    - 另注：`DenialCodes.java:14-20` 自己记录了"四个工具码未登记进 api-spec.md §1.2"的文档缺口——属已留痕的契约缺口，与本次审计结论一致。
11. 上下文五区只实现两项预算占比（B-07）。
12. `scope` 未知取值 fail-open 降级为 SELF（B-33）。
13. `dispatch(configOf)` 生产恒为 `seat -> null`（B-45）。
14. `promptTemplateId` 从未进入任何轨迹 payload：`AgentConfig.describeForTrace()`（`AgentConfig.java:138-144`）只含 `agentConfigId/model/profile/mode`，而 `AGENTS.md` §8 要求决策分析存储"prompt/model versions"，`LabAgentConfig.java:22` 也自称"它被记进轨迹与统计"——实际只在实验室批次元数据里（`LabBatchRunner.java:299`）。

---

## 3. 代码存在但文档未记载

| # | 代码 | 证据 | 说明 |
| --- | --- | --- | --- |
| 3-1 | `LlmCostGuard` 单局 token 上限与 `BUDGET_TOKENS` 降级 | `LlmCostGuard.java:25-101`；装配 `WolfMindConfiguration.java:121-124`（属性 `wolfmind.llm.max-tokens-per-game`）；使用 `AgentWorkflow.java:493-499,548-549,512-514` | `agent-runtime.md` §9/§13 没有"单局 token 上限"这一预算维度，§5.2 的 errorCode 枚举也没有 `BUDGET_TOKENS`；迁移文档 §5 只提"token 用量累计到对局成本守卫"，没有上限语义。这是 Phase 5 成本控制能力（`AGENTS.md` §11 Phase 5），设计文档未回写 |
| 3-2 | `DefensiveActionMatrix`（策略层内置动作/工具白名单） | `DefensiveActionMatrix.java:23-93`；使用 `WolfMindStrategy.java:79,88` | `tool-gateway.md` §3 与 `strategy-layer.md` §7 约束1 要求"只有一份矩阵"，`RolePhaseMatrix.java:9-14` 也称自己是唯一一份；本类虽标注"只为防御、生产不生效"（生产必注入端口，`WolfMindConfiguration.java:251`），但它确实是第二份手写的角色×阶段表，且无测试锁定它与 `RolePhaseMatrix` 同构 |
| 3-3 | `SequentialWorkflowEngine` 成为事实上的生产引擎 | `AgentWorkflow.java:382-386`；见 B-02 | 文档把顺序引擎描述为"对照与后备"（`TASKS.md:362`），实际是生产执行路径 |
| 3-4 | `InfrastructureGameHarness`（测试脚手架）位于 `main` | `infrastructure/agent/wiring/InfrastructureGameHarness.java:50-69`；仅测试引用（`GameDriverTest.java:60` 等），`GatewayBoundaryTest.java:54` 用白名单放行 | 生产源码树里含测试装配器，且它构造内存存储与单写者锁；`main` 无调用者 |
| 3-5 | `MetricsPort.Names` 的 agent/llm 指标名 | `MetricsPort.java:101-102` 等；`AgentWorkflow.java:965-988` | `agent-runtime.md` §12 只列 `AGENT_` 轨迹事件，未记指标名；`WolfMindConfiguration.java:108-109` 把它们暴露到 `/metrics` |
| 3-6 | `ToolGatewayPort` 的原生工具方法（`toolSpecifications/prepareCall/executeRead`）默认实现返回 `TOOL_UNKNOWN` | `ToolGatewayPort.java:59-69` | 契约"同进程直调、跨进程换实现"（§10）已记载，但"默认实现即拒绝"这条兼容语义未写进文档 |
| 3-7 | `CandidateGenerator.supportedRoles()`、`AgentRuntimeImpl.traces/hasInstance/configOf` 等测试/运维 API | `CandidateGenerator.java:72-74`、`AgentRuntimeImpl.java:223-235` | 交付面超出文档接口清单（`agent-runtime.md` §2 组件表），无行为风险 |

---

## 4. 未判定项

| # | 未判定内容 | 为什么未判定 | 需要什么才能判定（建议测试） |
| --- | --- | --- | --- |
| 4-1 | `ThreatModel`/`SuspicionModel` 的因子权重与区间是否逐条符合 `strategy-layer.md` §4.1/§4.2（"神职嫌疑 0.35 / 信息量 0.20 / 影响力 0.20 / 存活轮次 0.10 / 击杀风险 0.15"等） | 本次只确认两个模型存在、纯函数、用 `TieBreak` 排序并有单测（`ThreatModelTest`/`SuspicionModelTest`），没有逐因子读实现比对权重 | 读 `ThreatModel.java`/`SuspicionModel.java` 全文并对照文档表格；或写"给定固定视图 → 断言各因子贡献值"的参数化测试 |
| 4-2 | `HunterRoleStrategy`/`VillagerRoleStrategy` 的候选集与硬约束是否逐条符合 §5（含"放弃开枪"、"弃权"合法） | 只读了狼/预言家/女巫三个策略与 `AbstractRoleStrategy` 的公共构造 | 读两个策略类 + 其单测断言（`CandidateGeneratorTest`） |
| 4-3 | `LeakGuard` 的实际漏判率（文档要求"宁可漏判也不误杀"，但漏判多少算可接受没给阈值） | 静态无法评估语义覆盖；现有 `LeakGuardTest` 只覆盖正反例 | 用历史真实对局的发言集合构造对抗语料，统计"出现在视图外的私有事实未被拦截"的比例；或引入人工标注样本 |
| 4-4 | 与真实模型端点（DeepSeek/OpenAI 兼容）的协议一致性：`additionalProperties` 省略、`reasoning_content` 续接、thinking 字段被接受 | 现有证据是本机 HTTP/SSE 替身（`LangChain4jFunctionCallingTest.java:24-110`）与文档声称的线路测试；未复验真实端点，也未复验"供应商若拒绝 thinking 字段则以 `BAD_REQUEST` 走兜底"（`agent-runtime.md` §13） | 对小规模真实调用跑一次连接自检（`LlmConnectivityCheck`）并断言 errorCode 分类；或在 CI 外手工记录一次真实往返 |
| 4-5 | "生产装配使用的是顺序引擎"对实际对局行为的影响（B-02 是否只是"引擎选择"而无行为差异） | 两条路径共用同一批节点处理器，`LangGraph4jWorkflowEngineTest` 只做了有限输入的逐项对比；没有"同一随机对局分别跑两种引擎、比较事件流与 Trace"的测试 | 新增端到端对照测试：同一 seed/同一替身模型，分别用两种引擎跑完整局，断言事件流、Trace 与提交次数一致 |
| 4-6 | 记忆缺口的"影响面"：跨局记忆为空是否已让实验室结论失真 | 只能确认写入链不存在（B-08），无法量化"有记忆 vs 无记忆"的胜率差 | 实现最小写入者（`REFLECTION`）后做 A/B 对照；或先断言当前 `loadLongTerm` 在所有生产路径恒为空 |
| 4-7 | `NIGHT_KILL` 定向披露在长局（>500 事件窗口）下的可用性 | `AgentViewAdapter.java:108-113,199-208` 的窗口是 `version-499..version`；若当夜 `WOLF_KILL_SELECTED` 落在窗口外，女巫拿不到刀口、`WitchRoleStrategy.java:81-84` 会直接不给解药候选 | 构造 >500 事件的对局片段，断言女巫仍能拿到当夜刀口；否则属于"解药规则在长局失效"的功能缺陷（不是泄漏） |
| 4-8 | 隐藏信息保护的最终结论（是否有跨座位泄漏） | 我的结论是"静态未发现确认泄漏"，未做真实模型对局的渗透测试 | 建议的确认测试：①任意非女巫座位的 `AgentView` 断言无 `NIGHT_KILL`（已有，`AgentViewAdapterTest.java:35-94`）；②村民 `QueryHistoryTool` 全区间查询断言返回集合无 `WOLF_KILL_*`/`WOLF_CHAT_SENT`/`SEER_INSPECTED`（`ToolGatewayReadTest.java:120-160` 已有近似覆盖，可显式加断言）；③`QueryGameStateTool` 三 scope 对每个角色的字段级断言；④对进行中的对局断言 `/analysis` 不返回轨迹（`AnalysisQueryService.java:134-137` 已有 `isTerminal` 门，可加契约测试） |
