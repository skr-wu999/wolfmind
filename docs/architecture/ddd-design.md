# WolfMind 详细设计：DDD 设计

本文是 `DESIGN.md` 的细化，不改变 `DESIGN.md` 已经确定的分层、技术选型与架构风格。

## 0. 约束来源

`DESIGN.md` 已经确定的五个前提，本文所有内容都必须满足：

| 编号 | 约束 | 出处 |
| --- | --- | --- |
| C-1 | 不改变已有架构（DDD + Event Driven + Multi-Agent） | DESIGN.md §3 |
| C-2 | 不新增技术选型（Java / Redis / SQLite / Vue3 / WebSocket / LangGraph4j / LangChain4j / LLM） | DESIGN.md §3 §13 §14 |
| C-3 | 所有设计符合 DDD | DESIGN.md §4 §5 |
| C-4 | 所有状态变化事件化 | DESIGN.md §6 §7 |
| C-5 | AI 不得直接操作 Game Domain | DESIGN.md §9 §12 |

本文用 `C-n` 标注每条设计决定服务于哪个约束。术语、事件名、阶段名以本文与
[event-design.md](event-design.md) 为准，其余文档必须引用，不得各自命名。

## 1. 战略设计

### 1.1 限界上下文划分

`DESIGN.md` 已经存在三类控制器（`GameController`、`RoomController`、`UserController`）、
独立的 Agent System、以及统计/分析/实验室页面。据此划分五个限界上下文，划分结果与
`DESIGN.md` 已有结构一一对应，不新增模块：

| 上下文 | 类型 | 核心职责 | DESIGN.md 对应元素 |
| --- | --- | --- | --- |
| Game Context（对局） | 核心域 | 狼人杀规则、阶段状态机、行动校验、胜负判定、领域事件 | §5 Game Aggregate、§6 事件驱动、§7 Event Store |
| Room Context（房间） | 支撑域 | 房间生命周期、座位、准备状态、真人/AI 补位 | §14 游戏大厅、房间等待页；`RoomController` |
| Agent Context（智能体） | 支撑域 | Agent 生命周期、上下文与记忆、策略决策、工具调用 | §9 AI Agent 架构、§10 Agent Runtime、§11 Strategy Layer、§12 Tool Gateway |
| Insight Context（洞察） | 通用域 | 回放、AI 分析、统计、实验室批量实验（只读投影） | §13 SQLite `game_history` `statistics` `agent_memory`、§14 AI 分析页、AI 实验室 |
| Identity Context（身份） | 通用域 | 账号、会话、观战权限 | §4 `UserController` |

Game Context 是唯一持有游戏规则的地方。`DESIGN.md` §4 规定 Application Layer
"不包含游戏规则"，本文在 §5.3 用可执行的依赖规则把这条约束固定下来。

### 1.2 上下文映射

```
                    ┌───────────────────────────────┐
                    │        Identity Context       │
                    │  账号 / 会话 / 观战权限        │
                    └───────────────┬───────────────┘
                          OHS ↑     │ 提供身份标识
                                    │
   ┌────────────────┐   客户-供应商  ┌▼──────────────────────────────┐
   │  Room Context  │──────────────▶│        Game Context           │
   │ 房间 / 座位     │  StartGame    │  核心域：规则 / 状态机 / 事件   │
   └────────────────┘               └───────┬───────────────▲───────┘
                                            │               │
                       发布语言（领域事件，只读）│               │ 命令（受控写入）
                                            ▼               │
   ┌────────────────────┐            ┌───────────────────────┴───────┐
   │  Insight Context   │◀───────────│       Agent Context           │
   │ 回放 / 分析 / 统计  │  订阅事件   │ Runtime / Strategy / Gateway  │
   └────────────────────┘            └───────────────────────────────┘
```

映射关系与 DDD 模式的对应：

| 上游 | 下游 | 模式 | 说明 |
| --- | --- | --- | --- |
| Room → Game | 客户-供应商 | Room 调用 `StartGameApplicationService` 请求开局，Game 决定是否接受（人数、配置、准备度） |
| Game → Insight | 发布语言（Published Language）+ 事件订阅 | Insight 只订阅 `game:{id}:events`，不反向调用 Game 内部对象 |
| Game → Agent | 发布语言，只读视图 | Agent 只拿到角色可见的 `GameView`（[agent-runtime.md](../ai/agent-runtime.md)） |
| Agent → Game | 防腐层（ACL）+ 客户-供应商 | 唯一通道是 Tool Gateway，`DESIGN.md` §12 的 Permission Check 就是 ACL（C-5） |
| Identity → Room/Game | 开放主机服务（OHS） | 身份只提供 `userId` 与观战权限判定，不介入对局规则 |

Agent Context 与 Game Context 之间没有共享模型：Agent 侧不存在 `Game`、`Player`
对象，只有 `GameView`/`AgentTurn` 这类为推理服务的数据结构。这条边界是 C-5 的战略级保证。

### 1.3 统一语言

以下术语在所有文档、代码、协议中必须逐字一致：

