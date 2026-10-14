# WolfMind 详细设计：事件设计

本文细化 `DESIGN.md` §6（游戏事件驱动架构）、§7（Event Store）、§8（Snapshot）。
第 4 条约束"所有状态变化必须事件化"在本文给出可执行的契约。

## 1. 事件体系与三类事件

| 类别 | 产生方 | 存储 | 能否改变对局状态 | 用途 |
| --- | --- | --- | --- | --- |
| 领域事件 Domain Event | 聚合（`Game`、`Room`、`User`） | Redis Stream `game:{id}:events` 等 + SQLite `game_events` 归档 | 是，唯一途径 | 状态真相、恢复、回放、投影 |
| 集成事件 Integration Event | `EventPublisherPort` 发布领域事件时的对外形态 | 与领域事件同一条记录 | 否（只是同一事实的对外表述） | 跨限界上下文通知：房间、洞察、Agent |
| Agent 决策事件 Agent Trace Event | Agent Runtime | Redis Stream `game:{id}:agent:{playerId}` + SQLite `agent_memory` | 否，永不 | AI 分析页、实验室、调试 |

关键结论（C-4、C-5）：

1. 只有领域事件能改变状态，且改变状态的唯一路径是"领域事件被重放"（INV-13）。
2. 集成事件不引入独立的事件总线组件：`DESIGN.md` §6 的 Event Bus 就是 Redis Stream，
   发布与消费都通过消费组完成。不新增消息中间件（C-2）。
3. Agent 决策事件与领域事件使用**不同的键空间和不同的类型前缀**，从存储层面
   保证 AI 的推理痕迹无法写入对局事件流。

## 2. 事件信封

`DESIGN.md` §7 给出的最小结构为 `{eventId, gameId, version, type, payload}`。
详细设计在保留这四个必填字段的前提下，补齐重放与可见性所必需的字段：

| 字段 | 必填 | 来源 | 说明 |
| --- | --- | --- | --- |
| `eventId` | 是（DESIGN.md） | 应用层 | 全局唯一，推荐"时间前缀 + 序列"形式，不使用随机 UUID 以保持可排序 |
| `gameId` | 是（DESIGN.md） | 命令 | 流键，事件流的唯一分区依据 |
| `version` | 是（DESIGN.md） | 聚合 | 同一 `gameId` 内从 1 开始严格 +1，等于已产生事件数 |
| `type` | 是（DESIGN.md） | 领域 | 大写下划线，见 §7 目录 |
| `payload` | 是（DESIGN.md） | 领域 | 事件专属数据，见 §7 目录，字段名与统一语言一致 |
| `schemaVersion` | 是（补充） | 领域 | 该 `type` 的载荷版本，初始为 1，见 §6 |
| `occurredAt` | 是（补充） | 应用层 | 毫秒时间戳，由 `ActionContext` 注入，重放不重新取值 |
| `phase` | 是（补充） | 聚合 | 事件发生时的 `GamePhase`，用于回放时间线分组 |
| `round` | 是（补充） | 聚合 | 第几轮，`PHASE_CHANGED` 之外的事件也带，便于按轮归组 |
| `actorId` | 是（补充） | 应用层 | 触发者：`userId`（真人）/ `playerId`（AI、Bot）/ `SYSTEM` |
| `visibility` | 是（补充） | 领域 | `PUBLIC` / `WOLF` / `PRIVATE` / `JUDGE`，见 §4 |
| `causationId` | 是（补充） | 应用层 | 直接触发本次事件的命令 id 或上游 `eventId` |
| `correlationId` | 是（补充） | 应用层 | 一次完整用例的关联 id，跨事件追溯 |

示例（对应 `DESIGN.md` §7 的投票例子）：

