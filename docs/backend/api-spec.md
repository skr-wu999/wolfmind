# WolfMind 详细设计：REST API 规格

本文细化 `DESIGN.md` §4 的 Interface Layer（REST API）。所有写接口只做三件事：
鉴权与参数转换、调用 Application Service、把结果映射为 HTTP 响应。
任何写接口都不得直接访问 Redis、SQLite 或领域对象（C-1、C-3、C-4）。

## 1. 通用约定

| 项 | 约定 |
| --- | --- |
| 基础路径 | `/api/v1` |
| 传输 | HTTP + JSON，`Content-Type: application/json; charset=utf-8` |
| 认证 | 请求头 `Authorization: Bearer <sessionToken>`，令牌由登录接口签发 |
| 时间 | 毫秒时间戳（与事件 `occurredAt` 一致），不使用字符串时间格式 |
| 标识 | `gameId`、`roomId`、`userId` 为不透明字符串，客户端不得解析其结构 |
| 成功响应 | 直接返回资源或结果对象，不使用 `{code, data}` 包裹 |
| 错误响应 | `{code, message, details[], traceId}` |
| 分页 | `page`（从 1 开始）、`pageSize`（默认 20，上限 100），响应含 `total` |
| 幂等 | 所有 POST 写接口要求 `Idempotency-Key` 请求头，取值由客户端生成且全局唯一 |
| 并发 | 涉及对局状态变更的接口要求 `expectedVersion` |
| 幂等过期 | 幂等键默认保留 10 分钟，重复提交返回首次结果并带 `replayed: true` |

`Idempotency-Key` 与 `expectedVersion` 是两个不同的保护：前者防"同一意图重复提交"，
后者防"基于过期状态提交"。

### 1.1 错误响应

```json
{
  "code": "TURN_NOT_YOURS",
  "message": "当前回合属于座位 5",
  "details": [{ "field": "actionType", "reason": "phase=DAY_DISCUSS 且 currentTurn.seatNo=5" }],
  "traceId": "cor_5b21e0"
}
```

`traceId` 即事件的 `correlationId`，可直接用于排查日志与事件流。

### 1.2 错误码

| HTTP | code | 含义 | 客户端处理 |
| --- | --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 参数缺失或格式错误 | 修正请求 |
| 400 | `ACTION_INVALID_TARGET` | 目标非法（死亡、自身、越界） | 提示并刷新视图 |
| 401 | `AUTH_REQUIRED` | 未携带令牌 | 跳登录 |
| 401 | `AUTH_EXPIRED` | 令牌过期 | 跳登录 |
| 403 | `FORBIDDEN` | 无权访问该资源（非参与者、非观战者） | 提示 |
| 403 | `ACTION_NOT_ALLOWED` | 角色或阶段不允许该行动 | 提示，禁止重试同一请求 |
| 403 | `SEAT_NOT_YOURS` | 提交者不是该座位的控制者 | 提示 |
| 404 | `GAME_NOT_FOUND` / `ROOM_NOT_FOUND` / `USER_NOT_FOUND` | 资源不存在 | 返回列表页 |
| 409 | `GAME_VERSION_CONFLICT` | `expectedVersion` 已过期 | 先同步视图再重试 |
| 409 | `TURN_NOT_YOURS` | 不是该玩家的回合 | 等待 `TURN_GRANTED` |
| 409 | `GAME_BUSY` | 单写者锁被占用 | 退避重试（建议 200 ms，最多 3 次） |
| 409 | `GAME_ALREADY_FINISHED` | 对局已结束 | 跳回放页 |
| 409 | `ROOM_FULL` / `ALREADY_SEATED` / `NOT_READY` | 房间状态冲突 | 刷新房间 |
| 429 | `RATE_LIMITED` | 超过频率限制 | 按 `Retry-After` 退避 |
| 500 | `INTERNAL_ERROR` | 未预期异常 | 上报 traceId |
| 501 | `NOT_IMPLEMENTED` | 接口已定义但尚未实现 | 不要重试，属版本缺口 |
| 503 | `GAME_RECOVERING` | 对局正在恢复 | 退避重试 |
| 503 | `SERVICE_DEGRADED` | 依赖不可用（Redis 等） | 提示稍后重试 |

领域拒绝码与接口层错误码是两层：领域拒绝只能来自 `DomainErrorCode`（`domain.game.model`），
接口层可以补充 HTTP 语义专属的码（当前只有 `NOT_IMPLEMENTED`）。两层到 HTTP 状态的映射必须集中
在一处实现，不允许各控制器各自判断。

### 1.3 AI 工具网关的拒绝码（400/403/429）

工具网关（[tool-gateway.md §4](../ai/tool-gateway.md)）的拒绝体使用**同一套枚举**，但下面四个码
只在工具调用路径出现，REST 控制器不会产生它们。它们在此登记，是为了让"前后端与 AI 使用同一份语义"
（tool-gateway.md §5）在契约层面成立：

| HTTP 等价 | code | 含义 | AI 侧处理 |
| --- | --- | --- | --- |
| 400 | `TOOL_UNKNOWN` | 工具名不在白名单内 | 不可重试，改调用白名单内的工具 |
| 400 | `TOOL_ARGS_INVALID` | 参数 Schema 校验失败（类型/枚举/范围/长度/未知字段/必填组/互斥组） | 不可重试，按 `allowedAlternatives` 修正参数 |
| 403 | `TOOL_IDENTITY_MISMATCH` | 声称的身份与工具会话注入的身份不一致 | 不可重试；**身份由 Runtime 注入，模型不得指定** |
| 429 | `BUDGET_EXCEEDED` | 超过回合工具调用预算（`gateway.maxToolCallsPerTurn`） | 不可重试，本回合转兜底 |

这些码不是 HTTP 响应的组成部分（工具调用不经 HTTP），因此**不进入 §1.2 的 REST 状态映射**；
把它们列在这里只是为了让契约词汇表完整、并明确它们与 REST 错误码同源。

不可重试的码（`ACTION_NOT_ALLOWED`、`ACTION_INVALID_TARGET`、`SEAT_NOT_YOURS`、
`FORBIDDEN`）必须与可重试的码（`GAME_BUSY`、`GAME_VERSION_CONFLICT`、`RATE_LIMITED`、
`SERVICE_DEGRADED`、`GAME_RECOVERING`）区分，前端据此决定是否自动重试。