| 术语 | 英文/标识 | 定义 |
| --- | --- | --- |
| 对局 | Game / `gameId` | 一局完整的狼人杀，聚合根，事件流的单位 |
| 座位 | `seatNo` | 1..9，对局内唯一，玩家身份的稳定引用 |
| 玩家 | Player / `playerId` | 对局内的参与者，与"谁在操作"解耦 |
| 玩家控制器 | PlayerController | `HumanController` / `AIController` / `BotController`，见 §3.3 |
| 角色 | Role | `WEREWOLF` `VILLAGER` `SEER` `WITCH` `HUNTER` |
| 阵营 | Camp | `WOLF` / `VILLAGE`，胜负判定单位 |
| 阶段 | GamePhase | 见 §3.2 状态机，枚举值大写下划线 |
| 行动 | Action | 玩家意图的领域表达，如 `VoteAction`、`WolfKillAction` |
| 指令 | Command | 应用层入口对象，如 `StartGameCommand`、`SubmitActionCommand` |
| 领域事件 | DomainEvent | 状态变化的唯一记录，`type` 为 UPPER_SNAKE |
| 聚合版本 | `version` | `gameId` 内单调递增，事件流与快照的定位依据 |
| 可见性 | `visibility` | `PUBLIC` / `WOLF` / `PRIVATE` / `JUDGE`，见 [event-design.md §4](event-design.md) |
| 视图 | GameView | 按角色可见性过滤后的只读投影 |
| 记忆 | Memory | Agent 的短期对局记忆与长期跨局记忆 |
| 回合 | Turn | 一次"轮到某个玩家表态"的窗口，由领域发放 |

## 2. 战术设计总览

### 2.1 聚合清单

| 聚合根 | 标识 | 一致性边界 | 不变量 | 事件流 |
| --- | --- | --- | --- | --- |
| `Game` | `gameId` | 一局对局 | INV-1..INV-12（§4） | `game:{id}:events` |
| `Room` | `roomId` | 一个房间 | INV-R1..INV-R4（§4.3） | `room:{id}:events` |
| `User` | `userId` | 一个账号 | INV-U1..INV-U2（§4.3） | `user:{id}:events` |

`DESIGN.md` §5 只明确要求 `Game` 聚合。`Room` 与 `User` 是为支撑域补齐的聚合边界，
其依据是 `DESIGN.md` §14 的房间页面与 `RoomController`，不是新架构。

`Player`、`Role`、`Action`、`GamePhase`、`DomainEvent` 全部位于 `Game` 聚合内部，
不单独成为聚合，符合 `DESIGN.md` §5 对 `Game Aggregate` 的描述。

### 2.2 Game 聚合内部结构

```
Game (AggregateRoot)
 ├─ gameId            : GameId            (VO)
 ├─ version           : int               聚合版本 = 已产生事件数
 ├─ phase             : GamePhase         (VO/枚举)
 ├─ round             : int               第几轮（夜+昼为一轮）
 ├─ status            : GameStatus        (INIT / RUNNING / FINISHED / ABORTED)
 ├─ players           : Map<SeatNo, Player>   实体集合，恰好 9 个座位
 ├─ roleComposition   : RoleComposition   (VO) 角色配置（3 狼 3 民 1 预 1 女 1 猎）
 ├─ turnOrder         : List<SeatNo>      发言顺序（由开局事件固化）
 ├─ turnIndex         : int               当前发言序号
 ├─ currentTurn       : Turn              (VO) 当前回合窗口 {playerId, phase, deadline}
 ├─ nightActions      : NightActions      (VO) 当夜已提交的狼刀/查验/用药
 ├─ votes             : Map<SeatNo, Vote> 当前投票轮的票
 ├─ deathQueue        : List<DeathRecord> 待公布与待结算的死亡
 ├─ resumePhase       : GamePhase          猎人开枪等插入阶段结束后回到的阶段
 └─ uncommittedEvents : List<DomainEvent>  本次处理的待提交事件
```

`Player` 实体字段：

```
Player
 ├─ playerId      : PlayerId   (VO)
 ├─ seatNo        : SeatNo     (VO)
 ├─ role          : Role       (VO/枚举，开局事件固化后不可变)
 ├─ camp          : Camp       (VO，由 role 派生)
 ├─ alive         : boolean
 ├─ controller    : PlayerController (VO：类型 + 引用 id，见 §3.3)
 ├─ ready         : boolean
 └─ skillState    : SkillState (VO：解药可用、毒药可用、开枪可用)
```

`Game` 对外的唯一状态变更入口（签名固定，其余文档引用同一签名）：

```java
public final class Game extends AggregateRoot {

    /** 创建对局：校验人数与配置，产生 GAME_STARTED 与首批 ROLE_ASSIGNED。 */
    public List<DomainEvent> start(StartGameCommand cmd, ActionContext ctx);

    /** 统一行动入口。DESIGN.md §6 的 "Game.handle()" 即此方法。 */
    public List<DomainEvent> handle(Action action, ActionContext ctx);

    /** 阶段推进：仅由应用层在回合/子阶段完成后调用，不接收玩家输入。 */
    public List<DomainEvent> advance(ActionContext ctx);

    /** 从快照重建后的重放入口，不校验角色之外的时序。 */
    public void replay(DomainEvent event);
}
```

