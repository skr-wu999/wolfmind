# 审计D：游戏领域（规则／状态机／事件／不变量）与设计的全链路一致性

- 审计范围：`backend/engine/**`（domain）为主，`backend/server` 的 `application/game`、`interfaces/rest`、`infrastructure/agent` 仅用于核对规则落点与写路径。
- 对照文档：`docs/backend/domain-model.md`、`docs/architecture/event-design.md`、`docs/architecture/ddd-design.md`、`docs/backend/api-spec.md`、`TASKS.md`、`AGENTS.md`。
- 方法：只读。逐条读文档 + `grep`/`read` 代码与测试，每条结论给「文档出处（文件+章节/行）」与「代码证据（file:line）」。
- 未运行任何构建或测试（只读审计约定）。第 1.1 节引用的测试数字来自仓库内**既有** `target/surefire-reports`，不是本次运行结果。
- 术语：**已留痕** = 该偏差在 `TASKS.md` 有文字记录（含「未修/TBD」）；**未留痕** = `TASKS.md` 全文无任何相关记录。

---

## 0. 结论摘要（含 P0/P1/P2/P3 计数）

| 严重度 | 计数 | 含义 |
| --- | --- | --- |
| **P0** | **0** | 无「对局必然错乱／数据不可恢复／隐藏信息泄漏」级别的领域缺陷 |
| **P1** | **2** | 静默规则偏差 + 不变量可被公开 API 违反 |
| **P2** | **8** | 文档声称的控制/结构不存在，或架构校验规则证明力不足 |
| **P3** | **16** | 文档-代码字段/示例/位置漂移，死代码与未接线参数 |

### 0.1 一句话结论

1. **`docs/backend/domain-model.md` §3 的 18 个规则参数默认值与 `GameRules.DEFAULT` 逐项完全一致**：TASKS.md 记录过的 6 项规则变更（60 秒回合、女巫首夜自救、屠边胜负、dayShotLastWords、夜间猎人先公布死讯、平票去偏置）**文档侧同步改了**，不是"只改代码"。24 条 Game 事件的 `visibility` 与 §7.2 逐条一致，状态机转换表与 §3.2 逐条一致。这部分用户的怀疑不成立，属**正面结论**。
2. **真正的静默规则偏差在别处**：`api-spec.md` §4 承诺"房间配置的 `rules` 会被复制进 `GAME_STARTED.rules`"，生产开局路径写死 `GameRules.DEFAULT`（`StartRoomGameApplicationService.java:118`），房间 `config.rules` 被接受、存下、回显，然后**静默丢弃**——调用方拿 201，对局按另一套规则跑，无错误、无日志、`TASKS.md` 无留痕（P1-1）。
3. **不变量 INV-5 在 `DAY_VOTE` 有一个缺口**：`SkipAction` 既不进 V6 的重复提交判定，`DAY_VOTE` 又允许 `SkipAction`，所以同一座位可以在同一投票阶段产生**第二条** `PLAYER_VOTED` 并把已投的票改成弃权（P1-2）。
4. **`DependencyRulesTest` 的 DEP-5 没有证明它声称证明的东西**：它的第三方坐标白名单只有 `org.xerial`（server 版另加 `io.lettuce`、`org.springframework.data.redis`），而 `server/pom.xml` 真实依赖包含 spring-boot-starter-web/websocket/data-redis、langchain4j×3、langgraph4j。按真实坐标集合判定，`com.wolfmind.WolfMindApplication`、`interfaces.rest.*`（14 个文件）等大量 infrastructure 之外的类都在引入第三方 SDK（P2-1）。
5. **领域本身是真的干净**：我逐个扫描 `backend/engine/src/main/java`——无任何非 `java.*`/`javax.*`/`com.wolfmind.domain.*` 的 import，无 `System.currentTimeMillis`/`Instant.now`、无 `Random`/`UUID`/`Clock` API 调用（仅出现在注释与 `randomSeed` 标识符里）。`engine/pom.xml` 只有 `junit-jupiter`（test scope）。DEP-1/DEP-2 的**结论**成立，成立的理由比测试声称的更强。

### 0.2 关键计数与分布

| 分类 | P1 | P2 | P3 |
| --- | --- | --- | --- |
| 静默规则偏差（文档写 A、代码做 B、TASKS 无痕） | 1（P1-1） | 1（P2-6） | 2（P3-2、P3-3） |
| 不变量可被违反 | 1（P1-2） | — | — |
| 架构校验规则证明力 | — | 4（P2-1..P2-4） | 1（P3-16） |
| 文档↔代码结构/字段漂移 | — | 3（P2-4/5/7） | 12 |
| 测试覆盖缺口 | — | 1（P2-8） | — |

---

## 1. 不变量覆盖矩阵

文档来源：`domain-model.md` §8（INV-1..INV-16，L336–355）与 `ddd-design.md` §4.1/§4.2（L360–386）。
`实现` = 代码位置；`测试` = 实际存在的用例（引擎 + server，来自源码与既有 surefire 报告）。