## 2. 接口总览

| 分类 | 方法与路径 | 应用服务 | 是否写状态 |
| --- | --- | --- | --- |
| 认证 | `POST /auth/register` | `RegisterApplicationService` | 是（`USER_REGISTERED`） |
| 认证 | `POST /auth/login` | `LoginApplicationService` | 是（`USER_LOGGED_IN`） |
| 认证 | `POST /auth/logout` | `LogoutApplicationService` | 是（`USER_LOGGED_OUT`） |
| 认证 | `GET /auth/me` | `GetCurrentUserQueryService` | 否 |
| 用户 | `GET /users/{userId}` | `GetUserQueryService` | 否 |
| 用户 | `GET /users/me/stats` | `GetUserStatsQueryService` | 否 |
| 房间 | `POST /rooms` | `CreateRoomApplicationService` | 是（`ROOM_CREATED`） |
| 房间 | `GET /rooms` | `ListRoomsQueryService` | 否 |
| 房间 | `GET /rooms/{roomId}` | `GetRoomQueryService` | 否 |
| 房间 | `POST /rooms/{roomId}/join` | `JoinRoomApplicationService` | 是（`PLAYER_SEATED`） |
| 房间 | `POST /rooms/{roomId}/leave` | `LeaveRoomApplicationService` | 是（`PLAYER_LEFT`） |
| 房间 | `POST /rooms/{roomId}/seats/{seatNo}/take` | `SeatApplicationService` | 是（`PLAYER_SEATED`） |
| 房间 | `POST /rooms/{roomId}/seats/{seatNo}/ai` | `SeatApplicationService` | 是（`PLAYER_CONTROLLER_CHANGED`） |
| 房间 | `DELETE /rooms/{roomId}/seats/{seatNo}/ai` | `SeatApplicationService` | 是（`PLAYER_CONTROLLER_CHANGED`） |
| 房间 | `POST /rooms/{roomId}/ready` | `ReadyApplicationService` | 是（`PLAYER_READY`） |
| 对局 | `POST /rooms/{roomId}/games` | `StartGameApplicationService` | 是（`GAME_STARTED` 等 11 条） |
| 对局 | `GET /games/{gameId}` | `GetGameViewApplicationService` | 否 |
| 对局 | `POST /games/{gameId}/actions` | `SubmitActionApplicationService` | 是 |
| 对局 | `GET /games?status=&roomId=` | `ListGamesQueryService` | 否 |
| 回放 | `GET /games/{gameId}/replay` | `ReplayQueryService` | 否 |
| 分析 | `GET /games/{gameId}/analysis/summary` | `AnalysisQueryService` | 否 |
| 分析 | `GET /games/{gameId}/analysis/agents/{playerId}` | `AnalysisQueryService` | 否 |
| 实验室 | `GET /lab/agent-configs` | `LabQueryService` | 否 |
| 实验室 | `POST /lab/agent-configs` | `SaveAgentConfigApplicationService` | 是（配置事件） |
| 实验室 | `POST /lab/batches` | `StartBatchApplicationService` | 是（批量对局） |
| 实验室 | `GET /lab/batches/{batchId}` | `LabQueryService` | 否 |
| 实验室 | `GET /lab/comparisons` | `LabQueryService` | 否 |
| 运维 | `POST /admin/games/{gameId}/recover` | `RecoverGameApplicationService` | 是（`GAME_RECOVERED`）——**未实现** |
| 运维 | `GET /admin/dlq`、`POST /admin/dlq/{entryId}/replay` | `DlqAdminApplicationService` | 否 / 重投递——**未实现** |
| 运维 | `GET /health` | 健康检查 | 否 |
| 运维 | `GET /metrics`、`GET /metrics/names` | 指标快照 / 指标名目录 | 否 |

## 3. 认证与用户

### 3.1 `POST /auth/register`

请求：`{ "username": "wolf01", "password": "***", "displayName": "wolf01" }`
响应 `201`：`{ "userId": "u_8812", "username": "wolf01", "displayName": "wolf01" }`
错误：`VALIDATION_ERROR`（用户名重复、口令过短）。
口令只以散列与盐存储，永不进入事件与日志。

### 3.2 `POST /auth/login`

请求：`{ "username": "wolf01", "password": "***" }`
响应 `200`：

```json
{ "userId": "u_8812", "sessionToken": "s_...", "expiresAt": 1730003600000 }
```

产生 `USER_LOGGED_IN`。同一账号多端登录允许，各自独立会话。

### 3.3 `GET /auth/me`

响应：`{ "userId", "username", "displayName", "activeGameId", "spectatingGameIds" }`。
`activeGameId` 用于前端决定"继续对局"入口。
实现：取当前用户入座的第一局 `RUNNING` 对局，没有则为 `null`；`spectatingGameIds` 暂恒为空列表（观战关系尚未持久化）。

## 4. 房间

| 接口 | 请求要点 | 响应要点 |
| --- | --- | --- |
| `POST /rooms` | `{ "name", "config": { "playerCount": 9, "roleComposition": {...}, "rules": {...} } }` | `201 { roomId, ownerId, seats[] }` |
| `GET /rooms` | `?status=WAITING\|RUNNING\|CLOSED&page=&pageSize=`，缺省排除 CLOSED | `{ items[], page, pageSize, total }`；摘要含 occupied、humanCount、aiCount |
| `GET /rooms/{roomId}` | 无 | `{ roomId, status, ownerId, seats[9], readyCount, gameId? }` |
| `POST /rooms/{roomId}/join` | 无（自动分配最小空座） | `200 { seatNo, playerId }`；`409 ROOM_FULL` |
| `POST /rooms/{roomId}/leave` | 无；Idempotency-Key | `200 RoomView`；等待房释放座位，运行房 AI 接管，最后真人离开关闭房间并中止运行中的普通对局 |
| `POST /games/{gameId}/leave` | 无；Idempotency-Key；登录者须匹配关联房间原真人座位 | `200 RoomView`；重复退出成功；未登录 401、无幂等头 400、陌生账号/观战者 403 |
| `POST /rooms/{roomId}/seats/{seatNo}/take` | 无 | `200 { seatNo, playerId }`；座位被占返回 `409 ALREADY_SEATED` |
| `POST /rooms/{roomId}/seats/{seatNo}/ai` | `{ "agentConfigId" }` | `200 { seatNo, controller: { "type": "AI", "refId": "agent_07" } }`，产生 `PLAYER_CONTROLLER_CHANGED` |
| `DELETE /rooms/{roomId}/seats/{seatNo}/ai` | 无 | `200`，座位回到空置，产生 `PLAYER_CONTROLLER_CHANGED` |
| `POST /rooms/{roomId}/ready` | 无 | `200 { readyCount, allReady }` |