`ActionContext` 承载所有非确定性来源，聚合内部禁止自行取用：

```java
public record ActionContext(
    String     eventId,       // 由应用层生成，事件唯一标识
    long       occurredAt,    // 由应用层注入，重放时不重新取值
    String     actorId,       // 触发者：真人 userId、AI playerId 或 SYSTEM
    String     commandId,     // causationId：触发本次状态变化的命令 id
    String     correlationId, // 一次完整用例
    int        expectedVersion, // 乐观并发前置条件
    long       randomSeed     // 仅开局等需要随机的场景使用
) {}
```

**决定性规则（C-4 的关键）**：聚合内禁止调用时钟、随机数与 UUID 生成器。随机性只允许
出现在"产生事件的那一刻"，且结果必须写入事件 payload。重放时不得重新随机，
`ROLE_ASSIGNED` 事件就是角色分配的唯一真相。

### 2.3 值对象清单

| 值对象 | 约束 | 说明 |
| --- | --- | --- |
| `GameId` / `RoomId` / `PlayerId` / `UserId` | 非空、格式统一 | 强类型标识，避免 String 混用 |
| `SeatNo` | 1..9 | 座位号 |
| `Role` | 枚举 | 附带 `camp()`、`nightOrder()`、`isWolfTeam()` |
| `Camp` | `WOLF` / `VILLAGE` | 胜负判定单位 |
| `GamePhase` | 枚举 | 状态机节点，转换表见 §3.2 |
| `Visibility` | 枚举 | 与事件流过滤共用同一枚举（[event-design.md §4](event-design.md)） |
| `Speech` | 长度 1..500 字符，去首尾空白 | 发言内容，超长由领域拒绝 |
| `Vote` | `abstain = true` 时 `targetSeat` 为 null | 一票，不可改（改票需新事件，第一版不支持） |
| `RoleComposition` | 总数=座位数，狼数≥1，村民阵营≥1 | 开局配置 |
| `Turn` | `{playerId, phase, turnIndex, deadline}` | 回合窗口，`deadline` 由配置给定 |
| `SkillState` | 解药/毒药/开枪 三个布尔 | 一次性技能次数 |
| `DeathRecord` | `{playerId, cause, round}` | `cause ∈ WOLF_KILL / WITCH_POISON / HUNTER_SHOT / EXILE` |
| `PlayerController` | `{type, refId}` | `HUMAN`/`AI`/`BOT` + 引用标识，见 §3.3 |

### 2.4 领域服务

规则跨越多个实体或需要比较时，放入无状态的领域服务，不放应用层（否则违反
`DESIGN.md` §4 "Application Layer 不包含游戏规则"）：

| 领域服务 | 职责 |
| --- | --- |
| `RoleCompositionPolicy` | 校验角色配置合法性；按 `randomSeed` 洗牌分配座位 |
| `TurnOrderPolicy` | 由座位与夜间死亡结果决定发言顺序（首日从最小存活座位开始，之后顺延） |
| `VoteTallyPolicy` | 计票、判定平票（平票 → 本轮无人出局，产生 `VOTE_TALLY_RESOLVED{result:TIE}`） |
| `NightResolutionPolicy` | 夜间结算顺序：狼刀 → 女巫解药 → 女巫毒药，输出死亡列表 |
| `DeathPolicy` | 死亡判定与技能可用性（被毒不能开枪、猎人开枪需 `skillState.shotAvailable`） |
| `WinConditionPolicy` | 狼全灭 → `VILLAGE` 胜；按 `rules.winCondition`：`SIDE_KILL`（默认，屠边）平民全灭或神职全灭 → `WOLF` 胜；`WOLVES_OUTNUMBER` 狼数 ≥ 好人阵营存活数 → `WOLF` 胜 |
| `ActionValidator` | 行动合法性：角色、阶段、回合、目标存活、次数 |

## 3. 核心模型

### 3.1 角色配置（第一阶段标准局）

| 角色 | 数量 | 阵营 | 夜间顺序 | 技能 |
| --- | --- | --- | --- | --- |
| `WEREWOLF` | 3 | `WOLF` | 1 | 每夜共同选择一名击杀目标 |
| `SEER` | 1 | `VILLAGE` | 2 | 每夜查验一名玩家阵营 |
| `WITCH` | 1 | `VILLAGE` | 3 | 全局一次解药、一次毒药，同夜不可同用 |
| `HUNTER` | 1 | `VILLAGE` | 无 | 被狼刀或被放逐时开枪带走一人；被毒不可开枪 |
| `VILLAGER` | 3 | `VILLAGE` | 无 | 无 |