```json
{
  "eventId": "01H8X2-000123",
  "gameId": "g_20240501_0007",
  "version": 123,
  "type": "PLAYER_VOTED",
  "schemaVersion": 1,
  "occurredAt": 1730000000123,
  "phase": "DAY_VOTE",
  "round": 2,
  "actorId": "u_8812",
  "visibility": "PUBLIC",
  "causationId": "cmd_7f3a91",
  "correlationId": "cor_5b21e0",
  "payload": { "playerId": "p_3", "seatNo": 4, "targetId": "p_5", "targetSeatNo": 6 }
}
```

完整性规则：信封字段的缺失由写入前的校验拦截，不允许"默认值兜底"，否则重放会
因字段歧义产生不同状态。

## 3. Redis Stream 存储映射

### 3.1 流键与条目 ID

```
game:{id}:events          Stream，事件流（真相来源）
room:{id}:events          Stream，房间事件流
user:{id}:events          Stream，账号事件流
game:{id}:agent:{pid}     Stream，Agent 决策事件（非领域事件）
```

条目 ID 采用显式版本号：`XADD game:{id}:events {version}-0 ...`。

选择显式 ID 而不是 `*` 的两个原因：

1. Redis 会拒绝小于等于当前最大 ID 的写入。应用层用 `expectedVersion + 1` 作为
   ID 追加，Redis 自身就成为并发冲突的仲裁者，不需要额外的版本比对往返。
2. 事件流的物理顺序与聚合版本一一对应，`XRANGE game:{id}:events (50-0 +` 就是
   "重放 50 之后的事件"，快照恢复直接可用。

字段映射：`eventId`、`gameId`、`version`、`type`、`schemaVersion`、`occurredAt`、
`phase`、`round`、`actorId`、`visibility`、`causationId`、`correlationId` 各占一个
Stream field，`payload` 序列化为单字段 JSON 字符串。`gameId` 同时冗余在条目里，
使单个条目自解释，便于归档与 DLQ 搬运。

### 3.2 时间戳单调性

显式 ID 使用版本号，`occurredAt` 仅作字段，因此时钟回拨不会破坏流顺序。但投影与
回放按 `occurredAt` 排序时需要单调性，规则为：在单写者锁内取
`occurredAt = max(now, game:{id}:lastTs)`，并把结果写回 `game:{id}:lastTs`。
时间戳只影响展示与超时判定，不影响状态推导。

### 3.3 写入协议

```
1. GET  game:{id}:lock  (SET NX PX 3000)        单写者
2. 校验 XREVRANGE game:{id}:events COUNT 1 的 version == expectedVersion
3. XADD 事件 1..n（逐条，ID = version-0）
4. 更新 game:{id}:lastTs
5. 若 version % 50 == 0 或阶段为 GAME_OVER → 生成快照（§8）
6. 释放锁
```

第 3 步是原子追加的边界：允许一条命令产生多个事件（例如开局产生
`GAME_STARTED` + 9 条 `ROLE_ASSIGNED` + `PHASE_CHANGED`），此时全部事件
必须连续写入；中途失败时以脚本或事务化的批量追加保证要么全成、要么回到
`expectedVersion`，不允许出现版本空洞。

## 4. 可见性与信息不对称

狼人杀的核心难度来自信息不对称，因此可见性必须在事件层表达，而不是在推送层
临时判断。否则回放、AI 分析、观战都会泄漏隐藏信息。

| `visibility` | 含义 | 典型事件 |
| --- | --- | --- |
| `PUBLIC` | 所有订阅者可见 | `PHASE_CHANGED`、`PLAYER_SPOKE`、`PLAYER_VOTED` |
| `WOLF` | 仅狼人阵营可见 | `WOLF_KILL_NOMINATED`、`WOLF_KILL_SELECTED`、`WOLF_CHAT_SENT` |
| `PRIVATE` | 仅 `payload.playerId` 指定的玩家可见 | `ROLE_ASSIGNED`、`SEER_INSPECTED`、`WITCH_SAVED` |
| `JUDGE` | 系统与赛后回放可见 | `PLAYER_KILLED`、`GAME_RECOVERED` |

可见性矩阵：

