# 九人 AI 对局实跑报告

> 由 `NineAiGameReport` 生成，用于人工核对“9 个 AI 座位自动完成一整局”。

## 对局概要

| 项 | 值 |
| --- | --- |
| 对局标识 | g_report_nine_ai |
| 角色分配种子 | 20240501 |
| 决策来源 | 规则基线（确定性规则，不调用模型） |
| 驱动步数 | 28 |
| 聚合版本 / 事件条数 | 75 / 75 |
| 轮次 | 2 |
| 获胜阵营 | 狼人阵营 |
| 终局阶段 | GAME_OVER |
| AI 回合数 | 28 |
| 其中规则兜底 | 28 |
| 失败回合 | 0 |
| 超时跳过 | 0 |
| 阶段推进 | 0 |
| 在岗 Agent | 9 |

## 座位与角色

> 对局已终局，角色按披露策略公开。

| 座位 | 角色 | 阵营 | 终局状态 | 决策轨迹条数 |
| --- | --- | --- | --- | --- |
| 1 | 预言家 | 好人阵营 | 出局 | 20 |
| 2 | 村民 | 好人阵营 | 出局 | 10 |
| 3 | 村民 | 好人阵营 | 出局 | 10 |
| 4 | 狼人 | 狼人阵营 | 存活 | 20 |
| 5 | 村民 | 好人阵营 | 存活 | 10 |
| 6 | 狼人 | 狼人阵营 | 存活 | 20 |
| 7 | 狼人 | 狼人阵营 | 存活 | 20 |
| 8 | 猎人 | 好人阵营 | 存活 | 10 |
| 9 | 女巫 | 好人阵营 | 存活 | 20 |

## 事件时间线

> 只保留可读的关键事件；`GAME_STARTED` / `TURN_GRANTED` 等噪声事件折叠为计数。