该配置由 `RoleCompositionPolicy` 校验并可在实验室改配（`DESIGN.md` §14 AI 实验室）。
角色配置是配置数据，不是新架构；状态机与事件类型对角色数量不敏感。

### 3.2 阶段状态机

```
GAME_INIT ──start──▶ NIGHT_WOLF ──▶ NIGHT_SEER ──▶ NIGHT_WITCH ──▶ NIGHT_SETTLE
                                                                        │
                                                       (死亡结算，产生 PLAYER_KILLED)
                                                                        ▼
   ┌──────────────────────────────────────────────────────────── DAY_ANNOUNCE
   │                                                                    │
   │                                                       (公布死讯 DEATH_ANNOUNCED)
   │                                                                    ▼
   │                                                              LAST_WORDS
   │                                                                    │
   │                                                                    ▼
   │                                                              DAY_DISCUSS ──┐
   │                                                                    │        │ 轮到下一位
   │                                                                    ▼        │
   │                                                               DAY_VOTE ◀────┘
   │                                                                    │
   │                                                                    ▼
   │                                                             VOTE_SETTLE
   │                                                                    │
   │                                            (平票 → 无人出局；否则 PLAYER_KILLED)
   │                                                                    ▼
   │                                                              LAST_WORDS
   │                                                                    │
   └──────────────(未分胜负，round+1)───────────────────────────────────┘
                                                                        │
                                                             (分出胜负) ▼
                                                                   GAME_OVER
```

插入阶段 `HUNTER_SHOOT`：任意结算点产生猎人死亡时，聚合把 `resumePhase` 记为原本
将要进入的阶段，切换到 `HUNTER_SHOOT`，开枪后回到 `resumePhase`。该插入不改变
主流程，只增加一次阶段跳转，全部跳转都产生 `PHASE_CHANGED`。
夜里出局的猎人先公布当夜全部死讯（`DEATH_ANNOUNCED`），再切换到 `HUNTER_SHOOT`
（2026-10-12 所有者批准），见 `domain-model.md` §4。

阶段转换表（`GamePhase` 允许的后继，实现时作为常量表校验）：

| 当前阶段 | 允许后继 | 触发者 | 触发动作 |
| --- | --- | --- | --- |
| `GAME_INIT` | `NIGHT_WOLF` | 应用层 | `start` |
| `NIGHT_WOLF` | `NIGHT_SEER` | 领域 | `WolfKillAction` 全员提交或超时 |
| `NIGHT_SEER` | `NIGHT_WITCH` | 领域 | `SeerInspectAction` |
| `NIGHT_WITCH` | `NIGHT_SETTLE` | 领域 | `WitchAction`（用药或跳过） |
| `NIGHT_SETTLE` | `HUNTER_SHOOT` / `DAY_ANNOUNCE` | 领域 | `NightResolutionPolicy` |
| `DAY_ANNOUNCE` | `HUNTER_SHOOT` / `LAST_WORDS` | 领域 | `DeathPolicy` |
| `LAST_WORDS` | `DAY_DISCUSS` / `DAY_VOTE` / `HUNTER_SHOOT` / `NIGHT_WOLF` / `GAME_OVER` | 领域 | 遗言完成或超时 |
| `DAY_DISCUSS` | `DAY_DISCUSS` / `DAY_VOTE` | 领域 | 发言或超时，`TURN_GRANTED` 顺延 |
| `DAY_VOTE` | `VOTE_SETTLE` | 领域 | `VoteAction` 全员提交或超时 |
| `VOTE_SETTLE` | `HUNTER_SHOOT` / `LAST_WORDS` / `NIGHT_WOLF` / `GAME_OVER` | 领域 | `VoteTallyPolicy` + `WinConditionPolicy` |
| `HUNTER_SHOOT` | `resumePhase` | 领域 | `HunterShotAction` 或超时 |
| `GAME_OVER` | 无 | 领域 | 终态 |

两条与 §3.2 状态机图配套的补充说明（实现期发现的文档内部不一致，已按图为准补齐表格）：

1. **`LAST_WORDS → NIGHT_WOLF`**：被放逐者的遗言结束后，若未分出胜负就进入下一轮夜晚，
   即图中下方 `(未分胜负，round+1)` 回到 `NIGHT_WOLF` 的那条箭头。原先表格漏了这一行，
   导致"任何有人被放逐的对局都停在遗言结束时、round 永远到不了 2"。图与表以图为准。
2. **`LAST_WORDS → GAME_OVER`**：遗言阶段是最后能做胜负判定的地方（例如被放逐者使狼人达到
   人数优势）。进入终态本身是一次阶段跳转，必须产生 `PHASE_CHANGED`——`domain-model.md` §4 把
   `GAME_OVER` 的"进入时"动作写成 `PHASE_CHANGED`，`event-design.md` §14.4 也要求"每个阶段跳转
   都有 `PHASE_CHANGED`"。终局因此是两条事件：`PHASE_CHANGED(→GAME_OVER)` + `GAME_FINISHED`。