| 读取者 | PUBLIC | WOLF | PRIVATE | JUDGE |
| --- | --- | --- | --- | --- |
| 参与对局的玩家 | 可见 | 仅狼人阵营 | 仅本人 | 不可见 |
| 观战者（对局进行中，未参与本局） | 可见 | 不可见 | 不可见 | 不可见 |
| 赛后回放（包括公开只读查询） | 可见 | 可见 | 可见 | 可见 |
| AI Agent | 可见 | 仅当该 Agent 是狼人 | 仅当该 Agent 是本人 | 不可见 |

两条硬规则：

1. **AI 的可见性与它扮演的玩家完全一致**，不允许上帝视角。给 AI 更多信息就等于
   作弊，也会让实验结果失去意义（C-5）。
2. `visibility` 是事件的属性，判定逻辑只有一份（`VisibilityFilter`），WebSocket 推送、
   `QueryGameStateTool`、`QueryHistoryTool`、回放接口全部复用它。任何地方出现
   "另外写一份过滤逻辑"都是缺陷。

死亡信息的表达是可见性设计的典型用例：死亡在夜间结算时就已经是领域事实
（`PLAYER_KILLED`，`JUDGE`），但天亮公布是另一件事（`DEATH_ANNOUNCED`，`PUBLIC`）。
死因默认不公布：`DEATH_ANNOUNCED` 只带 `playerId` 与 `round`，被放逐与被枪击本身
已经是公开事件。这样"昨夜谁死了"与"为什么死"在事件层就是两件事，天然防泄漏。

## 5. 顺序、分区与因果

| 维度 | 保证 | 说明 |
| --- | --- | --- |
| 同一 `gameId` 内 | 全序 | 单写者锁 + 单调 `version` + Stream 物理顺序 |
| 跨 `gameId` | 无序 | 无共享状态，不要求顺序 |
| 跨聚合（Room → Game） | 无全序，靠因果字段 | `StartGameCommand` 的 `correlationId` 串联 `PLAYER_READY` 与 `GAME_STARTED` |
| 消费端 | 每组内有序 | 同一消费组按 Stream 顺序处理；不同组之间无顺序约束 |
| 并发行动 | 领域内排序 | 同一回合窗口内的并发提交由单写者锁串行化，先到者胜 |

推论：任何消费者都不得假设"看到了 `PLAYER_VOTED` 就一定已经看到 `PHASE_CHANGED`"，
除非两者在同一分区且位点已推进。需要跨事件判断时，以 `version` 为准做条件等待。

## 6. 契约演进

`DESIGN.md` 未规定演进规则，这里固定三条，避免后期改事件结构时破坏回放（INV-14）。

| 场景 | 规则 |
| --- | --- |
| 新增可选字段 | 直接加入 `payload`，`schemaVersion` 不变。消费者必须容忍未知字段 |
| 新增必填字段 | `schemaVersion` +1，并在 upcaster 中为旧版本补默认值 |
| 字段语义变更或删除 | 禁止。改为新增事件类型（`PLAYER_VOTED_V2`），旧类型保留至其事件全部过期 |
| 事件类型重命名 | 禁止直接改名。用别名表把旧 `type` 映射到新类型，别名表随代码版本发布 |
| 事件类重命名（Java） | 允许，只要 `type` 字符串不变；`type` 是契约，类名是实现细节 |

upcaster 在读取路径执行（`EventStorePort.read` 返回前），写入路径永远写最新版本。
upcaster 必须是纯函数，且不得访问外部数据；有外部依赖的补数据场景改为新增事件，
不改写历史。

## 7. 事件目录

### 7.1 Room 聚合（`room:{id}:events`）