| 不变量 | 文档出处 | 实现位置 | 测试证据 | 结论 |
| --- | --- | --- | --- | --- |
| INV-1 座位数正确、座位号不重复 | domain-model §8 L340 | `StartGameCommand.java:34-38`（distinct 计数 + `validateAgainstSeatCount`）、`RoleComposition.java:77-93` | **无**（grep 全仓测试无 `座位号重复`/`ROLE_COMPOSITION_INVALID`） | 实现成立，**文档写错位置**（§8 写 `Game.start`，`Game.start` 本身不校验），**测试缺失** |
| INV-2 角色不可变 | §8 L341 | `Player.java:84-`（`applyRole` 仅由 `Game.applyRoleAssigned` 调用，`Game.java:1205-1211`），无 setter | 仅 `PermissionCheckerTest.java:89` 的消息文本引用 | 编译期成立；无专门用例 |
| INV-3 行动者存活 + controller 匹配 | §8 L342 | 存活：`ActionValidator.java:87-105`；controller：`SubmitActionApplicationService.java:151-158` | `ActionValidatorTest` 系列；`SubmitActionApplicationServiceTest.java:151-158`（SEAT_NOT_YOURS） | 成立 |
| INV-4 行动匹配阶段与角色 | §8 L343 | `ActionValidator.java:115-146`（V3/V4） | `GameSimulationTest.java:1004`（越权零事件）；`PermissionCheckerTest` | 成立 |
| INV-5 同回合单次提交 | §8 L344 / ddd §4.1 L370 | `ActionValidator.java:163-197`（V6） | `GameSimulationTest.java:668`（女巫第二次用药）；`ToolGatewayWriteTest.java:129`（G12） | **部分成立**：`SkipAction` 无 V6 分支 → 见 **P1-2** |
| INV-6 狼刀目标非狼人 | §8 L345（称「`WolfKillPolicy` 内二次确认」） | `ActionValidator.java:222-225` | 网关：`PermissionCheckerTest.java:300`、`ThreatModelTest.java:163`；**引擎侧无用例** | 实现成立；**文档声称的二次确认不存在** → **P2-6**；引擎无测试 → **P2-8** |
| INV-7 女巫同夜不可双药 | §8 L346 | `ActionValidator.java:290-303` + `GameSnapshot.witchActed()`（`GameSnapshot.java:146-148`） | `GameSimulationTest.java:668`；`ToolGatewayWriteTest.java:206` | 成立 |
| INV-8 被毒猎人不开枪 | §8 L347 | `NightResolutionPolicy.java:117-141`；`Game.recordDeath`（`Game.java:1402-1404`）；`Game.derivePendingHunter`（`Game.java:1287-1290`） | `GameSimulationTest.java:705` | 成立（读 `causes` 集合，不是 `primaryCause`） |
| INV-9 死者无回合、不可被指向 | §8 L348 | `ActionValidator.java:87-105`、`272-285` | `GameSimulationTest.java:1004` | 成立 |
| INV-10 投票目标存活／弃权表达 | §8 L349 | `ActionValidator.java:203-211`；`Vote.java:15-23` | `PermissionCheckerTest.java:290`；`EventCatalogTest.java:265-274` | 成立 |
| INV-11 每次变化 ≥1 事件且 version +1 | §8 L350 | `Game.emit/apply`（`Game.java:1141-1145`、`1177`）；`EventEnvelope.assertFollows`（`EventEnvelope.java:120-124`）；`EventEnvelopes`；`StartGameApplicationService.java:81-83` | `GameFlowSmokeTest.java:75,299`；`GameSimulationTest.java:382,408,530,701`；`NineAiGameE2ETest.java:71` | 成立。注：`Game.advance` 允许返回空（ddd §3.2 L315 明确「什么都不该发生不是错误」），与 INV-11 不冲突 |
| INV-12 `GAME_OVER` 后无行动 | §8 L351 | `ActionValidator.java:69-79` | `GameSimulationTest.java:506` | 成立 |
| INV-13 状态只能由事件修改 | §8 L352 | `Game.java:1147-1182`（唯一 `apply` 分发点）+ 所有字段写入集中在 apply 内 | 无直接用例；以 INV-16 重放等价性为代理（`GameSimulationTest.java:540,1413`；`RoomAggregateTest.java:261`） | 成立（代码形态可核）；**文档校验时机写「代码评审 + 测试」，实际无专门测试** |
| INV-14 事件不可变 | §8 L353 | 全部事件为 `record`（`domain/game/event/*.java`），无 setter | `GameSimulationTest.java:589`（重复重放被拒）为间接证据 | 编译期成立 |
| INV-15 快照不是真相 | §8 L354 | `GameSnapshot.java:12-16`；`GameSnapshotCodec` + `InMemoryGameRepository`（checksum 退化路径 `InMemoryGameRepository.java:159-176`）；`SnapshotStorePort.java:28-40` | `InMemoryGameRepositoryTest.java:84`（快照+增量 == 全量重放）、`:146-163`（checksum 被篡改→全量重放）；`RedisStorageIntegrationTest.java:253-271` | 成立 |
| INV-16 重放确定性 | §8 L355 | `Game.replay`（`Game.java:444-449`）严格版本连续；领域无时钟/随机/UUID（已逐个扫描）；集合按座位号排序（`Game.java:746,107,307`） | `GameSimulationTest.java:540,567,1413`；`GameFlowSmokeTest.java:307`；`WolfChatTest.java:147`；`InMemoryGameRepositoryTest.java:84` | 成立 |
| INV-R1..R3、INV-U1..U2（其他聚合） | ddd §4.3 L388-396 | `Room.java`（`apply` 集中改状态）、`User.java` | `RoomAggregateTest.java:52,68,224,261` | 成立（本轮不在重点范围） |

### 1.1 测试覆盖盲区汇总（对应 P2-8）

| 缺口 | 文档依据 | 应有测试 | 现状 |
| --- | --- | --- | --- |
| INV-1 座位数/座位号重复 | domain-model §8 L340 | `StartGameCommand` 非法配置被拒且零事件 | 全仓无用例 |
| INV-6 领域侧狼刀队友被拒 | domain-model §8 L345 | 引擎用例 | 只有网关侧用例，领域分支无覆盖 |
| V7「目标非自身」 | domain-model §6.1 L265 | 引擎用例（如预言家查自己、狼刀自己） | 无用例（女巫自救是**允许**路径，另有 γ 用例） |
| INV-5 `DAY_VOTE` 二次 `SkipAction` | ddd §4.1 L370 | 第二次提交被拒且零事件 | 无用例（见 P1-2） |
| `abstainAllowed=false` 对 `SkipAction` 的约束 | domain-model §3 L131 | 不允许弃权时不产生弃权票 | 无用例 |
| INV-13 领域的「无 apply 之外写状态」 | domain-model §8 L352 | 无法直接测；至少应有 `snapshot()` 与重放逐字段等价的用例 | 有代理用例，无专门断言 |

既有测试规模（非本次运行，取自 `backend/*/target/surefire-reports`）：engine 10 个测试类 **118 例，0 失败**；server 侧报告 77 个测试类。

---

## 2. 规则参数对照表

文档来源：`domain-model.md` §3（L113–132）。代码来源：`GameRules.java:32-50`（`DEFAULT`）与 `EventCodec.java:283-306`（旧事件缺键时的解码默认值）。