| # | 轮 | 阶段 | 事件 | 谁 | 细节 |
| --- | --- | --- | --- | --- | --- |
| 2 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 预言家（好人阵营），座位 1 |
| 3 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 村民（好人阵营），座位 2 |
| 4 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 村民（好人阵营），座位 3 |
| 5 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 狼人（狼人阵营），座位 4 |
| 6 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 村民（好人阵营），座位 5 |
| 7 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 狼人（狼人阵营），座位 6 |
| 8 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 狼人（狼人阵营），座位 7 |
| 9 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 猎人（好人阵营），座位 8 |
| 10 | 1 | GAME_INIT | 角色分配（`ROLE_ASSIGNED`） | SYSTEM | 女巫（好人阵营），座位 9 |
| 11 | 1 | GAME_INIT | 阶段跳转（`PHASE_CHANGED`） | SYSTEM | GAME_INIT → NIGHT_WOLF（第 1 轮） |
| 12 | 1 | NIGHT_WOLF | 狼人提名（`WOLF_KILL_NOMINATED`） | p_4 | 狼 4 号提名 1 号 |
| 13 | 1 | NIGHT_WOLF | 狼人提名（`WOLF_KILL_NOMINATED`） | p_6 | 狼 6 号提名 1 号 |
| 14 | 1 | NIGHT_WOLF | 狼人提名（`WOLF_KILL_NOMINATED`） | p_7 | 狼 7 号提名 1 号 |
| 15 | 1 | NIGHT_WOLF | 狼刀确定（`WOLF_KILL_SELECTED`） | p_7 | 本夜刀口：1 号 |
| 16 | 1 | NIGHT_WOLF | 阶段跳转（`PHASE_CHANGED`） | p_7 | NIGHT_WOLF → NIGHT_SEER（第 1 轮） |
| 17 | 1 | NIGHT_SEER | 预言家查验（`SEER_INSPECTED`） | p_1 | 验 2 号 → 好人 |
| 18 | 1 | NIGHT_SEER | 阶段跳转（`PHASE_CHANGED`） | p_1 | NIGHT_SEER → NIGHT_WITCH（第 1 轮） |
| 19 | 1 | NIGHT_WITCH | 女巫用解药（`WITCH_SAVED`） | p_9 | 救下 1 号 |
| 20 | 1 | NIGHT_WITCH | 阶段跳转（`PHASE_CHANGED`） | p_9 | NIGHT_WITCH → NIGHT_SETTLE（第 1 轮） |
| 21 | 1 | NIGHT_SETTLE | 死讯公布（`DEATH_ANNOUNCED`） | p_9 | 公布死讯： |
| 22 | 1 | NIGHT_SETTLE | 阶段跳转（`PHASE_CHANGED`） | p_9 | NIGHT_SETTLE → DAY_ANNOUNCE（第 1 轮） |
| 23 | 1 | DAY_ANNOUNCE | 阶段跳转（`PHASE_CHANGED`） | p_9 | DAY_ANNOUNCE → LAST_WORDS（第 1 轮） |
| 24 | 1 | LAST_WORDS | 阶段跳转（`PHASE_CHANGED`） | p_9 | LAST_WORDS → DAY_DISCUSS（第 1 轮） |
| 26 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_1 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 3 号。 |
| 28 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_2 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 3 号。 |
| 30 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_3 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 4 号。 |
| 32 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_4 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 6 号。 |
| 34 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_5 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 6 号。 |
| 36 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_6 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 7 号。 |
| 38 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_7 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 4 号。 |
| 40 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_8 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 9 号。 |
| 42 | 1 | DAY_DISCUSS | 发言（`PLAYER_SPOKE`） | p_9 | 发言 31 字：第 1 天，DAY_DISCUSS。我的判断是先关注 1 号。 |
| 43 | 1 | DAY_DISCUSS | 阶段跳转（`PHASE_CHANGED`） | p_9 | DAY_DISCUSS → DAY_VOTE（第 1 轮） |
| 44 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_1 | 投 3 号 |
| 45 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_2 | 投 3 号 |
| 46 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_3 | 投 1 号 |
| 47 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_4 | 投 1 号 |
| 48 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_5 | 投 6 号 |
| 49 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_6 | 投 1 号 |
| 50 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_7 | 投 1 号 |
| 51 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_8 | 投 1 号 |
| 52 | 1 | DAY_VOTE | 投票（`PLAYER_VOTED`） | p_9 | 投 1 号 |
| 53 | 1 | DAY_VOTE | 阶段跳转（`PHASE_CHANGED`） | p_9 | DAY_VOTE → VOTE_SETTLE（第 1 轮） |
| 54 | 1 | VOTE_SETTLE | 计票结果（`VOTE_TALLY_RESOLVED`） | p_9 | 计票 EXILE，票型 1 号=6 票，3 号=2 票，6 号=1 票 |
| 55 | 1 | VOTE_SETTLE | 玩家出局（`PLAYER_KILLED`） | p_9 | 出局，死因 EXILE |
| 56 | 1 | VOTE_SETTLE | 死讯公布（`DEATH_ANNOUNCED`） | p_9 | 公布死讯：1 号（null） |
| 57 | 1 | VOTE_SETTLE | 阶段跳转（`PHASE_CHANGED`） | p_9 | VOTE_SETTLE → LAST_WORDS（第 1 轮） |
| 59 | 1 | LAST_WORDS | 发言（`PLAYER_SPOKE`） | p_1 | 发言 30 字：第 1 天，LAST_WORDS。我的判断是先关注 3 号。 |
| 60 | 2 | LAST_WORDS | 阶段跳转（`PHASE_CHANGED`） | p_1 | LAST_WORDS → NIGHT_WOLF（第 2 轮） |
| 61 | 2 | NIGHT_WOLF | 狼人提名（`WOLF_KILL_NOMINATED`） | p_4 | 狼 4 号提名 3 号 |
| 62 | 2 | NIGHT_WOLF | 狼人提名（`WOLF_KILL_NOMINATED`） | p_6 | 狼 6 号提名 3 号 |
| 63 | 2 | NIGHT_WOLF | 狼人提名（`WOLF_KILL_NOMINATED`） | p_7 | 狼 7 号提名 3 号 |
| 64 | 2 | NIGHT_WOLF | 狼刀确定（`WOLF_KILL_SELECTED`） | p_7 | 本夜刀口：3 号 |
| 65 | 2 | NIGHT_WOLF | 阶段跳转（`PHASE_CHANGED`） | p_7 | NIGHT_WOLF → NIGHT_SEER（第 2 轮） |
| 66 | 2 | NIGHT_SEER | 阶段跳转（`PHASE_CHANGED`） | p_7 | NIGHT_SEER → NIGHT_WITCH（第 2 轮） |
| 67 | 2 | NIGHT_WITCH | 女巫用毒药（`WITCH_POISONED`） | p_9 | 毒杀 2 号 |
| 68 | 2 | NIGHT_WITCH | 阶段跳转（`PHASE_CHANGED`） | p_9 | NIGHT_WITCH → NIGHT_SETTLE（第 2 轮） |
| 69 | 2 | NIGHT_SETTLE | 玩家出局（`PLAYER_KILLED`） | p_9 | 出局，死因 WITCH_POISON |
| 70 | 2 | NIGHT_SETTLE | 玩家出局（`PLAYER_KILLED`） | p_9 | 出局，死因 WOLF_KILL |
| 71 | 2 | NIGHT_SETTLE | 死讯公布（`DEATH_ANNOUNCED`） | p_9 | 公布死讯：2 号（null）、3 号（null） |
| 72 | 2 | NIGHT_SETTLE | 阶段跳转（`PHASE_CHANGED`） | p_9 | NIGHT_SETTLE → DAY_ANNOUNCE（第 2 轮） |
| 73 | 2 | DAY_ANNOUNCE | 阶段跳转（`PHASE_CHANGED`） | p_9 | DAY_ANNOUNCE → LAST_WORDS（第 2 轮） |
| 74 | 2 | LAST_WORDS | 阶段跳转（`PHASE_CHANGED`） | p_9 | LAST_WORDS → GAME_OVER（第 2 轮） |
| 75 | 2 | GAME_OVER | 对局结束（`GAME_FINISHED`） | p_9 | 狼人阵营 获胜，原因 WOLVES_OUTNUMBER_VILLAGE，共 2 轮，存活座位 [4, 5, 6, 7, 8, 9] |