2. **`HUNTER_SHOOT` 的行动者是死者**：猎人是在死亡结算中被转入该阶段的，因此
   `ActionValidator` 的"行动者必须存活"（V2/INV-9）必须对**待开枪的猎人本人**豁免，
   否则对局会在猎人死亡处永久卡死。豁免收紧到 `pendingHunter`，其他死者不受影响。
   开枪同时登记一条 `HUNTER_SHOT` 死因并把目标置为死亡；该死亡在下一个阶段跳转前用
   `DEATH_ANNOUNCED` 公布，而 `PLAYER_KILLED` 仍只记录一次死亡事实。

超时是合法输入而非异常：任何回合的 `deadline` 到期后，应用层提交一个
`SkipAction`，走同一条 `handle` 路径并产生事件。这样"AI 卡死"不会绕过领域，
重放结果唯一（C-4）。

超时看护的职责切分（实现期明确，避免应用层被迫写阶段分支而违反 §5.1）：

| 环节 | 归属 | 说明 |
| --- | --- | --- |
| 判断"是否到期" | 应用层 | 领域不读时钟（INV-16）；用 `Turn.deadline` 与 `PHASE_CHANGED.deadline` 比较 |
| 回答"到期该补什么" | 领域 | `Game.timeoutSkips(now, phaseDeadlinePassed)` 返回待提交的 `SkipAction` 与"是否需要 `advance`" |
| 提交兜底行动 | 应用层 | 每个 `SkipAction` 单独调一次 `Game.handle(action, ctx)`，ctx 按当时 `version()` 重建 |
| 阶段级收尾 | 领域 | `Game.advance(ctx)`：对自动结算阶段推进，对已满足条件的阶段收尾；**不承担超时兜底** |

`advance` 对同一状态是幂等的：条件未达成时返回空列表，"什么都不该发生"不是错误。

### 3.3 Player 与 PlayerController 分离

`DESIGN.md` §5 明确要求玩家不绑定 AI。落地方式：

```
Player (领域实体)
  │  controller: PlayerController { type, refId }
  │
  ├── HUMAN  → refId = userId      操作者：WebSocket/REST 提交行动
  ├── AI     → refId = agentId     操作者：Agent Runtime 经 Tool Gateway 提交行动
  └── BOT    → refId = strategyKey 操作者：内置规则机器人（无 LLM）
```

结果：

1. `Game` 聚合不引用 `AgentRuntime`、`LangChain4j`、`ToolGateway` 中的任何类型，
   领域模块对 AI 无编译期依赖（C-5）。
2. 同一套规则校验同时适用于真人、AI、Bot，`ActionValidator` 不需要分支。
3. AI 掉线可以把 `controller` 从 `AI` 换成 `BOT`（产生 `PLAYER_CONTROLLER_CHANGED`
   事件），对局不中断。这是 `DESIGN.md` §15 Phase 4 "AI 补位"的落点。

### 3.4 聚合与应用层的交互契约

```
Command ──▶ Application Service ──▶ GameRepository.load(gameId)
                                        │
                                   Game.handle(action, ctx)
                                        │ 校验 INV-1..INV-12
                                        ▼
                                 List<DomainEvent>
                                        │
                        EventStorePort.append(gameId, expectedVersion, events)
                                        │  单写者锁 + 版本校验
                                        ▼
                              Redis Stream 追加成功
                                        │
                        EventPublisherPort.publish(events)  ──▶ 消费组
```

应用服务不判断"这步该不该允许"，只做四件事：加载聚合、构造 `ActionContext`、
调用领域方法、持久化事件。任何"如果玩家是狼人则……"的判断出现在应用层，
都视为违反 `DESIGN.md` §4（C-3）。

## 4. 不变量清单

### 4.1 Game 聚合

| 编号 | 不变量 | 违反时的表现 |
| --- | --- | --- |
| INV-1 | 座位数恰好等于 `roleComposition.playerCount`，座位号不重复 | 开局被拒绝 |
| INV-2 | 每个玩家角色在 `GAME_STARTED` 后不可变 | 无接口可改，仅重放写入 |
| INV-3 | 行动必须来自存活玩家，且 `actorId` 与该座位的 `controller` 匹配 | `ACTION_NOT_ALLOWED` |
| INV-4 | 行动必须匹配当前阶段与角色（`ActionValidator`） | `ACTION_NOT_ALLOWED` |
| INV-5 | 同一回合窗口内，同一玩家对同一动作类型只接受一次提交 | 第二次提交返回幂等结果 |
| INV-6 | 狼刀目标不得为狼人阵营玩家 | `ACTION_INVALID_TARGET` |
| INV-7 | 女巫在同一夜不得同时使用解药与毒药 | `ACTION_NOT_ALLOWED` |
| INV-8 | 被毒死亡的猎人不产生 `HUNTER_SHOOT` 阶段 | 直接进入下一阶段 |
| INV-9 | 死者不再拥有回合，不再被投票，不再成为技能目标 | 校验拒绝 |
| INV-10 | 投票目标必须存活；弃权以 `abstain = true` 且 `targetSeat = null` 表达 | `ACTION_INVALID_TARGET` |
| INV-11 | 每次状态变化恰好产生 ≥1 个领域事件，且 `version` 严格 +1 | 事件存储拒绝写入 |
| INV-12 | `GAME_OVER` 之后不接受任何行动 | `GAME_ALREADY_FINISHED` |