| 规则 | 文档出处与文档值 | 代码出处与代码值 | 是否一致 | 严重度 | 是否已留痕 |
| --- | --- | --- | --- | --- | --- |
| `speechMaxLength` | §3 L115 = 500 | `GameRules.java:33` = 500 | ✅ | — | — |
| `turnDeadlineMs` | §3 L116 = 60000（2026-10-13 由 30000 调整） | `GameRules.java:34` = `60_000L` | ✅ | — | 是（TASKS.md:122、127） |
| `agentTurnDeadlineMs` | §3 L117 = 60000（2026-10-12 20000→30000、2026-10-13→60000） | `GameRules.java:35` = `60_000L` | ✅ | — | 是（TASKS.md:122、503） |
| `voteDeadlineMs` | §3 L118 = 45000 | `GameRules.java:36` = `45_000L` | ✅ | — | — |
| `firstNightLastWords` | §3 L119 = `true` | `GameRules.java:37` = true | ✅ | — | — |
| `laterNightLastWords` | §3 L120 = `false` | `GameRules.java:38` = false | ✅ | — | — |
| `exileLastWords` | §3 L121 = `true` | `GameRules.java:39` = true | ✅ | — | — |
| `dayShotLastWords` | §3 L122 = `true`；缺键按 `false` 解码 | `GameRules.java:40` = true；`EventCodec.java:294` fallback `false` | ✅ | — | 是（TASKS.md:731-733） |
| `witchSelfSave` | §3 L123 = `true`（由 false 调整） | `GameRules.java:41` = true | ✅ | — | 是（TASKS.md:138） |
| `witchSelfSaveFirstNightOnly` | §3 L124 = `true`；缺键按 `false` 解码 | `GameRules.java:42` = true；`EventCodec.java:297` fallback `false`；语义 `GameRules.java:87-89` | ✅ | — | 是（TASKS.md:138-140） |
| `witchSaveTargetMustBeKilled` | §3 L125 = `true` | `GameRules.java:43` = true；`ActionValidator.java:262-268` | ✅ | — | — |
| `allowWolfEmptyKill` | §3 L126 = `true` | `GameRules.java:44` = true；`ActionValidator.java:213-217` | ✅ | — | — |
| `wolfKillTieBreak` | §3 L127 = `MIN_SEAT`（只记载最小值语义） | `GameRules.java:45` = `MIN_SEAT`；`WolfKillPolicy.java:69-71` 另有 `MAX_SEAT` 分支，`WolfKillTieBreak.java:8` | ⚠️ 默认值一致，但 `MAX_SEAT` 未记载 | P3 | **未留痕**（TASKS.md 无 `MAX_SEAT`） |
| `voteTieRule` | §3 L128 = `NO_EXILE` | `GameRules.java:46` = `NO_EXILE`；`VoteTieRule.java` 只有该一个取值 | ⚠️ 值一致，但**全仓无任何代码读取它**（`VoteTallyPolicy.java` 不接收 `rules`） | P3 | **未留痕** |
| `hunterCanShootWhenPoisoned` | §3 L129 = `false` | `GameRules.java:47` = false；`NightResolutionPolicy.java:117-118` 真正接线 | ✅ | — | — |
| `winCondition` | §3 L130 = `SIDE_KILL`（由 `WOLVES_OUTNUMBER` 调整） | `GameRules.java:48` = `SIDE_KILL`；`WinConditionPolicy.java:49-71` | ✅ | — | 是（TASKS.md:139） |
| `abstainAllowed` | §3 L131 = `true` | `GameRules.java:49` = true；`ActionValidator.java:205` 只在显式 `VoteAction{abstain}` 生效 | ⚠️ `SkipAction` 路径绕过该规则 | P1/P2 | **未留痕**（见 P1-2） |
| `stalledRoundsLimit` | §3 L132 = 3；缺键按 0 解码 | `GameRules.java:50` = 3；`EventCodec.java:306` fallback 0；`Game.stalled()`（`Game.java:989-997`） | ✅ | — | 是（TASKS.md:743、780） |

### 2.1 结论

- **`domain-model.md` §3 的 18 项默认值 100% 与 `GameRules.DEFAULT` 一致，旧事件解码默认值 3 项也全部一致。**
- 近期 6 项规则变更在文档侧**均已同步**（`domain-model.md` §3/§4、`ddd-design.md` §3.2、`api-spec.md` §5.3、`strategy-layer.md` L424-425、`observability.md` L88）。
- 规则参数层面的**静默偏差不在 `GameRules` 里，而在参数到达 `GameRules` 的路上**：

#### P1-1（未留痕，静默规则偏差）：房间 `config.rules` 被静默忽略

| 侧 | 证据 |
| --- | --- |
| 文档 | `api-spec.md` §4 L152（创建房间请求体含 `"rules": {...}`）、**L162「房间配置的 `rules` 会被复制进 `GAME_STARTED.rules`，开局后修改房间配置不影响进行中的对局」**；代码内文档 `RoomCreatedEvent.java:17`（「`config` … 开局时它会被复制进 `GAME_STARTED.rules`」） |
| 代码 | `StartRoomGameApplicationService.java:118`：`GameRules rules = GameRules.DEFAULT;`（硬编码）；同文件 L120-123 直接用它构造 `StartGameCommand`。`RoomController.java:96-97` 把请求体 `config` 原样存入房间；`Room.java:387` `this.config = event.config()`；`RoomView.java:84` 回显 `room.config()`。`LabBatchRunner.java:195` 同样固定 `GameRules.DEFAULT` |
| 影响 | 调用方 PUT 一个带 `rules` 的 config → 201 成功、房间视图回显该 config → 对局按 `GameRules.DEFAULT` 跑。任何规则参数都无法按调用方意图生效（含 TASKS 记录过的 `turnDeadlineMs`、`winCondition`、`hunterCanShootWhenPoisoned`），**无报错、无日志**。对照 `roleComposition` 的处理是有意显式拒绝（`StartRoomGameApplicationService.java:311-319` 抛 `ROLE_COMPOSITION_INVALID`），说明这种不对称不是统一的"暂不支持"策略 |
| 留痕 | **未留痕**：`TASKS.md` 全文 grep `房间配置`、`config.rules`、`playerCount` 无任何命中 |

#### P1-2（未留痕）：`DAY_VOTE` 的 `SkipAction` 缺 V6/INV-5 守卫，可重复投票并改票

| 侧 | 证据 |
| --- | --- |
| 文档 | `ddd-design.md` §4.1 L370 INV-5「同一回合窗口内，同一玩家对同一动作类型只接受一次提交；第二次提交返回幂等结果」；`domain-model.md` §5.4 L238「`Vote`：一人一票，**提交后不可改**」；`api-spec.md` §5.3 L292 对 `SKIP` 的约束是「**存活且本阶段未提交者**」；`domain-model.md` §4 L149 `DAY_VOTE` 的「接受行动」只列 `VoteAction` |
| 代码 | ①`ActionValidator.java:124`：`case SkipAction -> phase.acceptsActions()`；②`GamePhase.java:121-126`：`DAY_VOTE` 属于 `acceptsActions()==true`；③`ActionValidator.java:163-197`：V6 分支只覆盖 `VoteAction/WolfKillAction/SeerInspectAction/WitchAction/WolfChatAction`，`SkipAction` 落到 `default`（L193）**无任何检查**；④`Game.java:482`：`case DAY_VOTE -> recordVote(ctx, actor, Vote.abstain(...))`；⑤`Game.java:1321-1327`：`applyPlayerVoted` 用 `votes.put(seat, ...)` **覆盖**已有票 |
| 可达路径 | REST 是唯一写入口（`GameController.java:187-195` 直接解码并调用 `SubmitActionApplicationService`），`ActionCodec.java:85` 把 `"SKIP"` 映射为 `SkipAction`；`SubmitActionApplicationService` 不查 `ActionPermissions`（全仓 grep：`ActionPermissions` 在 server main 中零引用）；AI 网关也授权 `DAY_VOTE` 的 `SKIP`（`RolePhaseMatrix.java:34-36` `ACTION_PHASES` 含 `DAY_VOTE`，L143-144 对全部角色发 `SKIP` 授予以 `Quota.NONE`） |
| 影响 | 同一座位在同一 `DAY_VOTE` 可产生**第二条** `PLAYER_VOTED`；先 `VOTE` 再 `SKIP` 可把已投的票改成弃权。违反 INV-5 与「提交后不可改」。默认 `abstainAllowed=true` 时计票仍正确（map 覆盖），但事件流与回放/分析会显示该座位投了两次票；`abstainAllowed=false` 时 `SkipAction` 还绕过了该规则（`validateTarget` 对 `SkipAction` 不做任何检查，`ActionValidator.java:237-239`） |
| 留痕 | **未留痕**：`TASKS.md` 无该场景记录；全仓无相关用例 |

