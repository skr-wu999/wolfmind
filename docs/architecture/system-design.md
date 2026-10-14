# WolfMind 详细设计：系统设计

本文细化 `DESIGN.md` §3（总体架构）、§4（后端分层）、§13（数据设计）、§15（开发路线）。
技术选型与 `DESIGN.md` 完全一致：Java、Redis、SQLite、Vue3、WebSocket、LangGraph4j、
LangChain4j、LLM。本文不引入任何新的中间件或框架。

## 1. 逻辑架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Frontend (Vue3)                               │
│  首页 / 大厅 / 房间 / 游戏 / 观战 / 回放 / AI 分析 / AI 实验室         │
└───────────────┬──────────────────────────────┬───────────────────────┘
                │ REST（命令与查询）             │ WebSocket（订阅与推送）
                ▼                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│ Interface Layer                                                      │
│  GameController RoomController UserController ReplayController        │
│  AnalysisController LabController                                    │
│  WebSocketGateway SubscriptionRegistry MessageCodec VisibilityFilter  │
└───────────────┬──────────────────────────────────────────────────────┘
                ▼
┌──────────────────────────────────────────────────────────────────────┐
│ Application Layer（只编排，不含规则）                                  │
│  对局：StartGame / SubmitAction / AdvancePhase / RecoverGame / GetView │
│  房间：Create / Join / Seat / Ready                                   │
│  Agent：AgentTurnApplicationService ToolInvocationApplicationService   │
│  端口：EventStore / SnapshotStore / GameStateCache / EventPublisher    │
│        GameViewQuery / AgentRuntime / Notification / UnitOfWork        │
└──────┬────────────────────────────────────────┬──────────────────────┘
       ▼                                        ▼
┌──────────────────────────────┐   ┌────────────────────────────────────┐
│ Domain Layer（规则唯一来源）    │   │ Agent System                       │
│  Game(AR) Player Role Action │   │  Agent Runtime                     │
│  GamePhase DomainEvent       │   │  Strategy Layer                    │
│  领域服务：计票/结算/胜负/校验  │   │  Tool Gateway（防腐层 + 权限）      │
│  仓储接口：GameRepository      │   │  LangGraph4j 工作流                 │
└──────────────┬───────────────┘   │  LangChain4j → LLM                 │
               │                    └───────────────┬────────────────────┘
               ▼                                    │ 只经 Tool Gateway
