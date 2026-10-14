# WolfMind 详细设计：Tool Gateway

本文细化 `DESIGN.md` §12（AI Tool Gateway）。这一层是"AI 不直接操作 Game Domain"
这条约束的落地点：它是 Agent 系统与 Game 限界上下文之间的防腐层（ACL），
也是唯一的写路径。

## 1. 错误与正确路径的对照

`DESIGN.md` §12 给出的反例与正例在本设计中就是硬性依赖规则：

| 路径 | 状态 |
| --- | --- |
| `LLM → GameService → Database` | 禁止。工具网关不暴露 `GameRepository`、`EventStorePort`、Redis/SQLite 句柄，Agent 侧代码也没有这些类型的编译期引用（DEP-7） |
| `LLM → ToolGateway → PermissionCheck → GameAction → GameEngine` | 唯一允许路径，本文的整条流水线 |

```
LLM（只选择工具与参数）
  │  原生调用请求（LangChain4j 底层 API，只声明、不自动执行）
  ▼
AgentWorkflow（只读循环；行动提案先校验表达与候选）
  ▼
ToolGateway
  ├─ 身份绑定（gameId / playerId 由 Runtime 注入，LLM 不可指定）
  ├─ 参数 Schema 校验 + 白名单
  ├─ PermissionCheck（角色 × 阶段 × 回合 × 次数 × 目标 × 资源）
  ├─ 幂等与并发（Idempotency-Key + expectedVersion）
  ▼
ToolInvocationApplicationService
  ▼
SubmitActionApplicationService
  ▼
Game.handle(action, ctx)      ← 规则判定仍在这里
  ▼
DomainEvent → Redis Stream    ← 状态变化的唯一记录
  ▼
ToolGateway 把事件结果映射回工具返回值，写 Trace
```

一个必须说清楚的语义：工具返回的"成功"只表示**命令被领域接受且事件已写入**，
最终状态以事件为准。工具永远不会直接修改状态。

## 2. 工具目录

工具清单与 `DESIGN.md` §12 完全一致，未新增工具。差异化的能力通过工具参数表达
（例如女巫的三种用药选择都在 `UseSkillTool` 内），这样工具数量与提示词长度都可控。

| 工具 | 类别 | 参数 | 映射的领域 Action | 产生的事件 |
| --- | --- | --- | --- | --- |
| `QueryGameStateTool` | 只读 | `{ scope: SELF \| PUBLIC \| MY_CAMP }` | 无 | 无 |
| `QueryHistoryTool` | 只读 | `{ fromVersion?, toVersion?, limit ≤ 100 }` | 无 | 无 |
| `SpeakTool` | 写 | `{ content }` 或 `{ skip: true, reason }` | `SpeechAction` / `SkipAction` | `PLAYER_SPOKE` / `SPEECH_SKIPPED` |
| `SubmitVoteTool` | 写 | `{ targetId }` 或 `{ abstain: true }` | `VoteAction` | `PLAYER_VOTED` |
| `KillPlayerTool` | 写 | `{ targetId }` 或 `{ empty: true }` | `WolfKillAction` | `WOLF_KILL_NOMINATED`（定案时 `WOLF_KILL_SELECTED`） |
| `UseSkillTool` | 写 | `{ skill, targetId? }` | 见 §2.2 | 见 §2.2 |

### 2.0 原生调用接入（2026-10-13）

ToolGatewayPort.toolSpecifications 通过 ModelToolCodec 从 ToolCatalog / RolePhaseMatrix 导出声明，
技能枚举按角色和阶段裁剪。模型声明不暴露行动者身份、版本或 commandId。
行动参数增加可选 confidence（0..1）、reasonCode（最多 100 字符）和既有 notes；
这些字段仅用于提案分析，不影响规则与授权。