---

## 3. 事件目录差异

对照：`event-design.md` §7.1（L179-188）／§7.2（L190-245）／§7.3（L247-255） ↔ `EventType.java:17-56`、24 个事件类、`EventCodec.java:75-201`、`EventCatalogTest.java`。

### 3.1 类型目录

| 检查项 | 结果 |
| --- | --- |
| §7 中的每个 `type` 是否在 `EventType` 存在 | ✅ 全部存在（`EventType.java:20-56`），`fromCode` 未知类型显式失败（L83-89） |
| 「文档有代码无」 | ✅ 无（Game/Room/User 三组均在 `EventType` 中） |
| 「代码有文档无」 | ⚠️ **1 处：`ROOM_STARTED`**（`EventType.java:50`）——`event-design.md` §7.1 只有 6 行，无 `ROOM_STARTED`。**已留痕**：`TASKS.md:2989`「`RoomStartedEvent` 写进目录后，`docs/architecture/event-design.md` §7.1 的表格尚未补这一行」（现状仍缺） |
| `visibility` 逐条一致 | ✅ **24/24 完全一致**（逐个核对 `domain/game/event/*.java` 的 `visibility()` 与 §7.2 的可见性列；`EventCatalogTest.java:166-178` 亦覆盖） |
| 事件类命名 = type 转 PascalCase + `Event` | ✅ 成立；但 `EventCatalogTest.java:192-224` 只扫 `domain/game/event`（`TestPaths.java:59-61`），`domain/room/event`、`domain/user/event` 的类名不受该规则约束 |
| `VOTE_TALLY_RESOLVED.result` 取值 | ✅ `EXILE/TIE/ALL_ABSTAIN`（`EventCatalogTest.java:289-297`） |
| 信封 13 字段 | ✅ `EventEnvelope.java:18-31` 与 §2 L27-41 的 13 个字段逐项一致，且构造器做非空/范围校验（L36-77） |
| `PLAYER_SPOKE.speechId` / `WOLF_CHAT_SENT.chatId` | ✅ `{gameId}:{aggregateVersion}`（`PlayerSpokeEvent.speechIdFor`、`WolfChatSentEvent.chatIdFor`，`Game.java:458,530`）与 §7.2 L221-224、L243-245 一致 |
| `PLAYER_CONTROLLER_CHANGED` 归属 | ⚠️ 文档 §7.1 列在 **Room 流**（payload `roomId, seatNo, fromType, toType, refId`），代码存在**两条流**的两种形态：`RoomControllerChangedEvent`（`Room.java:289`）与 `PlayerControllerChangedEvent`（`Game.java:426-434`，对局流额外带 `playerId`，两者都多出 `fromRefId`）。**已留痕**：`TASKS.md:219-220` 实现裁决 7。注意对局流形态**无生产调用者**（见 P3-14） |

### 3.2 payload 键差异：`代码有文档无`（12 处）

`event-design.md` §6 L167 允许「新增可选字段直接加入 payload、`schemaVersion` 不变」，因此不构成回放破坏；但 §7 目录被自述为 payload 契约（`EventCatalogTest.java:35-37`），而测试只断言「文档列出的必需键**存在**」（L227-241，子集检查），**结构上无法发现「代码有文档无」**。

| type | §7.2 文档列出的键 | 代码实际多出的键 | 代码出处 |
| --- | --- | --- | --- |
| `PHASE_CHANGED` | from, to, round, turnSeq, resumePhase? | `deadline` | `PhaseChangedEvent.java:68-70` |
| `TURN_GRANTED` | playerId, seatNo, phase, turnIndex, deadline | `controllerType` | `TurnGrantedEvent.java:61` |
| `WOLF_KILL_NOMINATED` | wolfId, targetId, targetSeatNo | `wolfSeatNo`, `emptyKill` | `WolfKillNominatedEvent.java:65,68` |
| `SEER_INSPECTED` | seerId, targetId, result | `seerSeatNo` | `SeerInspectedEvent.java:56` |
| `SEER_SKIPPED` | seerId, reason | `seatNo` | `SeerSkippedEvent.java:51` |
| `WITCH_SAVED` | witchId, targetId | `witchSeatNo`, `targetSeatNo` | `WitchSavedEvent.java:54,56` |
| `WITCH_POISONED` | witchId, targetId | `witchSeatNo`, `targetSeatNo` | `WitchPoisonedEvent.java:54,56` |
| `WITCH_PASSED` | witchId | `witchSeatNo`, `reason` | `WitchPassedEvent.java:53-54` |
| `HUNTER_SHOT` | hunterId, targetId, targetSeatNo | `hunterSeatNo` | `HunterShotEvent.java:54` |
| `HUNTER_SKIPPED` | hunterId, reason | `hunterSeatNo` | `HunterSkippedEvent.java:51` |
| `SPEECH_SKIPPED` | playerId, seatNo, reason | `turnIndex` | `SpeechSkippedEvent.java:57` |
| `PLAYER_CONTROLLER_CHANGED`（对局流） | roomId, seatNo, fromType, toType, refId | `playerId`, `fromRefId` | `PlayerControllerChangedEvent.java:80,83` |

「文档有代码无」：**无**（`mvpCandidates?`、`exiledPlayerId?`、`tieSeats[]?`、`seats[9]` 等可选键均已实现）。严重度 **P3**，未留痕。

---

## 4. 状态机转换表差异

对照：`ddd-design.md` §3.2 转换表（L270-285）与状态机图（L234-262）↔ `GamePhase.TRANSITIONS`（`GamePhase.java:43-61`）、`Game.transition`（`Game.java:1037-1064`）。

| 当前阶段 | 文档允许后继 | 代码允许后继 | 一致 |
| --- | --- | --- | --- |
| `GAME_INIT` | NIGHT_WOLF | NIGHT_WOLF | ✅ |
| `NIGHT_WOLF` | NIGHT_SEER | NIGHT_SEER | ✅ |
| `NIGHT_SEER` | NIGHT_WITCH | NIGHT_WITCH | ✅ |
| `NIGHT_WITCH` | NIGHT_SETTLE | NIGHT_SETTLE | ✅ |
| `NIGHT_SETTLE` | HUNTER_SHOOT / DAY_ANNOUNCE | 同 | ✅ |
| `DAY_ANNOUNCE` | HUNTER_SHOOT / LAST_WORDS | 同 | ✅ |
| `LAST_WORDS` | DAY_DISCUSS / DAY_VOTE / HUNTER_SHOOT / NIGHT_WOLF / GAME_OVER | 同 | ✅ |
| `DAY_DISCUSS` | DAY_DISCUSS / DAY_VOTE | 同 | ✅ |
| `DAY_VOTE` | VOTE_SETTLE | VOTE_SETTLE | ✅ |
| `VOTE_SETTLE` | HUNTER_SHOOT / LAST_WORDS / NIGHT_WOLF / GAME_OVER | 同 | ✅ |
| `HUNTER_SHOOT` | `resumePhase`（动态） | 10 个阶段的并集（去掉 GAME_INIT 与自身） | ✅（并入 `LEGAL_RESUME_PHASES` 校验） |
| `GAME_OVER` | 无 | 空集 | ✅ |

