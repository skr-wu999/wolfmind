# WolfMind 详细设计：领域模型

本文是 `DESIGN.md` §5（游戏领域设计 DDD）的细化。领域层是狼人杀规则的唯一实现位置，
不含任何基础设施依赖，也不含任何 AI 相关类型。

## 1. 模型全景

```
                         ┌──────────────────────────────────────────┐
                         │            Game (AggregateRoot)          │
                         │  gameId / version / phase / round         │
                         │  status / rules / roleComposition         │
                         │  turnOrder / turnIndex / currentTurn      │
                         │  nightActions / votes / deathQueue        │
                         │  resumePhase / players                    │
                         └───────────────┬──────────────────────────┘
                                         │ 1..9
                         ┌───────────────▼──────────────────────────┐
                         │              Player (Entity)             │
                         │  playerId / seatNo / role / camp / alive  │
                         │  controller / ready / skillState          │
                         └──────────────────────────────────────────┘

     值对象：SeatNo Role Camp GamePhase RoleComposition GameRules Vote
             Speech Turn SkillState DeathRecord NightActions PlayerController

     领域服务：RoleCompositionPolicy TurnOrderPolicy VoteTallyPolicy
               NightResolutionPolicy DeathPolicy WinConditionPolicy ActionValidator

     产出：DomainEvent（TURN_GRANTED PLAYER_VOTED PLAYER_KILLED …）
```

聚合边界就是一致性边界：`Game` 内部所有对象在同一个事务里一致，
`Room`、`User`、`Agent` 不共享可变状态。跨聚合协作只通过事件与 `correlationId`。

## 2. Game 聚合根

### 2.1 字段

| 字段 | 类型 | 可变 | 说明 |
| --- | --- | --- | --- |
| `gameId` | `GameId` | 否 | 聚合标识，也是事件流分区键 |
| `version` | `int` | 是 | 已产生事件数，等于下一条事件的 `version` 减一 |
| `status` | `GameStatus` | 是 | `INIT` / `RUNNING` / `FINISHED` / `ABORTED` |
| `phase` | `GamePhase` | 是 | 当前阶段，见 §4 |
| `round` | `int` | 是 | 第几轮，从 1 开始，夜与昼同属一轮 |
| `players` | `Map<SeatNo, Player>` | 是 | 恒为 9 个座位 |
| `roleComposition` | `RoleComposition` | 否 | 开局固化 |
| `rules` | `GameRules` | 否 | 开局固化，写入 `GAME_STARTED.rules` |
| `turnOrder` | `List<SeatNo>` | 是 | 当前轮发言顺序，开局与每轮开始时计算 |
| `turnIndex` | `int` | 是 | 当前发言序号，越界表示本轮发言结束 |
| `currentTurn` | `Turn?` | 是 | 当前回合窗口，无回合时为 `null` |
| `nightActions` | `NightActions` | 是 | 当夜狼刀目标、查验结果、用药 |
| `votes` | `Map<SeatNo, Vote>` | 是 | 当前投票轮，进入 `DAY_VOTE` 时清空 |
| `deathQueue` | `List<DeathRecord>` | 是 | 已结算未公布的死亡 |
| `resumePhase` | `GamePhase?` | 是 | 插入阶段（`HUNTER_SHOOT`）的返回点 |
| `pendingHunter` | `SeatNo?` | 是 | 待开枪的猎人座位 |
| `uncommittedEvents` | `List<DomainEvent>` | 是 | 本次处理产生、待应用层持久化的事件 |

### 2.2 行为方法

```java
public final class Game extends AggregateRoot {

    // 开局：校验 INV-1..INV-3，洗牌分配角色，产生 11 条事件
    public List<DomainEvent> start(StartGameCommand cmd, ActionContext ctx);

    // 唯一行动入口，DESIGN.md §6 的 Game.handle()
    public List<DomainEvent> handle(Action action, ActionContext ctx);

    // 阶段推进：只由应用层在"条件达成"或"超时"后调用
    public List<DomainEvent> advance(ActionContext ctx);

    // 重放入口：仅由 GameRepository 在恢复与投影重建时调用
    public void replay(DomainEvent event);

    // 只读查询，供应用层组装视图；不含规则判断
    public GameSnapshot snapshot();
}
```

`handle` 的固定流程，任何一步失败都返回拒绝结果且不产生事件：