prepareCall 验证原始 JSON、完整字段与服务器会话，返回 PreparedCall（提案、归一化 JSON 或拒绝），
此步骤无行动写入。executeRead 仅路由两个只读工具，按授权视图/事件返回 ModelReadResult。
行动最终通过 submit 进入既有 G1..G13 和应用服务。原生入口始终拒绝未知参数，
不因旧模式 denyUnknownArgs 的兼容配置放宽；参数必须是一个完整对象，不得有重复字段。

Agent 的主动跳过必须遵守实际对局规则与每阶段提交次数，投票后不能用 SKIP 覆盖原票，
禁止弃权时主动跳过投票被拒绝。TIMEOUT 是服务端超时看护的保留输入，应用命令入口拒绝模型/客户端冒用；
系统到期只补未提交者，不给模型新增超时权限或改写已提交的行动。

模型端不使用 Beta strict。LangChain4j 1.20.0 的非 strict OpenAI 序列化会省略
additionalProperties；参数范围、互斥与额外字段限制由服务器保证，不以模型端 schema 作为安全边界。
模型响应正文没有行动执行语义。

ToolSession 可携带 expectedRound；Runtime 构造的会话包含原轮次。
原阶段/轮次改变后拒绝旧行动，版本刷新不替换其身份、阶段、轮次或幂等键。
成功写入后审计失败仅记告警，仍返回已提交结果。

### 2.1 只读工具

`QueryGameStateTool` 返回角色可见的 `GameView`，与 `GET /games/{gameId}` 使用同一个
投影与同一份可见性判定（[event-design.md §4](../architecture/event-design.md)）：

| `scope` | 返回内容 |
| --- | --- |
| `SELF` | 自己的座位、角色、`skillState`、自己的私有事件摘要 |
| `PUBLIC` | 公开信息：阶段、轮次、存活、公开发言与投票、已公布死亡 |
| `MY_CAMP` | 在 `PUBLIC` 基础上加本阵营可见信息（狼人可见队友与狼刀） |

`QueryHistoryTool` 返回事件列表，按 `visibility` 过滤后分页，`limit` 上限 100。
两个只读工具都不产生事件，可以在一回合内多次调用（受预算约束）。

三条实现期明确的语义（原表未区分，按"最小惊讶"补齐）：

1. **只读工具不受 G6 回合门约束**：它们不产生事件、不改变状态，"等回合时先看一眼"不应被判非法。
   G6 只对写动作生效。
2. **scope 是单调包含**：`PUBLIC ⊆ MY_CAMP ⊆ SELF`。否则模型要拼出完整视角必须在一个回合里调用三次，
   既浪费预算又让 prompt 不稳定。`PUBLIC` / `MY_CAMP` 的 `me.role` 为 `null`，只有 `SELF` 带角色、
   技能与私有事实摘要。
3. **`MY_CAMP` 的"狼刀"经 `QueryHistoryTool` 取得**：`WOLF_KILL_NOMINATED` / `WOLF_KILL_SELECTED`
   本身就是 `WOLF` 可见性，按可见性过滤即可读到；`AgentView.PrivateFact` 的封闭词表里没有"狼刀"
   这一类（它只放本人技能结果与队友），因此不重复表达同一事实。

### 2.1.1 女巫当夜刀口：一处**定向披露**，不是可见性漏洞

`WITCH_SAVE` 的目标约束是"解药只能救当夜被狼刀者"（§2.2 与
[domain-model.md §6.1](../backend/domain-model.md) V7）。而 `WOLF_KILL_SELECTED` 是
`visibility = WOLF`（[event-design.md §4](../architecture/event-design.md)），女巫属于村民阵营，
按可见性矩阵本来看不到这条事件——**如果严格按矩阵执行，女巫永远无法正确使用解药**。

因此 Agent 侧的视图装配对女巫座位补一条**当夜的刀口事实**（`AgentView.PrivateFact` 的
`NIGHT_KILL`），除此之外不做任何额外披露。三条边界：