| type | 触发 | 可见性 | payload |
| --- | --- | --- | --- |
| `ROOM_CREATED` | 房主创建房间 | `PUBLIC` | `roomId, name, config, ownerId` |
| `PLAYER_SEATED` | 玩家/AI 入座 | `PUBLIC` | `roomId, playerId, seatNo, controllerType` |
| `PLAYER_LEFT` | 离开房间 | `PUBLIC` | `roomId, playerId, seatNo` |
| `PLAYER_READY` | 标记就绪 | `PUBLIC` | `roomId, playerId` |
| `PLAYER_CONTROLLER_CHANGED` | AI 接管 / 补位 / 真人主动退出 | `PUBLIC` | `roomId, seatNo, fromType, fromRefId, toType, refId` |
| `ROOM_CLOSED` | 房间关闭 | `PUBLIC` | `roomId, reason` |

### 7.2 Game 聚合（`game:{id}:events`）

`PLAYER_CONTROLLER_CHANGED` 同名事件由流分区区分：Room 流 payload 带 roomId、不带 playerId；Game 流带 playerId。
Room 控制者事件只注册 cg-broadcast，禁止送入 Game 调度/归档/统计组。运行房退出标记 `refId=departed:{原用户ID}`，
Game 用例据此幂等补做接管；最后真人退出提交 `GAME_ABORTED(reason=NO_HUMANS)`，不产生胜利事实。
终局关闭与失败补偿见 [房间生命周期](../backend/room-lifecycle.md)。

| type | 触发 | 可见性 | payload |
| --- | --- | --- | --- |
| `GAME_STARTED` | 开局 | `PUBLIC` | `gameId, roomId, seats[9], roleComposition, rules, turnOrder, round=1` |
| `ROLE_ASSIGNED` | 角色分配（每人一条） | `PRIVATE`（本人） | `playerId, seatNo, role, camp` |
| `PHASE_CHANGED` | 阶段跳转 | `PUBLIC` | `from, to, round, turnSeq, resumePhase?` |
| `TURN_GRANTED` | 发放回合窗口 | `PUBLIC` | `playerId, seatNo, phase, turnIndex, deadline` |
| `WOLF_KILL_NOMINATED` | 狼人提名目标 | `WOLF` | `wolfId, targetId, targetSeatNo` |
| `WOLF_KILL_SELECTED` | 狼刀定案 | `WOLF` | `targetId, targetSeatNo, nominations[]` |
| `WOLF_CHAT_SENT` | 狼人夜聊（`NIGHT_WOLF`，存活狼人，每人每晚 ≤ 20 条） | `WOLF` | `wolfId, wolfSeatNo, chatId, content` |
| `SEER_INSPECTED` | 预言家查验 | `PRIVATE`（预言家） | `seerId, targetId, result` |
| `SEER_SKIPPED` | 预言家放弃或超时未查验 | `PRIVATE`（预言家） | `seerId, reason` |
| `WITCH_SAVED` | 使用解药 | `PRIVATE`（女巫） | `witchId, targetId` |
| `WITCH_POISONED` | 使用毒药 | `PRIVATE`（女巫） | `witchId, targetId` |
| `WITCH_PASSED` | 本夜不用药 | `PRIVATE`（女巫） | `witchId` |
| `HUNTER_SHOT` | 猎人开枪 | `PUBLIC` | `hunterId, targetId, targetSeatNo` |
| `HUNTER_SKIPPED` | 猎人放弃开枪或超时 | `PUBLIC` | `hunterId, reason` |
| `PLAYER_SPOKE` | 发言（含遗言） | `PUBLIC` | `playerId, seatNo, speechId, content, turnIndex, phase` |
| `SPEECH_SKIPPED` | 超时或主动跳过 | `PUBLIC` | `playerId, seatNo, reason` |
| `PLAYER_VOTED` | 投票 | `PUBLIC` | `playerId, seatNo, targetId, targetSeatNo, abstain` |
| `VOTE_TALLY_RESOLVED` | 计票完成 | `PUBLIC` | `tally[], maxVotes, result, exiledPlayerId?, tieSeats[]?` |
| `PLAYER_KILLED` | 死亡事实 | `JUDGE` | `playerId, seatNo, causes[], primaryCause, round` |
| `DEATH_ANNOUNCED` | 公布死讯 | `PUBLIC` | `round, deaths[{playerId, seatNo}]` |
| `GAME_FINISHED` | 分出胜负 | `PUBLIC` | `winner, reason, rounds, survivors[], mvpCandidates[]?`；`reason` 取 `ALL_WOLVES_ELIMINATED` / `ALL_VILLAGERS_ELIMINATED` / `ALL_GODS_ELIMINATED`（`SIDE_KILL`）/ `WOLVES_OUTNUMBER_VILLAGE`（`WOLVES_OUTNUMBER`），见 domain-model.md §6.2 |
| `GAME_ABORTED` | 中止（人数不足、不可恢复；连续多轮无人出局时 `reason=STALLED`，见 domain-model.md §3 `stalledRoundsLimit`） | `PUBLIC` | `reason, atVersion` |
| `GAME_RECOVERED` | 从快照恢复 | `JUDGE` | `snapshotVersion, replayedTo, source` |