```
handle(action, ctx)
 1. status != RUNNING → GAME_ALREADY_FINISHED / GAME_NOT_RUNNING
 2. ActionValidator.validate(this, action)  → 逐条不变量
 3. 分派到阶段处理器 PhaseHandler[phase].apply(this, action, ctx)
 4. 阶段处理器内部：改状态 + 记录事件（两者必须成对）
 5. 检查阶段退出条件 → 可能追加 PHASE_CHANGED 与结算事件
 6. 检查胜负 → 可能追加 GAME_FINISHED
 7. 返回 uncommittedEvents（INV-11：非空）
```

第 3 步的"改状态与记录事件成对"由实现方式保证：所有状态修改都写在
`private void apply(XxxEvent e)` 方法里，`replay` 与命令处理共用同一批 `apply`，
因此"命令处理后的内存状态"与"重放同样事件后的内存状态"必然一致（INV-16）。

### 2.3 生命周期

| 状态 | 进入条件 | 允许的操作 | 退出 |
| --- | --- | --- | --- |
| `INIT` | 聚合创建 | `start` | `GAME_STARTED` → `RUNNING` |
| `RUNNING` | `GAME_STARTED` | `handle` / `advance` | `GAME_FINISHED` → `FINISHED`；`GAME_ABORTED` → `ABORTED` |
| `FINISHED` | 胜负判定成立 | 只读 | 终态 |
| `ABORTED` | 人数不足、不可恢复；连续 `stalledRoundsLimit` 轮无人出局（`reason=STALLED`） | 只读 | 终态；`finishReason` 记中止原因，无胜者 |

## 3. 规则配置

`GameRules` 在开局写入 `GAME_STARTED.rules`，重放时使用事件里的规则，
不使用服务端当前配置（否则实验室跑过的旧对局无法复现）。

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `speechMaxLength` | 500 | 单次发言字符上限（`DESIGN.md` §14 游戏页面发言区） |
| `turnDeadlineMs` | 60000 | 真人回合时限（发言、遗言、猎人开枪），同时是三个夜间行动阶段的窗口（2026-10-13 所有者批准由 30000 调整为 60000） |
| `agentTurnDeadlineMs` | 60000 | AI 回合时限（2026-10-12 由 20000 调整为 30000：真实模型下狼人、女巫发言常在 20 s 处超时转模板；2026-10-13 所有者批准调整为 60000，其中包含驱动层的发言阅读停顿，见 agent-runtime.md §4.1） |
| `voteDeadlineMs` | 45000 | 投票阶段整体时限 |
| `firstNightLastWords` | `true` | 首夜死亡玩家有遗言 |
| `laterNightLastWords` | `false` | 第二夜起夜间死亡无遗言 |
| `exileLastWords` | `true` | 被放逐者始终有遗言 |
| `dayShotLastWords` | `true` | 本轮放逐之后被猎人开枪带走者有遗言（2026-10-12 所有者批准）；开局事件缺该键时按 `false` 解码，旧对局按当时行为重放 |
| `witchSelfSave` | `true` | 女巫可以把解药用在自己身上；是否只限首夜由下一项决定（2026-10-13 所有者批准，由 `false` 调整为主流规则） |
| `witchSelfSaveFirstNightOnly` | `true` | 自救只在第 1 夜（`round <= 1`）可用，第二夜起不可自救（2026-10-13 所有者批准）；开局事件和快照缺该键时按 `false` 解码，旧对局按当时行为重放 |
| `witchSaveTargetMustBeKilled` | `true` | 解药只能救当夜被狼刀的目标 |
| `allowWolfEmptyKill` | `true` | 允许狼队全员弃刀形成空刀 |
| `wolfKillTieBreak` | `MIN_SEAT` | 狼刀提名平票时取座位号最小者，保证确定性 |
| `voteTieRule` | `NO_EXILE` | 投票平票时无人出局 |
| `hunterCanShootWhenPoisoned` | `false` | 被毒不能开枪 |
| `winCondition` | `SIDE_KILL` | 屠边：平民（`VILLAGER`）全部出局或神职（好人阵营中非 `VILLAGER`）全部出局时狼人胜；开局时就没有成员的一边不参与判定（2026-10-13 所有者批准，由 `WOLVES_OUTNUMBER` 调整）。可选 `WOLVES_OUTNUMBER`：狼数 ≥ 好人阵营存活数时狼人胜。旧对局的开局事件里写有当时的值，按原值重放 |
| `abstainAllowed` | `true` | 允许弃权 |
| `stalledRoundsLimit` | `3` | 含当前轮在内连续这么多轮无人出局（无死亡记录）时，本轮结束不进入下一轮，而是 `PHASE_CHANGED(→GAME_OVER)` + `GAME_ABORTED(reason=STALLED)`；`0` 表示不启用。用于全员挂机的对局（2026-10-12 所有者批准）；开局事件缺该键时按 `0` 解码 |