| 约束 | 说明 |
| --- | --- |
| 只给女巫 | 预言家、村民、猎人都拿不到 `NIGHT_KILL`；断言"村民视图不含 `NIGHT_KILL`"是测试必查项 |
| 只给当夜 | 只披露当前轮 `WOLF_KILL_SELECTED` 的定案目标，不披露提名过程、历史刀口或狼队身份 |
| 不进事件流 | 这是**装配期的定向披露**，不新增事件、不放宽 `WOLF_KILL_SELECTED` 的可见性；事件流与回放仍按矩阵过滤 |

这条豁免记录在此，是为了避免后来者把"女巫知道刀口"误认为过滤实现有 bug 而"修掉"它——
修掉它会让女巫的解药规则无法执行。若将来改变这条规则（例如女巫改为盲救），应同时修改本表、
§2.2 的 `WITCH_SAVE` 约束与 `GameRules.witchSaveTargetMustBeKilled`。

### 2.2 `UseSkillTool` 的技能矩阵

| `skill` | 允许角色 | 允许阶段 | 次数 | 目标约束 | 映射 Action | 事件 |
| --- | --- | --- | --- | --- | --- | --- |
| `SEER_INSPECT` | `SEER` | `NIGHT_SEER` | 每夜 1 次 | 存活、非自身 | `SeerInspectAction` | `SEER_INSPECTED` |
| `SEER_SKIP` | `SEER` | `NIGHT_SEER` | 每夜 1 次 | 无 | `SkipAction` | `SEER_SKIPPED` |
| `WITCH_SAVE` | `WITCH` | `NIGHT_WITCH` | 全局 1 次 | 当夜被刀者；自身仅在 `witchSelfSave=true` 且（`witchSelfSaveFirstNightOnly=false` 或 `round<=1`）时允许 | `WitchAction{SAVE}` | `WITCH_SAVED` |
| `WITCH_POISON` | `WITCH` | `NIGHT_WITCH` | 全局 1 次 | 存活、非自身 | `WitchAction{POISON}` | `WITCH_POISONED` |
| `WITCH_PASS` | `WITCH` | `NIGHT_WITCH` | 每夜 1 次 | 无 | `WitchAction{PASS}` | `WITCH_PASSED` |
| `HUNTER_SHOOT` | `HUNTER` | `HUNTER_SHOOT` | 全局 1 次 | 存活、非自身 | `HunterShotAction` | `HUNTER_SHOT` |
| `HUNTER_GIVE_UP` | `HUNTER` | `HUNTER_SHOOT` | 全局 1 次 | 无 | `SkipAction` | `HUNTER_SKIPPED` |

`WITCH_SAVE` 与 `WITCH_POISON` 的互斥由 INV-7 保证：同一夜已使用任一药时，
另一个请求被拒绝，且不产生事件。

## 3. 权限矩阵

工具的可用性由"角色 × 阶段"决定，这张表同时是 `availableTools` 的生成依据
（进 prompt 的白名单）和网关校验的依据（同一份约束定义生成两份视图）：

| 工具 | `WEREWOLF` | `SEER` | `WITCH` | `HUNTER` | `VILLAGER` | 观战者 |
| --- | --- | --- | --- | --- | --- | --- |
| `QueryGameStateTool` | 是 | 是 | 是 | 是 | 是 | 否 |
| `QueryHistoryTool` | 是 | 是 | 是 | 是 | 是 | 否 |
| `SpeakTool` | `DAY_DISCUSS`（轮到自己）、`LAST_WORDS` | 同左 | 同左 | 同左 | 同左 | 否 |
| `SubmitVoteTool` | `DAY_VOTE` | 同左 | 同左 | 同左 | 同左 | 否 |
| `KillPlayerTool` | `NIGHT_WOLF` | 否 | 否 | 否 | 否 | 否 |
| `UseSkillTool` | 否 | `NIGHT_SEER` | `NIGHT_WITCH` | `HUNTER_SHOOT` | 否 | 否 |

观战者没有工具。原因：观战者是对局之外的观察者，不具备行动资格；
AI 分析页与实验室读取归档数据，不需要对局内工具。

## 4. 校验流水线