- **12/12 一致，无未登记边。** `PhaseTransitionTableTest`（L140-175）**直接解析文档表格**再与枚举比对，`ACKNOWLEDGED_EXTRA_EDGES` 为空（L47），这是本仓最扎实的一条文档↔代码校验（与 §3 的 `EventCatalogTest` 形成对照）。既有报告：`PhaseTransitionTableTest` 6 例 0 失败。
- 该测试的**未覆盖面**：①只比「允许后继」列，不比「触发者/触发动作」列；②`HUNTER_SHOOT` 行不参与精确比对，`hunterShootReturnsToResumePhaseOnly`（L203-222）只校验并集属于合法集，不校验运行期 `transition(ctx,to,resume)` 实际传入的 `resume`（`Game.java:1058`）。

### 4.1 转换表语义层面的差异

| 项 | 文档 | 代码 | 严重度 | 留痕 |
| --- | --- | --- | --- | --- |
| `LAST_WORDS → HUNTER_SHOOT` 可达性 | §3.2 表内有该边 | 边存在但对局中**不可达**（`finishLastWords` 只做胜负判定，不结算新死亡，`Game.java:934-946`） | P3 | **已留痕**：`TASKS.md:242-244` TBD「被转换表允许但实际不可达」 |
| `abort()` 路径的阶段跳转 | `domain-model.md` §4 L152「`GAME_OVER` 进入时 = `PHASE_CHANGED`」、`event-design.md` §14.4「每个阶段跳转都有 `PHASE_CHANGED`」 | `Game.abort()`（`Game.java:398-405`）只发 `GAME_ABORTED`，**不产生 `PHASE_CHANGED`、不切阶段**（`applyGameAborted` 只改 status/finishReason，`Game.java:1427-1430`）。对比 stalled 路径（`Game.java:979-982`）是 `PHASE_CHANGED(→GAME_OVER)` + `GAME_ABORTED`，与 §3 L106/L132 一致 | P3 | **未留痕**；且 `abort()` **无生产调用者**（全仓 grep 只有测试辅助的 `Assumptions.abort`） |
| `DAY_VOTE` 的回合窗口 | `domain-model.md` §4 L169-170「`DAY_VOTE` 不使用 `TURN_GRANTED`：投票是同时提交，没有发言顺序，只有阶段级 `deadline`」 | ✅ 代码一致：`GamePhase.grantsTurn()`（L108-113）不含 `DAY_VOTE`；`Game.transition`（L1046-1047）给它写阶段级 `deadline`，`grantNextTurn` 不会在投票阶段发放回合 | ⚠️ 但 `api-spec.md` §5.2 的示例（L192）给 `DAY_VOTE` 配了 `currentTurn: {..., "phase":"DAY_VOTE","turnIndex":9,"deadline":...}`，与 `domain-model.md` §4 直接冲突 | P3 | 未留痕 |
| 进入 `NIGHT_WOLF` 的死讯公布次序 | `domain-model.md` §4 L160-164「夜里出局的猎人先公布死讯再开枪」 | ✅ `Game.applyDeaths`（L783-793）：`origin==NIGHT` 时先 `emitDeathAnnounced` 再 `transition(→HUNTER_SHOOT)`；`GameFlowSmokeTest.java:216` 覆盖 | ✅ | 已留痕（`TASKS.md:521-523`） |

---

## 5. 依赖规则测试的覆盖盲区

### 5.1 事实基础

| 模块 | 真实依赖 | 出处 |
| --- | --- | --- |
| `backend/engine` | **只有 `org.junit.jupiter:junit-jupiter`（test scope）** | `backend/engine/pom.xml:20-26` |
| `backend/server` | `wolfmind-engine`、`spring-boot-starter-web`、`spring-boot-starter-websocket`、`spring-boot-starter-data-redis`、`org.xerial:sqlite-jdbc`、`dev.langchain4j:langchain4j` / `langchain4j-open-ai` / `langchain4j-http-client-jdk`、`org.bsc.langgraph4j:langgraph4j-core`、`junit-jupiter`(test)、`spring-boot-starter-test`(test) | `backend/server/pom.xml:20-97` |

**存在两份 `DependencyRulesTest`**（数量与内容不一致）：

| | engine 版 | server 版 |
| --- | --- | --- |
| 文件 | `backend/engine/src/test/java/com/wolfmind/architecture/DependencyRulesTest.java`（322 行，**11 例**） | `backend/server/src/test/java/com/wolfmind/architecture/DependencyRulesTest.java`（304 行，**8 例**） |
| `THIRD_PARTY_PREFIXES` | `["org.xerial"]`（L57） | `["org.xerial","io.lettuce","org.springframework.data.redis"]`（L36-37） |
| 「domain 只能用 Java 标准库」断言 | 有（L271-290） | **无** |
| 独立实现理由 | — | 代码注释 L22-25 自述「故意不共享代码」 |

### 5.2 DEP-1..DEP-7 是否证明了它声称证明的东西

| 规则 | 文档原文 | 测试实现 | 是否真的证明 |
| --- | --- | --- | --- |
| DEP-1 | `domain..` 不得依赖 infrastructure/application/interface | engine L115-129（import + 全限定文本，含 `interfaces.` 与 `interface.`）、server L137-151 | ✅ **成立**。当前 `engine/src/main/java` 无任何非 JDK/非 domain 的 import（我逐个扫描确认） |
| DEP-2 | `domain..` 不得出现 redis/sqlite/langchain4j/langgraph4j/websocket/`java.time.Clock`/`java.util.Random`/`UUID` | engine L132-152、server L153-176（token 列表与文档逐字一致） | ⚠️ **token 列表与文档一致，但覆盖不足以支撑 `AGENTS.md` §5.2 的措辞**（见下） |
| DEP-3 | `application.game`/`application.room` ↔ `application.agent` 互不依赖 | engine L176-199、server L178-198 | ✅ 成立，但**只覆盖这 3 个切片**；`application.lab`、`application.analysis` 不受约束 |
| DEP-4 | `interface..rest` 之外不得出现 HTTP 类型 | engine L251-268、server L200-221（markers：`com.sun.net.httpserver`、`java.net.http`、`jakarta.servlet`、`javax.servlet`、`org.springframework.web`、`org.springframework.http`、`io.netty`） | ⚠️ **部分空转**：`com.sun.net.httpserver` 在 `src/main` 中已零引用（仅出现在 AI 测试的假 HTTP server），该 token 恒不命中；`org.springframework.stereotype/context/beans`、`org.springframework.boot.*` 不在 markers 内，故 `application.lab.LabBatchRunner`（`org.slf4j`）之类不受此规则约束 |
| DEP-5 | 只有 `infrastructure..` 可以依赖第三方 SDK | engine L202-223、server L223-244 | ❌ **未证明**（见 P2-1） |
| DEP-6 | `domain.game.model` 不得引用 `domain.game.repository` | engine L292-306、server L277-288 | ❌ **空转**（见 P2-3） |
| DEP-7 | `application.agent` 不得引用 `domain.game.model` | engine L308-321（`domain.game.model` 文本 + import）、server L290-303 | ✅ 成立 |