房间开局通过应用层 GameRulesCodec 合并 `config.rules` 的部分覆盖，创建/开局均校验未知字段、类型、
整数范围及枚举；不提供的字段取上述默认值。领域 GameRules 继续负责规则值约束。
已开局规则固化在 GAME_STARTED 中；事件/快照重放使用独立的历史解码逻辑，保留旧字段缺省含义，
不使用房间配置解码器重新合并当前默认值。

## 4. 阶段状态机

阶段枚举与转换表见 [ddd-design.md §3.2](../architecture/ddd-design.md)。这里给出每个阶段的
进入动作、接受的行动、退出条件与超时行为。

| 阶段 | 进入时 | 接受行动 | 退出条件 | 超时行为 |
| --- | --- | --- | --- | --- |
| `GAME_INIT` | 创建聚合 | `StartGameAction` | 开局校验通过 | 无超时 |
| `NIGHT_WOLF` | `PHASE_CHANGED` | `WolfKillAction`（每名存活狼人一次） | 全部存活狼人已提交，或超时 | 未提交者视为弃刀 |
| `NIGHT_SEER` | `PHASE_CHANGED` | `SeerInspectAction` | 提交或超时 | `SEER_SKIPPED` |
| `NIGHT_WITCH` | `PHASE_CHANGED` | `WitchAction` | 提交或超时 | `WITCH_PASSED` |
| `NIGHT_SETTLE` | `PHASE_CHANGED` | 无（领域自动结算） | `NightResolutionPolicy` 完成 | 无超时 |
| `DAY_ANNOUNCE` | `PHASE_CHANGED` | 无 | `DEATH_ANNOUNCED` 产生 | 无超时 |
| `LAST_WORDS` | `PHASE_CHANGED` | `SpeechAction`（仅遗言玩家） | 发言或超时 | `SPEECH_SKIPPED` |
| `DAY_DISCUSS` | `PHASE_CHANGED` + `TURN_GRANTED` | `SpeechAction`（当前回合玩家） | 全部存活玩家发言完毕 | 未发言者 `SPEECH_SKIPPED`，发放下一回合 |
| `DAY_VOTE` | `PHASE_CHANGED`（清空 `votes`） | `VoteAction`（存活玩家各一次，无顺序要求） | 全员提交或超时 | 未提交者记为弃权票 |
| `VOTE_SETTLE` | `PHASE_CHANGED` | 无 | `VoteTallyPolicy` + 死亡结算 | 无超时 |
| `HUNTER_SHOOT` | `PHASE_CHANGED`（记录 `resumePhase`） | `HunterShotAction`（仅该猎人） | 开枪、放弃或超时 | `HUNTER_SKIPPED` |
| `GAME_OVER` | `PHASE_CHANGED` | 无 | 终态 | 无超时 |

`LAST_WORDS` 的遗言队列按座位号排序，由遗言游标逐个发放 `TURN_GRANTED`，每名死者一次。
夜间死亡后的遗言队列取本轮全部有遗言资格的死者；本轮发生放逐后，队列只取放逐记录及其之后的死亡
（例如被放逐的猎人开枪带走的目标），清晨已发表遗言的夜间死者不再重复。
白天被猎人带走者（本轮放逐记录之后、死因含 `HUNTER_SHOT`）按 `dayShotLastWords` 决定，默认有遗言；
夜里出局的猎人天亮开枪带走的目标仍按 `firstNightLastWords` / `laterNightLastWords` 判断。

夜里出局的猎人**先公布死讯再开枪**（2026-10-12 所有者批准）：`NIGHT_SETTLE` 结算出可开枪的猎人时，
先产生覆盖当夜全部死者的 `DEATH_ANNOUNCED`，再 `PHASE_CHANGED → HUNTER_SHOOT`。这样同夜被毒的玩家
在开枪阶段已公开出局，不会出现在猎人的可选目标里，猎人也不会通过"选中被拒绝"提前得知死讯。
开枪带走的目标另有一条 `DEATH_ANNOUNCED`。白天被放逐的猎人本来就在放逐时公布，不受影响。
此前按"先开枪、开枪阶段结束再公布"记录的对局按事件原样重放，不重新计算顺序。