`PermissionChecker` 按固定顺序执行，任一步失败立即返回结构化拒绝，且**不产生任何事件**：

| 步 | 校验 | 失败码 |
| --- | --- | --- |
| G1 | 工具名在注册表白名单内 | `TOOL_UNKNOWN` |
| G2 | 参数 Schema：类型、枚举、范围、长度、未知字段拒绝 | `TOOL_ARGS_INVALID` |
| G3 | 身份绑定：`(gameId, playerId)` 由 Runtime 注入并与工具会话一致 | `TOOL_IDENTITY_MISMATCH` |
| G4 | 对局状态为 `RUNNING` | `GAME_ALREADY_FINISHED` |
| G5 | 阶段允许该工具 | `ACTION_NOT_ALLOWED` |
| G6 | 需要回合的阶段：`currentTurn.playerId == playerId` | `TURN_NOT_YOURS` |
| G7 | 角色具备该能力（§3 矩阵） | `ACTION_NOT_ALLOWED` |
| G8 | 次数门：本阶段/本夜/本局的提交次数未超限 | `ACTION_NOT_ALLOWED` 或幂等返回 |
| G9 | 目标门：存活、非自身、非队友、在候选集内 | `ACTION_INVALID_TARGET` |
| G10 | 资源门：`skillState` 与规则参数允许 | `ACTION_NOT_ALLOWED` |
| G11 | 回合预算：工具调用次数与时长 | `BUDGET_EXCEEDED` |
| G12 | 幂等门：同一 `idempotencyKey` 已提交 | 返回首次结果 |
| G13 | 提交：经应用层写入事件 | 版本冲突 → 刷新重试一次 |

G3 是最关键的一步：`gameId` 与 `playerId` **不由 LLM 提供**，而是 Runtime 在构造
工具会话时注入。模型即使在自己的输出里写了 `playerId`，也会被忽略或被判定为
身份不匹配。这条设计让"AI 操作别的座位"在协议层就不可能发生。

G9 的"在候选集内"是策略层与网关的双重校验：策略层保证 LLM 只在候选集里挑，
网关保证即使 LLM 越界也不会执行。

## 5. 拒绝语义

拒绝不是异常，而是结构化结果，供策略层下一轮修正：

```json
{
  "ok": false,
  "code": "ACTION_INVALID_TARGET",
  "message": "目标座位 7 已死亡",
  "tool": "SubmitVoteTool",
  "retryable": false,
  "allowedAlternatives": {
    "actionType": "SubmitVoteTool",
    "candidates": [4, 5, 6],
    "abstainAllowed": true
  },
  "traceId": "cor_5b21e0"
}
```

| 字段 | 用途 |
| --- | --- |
| `code` | 与 REST 错误码同一套枚举，前后端与 AI 使用同一份语义。`TOOL_UNKNOWN` / `TOOL_ARGS_INVALID` / `TOOL_IDENTITY_MISMATCH` / `BUDGET_EXCEEDED` 四个码只在工具路径出现，已登记在 [api-spec.md §1.3](../backend/api-spec.md) |
| `retryable` | `true` 表示可以原样重试（`GAME_BUSY`、`VERSION_CONFLICT`）；`false` 表示必须换动作或换目标 |
| `allowedAlternatives` | 网关从**策略层候选集**裁剪出的"现在还能做什么"。实现上由"同一份权限矩阵 + 同一份视图"派生（网关看不到策略层内部候选集），语义等价且不会出现"策略层允许、网关拒绝"的矛盾；直接拼进 `repair` 节点的 prompt |
| `traceId` | 与事件 `correlationId` 一致，可回溯 |

策略层收到拒绝后最多修复一次（`agent.maxRepairRounds`），仍失败走
[strategy-layer.md §9](strategy-layer.md) 的兜底。

## 6. 幂等与并发