### 4.2 事件化不变量（C-4）

| 编号 | 不变量 |
| --- | --- |
| INV-13 | 未产生事件的字段变更视为 bug：`Game` 可变状态只能由 `replay(event)` 修改 |
| INV-14 | 事件一旦写入不可修改、不可删除，只允许追加 |
| INV-15 | 快照不是真相来源，任何快照都必须可由事件流重新推导 |
| INV-16 | 重放同一事件流必须得到逐字段相同的聚合状态（含随机结果） |

### 4.3 其他聚合

| 编号 | 不变量 |
| --- | --- |
| INV-R1 | 房间座位数固定，同一座位同一时刻只有一个玩家 |
| INV-R2 | 只有全部座位就绪（真人 `ready`、AI 座位自动就绪）才能开局 |
| INV-R3 | 房间状态变化（创建、入座、就绪、离开、AI 补位）全部产生事件 |
| INV-U1 | 用户名唯一；口令只以散列形式存储 |
| INV-U2 | 账号状态变化（注册、登录、登出）产生事件 |

## 5. 分层与依赖规则

### 5.1 四层职责（对应 `DESIGN.md` §4）

| 层 | 职责 | 允许依赖 | 禁止 |
| --- | --- | --- | --- |
| Interface | REST API、WebSocket、参数转换、鉴权、DTO 映射 | Application | 直接依赖 Domain 内部对象做规则判断；直接访问 Redis/SQLite |
| Application | 用例编排、事务与并发前置条件、事件发布、视图查询 | Domain、Application Port | 游戏规则；跨聚合直接改对象；不产生事件的状态写入 |
| Domain | 规则、状态机、行动校验、领域事件 | 仅 Java 标准库 | Redis、SQLite、LangChain4j、LangGraph4j、WebSocket、时钟、随机数、UUID（C-5） |
| Infrastructure | Redis、SQLite、LangChain4j、WebSocket、文件存储的实现 | Domain 定义的端口、Application Port | 包含游戏规则；被 Domain 反向依赖 |

### 5.2 包结构

```
com.wolfmind
├── interface
│   ├── rest        GameController RoomController UserController
│   │               ReplayController AnalysisController LabController
│   └── ws          WebSocketGateway SubscriptionRegistry MessageCodec
│                   GameEventBroadcaster VisibilityFilter
├── application
│   ├── game        StartGameApplicationService SubmitActionApplicationService
│   │               TimeoutApplicationService RecoverGameApplicationService
│   │               GetGameViewApplicationService
│   ├── room        CreateRoomApplicationService JoinRoomApplicationService
│   │               SeatApplicationService ReadyApplicationService
│   ├── agent       AgentTurnApplicationService ToolInvocationApplicationService
│   └── port        EventStorePort SnapshotStorePort GameStateCachePort
│                   EventPublisherPort GameViewQueryPort AgentRuntimePort
│                   NotificationPort UnitOfWorkPort
├── domain
│   ├── game
│   │   ├── model   Game Player Role GamePhase Action Vote Speech Turn
│   │   │           SkillState DeathRecord RoleComposition ActionContext
│   │   ├── event   DomainEvent GameEventType 各具体事件类
│   │   ├── service RoleCompositionPolicy TurnOrderPolicy VoteTallyPolicy
│   │   │           NightResolutionPolicy DeathPolicy WinConditionPolicy
│   │   │           ActionValidator
│   │   └── repository GameRepository（接口，实现在 Infrastructure）
│   ├── room        Room Seat RoomConfig RoomRepository
│   └── user        User Session UserRepository
└── infrastructure
    ├── redis       RedisEventStore RedisSnapshotStore RedisGameStateCache
    │               RedisLock RedisSubscriber
    ├── sqlite      SqliteGameArchive SqliteAgentMemoryRepository
    │               SqliteStatisticsRepository SqliteUserRepository
    ├── ai          LangChain4jLlmClient LangGraph4jWorkflowEngine
    │               AgentMemoryAdapter
    └── ws          WebSocketServerAdapter
```

`domain.game.event` 中每个事件类的命名 = 事件类型转 PascalCase + `Event` 后缀：
`PLAYER_VOTED` → `PlayerVotedEvent`，与 `DESIGN.md` §6 示例一致。

### 5.3 依赖规则的机器校验

2026-10-14 的源码组织与装配职责见 [后端包结构说明](../backend/package-layout.md)。
房间开局与 Agent 恢复已迁入应用层，通过 AgentProvisioningPort 隔离 Agent 内部配置；
配置类按职责拆分在 infrastructure.config，不调整以下依赖约束。

以下规则写成测试，任何一条失败即构建失败。这是"不改变已有架构"的长期保障（C-1、C-3）：