房间配置的 `rules` 会被复制进 `GAME_STARTED.rules`，开局后修改房间配置不影响进行中的对局。

房间采用一房一局生命周期，终局自动 CLOSED；不会删除 Game 历史。进行中退出保留角色、存活状态和已提交行动。

2026-10-14：完整 RoomView 增加 `rules`，为 config.rules 与服务端默认值合并后的有效 GameRules，供等待房展示本局规则。
列表摘要仍不带完整 rules。非法旧房间返回 rules=null，不静默采用默认值；开局仍以 RULES_INVALID 拒绝。
GameView.rules 继续来自 GAME_STARTED，前端不得把屠边固定为所有对局的胜利条件。
断线宽限、多标签在线判定、跨 Room/Game 补偿与中止原因见 [房间生命周期](room-lifecycle.md)。

`config.rules` 是可选对象；不提供或提供空对象时采用当前 `GameRules.DEFAULT`，部分提供时只覆盖对应字段。
字段名与默认值见 [domain-model.md §3](domain-model.md)：18 个已有规则字段全部支持，不接受未知字段。
创建房间即校验，开局再次校验历史房间配置；错误返回 `400 RULES_INVALID`，消息指出 `config.rules` 及字段名，
不会静默退回默认规则，也不会创建对局/Agent。显式 `null`、字符串形式的数值/布尔值均不接受。
长度/停滞轮数为整数（`speechMaxLength >= 1`、`stalledRoundsLimit >= 0`，不超过 int 上限），
三种时限为正整数毫秒数且须落在 long 范围内；小数部分不为零或数值溢出拒绝。
枚举字符串区分大小写：`wolfKillTieBreak=MIN_SEAT|MAX_SEAT`、`voteTieRule=NO_EXILE`、
`winCondition=SIDE_KILL|WOLVES_OUTNUMBER`。未提供的布尔/枚举字段沿用默认值。

例如 `{"rules":{"voteDeadlineMs":15000,"abstainAllowed":false}}` 会产生 15 秒投票窗口，禁止主动弃权。
实际 GameView.rules、行动权限及回放以已提交的 GAME_STARTED.rules 为准；此次修复不改写已开局对局的规则或历史事件。

## 5. 对局

### 5.1 `POST /rooms/{roomId}/games`

请求：`{ "autoFillAi": true }`（为剩余空座填充 AI，`DESIGN.md` §15 Phase 4 "AI 补位"）
响应 `201`：

```json
{ "gameId": "g_20240501_0007", "status": "RUNNING", "version": 11, "phase": "NIGHT_WOLF" }
```

产生 11 条事件（`GAME_STARTED` + 9×`ROLE_ASSIGNED` + `PHASE_CHANGED`）。
未全部就绪返回 `409 NOT_READY`。

### 5.2 `GET /games/{gameId}`

返回角色可见的 `GameView`，由 `VisibilityFilter` 过滤。同一接口对不同身份返回不同内容，
这是设计的一部分，不是缺陷：

```json
{
  "gameId": "g_20240501_0007",
  "version": 123,
  "status": "RUNNING",
  "phase": "DAY_VOTE",
  "round": 2,
  "turnIndex": 9,
  "turnOrder": [1, 2, 4, 5, 6, 7, 8, 9],
  "currentTurn": { "playerId": "p_5", "seatNo": 6, "phase": "DAY_VOTE", "turnIndex": 9, "deadline": 1730000030000 },
  "rules": { "speechMaxLength": 500, "voteDeadlineMs": 45000 },
  "me": {
    "playerId": "p_3", "seatNo": 4, "role": "SEER", "camp": "VILLAGE",
    "alive": true, "controller": { "type": "HUMAN", "refId": "u_8812" },
    "skillState": { "antidoteAvailable": false, "poisonAvailable": false, "shotAvailable": false }
  },
  "players": [
    { "playerId": "p_1", "seatNo": 1, "alive": true, "ready": true,
      "controller": { "type": "AI", "refId": "agent_07" }, "role": null },
    { "playerId": "p_2", "seatNo": 2, "alive": false, "ready": true,
      "controller": { "type": "HUMAN", "refId": "u_4410" }, "role": null }
  ],
  "votes": [
    { "voterSeat": 1, "targetSeatNo": 6, "abstain": false },
    { "voterSeat": 4, "targetSeatNo": null, "abstain": true }
  ],
  "deaths": [{ "seatNo": 2, "round": 1 }],
  "spectator": false
}
```

`GameView` 的完整字段（前端只允许消费这些字段，不得自行推导）：

| 字段 | 类型 | 可见性 | 说明 |
| --- | --- | --- | --- |
| `gameId` / `version` / `status` | string / int / enum | 全部订阅者 | `version` 是 `expectedVersion` 的来源之一 |
| `phase` / `round` / `turnIndex` | enum / int / int | 全部订阅者 | 阶段与发言进度 |
| `turnOrder[]` | int[] | 全部订阅者 | 当前轮发言顺序（座位号） |
| `currentTurn` | `{playerId, seatNo, phase, turnIndex, deadline}` | 全部订阅者 | 回合窗口；`DAY_VOTE`、`NIGHT_WOLF` 等无回合阶段为 `null` |
| `rules` | object | 全部订阅者 | 本局规则参数，来自 `GAME_STARTED.rules` |
| `me` | `{playerId, seatNo, role, camp, alive, controller, skillState}` | 仅本人 | 观战者为 `null` |
| `players[]` | `{playerId, seatNo, alive, ready, controller, role}` | 全部订阅者 | `controller` 为 `{type, refId}`；非赛后视图的 alive 在公开死讯后才标记出局，me 保留本人实际状态 |
| `votes[]` | `{voterSeat, targetSeatNo, abstain}` | 全部订阅者 | 当前投票轮的公开票；未投票者不出现 |
| `deaths[]` | `{seatNo, round}` | 全部订阅者 | 只含已通过 `DEATH_ANNOUNCED` 公布的死亡，不含死因 |
| `spectator` | boolean | 全部订阅者 | 当前连接是否以观战者身份读取 |
| `permissions[]` | `{type, targets[], allowEmpty, remaining}` | 全部订阅者 | 读取者**当前可执行**的动作；观战者、匿名读取者、终局后为空数组 |