无技能持有者的阶段（例如预言家已死）只产生 `PHASE_CHANGED`，不产生跳过事件：
阶段跳转本身就是状态变化，已有事件承载。

`DAY_VOTE` 不使用 `TURN_GRANTED`：投票是同时提交，没有发言顺序，
只有阶段级 `deadline`。`NIGHT_WOLF` 同理，狼人各自提名，没有回合窗口。

服务端超时看护读取已提交的截止时间，再将是否到期交给 `timeoutSkips`。
三个夜间行动阶段的 `PHASE_CHANGED.deadline` 均为进入时间加该局
`turnDeadlineMs`；`DAY_VOTE` 使用 `voteDeadlineMs`。发言、遗言与猎人
使用各自 `TURN_GRANTED.deadline`，猎人不会因阶段到期标记被提前跳过。
狼人已经提名时，即使仍在等待同伴，读取视图也不再给该狼人行动权限。

## 5. 实体与值对象

### 5.1 Player

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `playerId` | `PlayerId` | 对局内稳定标识 |
| `seatNo` | `SeatNo` | 1..9 |
| `role` | `Role` | 开局固化（INV-2） |
| `camp` | `Camp` | 由 `role` 派生，不单独存储 |
| `alive` | `boolean` | 死亡后不再拥有回合、不可被投票、不可被技能指向 |
| `controller` | `PlayerController` | `HUMAN` / `AI` / `BOT` + 引用标识，见 [ddd-design.md §3.3](../architecture/ddd-design.md) |
| `ready` | `boolean` | 房间阶段使用（Room 聚合里同步保存） |
| `skillState` | `SkillState` | `antidoteAvailable` / `poisonAvailable` / `shotAvailable` |

`Player` 不含 `Agent`、`LlmClient`、`ToolGateway` 等任何 AI 类型（C-5）。
`controller.type == AI` 只说明"这个座位由 Agent Runtime 驱动"，
驱动方式在领域之外。

### 5.2 Role 与 Camp

| Role | Camp | 夜间顺序 | 能力 |
| --- | --- | --- | --- |
| `WEREWOLF` | `WOLF` | 1 | 每夜与队友共同选择击杀目标 |
| `SEER` | `VILLAGE` | 2 | 每夜查验一名玩家的阵营 |
| `WITCH` | `VILLAGE` | 3 | 全局一次解药、一次毒药，同夜不可同用 |
| `HUNTER` | `VILLAGE` | 无 | 被狼刀或被放逐时开枪；被毒不可开枪 |
| `VILLAGER` | `VILLAGE` | 无 | 无技能 |

数量的唯一来源是 `RoleComposition`（默认 3 / 3 / 1 / 1 / 1），
新增角色只需加枚举值与配置项（[ddd-design.md §9](../architecture/ddd-design.md)）。

### 5.3 Action 体系

```java
public sealed interface Action permits
        StartGameAction, SpeechAction, VoteAction, WolfKillAction,
        SeerInspectAction, WitchAction, HunterShotAction, SkipAction { }

record SpeechAction(SeatNo actor, String content)                  implements Action {}
record VoteAction(SeatNo actor, SeatNo target /*null=弃权*/, boolean abstain) implements Action {}
record WolfKillAction(SeatNo actor, SeatNo target /*null=弃刀*/)    implements Action {}
record SeerInspectAction(SeatNo actor, SeatNo target)              implements Action {}
record WitchAction(SeatNo actor, WitchMode mode /*SAVE|POISON|PASS*/, SeatNo target) implements Action {}
record HunterShotAction(SeatNo actor, SeatNo target /*null=放弃*/)  implements Action {}
record SkipAction(SeatNo actor, SkipReason reason /*TIMEOUT|VOLUNTARY*/) implements Action {}
```

Action 是"玩家意图"，不是"状态变更指令"：意图是否被接受由 `ActionValidator`
与阶段处理器决定。`SkipAction` 是超时与主动跳过共用的表达，它同样会落到具体事件
（`SPEECH_SKIPPED` / `WITCH_PASSED` / `HUNTER_SKIPPED` / 弃权票 / 空刀），
因此超时不绕过领域。