| 编号 | 规则 |
| --- | --- |
| DEP-1 | `domain..` 不得依赖 `infrastructure..`、`application..`、`interface..` |
| DEP-2 | `domain..` 不得出现 `redis`、`sqlite`、`langchain4j`、`langgraph4j`、`websocket`、`java.time.Clock`、`java.util.Random`、`UUID` 的引用 |
| DEP-3 | `application..game` 与 `application..room` 不得依赖 `application..agent`，反之亦然（同层通过事件与端口协作） |
| DEP-4 | `interface..rest` 之外不得出现 HTTP 类型；`interface..ws` 之外不得出现 WebSocket 类型 |
| DEP-5 | 只有 `infrastructure..` 可以依赖第三方 SDK |
| DEP-6 | `domain..game.model` 不得引用 `domain..game.repository`（聚合不感知持久化） |
| DEP-7 | `application..agent` 不得引用 `domain..game.model`（AI 侧只认 `GameView`，C-5） |

DEP-7 是最容易被无意破坏的一条：它把"AI 不直接操作 Game Domain"从约定升级为编译期约束。

两条实现期裁决（不改架构，只消除与语言/工具链的冲突）：

1. **DEP-5 的"第三方 SDK"指第三方坐标，不含 JDK 平台模块**。`java.*`、`javax.*`、`jdk.*`、
   `com.sun.net.httpserver` 属于 JDK 自带能力，不受 DEP-5 约束。真正被拦的是 `org.xerial`
   这类外部依赖。与之配套的更强断言是"落点校验"：`java.sql` / `javax.sql` / `org.xerial` 只能
   出现在 `infrastructure.sqlite`，`com.sun.net.httpserver` 只能出现在 `interfaces.rest`。
2. **DEP-4 的 WebSocket 两半归属**：§5.2 把 `WebSocketServerAdapter` 放在 `infrastructure/ws`，
   与本表的 "`interface..ws` 之外不得出现 WebSocket 类型" 看似矛盾，实际是两件事——
   **协议与语义在 `interfaces.ws`**（消息信封、编解码、可见性过滤、订阅关系），
   **传输管道在 `infrastructure.ws`**（服务器适配器、连接生命周期）。DEP-4 约束的是前者。

另外，Java 关键字 `interface` 不能作为包名，因此接口层的 Java 包名是 `com.wolfmind.interfaces.*`
（目录仍为 `interface/`）。这是命名细节，见根目录 `AGENTS.md` §15.1。

## 6. 应用层编排模式

### 6.1 命令处理模板

```java
public final class SubmitActionApplicationService {

    public ActionResult submit(SubmitActionCommand cmd) {
        // 1. 幂等：同 (gameId, idempotencyKey) 已处理则直接返回原结果
        // 2. 单写者锁：按 gameId 获取，失败快速返回 GAME_BUSY
        // 3. 加载：SnapshotStore 最新快照 + EventStore 其后的增量事件
        // 4. 构造 ActionContext（时间、事件 id、随机种子、expectedVersion）
        // 5. 领域：action 由 ActionCodec 还原为 Action 值对象
        //    List<DomainEvent> events = game.handle(action, ctx);
        // 6. 持久化：EventStorePort.append(gameId, expectedVersion, events)
        // 7. 发布：EventPublisherPort.publish(events)，消费者各自推进
        // 8. 返回：ActionResult{version, eventIds, view}
    }
}
```

顺序不可调换：先持久化后发布（`DESIGN.md` §6 流程），发布失败不影响状态一致性，
由事件存留 + 消费组位点补偿。

### 6.2 用例清单

| 用例 | 应用服务 | 输入 | 产生的领域事件 |
| --- | --- | --- | --- |
| 创建房间 | `CreateRoomApplicationService` | 房间配置 | `ROOM_CREATED` |
| 入座/离开 | `SeatApplicationService` | `roomId, seatNo` | `PLAYER_SEATED` / `PLAYER_LEFT` |
| 准备 | `ReadyApplicationService` | `roomId` | `PLAYER_READY` |
| 开始对局 | `StartGameApplicationService` | `roomId` | `GAME_STARTED` + 9×`ROLE_ASSIGNED` + `PHASE_CHANGED` |
| 提交行动 | `SubmitActionApplicationService` | `gameId, actionType, params, expectedVersion, idempotencyKey` | 按动作类型，见 [event-design.md §7](event-design.md) |
| 阶段推进 / 超时处理 | `TimeoutApplicationService` | 调度器登记的 `TurnTask`（ADVANCE / TIMEOUT） | 经领域处理后的事件；例如 `PHASE_CHANGED`、`SPEECH_SKIPPED`、`PLAYER_VOTED`；无事件不写入 |
| 读取视图 | `GetGameViewApplicationService` | `gameId, viewerId` | 无（只读，不产生事件） |
| 恢复对局 | `RecoverGameApplicationService` | `gameId` | `GAME_RECOVERED`（运维事件，`visibility=JUDGE`） |
| AI 回合 | `AgentTurnApplicationService` | `TURN_GRANTED` 事件 | 无（只发起 AI 推理，写路径回到"提交行动"） |