#### `permissions[]` 字段语义

它是**服务端计算的授权投影**，前端据此驱动行动 UI（目标选择、弃票、放弃开枪、药量显示、
二次确认）。前端**不得**按 `phase` + `me.role` 自行推导——行动合法性属于领域职责，
两端各实现一遍必然漂移。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | string | 动作类型，取值与 §5.3 的 `actionType` 白名单一致（`SPEAK` / `VOTE` / `WOLF_KILL` / `SEER_INSPECT` / `WITCH_SAVE` / `WITCH_POISON` / `WITCH_PASS` / `HUNTER_SHOOT` / `WOLF_CHAT`） |
| `targets[]` | int[] | 合法目标座位号，升序、去重、不含自己；无目标型动作（`SPEAK`、`WITCH_PASS`、`WOLF_CHAT`）为空数组 |
| `allowEmpty` | boolean \| null | 是否允许"空选择"（弃票、放弃开枪）；不适用时为 `null` |
| `remaining` | int \| null | 剩余可用次数（药量；`WOLF_CHAT` 为本夜剩余条数）；不适用时为 `null` |

规则要点（实现见 `domain.game.view.ActionPermissions`，测试见 `ActionPermissionsTest`）：

1. **观战者恒为空**：观战者永远不能提交行动（§10 尾注）。
2. **终局/中止后恒为空**：`status != RUNNING` 时没有可执行动作。
3. **回合制动作只在本人回合出现**：`SPEAK` 要求 `currentTurn.seatNo == me.seatNo`。
4. **遗言不看存活**：`LAST_WORDS` 阶段出局者仍可发言，否则会被卡住。
5. **女巫的 `WITCH_PASS` 始终可用**：它是一个合法且常见的选择；藏掉它会逼迫玩家用药。
   药量为 0 时不再给出对应的 `WITCH_SAVE` / `WITCH_POISON`。
6. **`WITCH_SAVE` 的目标是当夜被刀者**：该事实已按 §2.1.1 定向披露给女巫本人。
7. **目标列表只基于公开的存活状态**，不读取其他座位的角色，因此不泄漏任何身份信息。
8. **`WOLF_CHAT` 与提名互不影响**：`NIGHT_WOLF` 中存活狼人在本夜条数未满 20 时都有该权限，
   提名之后仍保留，直到阶段结束。

`role` 字段规则：自己的角色可见；狼人可见队友角色；其他玩家角色在对局进行中为 `null`，
`GAME_FINISHED` 后全部可见。观战者（`spectator: true`）的 `me` 为 `null`，
进行中 `players[].role` / `camp` / `skillState` 全部为 `null`；赛后全部可见。该披露政策于 2026-10-12 经项目所有者批准，见 [政策决策](../frontend/phase4-spectator-policy-proposal.md)。

`GameView` 不下发聚合内部中间状态（`nightActions`、`deathQueue`）与任何
`WOLF` / `PRIVATE` / `JUDGE` 事件原文，防止隐藏信息在客户端重新出现。

字段 `version` 是后续 `POST /actions` 的 `expectedVersion` 来源之一（另一个来源是
WebSocket 帧的 `seq`）。`DAY_VOTE` 与 `NIGHT_WOLF` 没有回合窗口，
这两个阶段的提交资格由"存活且本阶段未提交"决定，不依赖 `currentTurn`。

### 5.3 `POST /games/{gameId}/actions`（核心写接口）

统一动作入口，对应 `SubmitActionApplicationService`。

请求头：`Idempotency-Key: <客户端生成>`、`Authorization`
请求体：

```json
{
  "actionType": "VOTE",
  "params": { "targetSeatNo": 6 },
  "expectedVersion": 122
}
```

| `actionType` | `params` | 允许角色 | 允许阶段 | 领域 Action |
| --- | --- | --- | --- | --- |
| `SPEAK` | `{ "content": "..." }` | 任意存活玩家 | `DAY_DISCUSS`、`LAST_WORDS` | `SpeechAction` |
| `VOTE` | `{ "targetSeatNo": 6 }` 或 `{ "abstain": true }` | 任意存活玩家 | `DAY_VOTE` | `VoteAction` |
| `WOLF_KILL` | `{ "targetSeatNo": 6 }` 或 `{ "empty": true }` | `WEREWOLF` | `NIGHT_WOLF` | `WolfKillAction` |
| `SEER_INSPECT` | `{ "targetSeatNo": 6 }` | `SEER` | `NIGHT_SEER` | `SeerInspectAction` |
| `WITCH_SAVE` | `{ "targetSeatNo": 6 }` | `WITCH` | `NIGHT_WITCH` | `WitchAction{SAVE}` |
| `WITCH_POISON` | `{ "targetSeatNo": 6 }` | `WITCH` | `NIGHT_WITCH` | `WitchAction{POISON}` |
| `WITCH_PASS` | `{}` | `WITCH` | `NIGHT_WITCH` | `WitchAction{PASS}` |
| `HUNTER_SHOOT` | `{ "targetSeatNo": 6 }` 或 `{ "giveUp": true }` | `HUNTER` | `HUNTER_SHOOT` | `HunterShotAction` |
| `SKIP` | `{ "reason": "VOLUNTARY" }` | 当前应提交的玩家：有回合窗口的阶段是回合持有者，无回合窗口的阶段（`DAY_VOTE`、`NIGHT_WOLF`、`NIGHT_SEER`、`NIGHT_WITCH`）是存活且本阶段未提交者 | 任意需提交的阶段 | `SkipAction` |
| `WOLF_CHAT` | `{ "content": "..." }` | 存活 `WEREWOLF` | `NIGHT_WOLF` | `WolfChatAction` |