事件计数

- `GAME_STARTED` × 1
- `ROLE_ASSIGNED` × 9
- `PHASE_CHANGED` × 17
- `WOLF_KILL_NOMINATED` × 6
- `WOLF_KILL_SELECTED` × 2
- `SEER_INSPECTED` × 1
- `WITCH_SAVED` × 1
- `DEATH_ANNOUNCED` × 3
- `TURN_GRANTED` × 10
- `PLAYER_SPOKE` × 10
- `PLAYER_VOTED` × 9
- `VOTE_TALLY_RESOLVED` × 1
- `PLAYER_KILLED` × 3
- `WITCH_POISONED` × 1
- `GAME_FINISHED` × 1

## 每个座位的决策轨迹

> 轨迹写入 `game:{id}:agent:{playerId}`，**绝不进入对局事件流**（agent-runtime.md §12）。

### 座位 1（p_1）

- 轨迹条数：20
- 事件类型：{AGENT_TURN_STARTED=4, AGENT_STRATEGY_DECIDED=4, AGENT_CONTEXT_BUILT=4, AGENT_TOOL_CALLED=4, AGENT_TURN_FINISHED=4}
- 最后一次完成回合：round=1 phase=LAST_WORDS version=58 payload={chosen=SPEAK, repairRounds=0, submitted=true, fallbackUsed=true, version=60, toolCalls=1, llmCalls=0}

### 座位 2（p_2）

- 轨迹条数：10
- 事件类型：{AGENT_TURN_STARTED=2, AGENT_STRATEGY_DECIDED=2, AGENT_CONTEXT_BUILT=2, AGENT_TOOL_CALLED=2, AGENT_TURN_FINISHED=2}
- 最后一次完成回合：round=1 phase=DAY_VOTE version=44 payload={chosen=VOTE, repairRounds=0, submitted=true, fallbackUsed=true, version=45, toolCalls=1, llmCalls=0}

### 座位 3（p_3）