| 机制 | 说明 |
| --- | --- |
| `idempotencyKey` | 事件调度使用 `task:{taskId}`，同一窗口不同写工具共享提交身份；旧手动/测试会话仍用 `tool:{gameId}:{version}:{playerId}:{toolName}` |
| 幂等存储 | `idem:{gameId}:{key}`，保留 10 分钟，命中直接返回首次结果 |
| `expectedVersion` | 由网关从投影读取后填入，不由 LLM 提供；提交前读取一次，冲突时刷新后重试 |
| 重试上限 | 版本冲突与 `GAME_BUSY` 共用 `gateway.versionRetry`（默认 2，即最多 3 次尝试，与客户端 `GAME_BUSY` 上限一致）；`GAME_BUSY` 先退避 25 ms × 次数；用尽返回 `retryable: true`，由 Runtime 决定是否降级 |
| 并发 | 单写者锁 `game:{id}:lock` 在应用层生效。网关写路径另有进程内按对局分段的提交锁，只包住“刷新版本 + 提交”，不含模型调用：`DAY_VOTE` 并发派发时，瞬时返回的兜底动作若各自乐观提交会互相撞版本直到重试用尽 |

事件调度的窗口身份与聚合版本分离。ToolSession 带服务端任务凭证，经写路径传入
SubmitActionApplicationService；在游戏写锁内核验租约、控制者代次、窗口与截止，
模型/客户端不能生成凭证。任务的首次 dispatchVersion 不因夜聊、投票或重试改变。
模型和工具调用尝试先写任务预算账本；进程中断不自动重跑付费模型，转等待截止。
具体任务与确认消费契约见 agent-runtime.md §4、redis-schema.md §16。

## 7. 审计与 Trace

每次工具调用写一条 `AGENT_TOOL_CALLED` 决策事件，归档到 SQLite `agent_memory`
（`memory_type = 'DECISION_TRACE'`）：

| 字段 | 说明 |
| --- | --- |
| `tool` | 工具名 |
| `argsDigest` | 参数的摘要（目标、技能名、发言长度），不存发言全文 |
| `decision` | `ALLOWED` / `DENIED` |
| `denialCode` | 拒绝码 |
| `resultingEventId` / `version` | 成功时的领域事件与版本 |
| `latencyMs` | 从调用到返回 |
| `phase` / `round` / `turnIndex` | 回合定位 |
| `traceId` | 关联 `correlationId` |

只读工具同样记录，因为"AI 看到了什么"是分析策略质量的关键输入。
审计记录不写入 `game:{id}:events`，不改变对局状态（C-4、C-5）。

## 8. 安全

| 面 | 措施 |
| --- | --- |
| 身份伪造 | G3：身份由 Runtime 注入，LLM 无法指定座位或对局 |
| 越权动作 | G7 + G5 + §3 矩阵；村民不可能提交狼刀 |
| 参数注入 | G2：白名单枚举、整数范围、字符串长度上限、未知字段直接拒绝 |
| 任意状态写入 | 网关不暴露"写事件"工具；不提供批量修改、不提供 SQL/Redis 句柄 |
| 目标越界 | G9：存活、非自身、非队友、候选集内四重约束 |
| 资源滥用 | G8 + G11：次数与预算；超限进入兜底而不是无限重试 |
| 提示注入 | 玩家发言作为数据进入上下文，不作为指令；工具参数不接受携带指令的结构；`SYSTEM` 分区不含玩家文本 |
| 隐私泄漏 | 只读工具按可见性过滤；审计只存参数摘要；日志不记录私有 payload |
| 观测作弊 | 工具不做"上帝视角"旁路：不存在 `scope: GOD` |
| 重放攻击 | 幂等键 + 版本绑定；旧回合的调用在新回合因版本不匹配而失效 |

## 9. 与 Application Layer 的分工

| 关注点 | ToolGateway | Application Layer |
| --- | --- | --- |
| 工具名与参数校验 | 是 | 否 |
| 身份、阶段、回合、角色、次数、目标、资源校验 | 是（AI 侧前置门） | 是（领域不变量最终判定） |
| 游戏规则（票数、结算、胜负） | 否 | 领域 |
| 事件写入与发布 | 否 | 是 |
| 幂等键管理 | 是 | 是（同一份存储，两层各自校验） |
| 审计与 Trace | 是 | 否（只记事务日志） |