投票阶段的 `SKIP` 与 `VOTE` 共用“本阶段每座位一次”限制：已投票后，当前版本的新命令返回
`409 ACTION_ALREADY_SUBMITTED`，不覆盖原票、不产生新事件；相同幂等键仍返回首次结果。
主动 SKIP 等价于主动弃权，`abstainAllowed=false` 时返回 `403 ACTION_NOT_ALLOWED`。
`permissions[].allowEmpty` 的 VOTE 值来自实际 `abstainAllowed`，禁止弃权时不提示可弃票。
外部 REST/WebSocket/Agent 命令不能提交 `reason=TIMEOUT`（`403 ACTION_NOT_ALLOWED`）。
服务端 TimeoutApplicationService 在核对当前任务及已提交窗口截止时间后，只为未投票者补记弃权；
该超时行为不受“禁止主动弃权”影响，以免挂机导致阶段无法结束。领域 TIMEOUT 还必须携带系统上下文。

`WOLF_CHAT`（狼人夜聊，2026-10-13 经项目所有者批准）：

- 内容去首尾空白后 1..500 字，否则 `SPEECH_INVALID`；每名狼人每晚最多 20 条，超出 `ACTION_NOT_ALLOWED`；
  条数在进入下一个 `NIGHT_WOLF` 时清零。
- 产生一条 `WOLF_CHAT_SENT`（`WOLF` 可见），不是提名、不推进阶段。非狼人的推送、回放、AI 视图都拿不到它；
  终局后与其他 `WOLF` 事件一样对全员公开。
- AI 不发送夜聊（工具目录没有对应动作），只在 `NIGHT_WOLF` 决策时读取本晚夜聊。
- **版本校验放宽**：`expectedVersion` 与当前版本之间如果只追加了 `WOLF_CHAT_SENT`，命令不算过期，
  按当前版本提交。判据是 `stateVersion ≤ expectedVersion ≤ version`，其中 `stateVersion` 是最后一条
  非夜聊事件的版本（快照字段，见 [redis-schema.md](redis-schema.md)）。期间有任何其他事件仍返回
  `GAME_VERSION_CONFLICT`。这条放宽对所有 `actionType` 生效，因为夜聊不改变任何规则状态。

响应 `200`：

```json
{
  "accepted": true,
  "gameId": "g_20240501_0007",
  "version": 123,
  "eventIds": ["01H8X2-000123"],
  "eventTypes": ["PLAYER_VOTED"],
  "replayed": false
}
```

默认不返回 `view`，客户端的状态更新以 WebSocket 推送为准（单一数据来源）。
WebSocket 不可用时，可加 `?include=view` 让响应携带 `GameView` 作为兜底。

处理流程（与 [ddd-design.md §6.1](../architecture/ddd-design.md) 一致）：

```
1. 鉴权 → 取 userId
2. Idempotency-Key 命中 → 直接返回首次结果（replayed=true）
3. 解析 actionType → Action 值对象；参数校验（枚举、范围、长度）
4. 校验提交者控制该座位（HUMAN → userId 匹配；AI/BOT → 内部调用凭证）
5. 获取 game:{id}:lock → 失败返回 GAME_BUSY
6. 加载快照 + 增量事件 → 校验 expectedVersion
7. game.handle(action, ctx)
8. EventStorePort.append（显式条目 ID，Redis 拒绝版本冲突）
9. 返回 version 与 eventIds
```

关键语义：`accepted: true` 只表示"命令被领域接受并已写入事件"，
客户端必须等 `PLAYER_VOTED` 等事件到达后再更新界面。

### 5.4 `GET /games?status=RUNNING`

返回对局列表，供观战页与分析页入口使用：

```json
{
  "items": [
    { "gameId": "g_...", "status": "RUNNING", "phase": "DAY_VOTE", "round": 2,
      "playerCount": 9, "aiCount": 9, "spectatorCount": 12, "startedAt": 1729999000000 }
  ],
  "page": 1, "pageSize": 20, "total": 3
}
```

`spectatorCount` 由 WebSocket 订阅关系汇总，不作为对局状态。

## 6. 回放

`GET /games/{gameId}/replay?from=1&to=200[&atVersion=42]`

| 项 | 规则 |
| --- | --- |
| 权限 | 公开只读查询允许匿名：进行中仅 PUBLIC；登录参与者按本人/阵营授权过滤，其他登录用户仅 PUBLIC；已结束时完整披露 |
| 数据来源 | Redis Stream `game:{id}:events`；流已过期则读 SQLite `game_events` 归档 |
| 响应 | `{ gameId, from, to, total, truncated, events[] }`，`events[]` 为事件信封（[event-design.md §2](../architecture/event-design.md)） |
| `truncated` | **本次数据来自 SQLite 归档时为 `true`**（含义是"事件流已过期"，不是"可见事件少于请求条数"——PRIVATE/WOLF/JUDGE 被过滤是正常的授权结果） |
| 分页 | 按 `version` 区间，单次上限 500 条 |
| `atVersion` | 可选。给定时响应追加 `atVersion` 与 `snapshot`（该版本的 `GameView`，由服务端按确定性重放重建） |

回放页只读事件，不重放状态。需要"第 N 步的棋盘状态"时用 `?atVersion=` 由服务端重建，
**不要**让前端按事件增量演进：那等于让客户端再实现一遍脱敏投影，两份实现必然漂移（AP-6）。

`atVersion` 大于流尾时等价于读取当前视图（回放页播放到末尾的正常路径，不报错）；
小于 1 时返回 `400 VALIDATION_ERROR`。归档数据没有事件流水位，因此
`atVersion` 在归档来源下不可用（归档只保留终局快照的等价物，逐步棋盘需要历史事件全量）。

## 7. AI 分析

**必须登录**（§10：`/analysis` 匿名不可用）：分析页是赛后复盘，匿名开放等于把整局的角色与决策轨迹公开。

### 7.1 `GET /games/{gameId}/analysis/summary`