- 轨迹条数：10
- 事件类型：{AGENT_TURN_STARTED=2, AGENT_STRATEGY_DECIDED=2, AGENT_CONTEXT_BUILT=2, AGENT_TOOL_CALLED=2, AGENT_TURN_FINISHED=2}
- 最后一次完成回合：round=1 phase=DAY_VOTE version=45 payload={chosen=VOTE, repairRounds=0, submitted=true, fallbackUsed=true, version=46, toolCalls=1, llmCalls=0}

### 座位 4（p_4）

- 轨迹条数：20
- 事件类型：{AGENT_TURN_STARTED=4, AGENT_STRATEGY_DECIDED=4, AGENT_CONTEXT_BUILT=4, AGENT_TOOL_CALLED=4, AGENT_TURN_FINISHED=4}
- 最后一次完成回合：round=2 phase=NIGHT_WOLF version=60 payload={chosen=WOLF_KILL, repairRounds=0, submitted=true, fallbackUsed=true, version=61, toolCalls=1, llmCalls=0}

### 座位 5（p_5）

- 轨迹条数：10
- 事件类型：{AGENT_TURN_STARTED=2, AGENT_STRATEGY_DECIDED=2, AGENT_CONTEXT_BUILT=2, AGENT_TOOL_CALLED=2, AGENT_TURN_FINISHED=2}
- 最后一次完成回合：round=1 phase=DAY_VOTE version=47 payload={chosen=VOTE, repairRounds=0, submitted=true, fallbackUsed=true, version=48, toolCalls=1, llmCalls=0}

### 座位 6（p_6）

- 轨迹条数：20
- 事件类型：{AGENT_TURN_STARTED=4, AGENT_STRATEGY_DECIDED=4, AGENT_CONTEXT_BUILT=4, AGENT_TOOL_CALLED=4, AGENT_TURN_FINISHED=4}
- 最后一次完成回合：round=2 phase=NIGHT_WOLF version=61 payload={chosen=WOLF_KILL, repairRounds=0, submitted=true, fallbackUsed=true, version=62, toolCalls=1, llmCalls=0}

### 座位 7（p_7）

- 轨迹条数：20
- 事件类型：{AGENT_TURN_STARTED=4, AGENT_STRATEGY_DECIDED=4, AGENT_CONTEXT_BUILT=4, AGENT_TOOL_CALLED=4, AGENT_TURN_FINISHED=4}
- 最后一次完成回合：round=2 phase=NIGHT_WOLF version=62 payload={chosen=WOLF_KILL, repairRounds=0, submitted=true, fallbackUsed=true, version=66, toolCalls=1, llmCalls=0}

### 座位 8（p_8）

- 轨迹条数：10
- 事件类型：{AGENT_TURN_STARTED=2, AGENT_STRATEGY_DECIDED=2, AGENT_CONTEXT_BUILT=2, AGENT_TOOL_CALLED=2, AGENT_TURN_FINISHED=2}
- 最后一次完成回合：round=1 phase=DAY_VOTE version=50 payload={chosen=VOTE, repairRounds=0, submitted=true, fallbackUsed=true, version=51, toolCalls=1, llmCalls=0}

### 座位 9（p_9）

- 轨迹条数：20
- 事件类型：{AGENT_TURN_STARTED=4, AGENT_STRATEGY_DECIDED=4, AGENT_CONTEXT_BUILT=4, AGENT_TOOL_CALLED=4, AGENT_TURN_FINISHED=4}
- 最后一次完成回合：round=2 phase=NIGHT_WITCH version=66 payload={chosen=WITCH_POISON, repairRounds=0, submitted=true, fallbackUsed=true, version=75, toolCalls=1, llmCalls=0}

## 复现方式

```powershell
cd backend
mvn -o -B -pl server -am test -Dtest=NineAiGameReport -DfailIfNoSpecifiedTests=false
```

真实模型版本（需要 `WOLFMIND_LLM_*` 或 `wolfmind-llm.properties`）：

```powershell
mvn -o -pl server -am test-compile
java -cp "server/target/test-classes;server/target/classes;engine/target/classes;$env:CLASSPATH" com.wolfmind.agent.e2e.NineAiLiveGame
```