投票阶段的主动 SkipAction 与 VoteAction 共用提交次数校验，禁止修改已有票；
主动跳过须遵守 `abstainAllowed`。TIMEOUT 仅接受 ActionContext.isSystem，外部命令入口另行拒绝其冒用。
应用层超时服务负责核验已提交窗口的截止时间，领域 timeoutSkips 只枚举当前未投票者；
系统超时补记弃权不受主动弃权开关限制。被拒绝的投票/跳过不修改快照、版本或事件流。

Action 与事件是两套类型：外部输入只有 Action（REST 的 `actionType` 与工具调用
都映射到 Action），事件只由领域产生。

### 5.4 其他值对象

| 值对象 | 字段 | 约束 |
| --- | --- | --- |
| `Vote` | `voterSeat, targetSeat?, abstain, at` | 一人一票，提交后不可改 |
| `Speech` | `content` | 1..`rules.speechMaxLength` 字符，去首尾空白，非空 |
| `Turn` | `playerId, seatNo, phase, turnIndex, deadline` | `deadline` 写入 `TURN_GRANTED` |
| `SkillState` | `antidoteAvailable, poisonAvailable, shotAvailable` | 一次性技能用后置 false |

2026-10-14 用药修复：`WITCH_SAVED` 的 apply 消耗解药，`WITCH_POISONED` 的 apply 消耗毒药。
在线处理、快照恢复与事件重放必须得到同样的库存；每种药整局最多一次，同夜不可双药。
历史已提交事件仍按事实重放，不重新校验并取消旧行动；新命令依据正确库存拒绝重复用药。
| `DeathRecord` | `playerId, seatNo, causes:Set<DeathCause>, primaryCause, round` | `causes` 非空 |
| `DeathCause` | `WOLF_KILL, WITCH_POISON, HUNTER_SHOT, EXILE` | 枚举 |
| `NightActions` | `wolfKillTarget?, witchSaved?, witchPoisoned?, inspectedTarget?, inspectResult?` | 当夜临时状态，进入 `NIGHT_SETTLE` 后清空 |
| `RoleComposition` | `wolf, villager, seer, witch, hunter` | 合计等于座位数，狼数 ≥ 1，村民阵营 ≥ 1 |
| `PlayerController` | `type, refId` | `HUMAN` 时 `refId = userId`；`AI` 时 `refId = agentId`；`BOT` 时 `refId = strategyKey` |

## 6. 领域服务

领域服务是无状态的纯函数，输入是聚合当前状态，输出是判定结果或事件，
不访问仓储、时钟、随机数（随机种子由 `ActionContext` 传入）。

### 6.1 ActionValidator

校验顺序固定，先校验"能不能做"，再校验"做得对不对"：

| 序号 | 校验 | 失败结果 |
| --- | --- | --- |
| V1 | 对局处于 `RUNNING` | `GAME_ALREADY_FINISHED` |
| V2 | 行动座位存在且存活（`LAST_WORDS` 例外） | `ACTION_NOT_ALLOWED` |
| V3 | 行动类型在当前阶段被接受（§4 表） | `ACTION_NOT_ALLOWED` |
| V4 | 行动者角色具备该能力 | `ACTION_NOT_ALLOWED` |
| V5 | 需要回合窗口的阶段，行动者等于 `currentTurn.playerId` | `TURN_NOT_YOURS` |
| V6 | 该行动者在本阶段的提交次数未超限 | 幂等返回首次结果 |
| V7 | 目标合法：存在、存活、非自身、符合角色约束 | `ACTION_INVALID_TARGET` |
| V8 | 资源可用：`skillState` 与规则参数 | `ACTION_NOT_ALLOWED` |

V6 与幂等键是两层防护：领域内按"本阶段是否已提交"判定，应用层按
`idempotencyKey` 判定。领域层的判定保证即使幂等键丢失也不会重复计票。

### 6.2 各阶段的领域判定