┌──────────────────────────────────────────────────────────────────────┐
│ Infrastructure Layer                                                  │
│  Redis：Stream 事件存储 / 快照 / 状态投影 / 锁 / 订阅关系               │
│  SQLite：用户 / 对局 / 事件归档 / 历史 / Agent 记忆 / 统计              │
│  LangChain4j / LangGraph4j 适配器，WebSocket 服务端适配器               │
└──────────────────────────────────────────────────────────────────────┘
```

两条不可跨越的边界：

1. Domain 不依赖任何基础设施，也不依赖 Agent System（[ddd-design.md §5.3](ddd-design.md) DEP-1、DEP-2、DEP-7）。
2. Agent System 对 Game Domain 没有编译期依赖，写路径唯一入口是 Tool Gateway
   与 `SubmitActionApplicationService`（C-5）。

## 2. 运行时组件

| 组件 | 形态 | 启动方式 | 职责 |
| --- | --- | --- | --- |
| HTTP 服务 | 同一 Java 进程内的 REST 端点 | 随应用启动 | 命令提交、查询、实验室接口 |
| WebSocket 服务 | 同一 Java 进程内的长连接端点 | 随应用启动 | 订阅管理、事件推送、断线补偿 |
| 事件消费协程 | 进程内后台消费者 | 随应用启动 | 投影、广播、Agent 触发、洞察 |
| 回合调度器 | `cg-agent` 消费 `TURN_GRANTED`、`PHASE_CHANGED` 和行动提交事件 | 事件驱动 | 保存稳定窗口任务后异步调用 Agent Runtime |
| 超时看护 | 事件登记一次性唤醒；持久化到期索引每 500ms 补偿 | 定时任务 | 独立有限执行池提交 `SkipAction`，仍走领域路径 |
| 快照生成器 | 写入路径内联触发 | 每 50 个事件 | 生成并归档快照 |

进程内消费者与 Web 端点共享同一 JVM，因此第一至第四阶段是模块化单体部署；
第十节的拆分方案按 `DESIGN.md` §15 Phase 5 的 Docker 部署推进，不改变模块边界。

## 3. 关键用例时序

### 3.1 开局

```
真人/房主            接口层            应用层                领域            Redis           消费者
   │ POST /games        │                 │                    │               │               │
   ├───────────────────▶│ StartGame       │                    │               │               │
   │                    ├────────────────▶│ 加载 Room 聚合      │               │               │
   │                    │                 ├───────────────────▶│ start(cmd,ctx)│               │
   │                    │                 │                    │ 校验 INV-1..3 │               │
   │                    │                 │◀─── events ────────┤               │               │
   │                    │                 ├──── XADD 11 条 ───────────────────▶│               │
   │                    │                 ├──── 发布 ──────────────────────────────────────────▶│
   │◀── 201 {gameId} ───┤◀────────────────┤                    │               │               │
   │ WS: 先收 GAME_SNAPSHOT_SYNC，再收逐条事件（ROLE_ASSIGNED 只发本人）        │               │
```

开局是一次多事件写入：`GAME_STARTED` + 9×`ROLE_ASSIGNED` + `PHASE_CHANGED`，
共 11 条，`version` 1..11 连续。任一环节失败都不允许留下版本空洞。

### 3.2 夜晚狼刀

```
AI/真人(狼)       ToolGateway        应用层              领域 Game         Redis Stream
   │ KillPlayerTool{target}  │            │                  │                 │
   ├───────────────────────▶│ 阶段门/角色门/次数门          │                 │
   │                        ├───────────▶│ SubmitAction      │                 │
   │                        │            ├─────────────────▶│ handle(WolfKill) │
   │                        │            │                  │ 校验 INV-6       │
   │                        │            │◀── WOLF_KILL_NOMINATED (WOLF) ──────┤
   │                        │            ├── XADD ─────────────────────────────▶
   │                        │            │                                      │
   │                        │            │ 当 3 条提名齐备 → WolfKillPolicy 定案  │
   │                        │            ├── WOLF_KILL_SELECTED + PHASE_CHANGED(NIGHT_SEER)
   │                        │            ├── XADD ─────────────────────────────▶
   │◀── tool result{accepted}┤◀───────────┤                  │                 │
```

工具返回的 `accepted` 只代表命令被接受，最终状态以事件为准；被拒绝时返回结构化
原因与可选目标，供策略层下一轮修正。

### 3.3 白天投票

```
9 名玩家            接口层            应用层                    领域/Policy        Redis
   │ POST /games/{id}/actions {actionType:VOTE, targetId}        │                │
   ├──────────────────────────────────────────────────────────────▶│                │
   │                    │ 幂等键 → 单写者锁 → 加载 → handle(Vote)  │ PLAYER_VOTED   │
   │◀── 200 {version} ──┤                                          ├── XADD ──────▶│
   │  WS 广播 PLAYER_VOTED（PUBLIC，所有订阅者）                    │                │
   │  当 9 票齐备或全员弃权：VoteTallyPolicy 计票                    │                │
   │      → VOTE_TALLY_RESOLVED {result}                          │                │
   │      → 平票：无人出局，PHASE_CHANGED → 下一阶段                 │                │
   │      → 出局：PLAYER_KILLED{cause:EXILE}(JUDGE) + PHASE_CHANGED │                │
   │      → WinConditionPolicy：未分胜负则 round+1 → NIGHT_WOLF     │                │