只读用例不产生事件，例外在 INV-11 的定义内：不变量约束的是"状态变化"，
读模型投影的推进由消费组位点记录，不进入领域事件流。

## 7. 防腐层：Agent 上下文访问 Game 上下文

```
Agent Context                                  Game Context
─────────────                                  ────────────
StrategyLayer ──▶ ActionProposal
                      │
                      ▼
                 ToolGateway ─────────▶ PermissionCheck（角色 × 阶段 × 次数 × 目标）
                      │                          │
                      │                          ▼
                      │                 SubmitActionApplicationService
                      │                          │
                      │                          ▼
                      │                    Game.handle(action)
                      │                          │
                      ▼                          ▼
              AgentTrace（SQLite）         DomainEvent（Redis Stream）
                                                 │
                      ┌──────────────────────────┘
                      ▼
             GameViewQueryPort（只读，可见性过滤）
                      │
                      ▼
             QueryGameStateTool ──▶ Agent Context
```

防腐层的三条硬规则：

1. Agent Context 只能通过端口拿 `GameView`，不能拿 `Game` 聚合引用（DEP-7）。
2. Agent 的写请求一律转成 `Action`，由领域校验；工具返回值里的"成功"只表示
   "命令已被接受"，最终是否生效由 `PLAYER_VOTED` 等事件决定。
3. 工具返回的拒绝原因是结构化枚举（`ACTION_NOT_ALLOWED` 等），不是异常文本，
   便于策略层下一轮修正。

## 8. 一致性边界与并发

| 场景 | 机制 | 说明 |
| --- | --- | --- |
| 同一 `gameId` 并发写 | Redis 单写者锁 `game:{id}:lock` + `expectedVersion` 校验 | 锁保证串行，版本号保证不丢更新 |
| 跨 `gameId` | 无共享可变状态 | 不同对局的锁与流互不影响 |
| 聚合与投影 | 最终一致 | 投影（state 缓存、SQLite 归档、WebSocket 推送、Agent 记忆）都从事件流消费 |
| 视图读取 | 读投影 | 读接口不加载聚合，避免读放大 |
| 幂等 | `idem:{gameId}:{key}` | 玩家重试、AI 重试、网络重发都命中同一结果 |

`DESIGN.md` 只有一个写入路径（聚合 → 事件），因此不存在"命令模型与事件模型双写"
的问题；`game:{id}:state` 是投影，允许短暂滞后，任何业务判断都不得只读它。

## 9. 演进规则（不改架构的前提下扩展）

| 扩展点 | 做法 | 不触碰的部分 |
| --- | --- | --- |
| 新增角色（如白狼王） | 加 `Role` 枚举值 + `RoleComposition` 配置 + 对应 `Action` 与事件类型 + 策略类 | 状态机主干、事件信封、聚合边界 |
| 新增阶段 | 在 §3.2 转换表加一行 + 转换校验常量 | 信封、Event Store、门面 |
| 新增 AI 模型 | 实验室配置里的模型标识，`LangChain4jLlmClient` 换实现 | 领域、协议、事件 |
| 真人 + AI 混合 | `PlayerController` 换类型 + `PLAYER_CONTROLLER_CHANGED` 事件 | 规则校验 |
| 多实例部署 | WebSocket 订阅关系外置到 Redis，事件流消费组天然支持多实例 | 领域、事件契约、协议语义 |
| 事件结构升级 | 新版本事件类型 + upcaster（[event-design.md §6](event-design.md)） | 既有事件不可改（INV-14） |

## 10. 与 DESIGN.md 的对应关系（自检表）

| DESIGN.md 要求 | 本文落点 | 是否新增技术选型 |
| --- | --- | --- |
| §4 Interface/Application/Domain/Infrastructure 四层 | §5.1、§5.2 | 否 |
| §4 Application Layer 不含规则 | §2.4、§5.1、DEP 规则 | 否 |
| §5 Game Aggregate 含 players/phase/events | §2.2 | 否 |
| §5 Player 不绑定 AI，经 PlayerController 抽象 | §3.3 | 否 |
| §6 状态变化产生事件，经 Event Bus 到消费者 | §3.4、§6.1 | 否 |
| §7 Redis Stream 作为 Event Store | §8、[event-design.md](event-design.md) | 否 |
| §8 每 50 事件快照 | [event-design.md §8](event-design.md) | 否 |
| §9 AI 不在 Player 上 | §3.3、§7 | 否 |
| §12 AI 经 Tool Gateway 且需 Permission Check | §7 | 否 |
| §13 Redis 5 类键 / SQLite 6 张表 | [redis-schema.md](../backend/redis-schema.md)、[database-schema.md](../backend/database-schema.md) | 否 |
| §14 八个页面 | [page-design.md](../frontend/page-design.md) | 否 |

没有引入 `DESIGN.md` 之外的中间件、框架、存储或协议；新增内容全部是同一套
架构内的可执行细节。