| 服务 | 输入 | 算法 |
| --- | --- | --- |
| `RoleCompositionPolicy` | `RoleComposition`, `randomSeed` | 校验配置合法性；用确定性洗牌把角色分配到座位 |
| `TurnOrderPolicy` | 存活座位、上一轮首位、轮次 | 首轮从最小存活座位升序；之后从上一轮首位后的下一个存活座位顺延 |
| `WolfKillPolicy` | 存活狼人的提名 | 全部提名或超时后按票数定案；平票取最小座位号（`wolfKillTieBreak`）；全部弃刀则空刀 |
| `NightResolutionPolicy` | `NightActions` | 狼刀 → 解药（若目标是当夜被刀者）→ 毒药，合并为死亡集合与死因集合 |
| `DeathPolicy` | `DeathRecord` | 置 `alive=false`；死因含 `WITCH_POISON` 时 `shotAvailable=false`；猎人可开枪则转入 `HUNTER_SHOOT` |
| `VoteTallyPolicy` | `votes`、存活座位 | 统计有效票；最高票为 0 → `ALL_ABSTAIN`；最高票并列 → `TIE`；否则 `EXILE` |
| `WinConditionPolicy` | 玩家快照、`winCondition` | 狼数 = 0 → 村民胜（`ALL_WOLVES_ELIMINATED`）；`SIDE_KILL` 下平民全灭 → 狼人胜（`ALL_VILLAGERS_ELIMINATED`），神职全灭 → 狼人胜（`ALL_GODS_ELIMINATED`）；`WOLVES_OUTNUMBER` 下狼数 ≥ 好人阵营数 → 狼人胜（`WOLVES_OUTNUMBER_VILLAGE`）；否则继续 |
| `PhaseTransitionPolicy` | 当前阶段、退出条件 | 按转换表决定后继阶段，并校验后继合法 |

计票与胜负判定都是纯函数，可以直接单测覆盖边界：全员弃权、平票、狼人自刀出局、
最后一狼与最后一民同时死亡（`WinConditionPolicy` 的判定顺序固定为先判狼全灭，
因此同时死亡时判村民胜）。

### 6.3 夜晚结算顺序

```
输入：nightActions { wolfKillTarget, witchSaved, witchPoisoned }
1. deaths := {}
2. 若 wolfKillTarget != null
     若 witchSaved == wolfKillTarget 且解药在当夜被使用 → 该目标不死
     否则 deaths[wolfKillTarget] += WOLF_KILL
3. 若 witchPoisoned != null → deaths[witchPoisoned] += WITCH_POISON
4. 对每个 playerId：
     causes := deaths[playerId]
     primaryCause := 按 WOLF_KILL > WITCH_POISON > HUNTER_SHOT > EXILE 取首个
     产生 PLAYER_KILLED { playerId, seatNo, causes, primaryCause, round }
5. 更新 alive 与 skillState，写入 deathQueue
6. 若有死者且死者为猎人且 shotAvailable → 先产生 DEATH_ANNOUNCED（当夜全部死者），
   再 pendingHunter := 该座位，进入 HUNTER_SHOOT
输出：PLAYER_KILLED 列表（按座位号升序，保证事件顺序确定）
```

第 4 步按座位号升序产生事件，避免"集合遍历顺序"影响事件顺序（INV-16 的一部分）。

## 7. 行动到事件的映射

| Action | 允许角色 | 允许阶段 | 产生事件 | 可见性 |
| --- | --- | --- | --- | --- |
| `StartGameAction` | 房主（应用层校验） | `GAME_INIT` | `GAME_STARTED` + 9×`ROLE_ASSIGNED` + `PHASE_CHANGED` | `PUBLIC` / `PRIVATE` |
| `SpeechAction` | 任意存活玩家 | `DAY_DISCUSS` | `PLAYER_SPOKE` | `PUBLIC` |
| `SpeechAction` | 遗言玩家 | `LAST_WORDS` | `PLAYER_SPOKE` | `PUBLIC` |
| `SkipAction` | 当前回合玩家 | `DAY_DISCUSS` / `LAST_WORDS` | `SPEECH_SKIPPED` | `PUBLIC` |
| `WolfKillAction` | `WEREWOLF` | `NIGHT_WOLF` | `WOLF_KILL_NOMINATED`；定案后 `WOLF_KILL_SELECTED` | `WOLF` |
| `SkipAction` | `WEREWOLF` | `NIGHT_WOLF` | 提名放弃；全员弃刀时 `WOLF_KILL_SELECTED{targetId:null}` | `WOLF` |
| `WolfChatAction` | 存活 `WEREWOLF` | `NIGHT_WOLF` | `WOLF_CHAT_SENT`（1..500 字，每人每晚 ≤ 20 条，V6；不推进阶段） | `WOLF` |
| `SeerInspectAction` | `SEER` | `NIGHT_SEER` | `SEER_INSPECTED` | `PRIVATE` |
| `SkipAction` | `SEER` | `NIGHT_SEER` | `SEER_SKIPPED` | `PRIVATE` |
| `WitchAction{SAVE}` | `WITCH` | `NIGHT_WITCH` | `WITCH_SAVED` | `PRIVATE` |
| `WitchAction{POISON}` | `WITCH` | `NIGHT_WITCH` | `WITCH_POISONED` | `PRIVATE` |
| `WitchAction{PASS}` / `SkipAction` | `WITCH` | `NIGHT_WITCH` | `WITCH_PASSED` | `PRIVATE` |
| `HunterShotAction` | `HUNTER` | `HUNTER_SHOOT` | `HUNTER_SHOT` | `PUBLIC` |
| `SkipAction` | `HUNTER` | `HUNTER_SHOOT` | `HUNTER_SKIPPED` | `PUBLIC` |
| `VoteAction` | 任意存活玩家 | `DAY_VOTE` | `PLAYER_VOTED` | `PUBLIC` |
| 领域自动 | 无 | `NIGHT_SETTLE` / `VOTE_SETTLE` | `PLAYER_KILLED`、`VOTE_TALLY_RESOLVED` | `JUDGE` / `PUBLIC` |
| 领域自动 | 无 | `DAY_ANNOUNCE` | `DEATH_ANNOUNCED` | `PUBLIC` |
| 领域自动 | 无 | 任意阶段跳转 | `PHASE_CHANGED` | `PUBLIC` |
| 领域自动 | 无 | 胜负成立 | `GAME_FINISHED` | `PUBLIC` |