#### P2-1（未留痕）：DEP-5 的第三方坐标白名单远小于真实依赖面

- 文档：`ddd-design.md` §5.3 L462「只有 `infrastructure..` 可以依赖第三方 SDK」；测试内注释 `engine DependencyRulesTest.java:52-57` 自述「**本项目当前唯一一条**（见 backend/pom.xml 的 `sqlite.version`）」。
- 代码：`engine DependencyRulesTest.java:57` `THIRD_PARTY_PREFIXES = List.of("org.xerial")`；server 版 L36-37 加 `io.lettuce`/`org.springframework.data.redis`。
- 反证：`server/pom.xml:30-86` 有 6 组第三方坐标。如果按真实坐标集合（`org.springframework`、`dev.langchain4j`、`org.bsc.langgraph4j`、`com.fasterxml.jackson`、`org.slf4j`）判定，`infrastructure..` 之外会命中大量文件，例如（我逐个 import 扫描 `server/src/main/java` 的实测结果）：
  - `com.wolfmind.WolfMindApplication.java`、`com.wolfmind.WolfMindConfiguration.java` → `org.springframework`
  - `com.wolfmind.interfaces.rest.*` **14 个文件** → `org.springframework` / `com.fasterxml.jackson`
  - `com.wolfmind.interfaces.ws.WebSocketGateway.java` → `com.fasterxml.jackson`、`org.slf4j`
  - `com.wolfmind.application.lab.LabBatchRunner.java` → `org.slf4j`
- 影响：DEP-5 的实际判定强度取决于一个**不完整的手写坐标清单**；"只有 infrastructure 依赖第三方 SDK"这句话当前既不成立（按字面），也无法被该测试证伪。加上两份副本清单不一致，同一条 DEP 在两个模块的判定强度不同。
- 留痕：**未留痕**（`TASKS.md` 无 DEP-5 坐标清单的讨论；`ddd-design.md` §5.3 L468-473 只解释了"平台模块不算第三方"）。

#### P2-2（未留痕）：DEP-2 的具体盲区

| 漏检写法 | 为什么能通过 |
| --- | --- |
| `@org.springframework.stereotype.Component`、`org.mybatis.*`、`org.springframework.context.*` | token 列表无 `org.springframework`/`mybatis`；DEP-4 的 markers 只认 `org.springframework.web`/`.http`；engine 的「只能用 Java 标准库」测试（L271-290）**只扫 import**，全限定引用可绕过 |
| `java.net.http.HttpClient` | `java.*` 前缀被「标准库」测试放行（L280），DEP-2 token 列表也没有它 |
| `java.time.LocalDateTime.now()` / `ZonedDateTime.now()` / `System.nanoTime()` | 时钟检查只列 `System.currentTimeMillis` 与 `Instant.now`（engine L39-41、server L167） |
| `java.util.concurrent.ThreadLocalRandom` / `SplittableRandom` | 随机数检查只列 `java.util.Random`；`java.util.*` 被标准库测试放行 |

- **当前源码不存在这些写法**（我逐文件扫描 `engine/src/main/java`：无第三方 import，`Random/UUID/Clock` 仅出现在注释与 `randomSeed` 标识符），所以这是**规则证明力的缺口**，不是现存违规。
- 留痕：**未留痕**。

#### P2-3（未留痕）：DEP-6 空转，`GameRepository` 接口根本不存在

- 文档：`ddd-design.md` §5.2 L436 `domain/game/repository GameRepository（接口，实现在 Infrastructure）`；§5.3 L463 DEP-6。
- 代码：`backend/engine/src/main/java/com/wolfmind/domain/` 下**没有** `game/repository` 目录（实际子包：`game/{event,model,service,view}`、`room/{event,model}`、`user/{event,model}`）。持久化端口实际在 `com.wolfmind.application.port.GameRepositoryPort`。
- 影响：DEP-6 永远通过，无法证明任何东西；同时说明 §5.2 的包结构与实现不一致（§5.2 还把 `VisibilityFilter` 放在 `interface/ws`，实际在 `domain.game.view`——后者已由 `TASKS.md:223-225` 裁决 9 留痕）。
- 留痕：DEP-6 空转这件事**未留痕**（`VisibilityFilter` 迁移已留痕）。

#### P2-4（已留痕但测试具误导性）：`EventCatalogTest` 的「文档目录」不是从文档解析来的

- 文档：`event-design.md` §7.1 共 6 行（L183-188），**无 `ROOM_STARTED`**。
- 代码：`EventCatalogTest.java:54-56` 把 `"ROOM_STARTED"` 硬编码进 `ROOM_TYPES`，并在 L53 注明这是「event-design.md §7.1 Room 聚合事件」；随后 L160-163 断言 `documented.size() == EventType.values().length`。因为期望集合是手写的，该断言在"文档缺一行、代码多一行"时**恒真**。
- 影响：这是本仓唯一一个"事件目录一致性"的机器校验，但它只能发现"代码多出类型"，**发现不了"文档缺行"**。`ROOM_STARTED` 的文档缺行正是它漏掉的实例（`TASKS.md:2989` 已自认）。对照 `PhaseTransitionTableTest` 的做法（从文档解析期望值），可见同一仓库内两种方法论并存。
- 留痕：`ROOM_STARTED` 缺行已留痕；测试方法论的局限**未留痕**。

#### P2-8（未留痕）：领域不变量测试盲区

见 §1.1 表格。最关键的 3 条：INV-1 无用例、INV-6 领域分支无用例、`DAY_VOTE` 二次 `SkipAction` 无用例（后者直接掩盖了 P1-2）。

#### P3-16（未留痕）：两份 DEP 测试清单不一致

`engine` 版与 `server` 版 `THIRD_PARTY_PREFIXES` 不同（3 项 vs 1 项），断言条数不同（11 vs 8）。代码注释（server L22-25）解释了"故意不共享代码"，但没有解释"清单可以不同"；`TASKS.md` 无记录。同一条 DEP 规则在两个模块的判定强度不同，是规则漂移的温床。

---

## 6. 未判定项