```json
{
  "gameId": "g_1", "status": "FINISHED", "disclosure": "FULL",
  "winner": "WOLF", "finishReason": "WOLVES_OUTNUMBER_VILLAGE",
  "rounds": 3, "version": 150, "startedAt": 1730000000000, "finishedAt": 1730000600000,
  "players": [{
    "playerId": "p_1", "seatNo": 1, "role": "SEER", "camp": "VILLAGE",
    "controllerType": "AI", "controllerRefId": "p_1", "alive": false,
    "speechCount": 4, "voteCount": 3, "abstainCount": 0,
    "voteHitCount": 2, "voteAccuracy": 0.6667,
    "survivedRounds": 1, "deathRound": 2,
    "decisionTraces": 24, "llmCalls": 5, "promptTokens": 900, "completionTokens": 300,
    "toolCalls": 12, "fallbackTurns": 1, "totalLlmLatencyMs": 8000, "maxLlmLatencyMs": 3000,
    "voteTargetSeats": [2, 5]
  }],
  "keyEvents": [
    { "version": 12, "type": "PLAYER_VOTED", "phase": "DAY_VOTE", "round": 1,
      "occurredAt": 1730000010000, "seatNo": 1, "summary": "1 号投票给 2 号" }
  ],
  "truncated": false,
  "provenance": "统计来自已归档的对局事件与 AGENT_ 决策轨迹；角色在对局结束后完整披露。"
}
```

**`disclosure` 是这份响应的核心字段**，取值 `FULL` / `PUBLIC`，**由服务端按对局状态决定**：

| 值 | 条件 | 含义 |
| --- | --- | --- |
| `PUBLIC` | 对局仍在进行 | 角色为 `null`、由角色派生的指标（`voteHitCount` / `voteAccuracy`）为 `null` |
| `FULL` | `GAME_FINISHED` / `GAME_ABORTED` | 完整角色与派生统计 |

客户端**不得**根据"自己是不是参与者"重新推导披露层级（AP-6）。

#### 每个统计字段的来源

| 字段 | 来源 |
| --- | --- |
| `speechCount` | `PLAYER_SPOKE` 的 `actorId` 命中次数 |
| `voteCount` / `abstainCount` | `PLAYER_VOTED` 的 `abstain` |
| `voteHitCount` / `voteAccuracy` | 票的目标是否为狼人阵营；**仅在角色已披露时有值**，否则为 `null` |
| `deathRound` / `survivedRounds` | `DEATH_ANNOUNCED.round` 与 `GAME_FINISHED.rounds`；出局者记 `deathRound - 1`，存活到终局记总轮数 |
| `decisionTraces` / `llmCalls` / token / 耗时 / `toolCalls` / `fallbackTurns` | `AGENT_` 决策轨迹（不进入对局事件流） |
| `keyEvents` | 只收推进了对局的事件类型；`ROLE_ASSIGNED` 与 `WOLF_*` 永不进入（前者是 PRIVATE，放进来等于把角色印在公开时间轴上） |

**`keyEvents[].summary` 由服务端生成**，`PLAYER_SPOKE` 只报字数不报正文：
正文已经在 `GET /replay` 里，摘要区再放一份会让页面在两个地方渲染同一段可能被重写的文本。

### 7.2 `GET /games/{gameId}/analysis/agents/{playerId}`

```json
{
  "gameId": "g_1", "playerId": "p_1", "seatNo": 1,
  "role": "SEER", "camp": "VILLAGE", "controllerType": "AI", "controllerRefId": "p_1",
  "disclosure": "FULL",
  "traces": [{
    "id": "p_1:12:STRATEGY:1730000010000", "kind": "STRATEGY",
    "version": 12, "phase": "DAY_DISCUSS", "round": 1, "occurredAt": 1730000010000,
    "candidates": [{ "actionType": "SPEAK", "targetSeatNo": null, "score": 0.7 }],
    "chosenAction": null, "chosenTargetSeatNo": null, "submitted": null, "fallbackUsed": null,
    "llmCalls": null, "repairRounds": null,
    "model": null, "provider": null, "promptTokens": null, "completionTokens": null, "llmLatencyMs": null,
    "tool": null, "toolArgsDigest": null, "toolDecision": null,
    "denialCode": null, "stage": null, "reason": null, "draft": null
  }],
  "note": "轨迹只包含运行时可观察的事实：候选集与打分、工具调用、token 与耗时、是否兜底；不包含模型私有推理。"
}
```

`kind` 取值与字段分组（不适用时一律 `null`）：

| `kind` | 用到的字段 | 来源轨迹事件 |
| --- | --- | --- |
| `TURN_STARTED` | — | `AGENT_TURN_STARTED` |
| `CONTEXT` | — | `AGENT_CONTEXT_BUILT` |
| `STRATEGY` | `candidates` | `AGENT_STRATEGY_DECIDED` |
| `LLM` | `model` `provider` `promptTokens` `completionTokens` `llmLatencyMs` | `AGENT_LLM_INVOKED` |
| `TOOL` | `tool` `toolArgsDigest` `toolDecision` | `AGENT_TOOL_CALLED` |
| `REJECTED` | `stage` `denialCode` `reason` `draft` | `AGENT_OUTPUT_REJECTED` |
| `TIMEOUT` | `stage` | `AGENT_TURN_TIMEOUT` |
| `FAILED` | `denialCode` | `AGENT_TURN_FAILED` |
| `FINISHED` | `chosenAction` `chosenTargetSeatNo` `submitted` `fallbackUsed` `llmCalls` `repairRounds` | `AGENT_TURN_FINISHED` |

复读拦截（`stage` 为 `ECHO_CHECK` / `ECHO_CHECK_REWRITE`，strategy-layer.md §8.3）的 `reason` 由服务端写成
"与 3 号第 1 轮发言相似度 0.81"（比对对象是本人时写"与自己"），改写稿仍命中时追加"，提交了首稿 / 改写稿"；
`draft` 是被拦下的发言稿。其他 `stage` 的 `draft` 恒为 `null`，**泄密拦截不返回原稿**。
`draft` 为 2026-10-13 新增字段，旧客户端忽略即可。

**它暴露的是运行时可观察的事实，不是模型的思维过程**（AGENTS.md §8、agent-runtime.md §12）。
接口不返回完整 prompt，`note` 里也必须把这一点说清楚。