```

### 3.4 AI 回合（事件驱动的闭环）

```
领域               cg-agent 消费者        AgentRuntime        StrategyLayer      ToolGateway      应用层
 │ TURN_GRANTED          │                      │                   │                 │              │
 ├──────────────────────▶│ executeTurn(req)     │                   │                 │              │
 │                       ├─────────────────────▶│ 装配上下文         │                 │              │
 │                       │                      ├──────────────────▶│ 威胁评估/候选集  │              │
 │                       │                      │                   ├── LangGraph4j ──▶│              │
 │                       │                      │                   │◀── LLM 输出 ────┤              │
 │                       │                      │◀── ActionProposal ┤ 守卫校验        │              │
 │                       │                      ├───────────────────────────────────▶│ SpeakTool 等 │
 │                       │                      │                   │                 ├─────────────▶│
 │                       │                      │                   │                 │  校验+提交    │
 │◀──────────────────────┴──────────────────────┴───────────────────┴─────────────────┤ 事件入流     │
 │ PLAYER_SPOKE / PLAYER_VOTED / …                                                    │              │
```

闭环由已提交事件驱动：顺序发言/遗言/猎人使用 `TURN_GRANTED`，夜间和投票使用
`PHASE_CHANGED` 及本阶段行动事件。应用层只根据权威状态规划有效任务，AI 无法自行
开始回合或通过工具改变行动资格与顺序。消费器保存任务后 ACK，模型执行与超时处理分离；
窗口身份、恢复与回退契约见 agent-runtime.md §4。

### 3.5 断线重连

```
客户端                       WebSocketGateway                     Redis Stream
  │ 连接 + AUTH 帧                 │                                   │
  ├───────────────────────────────▶│ 校验会话令牌                       │
  │◀── AUTH_OK ────────────────────┤                                   │
  │ SUBSCRIBE {gameId, lastSeq:88} │                                   │
  ├───────────────────────────────▶│ XRANGE (88-0 +  ← 按可见性过滤      │
  │                                ├──────────────────────────────────▶│
  │◀── SUBSCRIBED {from:89, truncated:false} + 补发事件（唯一订阅确认帧，SUBSCRIBE 与 RESUME 共用）              │
  │    lastSeq 早于流内最早版本 → truncated:true，改为 GAME_SNAPSHOT_SYNC │
```

补发与实时推送之间的空隙由连接级 `seq` 去重处理：客户端丢弃 `seq <= lastSeq`
的帧，缺号则再次请求 `RESUME`。

### 3.6 崩溃恢复

```
启动 / 守护检查
  1. 扫描 game:{id}:events 存在但 game:{id}:state 位点落后的对局
  2. 对每个对局获取单写者锁
  3. RecoverGameApplicationService：快照 + 增量重放（event-design.md §11）
  4. 校验 version 连续性与快照 checksum
  5. 产生 GAME_RECOVERED（JUDGE）
  6. 若对局已超时（超过回合 deadline 的容忍倍数）→ 提交 SkipAction 推进
  7. 锁释放，恢复正常读写
```

步骤 6 的推进仍以 `SkipAction` 走领域路径，因此"崩溃后自动跳过"也是对局历史里
可重放的事实，而不是服务端的隐式修补。

## 4. 并发控制

| 场景 | 手段 | 失败表现 |
| --- | --- | --- |
| 同一对局并发写 | `game:{id}:lock`（SET NX PX 3s，持有者续租） | 获取失败返回 `GAME_BUSY`，客户端退避重试 |
| 重复提交 | `idem:{gameId}:{idempotencyKey}` | 返回首次结果，`replayed: true` |
| 版本过期 | 事件条目显式 ID + Redis 拒绝小 ID | `GAME_VERSION_CONFLICT`，客户端先同步再重试 |
| 锁持有者崩溃 | 锁 TTL 到期自动释放 | 新写者接管；因显式 ID 校验，不会产生双写 |
| AI 与真人同回合 | 领域只接受当前 `currentTurn.playerId` | `TURN_NOT_YOURS` |
| 快照与写入 | 快照在持有锁时生成 | 不会出现半个状态 |

三秒锁 TTL 与"单次命令处理不含 LLM 调用"是配套的：命令路径不得调用 LLM，
否则锁会被拖到超时。AI 推理发生在锁之外，推理完成后才提交命令。

## 5. 事务边界

| 边界 | 范围 | 保证 |
| --- | --- | --- |
| 领域事务 | 单个聚合的一次 `handle` | 全部事件连续写入，无版本空洞 |
| 跨聚合 | 不同聚合只通过事件协作 | 不承诺原子性；`correlationId` 串联 |
| 归档 | SQLite 写入在消费组 ACK 之前 | 归档完成才推进位点 |
| 幂等记录 | 与事件写入同批 | 结果可取回 |
| 推送 | 不属于事务 | 允许延迟与重复，客户端幂等 |

SQLite 写入按批次提交，单批事件数上限 200，避免长事务阻塞归档消费者。

## 6. 错误处理与降级

| 故障 | 影响 | 降级策略 |
| --- | --- | --- |
| Redis 不可用 | 无法写入新状态 | 拒绝写请求（`SERVICE_DEGRADED`），读请求返回最近投影；已结束对局仍可从 SQLite 回放 |
| SQLite 不可用 | 归档与长期记忆不可写 | 停止推进 `cg-projection` 位点，Redis 继续服务；告警 |
| LLM 不可用或超时 | AI 无法推理 | 策略层确定性兜底（规则 Top-1）提交合法动作，`AGENT_TURN_FAILED` 记录；对局不中断 |
| AI 回合超时 | 回合停滞 | `deadline` 到期由超时看护提交 `SkipAction` |
| WebSocket 断连 | 客户端状态过期 | 重连 + `RESUME` 补发；无法补发则全量快照同步 |
| 消费组滞后 | 投影陈旧 | 告警；若滞后超过阈值，暂停接受该对局的新写请求 |
| 快照损坏 | 恢复变慢 | 校验和失败则全量重放，事件流仍是唯一真相 |
| 事件流与归档不一致 | 恢复来源冲突 | 以版本号逐条比对，取归档补齐并重新 XADD |

降级不等于绕过领域：所有降级动作（跳过、兜底决策）都表现为合法 `Action`，
都会产生事件（C-4）。

## 7. 性能目标与容量估算

设计目标（非实测数据，用于确定架构边界）：

| 指标 | 目标 | 说明 |
| --- | --- | --- |
| 单条命令处理 | P95 < 50 ms | 不含 LLM；锁获取 + 事件写入 + 发布 |
| 事件到推送 | P95 < 200 ms | 消费组拉取 + 可见性过滤 + 发送 |
| 单 AI 回合 | 2 s 到 15 s | 取决于模型；默认 deadline 30 s |
| 并发对局 | 200 局 / 实例 | 每局事件写入量小，瓶颈在 LLM 调用并发 |
| WebSocket 连接 | 2000 / 实例 | 广播放大系数约为订阅者数 |

单局事件量估算（9 人标准局，4 轮结束）：

```
开局           1 GAME_STARTED + 9 ROLE_ASSIGNED + 1 PHASE_CHANGED      =  11
每轮夜间        PHASE_CHANGED×4 + 狼提名 3 + 狼定案 1 + 查验 1
                + 用药 1 + PLAYER_KILLED 1 + TURN_GRANTED×5           ≈  16
每轮白天        PHASE_CHANGED×4 + TURN_GRANTED×9 + PLAYER_SPOKE 9
                + PLAYER_VOTED 9 + VOTE_TALLY_RESOLVED 1 + 死亡 1      ≈  33
每轮小计                                                              ≈  49
4 轮合计                                                              ≈ 196
收尾            GAME_FINISHED 1 + 遗言与猎人插入事件若干                 ≈   8
单局合计                                                              ≈ 204
```

按每条事件平均 500 字节计算，单局约 100 KB；每日 1000 局（含实验室批量对局）
约 100 MB 事件数据；完整归档后 Redis 保留最多 7 天，未归档不自动过期，容量还需计入积压。Agent 决策事件
数量约为领域事件的 3 到 5 倍，但只在归档与调试时需要，因此按对局结束 7 天清理。

## 8. 可观测性

`DESIGN.md` §15 Phase 5 要求监控。指标按"领域 + 基础设施"两层采集：

| 类别 | 指标 | 用途 |
| --- | --- | --- |
| 对局 | 进行中对局数、平均回合时长、按阶段停留时长 | 发现规则层卡顿 |
| 事件 | 每秒事件数、版本空洞告警、DLQ 条数、快照生成次数 | 事件化健康度 |
| 消费 | 各组位点滞后（条数）、消费失败率 | 投影新鲜度 |
| AI | 每回合 token 消耗、LLM 延迟、工具拒绝率、兜底率 | 策略质量与成本 |
| 连接 | 在线连接数、重连率、补发事件量、丢弃帧数 | 前端体验 |
| 存储 | Redis 内存、Stream 长度、SQLite 大小与写入延迟 | 容量规划 |

日志与追踪：每次命令处理记录 `correlationId`、`causationId`、`gameId`、`version`；
领域事件日志只记录 `type` 与 `version`，不记录 `PRIVATE` 事件的 payload，
避免日志成为隐藏信息泄漏渠道。

## 9. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `game.playerCount` | 9 | 座位数 |
| `game.roleComposition` | 3 狼 3 民 1 预 1 女 1 猎 | 角色配置 |
| `game.speech.maxLength` | 500 | 发言字符上限（领域校验） |
| `game.turn.deadlineMs` | 30000 | 真人回合时限 |
| `agent.turn.deadlineMs` | 30000 | AI 回合时限 |
| `game.snapshot.interval` | 50 | 快照间隔事件数 |
| `game.stream.retentionDays` | 7 | 结束后事件流保留天数 |
| `game.stream.maxRetentionDays` | 30 | 无结束事件时的上限 |
| `game.lock.ttlMs` | 3000 | 单写者锁 TTL |
| `idempotency.ttlMinutes` | 10 | 幂等键保留 |
| `ws.heartbeat.intervalMs` | 15000 | 心跳间隔 |
| `ws.idle.timeoutMs` | 45000 | 空闲断连阈值 |
| `ws.maxPendingFrames` | 200 | 单连接积压上限 |
| `agent.maxRepairRounds` | 1 | 输出校验失败后的修复轮数 |
| `agent.fallback.enabled` | true | LLM 失败时启用规则兜底 |
| `lab.maxConcurrentGames` | 8 | 实验室批量对局并发上限 |

配置按"领域规则类"与"运行调优类"分开：前者变更需要产生配置事件并影响重放
（角色配置写入 `GAME_STARTED`），后者可热更新且不影响历史。

## 10. 部署形态与拆分路径

| 阶段 | 形态 | 说明 |
| --- | --- | --- |
| Phase 1 | 单进程，仅领域与内存事件 | 纯 Java 模拟，Redis 可缺席（内存实现端口） |
| Phase 2 | 单进程 + Redis | 事件存储、快照、恢复 |
| Phase 3 | 单进程 + Agent | AI 与对局同进程，工具网关在同 JVM 内仍是唯一写路径 |
| Phase 4 | 单进程 + WebSocket | 接入层与对局同进程 |
| Phase 5 | 容器化，按需拆分 | 可拆为：接入层（HTTP + WebSocket）、对局服务（应用 + 领域 + Redis）、AI 服务（Agent + LLM） |

拆分的依据是端口边界：`AgentRuntimePort` 与 `NotificationPort` 已经是进程边界，
跨进程时把实现从"本地调用"换成"消费组 + 队列"即可，领域、事件契约、协议语义
全部不变。拆分为多实例的前提条件：

1. WebSocket 订阅关系外置到 Redis（`ws:sub:{gameId}`、`ws:conn:{connId}`）。
2. 单写者锁继续按 `gameId` 生效，天然支持多实例。
3. 消费组按 `gameId` 分片，同一对局只在一个实例上消费。

## 11. 安全

| 面 | 措施 |
| --- | --- |
| 身份 | 会话令牌随 REST（请求头）与 WebSocket（`AUTH` 帧）传递，不在 URL 中携带 |
| 口令 | 只存散列与盐，不进入事件流与日志 |
| 授权 | 行动提交校验"提交者是否是该座位的控制者"（真人 → `userId`，AI → 内部调用凭证） |
| 观战 | 进行中仅公开记录，赛后完整披露；身份由服务端反查，参与者使用本人视图；观战者不能提交行动（2026-10-12 所有者批准） |
| 隐藏信息 | 过滤在服务端完成，前端拿到的数据本身就不含他人私有信息（[websocket-protocol.md](../frontend/websocket-protocol.md)） |
| AI 权限 | 工具调用受"角色 × 阶段 × 次数 × 目标"矩阵约束（[tool-gateway.md](../ai/tool-gateway.md)） |
| 提示注入 | 玩家发言只作为数据进入其他 Agent 的上下文，不作为指令；记忆写入时标记来源 |
| 输入校验 | 长度、枚举、范围在工具网关与领域双层校验 |
| 滥用防护 | 按连接与按账号的提交频率限制、发言长度限制、实验室并发上限 |
| 审计 | 每次工具调用与状态变更都有 `correlationId` 可追溯 |

## 12. 与开发路线的映射

| `DESIGN.md` 阶段 | 交付物 | 本文相关章节 |
| --- | --- | --- |
| Phase 1 游戏核心 | DDD 模型、状态机、Action、Domain Event | §1 §3.1 §3.2 §3.3、[domain-model.md](../backend/domain-model.md) |
| Phase 2 Event Store | Redis Stream、Snapshot、Replay | §3.6 §4 §5、[event-design.md](event-design.md) |
| Phase 3 AI Agent | Agent Runtime、Strategy Layer、LangGraph4j、LangChain4j、Tool Gateway | §3.4、`docs/ai/` 三篇 |
| Phase 4 在线游戏 | Vue3、WebSocket、房间系统、AI 补位 | §3.5 §10、[page-design.md](../frontend/page-design.md) |
| Phase 5 高级能力 | AI 分析、实验室、Docker 部署、监控 | §8 §10 §11 |

## 13. 约束合规自检

| 要求 | 落地机制 | 验证方式 |
| --- | --- | --- |
| 不改变已有架构 | 四层、聚合、事件流、Agent 结构均照搬 `DESIGN.md`；新增内容全部是实现细节 | §1 组件与 `DESIGN.md` §3 图逐项对应 |
| 不新增技术选型 | 只用 Redis Stream、SQLite、WebSocket、LangGraph4j、LangChain4j、Vue3 | 全文技术名词扫描 |
| 符合 DDD | 聚合边界、不变量、领域服务、端口与适配器、上下文映射齐备 | [ddd-design.md](ddd-design.md) 全篇，DEP-1..DEP-7 可测 |
| 所有状态变化事件化 | 写路径唯一，先事件后投影；降级动作也事件化 | INV-11..INV-16、event-design §14 校验清单 |
| AI 不直接操作 Game Domain | 无编译期依赖（DEP-7）+ 工具网关唯一写路径 + 角色可见视图 | 工具权限矩阵、可见性矩阵 |