`VOTE_TALLY_RESOLVED.result` 取值：`EXILE`（有人出局）、`TIE`（平票，无人出局）、
`ALL_ABSTAIN`（全员弃权）。

`PLAYER_SPOKE.speechId` 对客户端是不透明字符串。实现上取 `{gameId}:{aggregateVersion}`，其中版本是
这条 `PLAYER_SPOKE` 自身的版本（`PlayerSpokeEvent.speechIdFor`）。AI 在提交前就能按"期望版本 + 1"
推出同一个标识，因此流式预览帧（`AGENT_SPEECH_DELTA`）与随后提交的权威发言能对上；提交被拒绝或
版本变化时预览按 websocket-protocol.md D-2 丢弃，不影响事件流。

`DEATH_ANNOUNCED` 的 `deaths` 为空数组时表示平安夜，仍然产生事件，前端据此显示
"昨夜无人死亡"，而不是靠"没有收到事件"推断。

`GAME_STARTED.rules` 记录本局规则参数（发言长度上限、遗言规则、女巫自救与是否仅限首夜、
平票处理、狼刀平票裁决、胜负条件等，见 [domain-model.md §3](../backend/domain-model.md)）。
后加的键在旧事件里缺失时按加入前的行为解码（如 `witchSelfSaveFirstNightOnly` 缺失解码为 `false`）。
规则参数写入事件是重放一致性的前提：`DESIGN.md` §9 的实验室会跑不同规则的批量对局，
重放时必须使用当时的规则，而不是服务端当前配置。

`PLAYER_KILLED.causes` 是死因集合，用于表达同夜既被狼刀又被毒药的情况；
`primaryCause` 按 `WOLF_KILL` > `WITCH_POISON` > `HUNTER_SHOT` > `EXILE` 取首个，
仅用于展示。技能可用性判定必须读取 `causes` 集合（含 `WITCH_POISON` 则猎人不能开枪），
不能只看 `primaryCause`。

`WOLF_KILL_SELECTED.targetId` 为 `null` 表示空刀（存活狼人全部弃刀），仍然产生事件，
因为"当夜无人被刀"是可回放的对局事实。

`WOLF_CHAT_SENT` 只改变"本夜每名狼人已发条数"，不改变阶段、提名与回合。`chatId` 与 `speechId`
同一派生方式（`{gameId}:{aggregateVersion}`）。它不推进 `stateVersion`（快照字段，最后一条非夜聊事件的版本），
应用层据此判断客户端的 `expectedVersion` 之后是否只有夜聊（api-spec.md §5.3）。

### 7.3 User 聚合（`user:{id}:events`）

| type | 触发 | 可见性 | payload |
| --- | --- | --- | --- |
| `USER_REGISTERED` | 注册 | `JUDGE` | `userId, username` |
| `USER_LOGGED_IN` | 登录 | `JUDGE` | `userId, sessionId, at` |
| `USER_LOGGED_OUT` | 登出 | `JUDGE` | `userId, sessionId, at` |

口令、令牌等敏感值不进入事件（事件是长期留存的数据）。