每个阶段发放回合时产生 `TURN_GRANTED`（`DAY_DISCUSS` 每位发言者一条，
`HUNTER_SHOOT` 进入时一条）。

## 8. 不变量与实现位置

| 不变量 | 实现位置 | 校验时机 |
| --- | --- | --- |
| INV-1 座位数正确 | `Game.start` | 开局 |
| INV-2 角色不可变 | 无 setter，`role` 只在 `ROLE_ASSIGNED` 的 `apply` 中写入 | 编译期 + 重放 |
| INV-3 行动者存活且控制器匹配 | `ActionValidator` V2，`controller` 匹配由应用层校验 | 每次行动 |
| INV-4 行动匹配阶段与角色 | `ActionValidator` V3、V4 | 每次行动 |
| INV-5 同回合单次提交 | `ActionValidator` V6 + `nightActions` / `votes` 已含该座位 | 每次行动 |
| INV-6 狼刀目标非狼人 | `ActionValidator` V7（`WolfKillPolicy` 内二次确认） | 每次狼刀 |
| INV-7 女巫同夜不可双药 | `ActionValidator` V8（已用任一药则拒绝另一药） | 女巫行动 |
| INV-8 被毒猎人不开枪 | `DeathPolicy` | 夜晚结算 |
| INV-9 死者无回合、不可被指向 | `ActionValidator` V2、V7 | 每次行动 |
| INV-10 投票目标存活 | `ActionValidator` V7 | 每次投票 |
| INV-11 每次变化至少一个事件且版本 +1 | 应用层 `EventStorePort.append` 断言 + 聚合返回非空校验 | 每次写入 |
| INV-12 `GAME_OVER` 后无行动 | `ActionValidator` V1 | 每次行动 |
| INV-13 状态只能由事件修改 | 所有字段修改集中在 `apply(event)` 方法 | 代码评审 + 测试 |
| INV-14 事件不可变 | 事件类为不可变记录，无 setter | 编译期 |
| INV-15 快照不是真相 | `snapshot()` 是只读推导，恢复路径始终校验 | 恢复流程 |
| INV-16 重放确定性 | 领域不使用时钟、随机数、UUID；集合遍历按座位号排序 | 重放测试 |

## 9. Java 类型与事件类型对照

| 事件 `type` | Java 类 | 事件 `type` | Java 类 |
| --- | --- | --- | --- |
| `GAME_STARTED` | `GameStartedEvent` | `PLAYER_VOTED` | `PlayerVotedEvent` |
| `ROLE_ASSIGNED` | `RoleAssignedEvent` | `VOTE_TALLY_RESOLVED` | `VoteTallyResolvedEvent` |
| `PHASE_CHANGED` | `PhaseChangedEvent` | `PLAYER_KILLED` | `PlayerKilledEvent` |
| `TURN_GRANTED` | `TurnGrantedEvent` | `DEATH_ANNOUNCED` | `DeathAnnouncedEvent` |
| `WOLF_KILL_NOMINATED` | `WolfKillNominatedEvent` | `GAME_FINISHED` | `GameFinishedEvent` |
| `WOLF_KILL_SELECTED` | `WolfKillSelectedEvent` | `GAME_ABORTED` | `GameAbortedEvent` |
| `SEER_INSPECTED` | `SeerInspectedEvent` | `GAME_RECOVERED` | `GameRecoveredEvent` |
| `SEER_SKIPPED` | `SeerSkippedEvent` | `HUNTER_SHOT` | `HunterShotEvent` |
| `WITCH_SAVED` | `WitchSavedEvent` | `HUNTER_SKIPPED` | `HunterSkippedEvent` |
| `WITCH_POISONED` | `WitchPoisonedEvent` | `PLAYER_SPOKE` | `PlayerSpokeEvent` |
| `WITCH_PASSED` | `WitchPassedEvent` | `SPEECH_SKIPPED` | `SpeechSkippedEvent` |
| `WOLF_CHAT_SENT` | `WolfChatSentEvent` | | |