| 项 | 为什么未判定 | 需要什么证据 |
| --- | --- | --- |
| `Game.timeoutSkips(now, phaseDeadlinePassed)` 的 `phaseDeadlinePassed` 在真实驱动中的取值是否正确（`HUNTER_SHOOT` 的 `PHASE_CHANGED.deadline` 为 `null`，`Game.java:1045-1051` 不给它写 deadline） | 判定逻辑在 `interfaces/rest/GameDriver.java:423-444` 与 `phaseDeadlinePassed(...)`（属基础设施/接口链路，审计A/C 范围）。`TASKS.md:395-396` 自述「真实模型超时路径尚未在端到端中覆盖」 | 读 `GameDriver.phaseDeadlinePassed` 全文 + 一次真实对局的超时日志 |
| `GAME_ABORTED(reason=STALLED)` 之后 `phase=GAME_OVER` 而 `status=ABORTED` 的组合是否被投影/回放/前端正确处理 | 校验点在 `infrastructure`/`interfaces` 与前端，超出本轮范围 | 审计A/C 的投影与前端终态判定证据 |
| `PLAYER_CONTROLLER_CHANGED` 出现在对局流时消费端（投影、归档、WebSocket）是否正确处理（该 payload 含 nullable `roomId`） | 同上 | 审计A 的消费组/归档证据 |
| `LabBatchRunner` 之外是否存在其它能构造非 `GameRules.DEFAULT` 对局的路径（例如测试脚手架、`InfrastructureGameHarness`） | `InfrastructureGameHarness.java:93` 接受外部传入 `GameRules`，但它是 `infrastructure/agent/wiring` 下的装配类；是否只被测试使用我未穷尽 | grep 该类的全部调用点（本轮只确认 `LabBatchRunner:195` 与 `StartRoomGameApplicationService:118` 是产品路径） |
| 前端是否自行推导披露等级或从载荷推断秘密身份（AGENTS §9 / AP-6） | 属审计C | 审计C 的报告 |
| `api-spec.md` §1.2 缺的 11 个领域错误码在前端是否有对应分支 | 属审计C | 前端 `LobbyError`/错误码表 |

---

## 附录 A：P2/P3 明细（编号与正文对应）

### P2（8 条）

| 编号 | 结论 | 文档出处 | 代码证据 | 留痕 |
| --- | --- | --- | --- | --- |
| P2-1 | DEP-5 的第三方坐标白名单远小于真实依赖面，「只有 infrastructure 依赖第三方 SDK」未被证明 | `ddd-design.md` §5.3 L462 | `engine DependencyRulesTest.java:57`；`server DependencyRulesTest.java:36-37`；`server/pom.xml:30-86` | 未留痕 |
| P2-2 | DEP-2 漏检 Spring/MyBatis/jakarta/`java.net.http`/`LocalDateTime.now`/`nanoTime`/`ThreadLocalRandom`；兜底测试只扫 import | `ddd-design.md` §5.3 L459；`AGENTS.md` §5.2 | `engine DependencyRulesTest.java:26-41,271-290` | 未留痕 |
| P2-3 | DEP-6 空转：`domain.game.repository` 包不存在（`GameRepository` 接口未实现，端口在 `application.port`） | `ddd-design.md` §5.2 L436、§5.3 L463 | `backend/engine/.../domain/` 子包列表；`application/port/GameRepositoryPort.java` | 未留痕 |
| P2-4 | `EventCatalogTest` 的期望目录是手写常量，能发现"代码多类型"、发现不了"文档缺行" | `event-design.md` §7.1 L183-188 | `EventCatalogTest.java:53-56,160-163`（对照 `PhaseTransitionTableTest.java:56-122` 从文档解析） | 部分留痕（`TASKS.md:2989`） |
| P2-5 | 文档记录的 3 个领域服务不存在（`RoleCompositionPolicy`/`DeathPolicy`/`PhaseTransitionPolicy`）；`NightActions` 是死代码，且文档说"进入 `NIGHT_SETTLE` 后清空"而代码在进入 `NIGHT_WOLF` 时清 | `domain-model.md` §1 L27-28、§6.2 L282、§2.1 L53、§5.4 L244 | `domain/game/service/` 实际类列表；`StartGameCommand.java:34-38`；`GamePhase.java:43-61`；`Game.transition`（`Game.java:1037-1064`）；`Game.recordDeath`（L1395-1406）；`NightActions.java`（零引用，仅 `GameViewProjector.java:23` 提及）；`Game.java:1228-1236` | 未留痕 |
| P2-6 | `domain-model.md` §8 声称 INV-6 有「`WolfKillPolicy` 内二次确认」——该确认不存在 | `domain-model.md` §8 L345 | `WolfKillPolicy.java:41-74`（无 `Camp`/`Role` 判定，无 `isWolf`）；唯一实现在 `ActionValidator.java:222-225` | 未留痕 |
| P2-7 | `AGENTS.md` §6/§7 要求的「命令携带 expected phase」「`phaseVersion` 拒绝过期阶段的行动」未实现 | `AGENTS.md` §6、§7 | `SubmitActionCommand.java:20-27`（只有 `expectedVersion`）；`ActionContext.java:9-16`（无 `expectedPhase`）；全仓无 `phaseVersion` | 未留痕（`stateVersion` 放宽本身已在 `api-spec.md:302-305` 与 `TASKS.md:77` 留痕，**不计入偏差**） |
| P2-8 | 领域不变量测试盲区：INV-1、INV-6、V7「非自身」、`DAY_VOTE` 二次 `SkipAction` 均无用例 | `domain-model.md` §8、§11 L389-405 | §1.1 表；grep 结果 | 未留痕 |

### P3（16 条，摘要）