### 7.4 Agent 决策事件（`game:{id}:agent:{playerId}`，非领域事件）

| type | 触发 | 关键 payload |
| --- | --- | --- |
| `AGENT_TURN_STARTED` | 收到 `TURN_GRANTED` | `agentId, playerId, version, deadline` |
| `AGENT_CONTEXT_BUILT` | 上下文装配完成 | `eventRange, tokenEstimate, memoryHits` |
| `AGENT_STRATEGY_DECIDED` | 策略层产出候选集 | `candidateActions[], scores[], hardConstraints[]` |
| `AGENT_LLM_INVOKED` | 调用 LLM | `model, promptTokens, completionTokens, latencyMs` |
| `AGENT_OUTPUT_REJECTED` | 守卫或工具校验拒绝 | `stage, reason, rawOutputRef` |
| `AGENT_TOOL_CALLED` | 工具调用 | `tool, argsDigest, decision, resultingEventId?` |
| `AGENT_TURN_FINISHED` | 回合完成 | `chosenAction, submittedEventId, fallbackUsed` |
| `AGENT_TURN_TIMEOUT` | 超时降级 | `deadline, fallbackAction` |
| `AGENT_TURN_FAILED` | 不可恢复失败 | `stage, errorCode` |

这些事件的类型前缀统一为 `AGENT_`，写入 `game:{id}:agent:{playerId}`，
由归档消费者落到 SQLite `agent_memory`（`memory_type = 'DECISION_TRACE'`，
见 [database-schema.md](../backend/database-schema.md)）。它们是 `DESIGN.md` §14
"AI 分析页"与 AI 实验室的数据来源，任何一条都不允许出现在 `game:{id}:events`。

## 8. 快照

2026-10-14 女巫库存修复：快照状态 JSON 增加内部标记 `skillStateSchemaVersion=2`。
此前快照可能保存了用药后仍可用的库存；该标记缺失或不是 2 时，仓储从完整事件流重建，不能继续信任旧库存。
新快照仍用原 checksum、聚合 version 与保存策略，资源语义标记不是 aggregateVersion/phaseVersion/stateVersion。
旧快照可解码用于诊断，但不作为命令处理的恢复起点；坏快照/旧格式降级沿用现有告警计数。
此修复不更改原事件、胜负或玩家行为，不调用模型重算历史。

`DESIGN.md` §8 规定每 50 个事件生成一次快照。

| 项 | 设计 |
| --- | --- |
| 触发 | `version % 50 == 0`，或 `GAME_FINISHED` / `GAME_ABORTED` / `GAME_RECOVERED` 时强制 |
| 存储 | `game:{id}:snapshot`（最新快照）+ `game:{id}:snapshots`（ZSet，score = version，member = 快照键名） |
| 内容 | `gameId, version, phase, round, status, resumePhase, players[{playerId, seatNo, role, camp, alive, controller, skillState}], turnOrder, turnIndex, currentTurn, nightActions, votes, deathQueue, checksum` |
| 保留 | 保留最近 3 个快照，更早的删除，但归档到 SQLite `game_history`（`kind = 'SNAPSHOT'`） |
| 一致性 | `checksum` 为状态序列化后的校验和；恢复时若校验失败，退化为从版本 1 全量重放并告警 |

快照是缓存而不是真相（INV-15）。任何"只改快照不改事件"的实现都是缺陷，
必须能通过 `EventStorePort.read(1..version)` 推导出与快照逐字段一致的状态。

## 9. 消费组