**进行中不返回轨迹**（`disclosure = PUBLIC` 时 `traces` 为空）：轨迹含候选集与拒绝原因，
是最强的事实披露。

数据来源：`agent_memory` 表中 `memory_type = 'DECISION_TRACE'` 的记录
（[database-schema.md](database-schema.md) §3.6），进程内轨迹优先、归档兜底。
`playerId` 不属于该对局时返回 `404 NOT_FOUND`。


## 8. AI 实验室

**必须登录**（§10：`/lab` 匿名不可用）。配置与批次都**按账号隔离**：实验配置含模型、温度与人格，
是这个人做过的实验条件，不该成为别人可读的资产；读取别人的批次 id 按"不存在"处理（404），
以免用 404/403 的差别探测它是否存在。

| 接口 | 请求 | 响应 |
| --- | --- | --- |
| `GET /lab/agent-configs` | `?page=&pageSize=` | `{ items[], page, pageSize, total, batches[], latestBatchId, models[] }` |
| `POST /lab/agent-configs` | 见下 | `201 { agentConfigId, name, role, model }` |
| `POST /lab/batches` | `{ "name", "gameCount", "agentConfigIds": [...], "maxConcurrentGames" }` | `202 { batchId, status, total, maxConcurrentGames, serverMaxConcurrentGames }` |
| `GET /lab/batches/{batchId}` | 无 | `{ batchId, name, status, completed, total, maxConcurrentGames, agentConfigIds, games[], statistics, error, createdAt, updatedAt }` |
| `GET /lab/comparisons` | `?agentConfigIds=a,b&role=` | `{ rows[], provenance }` |

### 8.1 Agent 配置的字段

```json
{
  "agentConfigId": "ac_1", "name": "狼人A", "role": "WEREWOLF",
  "model": "deepseek-flash", "temperature": 0.7,
  "strategyProfile": "AGGRESSIVE", "persona": "激进强势",
  "memoryMode": "long", "promptTemplateId": "default"
}
```

| 字段 | 约束 | 运行时消费点 |
| --- | --- | --- |
| `name` | 1–24 字符 | — |
| `role` | `WEREWOLF` / `VILLAGER` / `SEER` / `WITCH` / `HUNTER` | 批量对局按实际角色分配把配置匹配到座位 |
| `model` | 1–120 字符 | `LlmInvoker` 发出的请求 |
| `temperature` | 0–2（**不是 0–1**，与 `AgentConfig` 一致） | 同上 |
| `strategyProfile` | `AGGRESSIVE` / `BALANCED` / `CONSERVATIVE` | `DecisionPolicy.weightsFor` 的打分权重 |
| `persona` | ≤ 120 字符 | `ContextAssembler` 的【配置】区（影响表达，不变约束） |
| `memoryMode` | `off` / `short` / `long` → 召回 0 / 4 / 8 条 | `AgentWorkflow.recall` |
| `promptTemplateId` | ≤ 64 字符 | **只作为比对维度**（记进轨迹与统计）；本阶段不改变提示词装配 |

**没有"设了不生效"的开关。** 每个字段都能追到一处消费点；工具开关与自定义提示词
没有服务端消费点，因此**不在契约里**——发了也会被忽略，而"我关了历史工具"会变成一句错话。

`POST /lab/agent-configs` 带 `agentConfigId` 时为覆盖保存（保留 `createdAt`）；
覆盖别人的配置返回 `403`。

### 8.2 批量对局

`POST /lab/batches` 只创建**批次记录**，不创建对局：一个 50 局的批次如果同步开 50 局，
请求会阻塞几分钟而客户端拿不到 `202 { batchId }`。对局由服务端按并发上限逐局开出：

1. 为 9 个座位注册规则基线 Agent（毫秒级，不调模型）→ 抢占"驱动在窗口内派发到未注册座位"的空档；
2. 用 `StartGameApplicationService` 开局（**与真人对局完全相同的路径**）；
3. 读出座位角色，把命中实验室配置的座位换成目标配置；
4. 之后由应用层 `EventDrivenTurnScheduler` 推进，与真人对局共用同一套规则、事件流与调度；显式 POLLING 回退仍复用该调度器。

**并发上限是全局的**（`lab.maxConcurrentGames`，默认 8），跨批次共享：两个批次各自 7 路并发
加起来就是 14 局，而"服务端上限"说的显然不是每一批的上限。
达到上限时返回 `429 RATE_LIMITED` + `Retry-After`；申请值大于剩余容量时**按剩余容量授信**
（"现在只能再跑 3 局"是有用的答案，"什么都不给你"不是）。

`roleComposition` 目前只支持经典九人；显式传自定义配置返回 `400` 并指明字段，
而不是静默用默认值开局。

`GET /lab/batches/{batchId}` 的 `games[]`：

```json
{ "index": 0, "gameId": "g_lab_lb_1_0", "status": "FINISHED", "winner": "WOLF", "rounds": 2,
  "roleAssignments": { "SEER": { "agentConfigId": "ac_1", "seatNo": 7, "camp": "VILLAGE" } } }
```

`status` 取 `PENDING` / `RUNNING` / `FINISHED` / `ABORTED` / `FAILED`（后两者计入 `completed`）。
进度**不累加**而是每轮重读对局仓储：计数式进度在进程重启后会归零或翻倍。

### 8.3 比对表

`rows[]` 的每一行是 `(agentConfigId, role)` 的组合：

| 字段 | 说明 |
| --- | --- |
| `games` / `wins` / `winRate` | 分母是**该配置参与且已结束**的局数；`winRate` 为 0–1 的小数 |
| `averageRounds` | 平均轮数 |
| `llmCalls` / `tokens` / `averageLlmLatencyMs` / `fallbackTurns` | 来自 `AGENT_LLM_INVOKED` 决策轨迹；规则基线座位为 0，表示这一局没有花模型成本 |

`provenance` 里必须写明口径与样本量提醒：**样本量小于 20 局时差异不具统计意义**。


## 9. 运维接口