| 编号 | 结论 | 关键证据 | 留痕 |
| --- | --- | --- | --- |
| P3-1 | 12 处 payload 键「代码有文档无」 | §3.2 表 | 未留痕 |
| P3-2 | `WolfKillTieBreak.MAX_SEAT` 代码有、文档无，且无产品入口可设置 | `WolfKillTieBreak.java:8`；`WolfKillPolicy.java:69-71`；`StartRoomGameApplicationService.java:118` | 未留痕 |
| P3-3 | `GameRules.voteTieRule` 无任何代码读取（平票行为实际硬编码在 `VoteTallyPolicy`） | `VoteTallyPolicy.java:28-69` 不接收 `rules`；`voteTieRule` 仅出现于 `EventCodec/GameStartedEvent/GameViewProjector/GameRules` | 未留痕 |
| P3-4 | 值对象字段漂移：`Vote.at` 文档有代码无；`DeathRecord.announced` 代码有文档无；`GameSnapshot` 用 `deaths` 而非文档的 `deathQueue`/`nightActions` | `Vote.java:9`；`DeathRecord.java:22`；`GameSnapshot.java:44`；`domain-model.md` §5.4 L238,242、§2.1 L53-55 | 未留痕 |
| P3-5 | `api-spec.md` §5.2 示例给 `DAY_VOTE` 配 `currentTurn`，与 `domain-model.md` §4 L169-170 冲突 | `api-spec.md:192` vs `domain-model.md:169-170`；代码与后者一致（`GamePhase.java:108-113`） | 未留痕 |
| P3-6 | `api-spec.md` §7.1 示例 `finishReason` 仍是 `WOLVES_OUTNUMBER_VILLAGE`，默认已改 `SIDE_KILL` | `api-spec.md:385`；`GameRules.java:48` | 未留痕 |
| P3-7 | `TASKS.md:247-248` 的 TBD（被枪击者不给遗言）已被 `dayShotLastWords` 覆盖但未撤回 | `TASKS.md:247-248`；`domain-model.md` §3 L122、§4 L157；`Game.lastWordsAllowed`（`Game.java:867-875`） | 已留痕但未闭环 |
| P3-8 | `ROOM_STARTED` 未登记 `event-design.md` §7.1 | `EventType.java:50`；§7.1 L183-188 | 已留痕（`TASKS.md:2989`） |
| P3-9 | `domain-model.md` §2.2 写 `Game extends AggregateRoot`，仓库中无 `AggregateRoot` 类 | `domain-model.md:63`；`Game.java:66` `public final class Game` | 未留痕 |
| P3-10 | `domain-model.md` §8 INV-1 实现位置写 `Game.start`，实际在 `StartGameCommand` 构造器 | `domain-model.md:340`；`StartGameCommand.java:34-38`；`Game.start`（`Game.java:286-341`）无该校验 | 未留痕 |
| P3-11 | `domain-model.md` §2.1「`players` 恒为 9 个座位」与可配置 `RoleComposition` 不符（产品路径只支持 `STANDARD_NINE`） | `domain-model.md:47`；`RoleComposition.java:77-93`；`StartRoomGameApplicationService.java:311-319` | 未留痕 |
| P3-12 | `ddd-design.md` §3.1 称 `RoleComposition`「可在实验室改配」，实验室与房间都固定标准九人 | `ddd-design.md:229`；`LabBatchRunner.java:195`；`StartRoomGameApplicationService.java:311-319`（显式拒绝） | 未留痕 |
| P3-13 | `Game.abort()` 不产生 `PHASE_CHANGED` 且无生产调用者 | `Game.java:398-405,1427-1430`；对照 `Game.java:979-982`；`domain-model.md` §4 L152；`event-design.md` §14.4 | 未留痕 |
| P3-14 | `Game.changeController`（对局流 `PLAYER_CONTROLLER_CHANGED`）无生产调用者，只有测试使用 | `Game.java:412-436`；grep 调用点仅 `GameDriverTest.java:110`、`GameDriverParallelVoteTest.java:141`；文档宣称见 `PlayerControllerChangedEvent.java:14-17`、`TASKS.md:219-220` | 已留痕（设计意图）但无生产路径未留痕 |
| P3-15 | `ddd-design.md` §5.2 的 `domain/game/repository`、`interfaces/ws/VisibilityFilter` 与实现不一致 | §5.2 L416,L436；实际 `domain.game.view.VisibilityFilter` | `VisibilityFilter` 迁移已留痕（`TASKS.md:223-225`）；`repository` 未留痕 |
| P3-16 | 两份 `DependencyRulesTest` 的第三方清单与断言集合不一致 | `engine ...:57`（1 项）vs `server ...:36-37`（3 项）；11 例 vs 8 例 | 未留痕（代码注释解释"不共享"，未解释清单差异） |

---

## 附录 B：本轮确认**没有**偏差的项（避免误报）

| 项 | 结论 |
| --- | --- |
| `GameRules.DEFAULT` 18 项 vs `domain-model.md` §3 | 逐项一致（§2 表） |
| `EventCodec` 3 项「旧事件缺键」解码默认值（`dayShotLastWords`→false、`witchSelfSaveFirstNightOnly`→false、`stalledRoundsLimit`→0） | 与文档一致（`EventCodec.java:294,297,306`），且有回放用例（`GameSimulationTest.java:1225,1314,1332`） |
| 24 条 Game 事件的 `visibility` | 与 `event-design.md` §7.2 逐条一致 |
| 12 个阶段的转换表 | 与 `ddd-design.md` §3.2 逐条一致，无未登记边 |
| `WinConditionPolicy` 的两种胜负条件与判定顺序 | 与 `domain-model.md` §6.2 L281、`ddd-design.md` §2.4 L214 一致；「先判狼全灭」在 `WinConditionPolicy.java:45-48` |
| `NightResolutionPolicy` 结算顺序（狼刀→解药→毒药，按座位升序出事件） | 与 §6.3 L288-307 一致（`NightResolutionPolicy.java:73-107`） |
| `DeathCause.primaryCause` 优先级 | 与 §6.3 L299 一致（`DeathCause.java:10-24`） |
| `TurnOrderPolicy` 首轮升序 + 环形顺延 | 与 §6.2 L276 一致（`TurnOrderPolicy.java:26-51`） |
| `Speech` 去首尾空白 + 1..`speechMaxLength` | 与 §5.4 L239 一致（`Speech.java:10-27`） |
| `RoleComposition` 约束（合计=座位数、狼≥1、村民阵营≥1） | 与 §5.4 L245 一致（`RoleComposition.java:77-93`） |
| 领域依赖纯净度 | `engine/pom.xml` 只有 junit(test)；`engine/src/main/java` 无第三方 import、无时钟/随机/UUID 调用 |
| 拒绝命令零事件 | `Game.handle` 先校验后改状态，`GameException` 抛出时 `uncommittedEvents` 已被 `begin()` 清空（`Game.java:1120-1125`）；用例 `GameSimulationTest.java:530,701` |
| 先持久化后发布 | `SubmitActionApplicationService.java:116-126`（commit → 幂等记录 → publish）；`GameDriver.java:463-476`（commit → publishRealtime） |
| LLM 不能直接改角色/杀玩家/判胜负 | `server/src/main/java` 中零处构造领域事件（grep `new (PlayerKilledEvent|GameFinishedEvent|RoleAssignedEvent|...)` = 0 命中）；角色/死亡/胜负只在 `Game.apply*` 中写入 |
| AI 写路径唯一 | AI 经 `AIControllerAdapter → ToolGatewayAdapter → WriteActionHandler → SubmitActionApplicationService`（`ToolGatewayAdapter.java:230`、`WriteActionHandler.java:131`），无第二条写路径 |
| `stateVersion ≤ expectedVersion ≤ version` 放宽 | 已在 `api-spec.md:302-305` 与 `redis-schema.md:609` 定义，并 `TASKS.md:77` 留痕；代码 `SubmitActionApplicationService.java:133-135` 与文档判据一致 |

---

## 附录 C：审计方法与局限

- 工具：`ops_glob`/`read`/`grep` + `pwsh` 只读扫描（`Select-String`、`Get-ChildItem`）。未修改任何源码或文档，除本报告外未写任何文件，未执行 git 操作。
- 未运行 `mvn`/`npm`（只读约定；`TASKS.md` 与既有 `target/surefire-reports` 已提供测试结果，本报告引用时明确标注来源）。
- 「未留痕」判定方法：对每个结论在 `TASKS.md` 上 grep 关键标识符（`SKIP`、`NightActions`、`MAX_SEAT`、`房间配置`、`config.rules`、`phaseVersion`、`ACTION_ALREADY_SUBMITTED`、`deathQueue` 等），零命中即判定未留痕。该方法不能排除"用完全不同的措辞记录过同一件事"，但覆盖了同义词范围内的常见写法。