网关**不含规则**：它只判断"这个工具在这个阶段对这个角色是否开放"，
不判断"这一票是否算数"。后者的唯一实现位置是 `Game.handle` 内的 `ActionValidator`
与各 Policy（[domain-model.md §6](../backend/domain-model.md)）。

## 10. 部署形态

| 阶段 | 形态 | 说明 |
| --- | --- | --- |
| Phase 3、4 | 同进程直接调用 | `ToolGatewayPort` 的本地实现，仍然经过完整校验流水线 |
| Phase 5 | 可跨进程 | 换成"消费组 + 调用通道"的实现；工具契约、拒绝语义、幂等规则不变 |

跨进程时唯一变化是 `ToolGatewayPort` 的实现，Agent 侧与领域侧代码都不需要改。
这也是把网关定义为端口而不是工具类的原因。

## 11. 配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `gateway.maxToolCallsPerTurn` | 6 | 与 Agent 预算一致 |
| `gateway.queryHistory.maxLimit` | 100 | 历史查询上限 |
| `gateway.idempotency.ttlMinutes` | 10 | 幂等键保留 |
| `gateway.versionRetry` | 2 | 版本冲突与 `GAME_BUSY` 的重试次数 |
| `gateway.audit.enabled` | true | 审计开关 |
| `gateway.denyUnknownArgs` | true | 未知参数直接拒绝 |

## 12. 测试清单

| 测试 | 覆盖 |
| --- | --- |
| 权限矩阵 | §3 矩阵逐格覆盖：允许的组合成功，禁止的组合返回指定码且零事件 |
| 身份伪造 | 模型输出里带 `playerId`/`gameId` 时被忽略；不匹配时返回 `TOOL_IDENTITY_MISMATCH` |
| 参数注入 | 非法枚举、超范围整数、超长发言、未知字段全部 `TOOL_ARGS_INVALID` |
| 目标约束 | 死亡目标、自身目标、狼刀队友全部拒绝（INV-6、INV-9） |
| 阶段与回合 | 非当前回合发言返回 `TURN_NOT_YOURS`；阶段不符返回 `ACTION_NOT_ALLOWED` |
| 次数 | 女巫同夜双药第二次被拒（INV-7）；狼人重复提名返回幂等结果（INV-5） |
| 幂等 | 同一 `idempotencyKey` 重复调用返回首次结果，事件数不增加 |
| 可见性 | `QueryGameStateTool` 的各 `scope` 不含越权信息；不存在 `GOD` scope |
| 只读无副作用 | 只读工具调用前后事件流长度不变 |
| 拒绝无副作用 | 所有拒绝路径下 `game:{id}:events` 长度不变 |
| 审计 | 每次调用产生一条 Trace，字段完整且不含发言全文 |
| 预算 | 超过工具调用上限返回 `BUDGET_EXCEEDED` |

## 13. 禁止事项

| 禁止 | 原因 |
| --- | --- |
| 向 Agent 暴露 `GameRepository`、`EventStorePort`、SQL/Redis 客户端 | 绕过规则与事件化（C-4、C-5） |
| 提供"写任意事件"或"批量修改状态"的工具 | 同上 |
| 接受 LLM 指定的 `gameId` / `playerId` | 身份伪造 |
| 提供 `GOD` 视图或 `JUDGE` 事件查询 | 作弊，实验结论失效 |
| 在网关内实现规则判定（票数、结算、胜负） | 规则只能有一份实现，位于领域 |
| 在网关内持有 `game:{id}:lock` | 把 LLM 延迟带进锁内，阻塞真人与其他 AI |
| 使用 `SELECT`/命令名之类的字符串拼接构造调用 | 参数注入面 |
| 把拒绝原因写成自然语言提示塞回 prompt 而不给结构化候选 | 模型无法可靠修正，会反复触发同一拒绝 |