| 接口 | 说明 |
| --- | --- |
| `POST /admin/games/{gameId}/recover` | 触发恢复流程，产生 `GAME_RECOVERED`（`visibility=JUDGE`）。**尚未实现**（无鉴权体系时不开放运维写接口） |
| `GET /admin/dlq?gameId=&groupId=` | 查询死信条目。**尚未实现**（无消费者组） |
| `POST /admin/dlq/{entryId}/replay` | 重投递单条死信。**尚未实现** |
| `GET /health` | 依赖健康：Redis、SQLite、LLM。`required` 项不健康 → `503`；**LLM 不可用不算不健康**（对局仍走规则兜底） |
| `GET /metrics` | 运行指标（见 [observability.md](../ops/observability.md)）。**永远 200** |
| `GET /metrics/names` | 指标名目录 |

`GET /health` 的响应：

任一 `required=true` 检查项返回 DOWN 时，HTTP 状态为 503，正文整体 status 为 DOWN。
否则 HTTP 200、整体 UP；可选 LLM 检查项可以是 DOWN，仍在 dependencies 中保留其状态/详情。
以下 JSON 格式在两种状态下均保持相同字段，不套用业务错误响应信封。

```json
{
  "status": "UP",
  "version": "0.1.0",
  "dependencies": [
    { "name": "llm", "status": "UP", "required": false, "detail": "未配置模型：AI 走规则兜底，对局仍可完成" },
    { "name": "redis", "status": "UP", "required": true, "detail": "事件流/锁/会话/实验室存储；PING=PONG" },
    { "name": "sqlite", "status": "UP", "required": true, "detail": "账号库与事件归档" }
  ]
}
```

**`dependencies` 为空是缺陷而不是"依赖都好"。** 内存模式下也必须报告一个
`event-store` 检查项——否则 `[]` 会被读成"没有任何问题"，而它实际的含义是"没有任何检查"。

`GET /metrics` 输出 `{ since, uptimeMillis, counters, gauges, timers, llmCost, llmBudget }`。
**它刻意不返回 5xx**：指标里的数字不好看不是"服务不健康"，把两者混在一起会让编排层
重启一个其实正常工作的实例。指标名目录与读法见 [observability.md](../ops/observability.md)。

运维接口要求管理员身份，且全部操作写入审计（含 `traceId`）——**管理员角色尚未实现**，
因此 `/admin/*` 三个接口当前不注册路由。

## 10. 权限矩阵

| 接口组 | 匿名 | 登录用户 | 房主 | 对局参与者 | 观战者 | 管理员 |
| --- | --- | --- | --- | --- | --- | --- |
| `/auth/*` | 注册/登录 | 登出/me | | | | |
| `GET /rooms*` | 否 | 是 | 是 | 是 | 是 | 是 |
| `POST /rooms*` | 否 | 是 | 关闭房间 | 入座/就绪/离开 | 否 | 是 |
| `POST /rooms/{id}/games` | 否 | 否 | 是 | 否 | 否 | 是 |
| `GET /games/{id}` | 公开视图 | 是（按可见性裁剪） | 同参与者 | 是 | 进行中公开，赛后完整 | 是 |
| `POST /games/{id}/actions` | 否 | 仅当控制某座位 | 同参与者 | 是 | 否 | 否 |
| `GET /games/{id}/replay` | 进行中 PUBLIC，赛后完整 | 按状态与身份过滤 | 同参与者 | 进行中按可见性 | 进行中 PUBLIC，赛后完整 | 是 |
| `/analysis`、`/lab` | 否 | 是（**且 `/lab` 按账号隔离**） | 是 | 是 | 是 | 是 |
| `/admin/*` | 否 | 否 | 否 | 否 | 否 | 是（**管理员角色未实现，路由未注册**） |
| `/health`、`/metrics` | 是（运维探针，无业务数据） | 是 | 是 | 是 | 是 | 是 |

观战者永远不能提交行动；参与者不能同时作为本局观战者（[event-design.md §4](../architecture/event-design.md)）。

## 11. 与 WebSocket 的分工

| 能力 | REST | WebSocket |
| --- | --- | --- |
| 提交命令 | 是 | 否 |
| 查询当前视图 | 是 | 首次订阅返回 `GAME_SNAPSHOT_SYNC` |
| 接收状态变化 | 否（除非 `?include=view`） | 是，唯一权威来源 |
| 断线补发 | 否 | 是（`RESUME` + `lastSeq`） |

写操作走 REST 的原因：需要幂等键、`expectedVersion`、明确的错误码与审计，
这些用请求-响应模型表达最直接；WebSocket 只做推送，避免两条写路径产生语义分歧
（[websocket-protocol.md](../frontend/websocket-protocol.md)）。

## 12. 频率限制

| 维度 | 限制 | 超限 |
| --- | --- | --- |
| 单账号写请求 | 每秒 5 次，每分钟 60 次 | `429 RATE_LIMITED` |
| 单账号对局动作 | 每分钟 120 次（覆盖实验室批量场景） | `429` |
| 单 IP 匿名请求 | 每分钟 30 次 | `429` |
| 实验室批量对局 | 全局并发不超过 `lab.maxConcurrentGames`（默认 8，跨批次共享） | `429` + `Retry-After` |

响应带 `Retry-After`（秒）与 `X-RateLimit-Remaining`。
实验室并发上限是**准入控制**而不是领域规则，因此它抛的是接口层的
`RATE_LIMITED`（`Errors.Code`），不借用任何 `DomainErrorCode`。

## 13. 约束合规检查

| 要求 | 本文件的落点 |
| --- | --- |
| 不改变已有架构 | 接口分组与 `DESIGN.md` §4 的 `GameController` / `RoomController` / `UserController` 一致，新增控制器都属于 Interface Layer 的同类职责 |
| 不新增技术选型 | 只用 HTTP + JSON + WebSocket 与既有 Redis/SQLite，无网关、无消息队列、无额外协议 |
| 符合 DDD | 每个写接口对应一个 Application Service，接口层不含规则；`actionType` 映射到领域 Action，规则判定在聚合内 |
| 所有状态变化事件化 | 每个写接口在"是否写状态"列标注，且响应返回 `version` 与 `eventIds` |
| AI 不直接操作 Game Domain | 分析接口只读 `agent_memory` 归档；实验室批量对局走 `StartGameApplicationService`，与真人对局同一路径 |