| 消费组 | 消费者 | 职责 | 位点语义 |
| --- | --- | --- | --- |
| `cg-projection` | `EventGroupWorker` + `GameArchivalService` | 更新 Redis 查询投影、SQLite 事件及核验完成标记 | 写入成功才 ACK；暂时故障重试，坏事件三次后隔离 |
| `cg-broadcast` | `WebSocketBroadcaster` | 按 `VisibilityFilter` 过滤后推送 | 推送失败不回退位点，只记连接；由重连补偿 |
| `cg-agent` | `EventDrivenTurnScheduler` / 记忆更新器 | 按窗口事件规划行动、超时和取消任务；记忆仍可重建 | 调度任务可靠保存或已核实窗口失效后 ACK；调度失败保留 PEL；记忆失败不得吞掉调度 |
| `cg-insight` | `EventGroupWorker` + `GameInsightService` | 核验终局结果、回放索引和统计；不得改归档水位 | SQLite 事务提交后 ACK，可由完整归档补做 |

每个消费组独立位点，组内并行度按 `gameId` 分片（同一对局只在一个消费者实例上处理）。
消费组名与流键的组合一经发布不得更改，更改等同于全量重放。

当前四组实现与字段所有权见 [event-consumers.md](../backend/event-consumers.md)。各实例使用 UUID
消费者名，单局组内租约保护未确认前缀，异名闲置 60 秒消息接管；本机追加即时唤醒，
持久化目录与 PEL 负责补偿。Redis 模式提交后的进程内直接广播关闭，广播组复用原权限过滤/重连同步。

死信：`game:{id}:events:dlq`，条目含原事件、消费组名、失败次数、最后错误、
进入时间。DLQ 保留 7 天，重放走运维接口（`JUDGE` 可见，产生
`GAME_RECOVERED` 之外不产生游戏事件）。

## 10. 幂等与去重

| 层 | 机制 | 效果 |
| --- | --- | --- |
| 命令层 | `idem:{gameId}:{idempotencyKey}` 存 `{eventId, version, resultHash}` | 玩家重试、AI 重试、网络重发返回同一结果，不产生新事件 |
| 事件存储层 | 显式条目 ID + 唯一约束 | 重复写入被 Redis 或 SQLite 拒绝 |
| 消费层 | 以 `eventId` 去重（`game_events` 唯一索引） | 至少一次投递不会导致投影重复累加 |
| 推送层 | 连接级 `seq` 单调 + 客户端 `lastSeq` | 重复帧被客户端丢弃，缺帧触发补发 |

"至少一次投递 + 幂等消费"是这套设计的一致性基础，不追求端到端恰好一次（C-2）。

## 11. 重放与恢复

三种重放语义必须区分，实现上共用读取端口，用途不同：

| 语义 | 起点 | 终点 | 用途 |
| --- | --- | --- | --- |
| 恢复重放 | 最新快照的 `version` | 当前 `version` | 加载聚合，服务正常读写 |
| 重建重放 | 1（或 SQLite 归档中的最早版本） | 当前 `version` | 投影损坏、Redis 数据丢失后重建状态 |
| 展示重放 | 事件流按版本或时间区间 | 指定区间 | 回放页时间线、AI 分析页 |

恢复流程（`DESIGN.md` §8 的 "Load Snapshot + Replay Later Events"）：

```
RecoverGameApplicationService
  1. GET game:{id}:snapshot            → 最新快照（可能缺失）
  2. 缺失则：XRANGE game:{id}:events - + 全量重放
     存在则：XRANGE game:{id}:events ({snapshotVersion}-0 +
  3. Game.replay(event) 逐条应用
  4. 校验 version 连续、checksum 一致；不一致 → 从 SQLite game_events 重建
  5. 产生 GAME_RECOVERED（visibility=JUDGE）
  6. 释放锁；恢复期间的写请求返回 GAME_RECOVERING
```

Redis 事件流缺失时，Redis 模式的仓储 `load` / `loadAt` 调用
`RestoreArchivedGameApplicationService`。它要求 SQLite 完成标记、水位和事件条数一致，
从版本 1 全量重放并核对终局状态、阶段、回合与胜方，再安装历史事件流。
事件 ID、版本、时间戳、可见性和载荷保持不变。它恢复存储副本，不追加 `GAME_RECOVERED`，
不发布历史行动、不唤醒 AI；已有流不覆盖，不完整或非终局归档拒绝恢复。
已有流上的恢复留痕仍由 `RecoverGameApplicationService` 处理，两条路径职责不同。