类名是 Java 实现细节，`type` 字符串是契约（[event-design.md §6](../architecture/event-design.md)）。

## 10. 领域层的依赖形态

```
domain.game.model   →  domain.game.event      （产生事件）
domain.game.service →  domain.game.model      （纯函数判定）
domain.game.model   ↛  domain.game.repository （聚合不感知持久化，DEP-6）
domain.game.*       ↛  infrastructure / application / interface / ai（DEP-1、DEP-7）
```

领域层可用的外部 API 只有 Java 标准库的集合、字符串、数学与 `BigDecimal` 级别的东西。
禁止出现 `Clock`、`Instant.now()`、`Random`、`UUID`、日志框架调用（日志在应用层与
基础设施层记录），这条约束是 INV-16 的实现前提。

## 11. 领域测试清单

| 测试 | 覆盖 |
| --- | --- |
| 开局事件序列 | 11 条事件、`version` 连续、9 条 `ROLE_ASSIGNED` 各含不同角色 |
| 角色分配覆盖率 | 洗牌后狼人恰好 3 名，神职各 1 名 |
| 狼刀平票 | 提名 1/2/2 时按最小座位定案；全员弃刀产生空刀且无人死亡 |
| 女巫解药 | 救下被刀者；默认规则下首夜可自救、第二夜起不可自救（`NOT_ALLOWED`）；`witchSelfSave=false` 时任何夜晚都不可自救；非当夜被刀者不可救；旧开局事件缺 `witchSelfSaveFirstNightOnly` 时解码为 `false` |
| 女巫双药 | 同夜第二次用药被拒绝，且不产生事件 |
| 毒药与开枪 | 被毒猎人进入 `GAME_OVER` 前不产生 `HUNTER_SHOOT` 阶段 |
| 猎人开枪 | 开枪带走目标后立即判定胜负；放弃开枪产生 `HUNTER_SKIPPED` |
| 计票 | 平票 `TIE`、全弃权 `ALL_ABSTAIN`、普通 `EXILE` 三种结果 |
| 胜负 | 狼全灭判村民胜；同时死亡时先判狼全灭；`WOLVES_OUTNUMBER` 下狼数等于好人数判狼人胜；`SIDE_KILL` 下狼数等于好人数但两边都有人存活时继续，平民或神职一边全灭判狼人胜 |
| 阶段转换 | 转换表内每一条允许的转换都有用例，表外转换全部拒绝 |
| 超时 | `SkipAction` 在每个阶段产生的具体事件 |
| 重放确定性 | 同一事件流重放两次结果逐字段相同；乱序重放被拒绝 |
| 越权 | 村民提交狼刀、死者投票、非当前回合发言全部被拒且零事件 |

## 12. 禁止事项

| 禁止 | 原因 |
| --- | --- |
| 在领域内 import Redis、SQLite、LangChain4j、LangGraph4j 类型 | 破坏 DEP-1、DEP-2（C-1、C-3） |
| 在领域内引用 `Agent`、`AgentRuntime`、`ToolGateway` | 违反 C-5，且让真人/AI 分叉出两套规则 |
| 在领域内取当前时间或随机数 | 破坏重放确定性（INV-16） |
| 应用层出现"如果是狼人则……"的判断 | 违反 `DESIGN.md` §4（Application Layer 不含规则） |
| 为了"方便"提供 setter 或可变字段 | 破坏 INV-13 |
| 把多个状态变化合并成一条事件 | 违背 C-4，回放无法还原中间状态 |
| 在事件 payload 里放聚合对象引用或基础设施句柄 | 破坏 INV-14 与序列化稳定性 |