安装使用 Redis 临时流（`wolfmind:archive-restore:{uuid}`，构建期 10 分钟 TTL），
完整后在 Lua 中核对条数/尾版本、检查目标仍不存在，再原子替换为正式流。
正式流沿用归档后的 7 天 TTL，旧孤立快照清除后按事件重建；并发恢复只有一个安装成功。
失败不暴露半份正式流，临时流由 finally 清理或 TTL 回收。历史回放仍可直接读 SQLite，
不因只读回放而必须重建 Redis；当前状态/指定版本视图需要聚合时自动恢复。

## 12. 保留与归档

| 数据 | 位置 | 保留策略 |
| --- | --- | --- |
| 运行中/未归档事件流 | `game:{id}:events` | 不自动过期；终局且 SQLite 完整归档核对通过后才设最多 7 天 |
| 事件永久归档 | SQLite `game_events` | 永久（由 `cg-projection` 在 ACK 前写入） |
| 快照 | `game:{id}:snapshot(s)` | Redis 保留最近 3 个；当前 game_history 归档终局完整快照，其他历史状态由事件重放重建 |
| Agent 决策事件 | `game:{id}:agent:{pid}` | 对局结束后 7 天；归档到 `agent_memory` |
| 死信 | `game:{id}:events:dlq` | 7 天 |

当前实现的归档先于过期保证：追加脚本对对局流执行 `PERSIST`，
`cg-projection` 与 `TerminalGameArchiver` 复用 `GameArchivalService` 写入 SQLite 并核对内容/版本，
写入完成标记后才缩短保留期；前者负责正常消费，后者定期检查补漏。
新版本启动也核对旧对局流，未归档或水位不符的流/快照取消原 TTL。
`cg-projection` 已实现归档成功后 ACK；模型任务与统计/推送使用各自独立位点。

## 13. 反模式清单

以下写法视为缺陷，评审时逐条检查：

| 编号 | 反模式 | 后果 |
| --- | --- | --- |
| AP-1 | 直接 `HSET game:{id}:state ...` 改状态而不产生事件 | 状态与事件流分叉，无法恢复 |
| AP-2 | 修改或删除已写入的事件 | 破坏 INV-14，历史不可信 |
| AP-3 | 应用层用 `System.currentTimeMillis()` 重新生成事件时间 | 重放结果不一致 |
| AP-4 | 消费者把处理结果写回事件流 | 事件流被污染 |
| AP-5 | AI 或 Agent Runtime 直接 XADD 到 `game:{id}:events` | 绕过领域校验（C-5） |
| AP-6 | 在推送层另写一份可见性判断 | 隐藏信息泄漏 |
| AP-7 | 领域事件里携带 Redis 连接、聚合引用、LLM 响应等基础设施对象 | 领域被污染，序列化不稳定 |
| AP-8 | 依赖 `game:{id}:state` 做业务判断 | 读到滞后投影 |
| AP-9 | 跨聚合同一次写入 | 破坏聚合边界与单写者模型 |
| AP-10 | 为省事件而合并多次状态变化到一条事件 | 回放无法还原中间状态 |

## 14. 校验清单

实现完成后，以下检查必须全部通过：

1. 每条 `type` 在 §7 目录中有定义，且 `visibility` 与目录一致。
2. 任意一条对局的事件流满足：`version` 从 1 连续递增，无空洞、无重复（INV-11）。
3. 全量重放得到的状态与 `game:{id}:state` 投影逐字段一致（INV-15、INV-16）。
4. 每个阶段跳转都有 `PHASE_CHANGED`，转换组合都在 §3.2 转换表内。
5. `PRIVATE` / `WOLF` / `JUDGE` 事件不出现在非授权订阅者的推送中（§4 矩阵）。
6. 同一 `idempotencyKey` 重复提交只产生一条事件。
7. `AGENT_` 前缀事件不出现在 `game:{id}:events` 中。
