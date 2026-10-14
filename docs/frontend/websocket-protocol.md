# WolfMind 详细设计：WebSocket 协议

本文细化 `DESIGN.md` §3（总体架构中的 WebSocket Gateway）、§6（游戏事件驱动架构）、§14（前端设计），
把"事件如何从服务端到达前端"固定成可实现的线协议。协议语义与 [event-design.md](../architecture/event-design.md)、
[system-design.md](../architecture/system-design.md)、[ddd-design.md](../architecture/ddd-design.md) 保持逐字一致。

## 0. 约束来源

| 编号 | 约束 | 出处 |
| --- | --- | --- |
| C-1 | 不改变已有架构（DDD + Event Driven + Multi-Agent） | `DESIGN.md` §3 |
| C-2 | 不新增技术选型，不引入任何消息中间件与第三方实时协议 | `DESIGN.md` §3 §6 |
| C-3 | 符合 DDD：通道只承载 `GameView` 与事件 DTO，不承载聚合与领域对象 | `DESIGN.md` §4 §5、[ddd-design.md](../architecture/ddd-design.md) §1.2 |
| C-4 | 所有状态变化事件化：推送内容全部来自事件流 | `DESIGN.md` §6 §7 |
| C-5 | AI 不得直接操作 Game Domain：AI 的动作与真人一样表现为领域事件 | `DESIGN.md` §9 §12 |

技术名词白名单：WebSocket、REST、Vue3（单文件组件、组合式 API）、浏览器原生能力。
**前端不引入 `DESIGN.md` 之外的新依赖。**

命名对齐说明：`SUBSCRIBE` 与 `RESUME` 共用同一个订阅确认帧 `SUBSCRIBED`，
`from` 与 `truncated` 字段在两个场景下语义一致；本协议不引入第二个确认帧类型。

## 1. 定位与范围

| 项 | 内容 |
| --- | --- |
| 端点 | `/ws`，单一路径，不区分对局；对局通过订阅键区分；等待房另有 `roomId` 订阅（§5.1 `ROOM_SUBSCRIBE`，2026-10-12 所有者批准） |
| 通道数 | 一条。所有实时内容走这一条长连接，不按页面、不按对局开新连接 |
| 传输方向 | 服务端到客户端是推送；客户端到服务端只有订阅管理与连接管理 |
| 与 REST 的关系 | 写操作走 REST，读操作走 REST；WebSocket 只做订阅与推送 |
| 数据来源 | `cg-broadcast` 消费组按 `VisibilityFilter` 过滤后推送（[event-design.md](../architecture/event-design.md) §9） |

三条定位结论：

1. 协议是推送式的，客户端不为获取对局状态而重复查询。`GET /games/{gameId}` 只用于首次进入与显式恢复，
   对局推进期间前端唯一的输入是 `GAME_EVENT`。
2. 协议只有一个通道：连接、认证、订阅、心跳、背压、补发都在这一条连接上完成，
   不新增按对局的连接、不新增分片通道。
3. 协议内容是只读投影：服务端从不要求客户端回传状态，客户端也从不发送补丁、补丁回执或本地计算结果。

## 2. 为什么写操作走 REST、WebSocket 只做订阅与推送

| 维度 | REST（写） | WebSocket（订阅与推送） |
| --- | --- | --- |
| 用途 | 提交命令：注册、登录、房间操作、开局、行动、实验室 | 订阅对局与推送事件 |
| 一致性 | 单写者锁 + `expectedVersion` + 幂等键，命令有明确成败与版本结果 | 允许延迟与重复，客户端按 `seq` 幂等 |
| 审计 | 每次命令有 `correlationId`、`causationId`、`version`，可追溯（[system-design.md](../architecture/system-design.md) §8） | 只记录连接与订阅关系，不产生审计事实 |
| 失败语义 | 结构化错误码，客户端可决定重试与提示 | 帧级错误与连接级关闭码 |
| 幂等 | `Idempotency-Key` + `idem:{gameId}:{key}` | `seq` 去重，重复帧丢弃 |

写操作走 REST 的三个理由：

| 编号 | 理由 | 依据 |
| --- | --- | --- |
| W-1 | 命令需要原子性与明确结果：单写者锁、版本前置条件与幂等记录都属于一次 HTTP 请求的处理过程 | [system-design.md](../architecture/system-design.md) §4 §5 |
| W-2 | 审计要求"谁在何时提交了什么"，请求头与响应体比长连接上的帧更容易形成完整记录 | [system-design.md](../architecture/system-design.md) §8 §11 |
| W-3 | 推送是"至少一次投递 + 幂等消费"，把命令放进来会让重试与去重边界混在一起 | [event-design.md](../architecture/event-design.md) §10 |

推论：`ACK` 帧不是"确认收到命令"，而是"确认已处理到某个 `seq`"，只影响服务端的积压缓冲，不影响领域状态。

## 3. 连接生命周期

### 3.1 正常流程

```
客户端                          服务端 /ws                        说明
  │ 建立连接（浏览器原生 WebSocket） │                                │
  ├───────────────────────────────▶│                                │
  │                                 │ 启动 3 秒认证计时              │
  │ AUTH{token}                     │                                │
  ├───────────────────────────────▶│ 校验会话                        │
  │◀── AUTH_OK{...} ────────────────┤ 计时停止，进入已认证态          │
  │ SUBSCRIBE{gameId, lastSeq}      │                                │
  ├───────────────────────────────▶│ 过滤 + 读取 (lastSeq-0 +        │
  │◀── SUBSCRIBED{from,truncated} ──┤                                │
  │◀── GAME_SNAPSHOT_SYNC 或补发 ───┤ truncated:true 时只发同步帧      │
  │◀── GAME_EVENT 持续推送 ─────────┤ seq 单调递增                    │
  │ PING（每 15s）                  │                                │
  ├───────────────────────────────▶│                                │
  │◀── PONG ────────────────────────┤                                │
  │ ACK{seq}                        │ 释放已确认的积压                │
  ├───────────────────────────────▶│                                │
  │ UNSUBSCRIBE{gameId}             │                                │
  ├───────────────────────────────▶│                                │
  │◀── UNSUBSCRIBED{gameId,to} ─────┤                                │
  │ 关闭连接                         │                                │
  └─────────────────────────────────┴────────────────────────────────┘
```

### 3.2 阶段与超时

| 阶段 | 允许的客户端帧 | 超时 | 超时结果 |
| --- | --- | --- | --- |
| 已连接未认证 | 只允许 `AUTH` | 3 秒 | 关闭 4408 |
| 已认证未订阅 | `AUTH` 之外的全部管理帧 | 无强制订阅超时，受空闲 45s 约束 | 关闭 4408 |
| 已订阅 | 全部客户端帧 | 空闲 45s 无任何客户端帧 | 关闭 4408 |

### 3.3 心跳与空闲

| 项 | 设计值 | 说明 |
| --- | --- | --- |
| 心跳间隔 | 15s | 客户端每 15s 发一次 `PING`，服务端必须回 `PONG` |
| 心跳判定 | 连续两次心跳未得到 `PONG`（即 30s）时视为连接可疑 | 客户端主动重连，不等 45s |
| 空闲阈值 | 45s | 服务端在 45s 内未收到任何客户端帧则关闭 4408 |
| 非状态帧 | `PING`、`ACK` 都属于有效活动，能刷新空闲计时 | 只订阅不发言的连接不会因空闲被断开 |
| 服务端侧 | 服务端使用 WebSocket 协议层的 ping 帧作为补充探测 | 与 `PING`/`PONG` 应用帧是两回事，前者不影响 `seq` |

### 3.4 重连与退避

| 触发 | 是否重连 | 退避序列 | 重连后动作 |
| --- | --- | --- | --- |
| 网络中断、连接被中间设备断开 | 是 | 1s、2s、4s、8s、15s，之后保持 15s；每次加不超过退避值 20% 的抖动 | `AUTH`，然后对每个仍需要的 `gameId` 发 `RESUME{gameId, lastSeq}` |
| 关闭码 4401 | 否 | 无 | 清除本地会话，跳登录 |
| 关闭码 4403 | 否 | 无 | 进入只读展示，不再订阅该对局 |
| 关闭码 4408 | 是 | 同上 | 重新 `AUTH`；超时不是权限问题，重建连接即可恢复 |
| 关闭码 4429 | 是 | 退避序列起点改为 15s | 降低帧发送频率后重连 |
| 关闭码 1011 | 是 | 同上 | 重连并按 `RESUME` 补齐；若服务端持续 1011，展示服务不可用提示 |
| 页面关闭或登出 | 否 | 无 | 主动 `UNSUBSCRIBE` 后关闭连接 |

### 3.5 主动关闭

| 场景 | 客户端动作 | 服务端行为 |
| --- | --- | --- |
| 离开对局页面 | 发 `UNSUBSCRIBE{gameId}`，收到 `UNSUBSCRIBED` 后关闭连接或保留连接 | 解除订阅，之后不再为该连接过滤该对局的事件 |
| 登出 | 先 `POST /auth/logout`，再 `UNSUBSCRIBE` 全部对局，最后关闭连接 | 会话失效，连接随之关闭 |
| 切换到另一对局 | 先 `UNSUBSCRIBE` 旧 `gameId`，再 `SUBSCRIBE` 新 `gameId` | 两个订阅各自独立计数 |
| 客户端不发关闭帧直接关闭 | 无 | 服务端按连接断开清理订阅关系，不产生事件 |

### 3.6 关闭码表

2026-10-14 房间生命周期补充：退订/断线只改变在线状态，不立即释放座位。服务端按账号汇总已认证的 ROOM_SUBSCRIBE/
SUBSCRIBE，任一标签页仍订阅该房间或关联 Game 即视为在线；其他账号观战不能代替成员在线。
默认断线宽限 120 秒，后台每 30 秒检查，重连取消离线计时；超期通过房间退出用例处理。
“退出房间并返回大厅”先调用 REST leave，成功后才退订/导航；错误留在页面。最后真人离开使普通房间关闭、
运行中的 Game 中止，不删除回放。独立 AI 竞技场与实验室不适用。完整契约见 [room-lifecycle.md](../backend/room-lifecycle.md)。

| 关闭码 | 含义 | 触发条件 | 客户端处理 |
| --- | --- | --- | --- |
| 4401 | 未认证 | 首个帧不是 `AUTH`，或 `AUTH` 的令牌无效、已过期 | 清除会话，跳登录，不重连 |
| 4403 | 无权限 | 订阅了无权访问的 `gameId`（例如进行中对局的参与者试图以观战者身份订阅） | 展示拒绝原因，不重连该订阅 |
| 4408 | 认证、订阅与心跳超时 | 连接后 3 秒内未 `AUTH`；或 45 秒内未收到任何客户端帧；或订阅请求超时未完成 | 重连并重新 `AUTH`，按 §3.4 退避 |
| 4429 | 频率超限 | 客户端帧速率超过服务端限制 | 提高退避起点后重连，降低帧频率 |
| 1011 | 服务端异常 | 服务端内部错误导致连接无法继续 | 重连，按 `RESUME` 补齐；连续失败时提示服务不可用 |

4408 在本协议中覆盖"认证、订阅与心跳"三类超时。它们对客户端是同一个后果：连接需要重建，重建后必须重新 `AUTH`。

## 4. 帧信封

### 4.1 服务端到客户端

| 字段 | 类型 | 必填 | 语义 |
| --- | --- | --- | --- |
| `v` | 整数 | 是 | 信封结构版本，当前为 `1`，演进规则见 §12 |
| `id` | 字符串 | 是 | 帧标识，服务端生成，连接内唯一并单调递增；用于客户端日志关联，不参与状态推进 |
| `type` | 字符串 | 是 | 帧类型，大写下划线，取值见 §5 |
| `ts` | 整数 | 是 | 服务端产生该帧的毫秒时间戳；用于时钟偏移校正与展示排序，不参与状态推进 |
| `seq` | 整数 | 是 | 连接级单调递增序号；权威状态帧的 `seq` 等于事件 `version` 或同步点版本，见 §4.3 |
| `payload` | 对象 | 是 | 帧数据，结构由 `type` 决定；固定使用空对象 `{}` 而不是 `null` |

### 4.2 客户端到服务端

| 字段 | 类型 | 必填 | 语义 |
| --- | --- | --- | --- |
| `id` | 字符串 | 是 | 客户端生成，连接内唯一；服务端在 `ERROR` 的 `details[]` 中回引该 `id` |
| `type` | 字符串 | 是 | 帧类型，取值见 §5 |
| `payload` | 对象 | 是 | 帧数据；无参数的帧使用 `{}` |

客户端帧没有 `v`、`ts`、`seq` 字段。客户端不声明协议版本，服务端按 §12 的兼容规则发送内容。

### 4.3 三个字段的精确语义

| 字段 | 语义 | 不做什么 |
| --- | --- | --- |
| `id` | 帧的身份，用于去重与日志关联。同一连接内可比较先后，跨连接无意义 | 不作为幂等键，不代表命令已生效 |
| `ts` | 服务端时间。客户端用它计算本地时钟偏移，再据此校正倒计时显示 | 不用于排序事件，事件顺序只看 `seq` |
| `seq` | 状态推进序号。`GAME_EVENT` 的 `seq` 等于该事件的 `version`；`GAME_SNAPSHOT_SYNC` 的 `seq` 等于同步点版本 `replayedTo` | 不从客户端帧推导，不由客户端自增 |

`payload` 约定：

| 编号 | 约定 |
| --- | --- |
| P-1 | `GAME_EVENT` 的 `payload` 就是 [event-design.md](../architecture/event-design.md) §2 的事件信封，字段名逐字一致。 |
| P-2 | `GAME_SNAPSHOT_SYNC` 的 `payload` 与 `GET /games/{gameId}` 返回的 `GameView` 字段一致，另加 `snapshotVersion`、`replayedTo`、`source`。 |
| P-3 | 所有 `payload` 都不含聚合对象、仓储接口、Redis 键名、LLM 响应等基础设施内容。 |
| P-4 | 时间一律为毫秒整数，枚举一律为大写下划线或大写字面量，与领域枚举逐字一致。 |
| P-5 | 未知字段必须被忽略，缺失字段按 §12 的降级规则处理，不因任一情况报错或断连。 |

## 5. 完整帧目录

### 5.1 客户端到服务端

#### AUTH

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 连接建立后 3 秒内，且只允许发送一次 |
| payload | `{token}` |

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `token` | 字符串 | 是 | 会话令牌，与 REST 请求头使用的是同一个令牌，不在 URL 中出现 |

```json
{ "id": "c-1", "type": "AUTH", "payload": { "token": "<会话令牌>" } }
```

客户端处理规则：收到 `AUTH_OK` 之前不发送任何其他帧；收到 `ERROR{code:"AUTH_REQUIRED"}` 时按 §3.4 不重连，跳登录。

#### SUBSCRIBE

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 进入 `/games/{gameId}` 或 `/watch/{gameId}` 后 |
| payload | `{gameId, lastSeq}` |

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `gameId` | 字符串 | 是 | 订阅键 |
| `lastSeq` | 整数 | 否 | 客户端已确认的最大 `seq`；省略或为 `0` 表示不要历史补发 |

```json
{ "id": "c-2", "type": "SUBSCRIBE", "payload": { "gameId": "g_20240501_0007", "lastSeq": 88 } }
```

客户端处理规则：`lastSeq` 省略时等待 `GAME_SNAPSHOT_SYNC` 建立基线；重复订阅同一 `gameId` 由客户端避免，
服务端对重复订阅只回一次 `SUBSCRIBED`。

#### UNSUBSCRIBE

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 离开对局页面、切换到另一对局、登出 |
| payload | `{gameId}` |

```json
{ "id": "c-3", "type": "UNSUBSCRIBE", "payload": { "gameId": "g_20240501_0007" } }
```

客户端处理规则：发出后立即停止把该 `gameId` 的帧交给界面，即使仍有在途帧到达也丢弃。

#### RESUME

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 重连成功并 `AUTH` 之后；收到 `SYNC_REQUIRED` 之后；检测到 `seq` 缺号之后 |
| payload | `{gameId, lastSeq}` |

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `gameId` | 字符串 | 是 | 订阅键 |
| `lastSeq` | 整数 | 是 | 客户端已确认的最大 `seq`，缺省不发该帧 |

```json
{ "id": "c-4", "type": "RESUME", "payload": { "gameId": "g_20240501_0007", "lastSeq": 88 } }
```

客户端处理规则：`RESUME` 之后进入"同步中"状态，禁用提交控件；收到 `SUBSCRIBED` 与随后的
`GAME_SNAPSHOT_SYNC` 或补发事件后恢复在线状态。同一连接上 `RESUME` 可以发送多次，服务端按最后一次为准。

#### PING

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 每 15s 一次，连接空闲时也发送 |
| payload | `{}` |

```json
{ "id": "c-5", "type": "PING", "payload": {} }
```

客户端处理规则：发送后启动应答计时；连续两次没有得到 `PONG` 视为连接可疑，主动关闭并按 §3.4 重连。

#### ACK

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 客户端已完成一批 `GAME_EVENT` 的界面归约 |
| payload | `{seq}` |

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `seq` | 整数 | 是 | 已成功处理的最大 `seq`，只增不减 |

```json
{ "id": "c-6", "type": "ACK", "payload": { "seq": 91 } }
```

客户端处理规则：合并发送，不对每条事件单独 `ACK`；`ACK` 只影响服务端的积压缓冲，不表示命令成功。

#### ROOM_SUBSCRIBE

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 进入 `/rooms/{roomId}` 等待页且连接已 `AUTH_OK`；重连并 `AUTH` 之后再次发送 |
| payload | `{roomId}` |

```json
{ "id": "c-8", "type": "ROOM_SUBSCRIBE", "payload": { "roomId": "r_mv5a1b2c_1" } }
```

服务端规则：

| 编号 | 规则 |
| --- | --- |
| R-1 | 需已认证，否则回 `ERROR{code:"AUTH_REQUIRED"}`。不另设读权限：房间视图与 `GET /rooms/{roomId}` 相同，那条路由不要求登录。 |
| R-2 | 房间存在时立即回一帧 `ROOM_SNAPSHOT`（它就是订阅应答，没有单独的确认帧）；不存在回 `ERROR{code:"ROOM_NOT_FOUND"}`，不建立订阅。 |
| R-3 | 先登记订阅再读取快照，因此订阅期间提交的房间变化不会漏推。 |
| R-4 | 单连接最多同时订阅 4 个房间，超出回 `ERROR{code:"VALIDATION_ERROR"}`；重复订阅已订阅的房间不占名额，照常回快照。 |
| R-5 | 房间订阅与对局订阅互不影响，不改变连接阶段，也不推进任何 `lastSeq`。 |

#### ROOM_UNSUBSCRIBE

| 项 | 内容 |
| --- | --- |
| 方向 | 客户端到服务端 |
| 触发 | 离开等待页（包括开局后跳到对局页） |
| payload | `{roomId}` |

```json
{ "id": "c-9", "type": "ROOM_UNSUBSCRIBE", "payload": { "roomId": "r_mv5a1b2c_1" } }
```

客户端处理规则：发出后即停止处理该房间的 `ROOM_SNAPSHOT`；服务端不回应答帧。

### 5.2 服务端到客户端

#### AUTH_OK

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | `AUTH` 校验通过 |
| payload | `{userId, username, sessionId, heartbeatIntervalMs, idleTimeoutMs, maxPendingFrames, maxMessageBytes, serverTime}` |

| 字段 | 说明 |
| --- | --- |
| `userId` | 当前会话用户 |
| `username` | 展示名 |
| `sessionId` | 会话标识，与 `USER_LOGGED_IN` 事件中的 `sessionId` 一致 |
| `heartbeatIntervalMs` | 心跳间隔，设计值 15000 |
| `idleTimeoutMs` | 空闲断开阈值，设计值 45000 |
| `maxPendingFrames` | 单连接积压上限，设计值 200 |
| `maxMessageBytes` | 单帧消息上限，设计值 65536 |
| `serverTime` | 服务端当前毫秒时间，用于首次时钟偏移校正 |

```json
{
  "v": 1, "id": "s-1", "type": "AUTH_OK", "ts": 1730000000100, "seq": 0,
  "payload": {
    "userId": "u_8812", "username": "p_3", "sessionId": "sess_7f3a",
    "heartbeatIntervalMs": 15000, "idleTimeoutMs": 45000,
    "maxPendingFrames": 200, "maxMessageBytes": 65536,
    "serverTime": 1730000000100
  }
}
```

客户端处理规则：保存 `serverTime` 与本地时间之差作为时钟偏移；按服务端下发的间隔启动心跳，不使用写死的间隔。

#### SUBSCRIBED

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | `SUBSCRIBE` 或 `RESUME` 被接受 |
| payload | `{gameId, scope, from, to, truncated, resumed}` |

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `gameId` | 字符串 | 订阅键 |
| `scope` | 字符串 | 订阅身份：`PLAYER` 表示以对局内玩家身份订阅；`SPECTATOR` 表示以观战者身份订阅。由服务端判定 |
| `from` | 整数 | 补发起始版本，等于 `lastSeq + 1`；无补发时为同步点版本 |
| `to` | 整数 | 当前事件流的最大版本 |
| `truncated` | 布尔 | `true` 表示无法补发（`lastSeq` 早于流内最早版本），服务端将改发 `GAME_SNAPSHOT_SYNC` |
| `resumed` | 布尔 | `true` 表示本次是 `RESUME` 的应答，`false` 表示首次订阅 |

```json
{
  "v": 1, "id": "s-2", "type": "SUBSCRIBED", "ts": 1730000000200, "seq": 88,
  "payload": { "gameId": "g_20240501_0007", "scope": "PLAYER", "from": 89, "to": 88, "truncated": false, "resumed": true }
}
```

客户端处理规则：`truncated` 为 `true` 时进入等待同步状态，直到 `GAME_SNAPSHOT_SYNC` 到达才重建投影；
`scope` 为 `SPECTATOR` 时显示“公开观战”；该字段只描述订阅身份，不代表拥有私有事件权限。进行中仅收到 PUBLIC，赛后由服务端状态触发完整披露。`PLAYER` 保持本人/阵营权限。

#### UNSUBSCRIBED

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | `UNSUBSCRIBE` 被处理 |
| payload | `{gameId, to}` |

```json
{
  "v": 1, "id": "s-3", "type": "UNSUBSCRIBED", "ts": 1730000000300, "seq": 91,
  "payload": { "gameId": "g_20240501_0007", "to": 91 }
}
```

客户端处理规则：清除该 `gameId` 的本地订阅状态；不删除已在界面上渲染的历史内容，除非页面本身离开。

#### GAME_SNAPSHOT_SYNC

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | 首次订阅且未带 `lastSeq`；`truncated` 为 `true`；对局开局后的首帧 |
| payload | `GameView` 字段 + `{snapshotVersion, replayedTo, source}` |

| 字段 | 说明 |
| --- | --- |
| `gameId`、`version`、`phase`、`round`、`status`、`players[]`、`me`、`turnOrder`、`turnIndex`、`currentTurn`、`votes` | 与 `GET /games/{gameId}` 返回的 `GameView` 字段一致，字段来源见 [page-design.md](page-design.md) §2.3 |
| `snapshotVersion` | 基线使用的快照版本，无快照时为 `0` |
| `replayedTo` | 同步点版本，等于本帧的 `seq` |
| `source` | 基线来源：`SNAPSHOT`（快照加增量重放）或 `FULL_REPLAY`（从版本 1 全量重放） |

```json
{
  "v": 1, "id": "s-4", "type": "GAME_SNAPSHOT_SYNC", "ts": 1730000000400, "seq": 11,
  "payload": {
    "gameId": "g_20240501_0007", "version": 11, "phase": "NIGHT_WOLF", "round": 1, "status": "RUNNING",
    "players": [{ "playerId": "p_1", "seatNo": 1, "alive": true, "controller": { "type": "HUMAN", "refId": "u_8812" }, "ready": true }],
    "me": { "playerId": "p_1", "seatNo": 1, "role": "WEREWOLF", "camp": "WOLF", "alive": true, "skillState": {} },
    "turnOrder": [1, 2, 3, 4, 5, 6, 7, 8, 9], "turnIndex": 0,
    "currentTurn": { "playerId": "p_1", "seatNo": 1, "phase": "NIGHT_WOLF", "turnIndex": 0, "deadline": 1730000030000 },
    "votes": [],
    "snapshotVersion": 0, "replayedTo": 11, "source": "FULL_REPLAY"
  }
}
```

客户端处理规则：整帧覆盖本地投影，覆盖后把 `lastSeq` 设为 `seq`；同步期间到达的、`seq` 小于等于该同步点的
`GAME_EVENT` 一律丢弃。快照不是真相来源（INV-15），客户端不缓存该帧中未出现的字段。

#### GAME_EVENT

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | `cg-broadcast` 消费到新事件并通过 `VisibilityFilter`（[event-design.md](../architecture/event-design.md) §9） |
| payload | 事件信封，字段见 [event-design.md](../architecture/event-design.md) §2 |

| 字段 | 说明 |
| --- | --- |
| `eventId` | 事件标识 |
| `gameId` | 事件流键 |
| `version` | 聚合版本，等于本帧的 `seq` |
| `type` | 事件类型，见 §7 映射表 |
| `schemaVersion` | 该 `type` 的载荷版本 |
| `occurredAt` | 事件发生时间，由 `ActionContext` 注入 |
| `phase` | 事件发生时的 `GamePhase` |
| `round` | 轮次 |
| `actorId` | 触发者：`userId`（真人）、`playerId`（AI、Bot）或 `SYSTEM` |
| `visibility` | `PUBLIC` / `WOLF` / `PRIVATE` / `JUDGE` |
| `causationId` | 直接触发本事件的命令 id 或上游 `eventId` |
| `correlationId` | 一次完整用例的关联 id |
| `payload` | 事件专属数据，字段名与统一语言一致 |

```json
{
  "v": 1, "id": "s-5", "type": "GAME_EVENT", "ts": 1730000000500, "seq": 123,
  "payload": {
    "eventId": "01H8X2-000123", "gameId": "g_20240501_0007", "version": 123, "type": "PLAYER_VOTED",
    "schemaVersion": 1, "occurredAt": 1730000000123, "phase": "DAY_VOTE", "round": 2, "actorId": "u_8812",
    "visibility": "PUBLIC", "causationId": "cmd_7f3a91", "correlationId": "cor_5b21e0",
    "payload": { "playerId": "p_3", "seatNo": 4, "targetId": "p_5", "targetSeatNo": 6, "abstain": false }
  }
}
```

客户端处理规则：`seq <= lastSeq` 丢弃；`seq = lastSeq + 1` 归约并推进 `lastSeq`；`seq > lastSeq + 1` 先缓存并立即发
`RESUME{gameId, lastSeq}`。归约只按 `type` 分派，不解析 `payload` 之外的语义，不依据事件自行推导阶段、胜负或票数。

#### AGENT_SPEECH_DELTA

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | AI 回合中发言内容流式产出，抢在 `PLAYER_SPOKE` 之前 |
| payload | `{gameId, playerId, seatNo, speechId, delta, index, final}` |

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `gameId` | 字符串 | 对局标识 |
| `playerId` | 字符串 | 发言的 AI 玩家 |
| `seatNo` | 整数 | 座位号 1..9 |
| `speechId` | 字符串 | 与最终 `PLAYER_SPOKE.payload.speechId` 相同的标识，用于把预览与权威内容对上 |
| `delta` | 字符串 | 本次增量文本 |
| `index` | 整数 | 增量序号，从 `0` 开始，连接内针对同一 `speechId` 单调递增 |
| `final` | 布尔 | `true` 表示预览结束，服务端不再为该 `speechId` 发送增量 |

```json
{
  "v": 1, "id": "s-6", "type": "AGENT_SPEECH_DELTA", "ts": 1730000000600, "seq": 123,
  "payload": {
    "gameId": "g_20240501_0007", "playerId": "p_4", "seatNo": 5,
    "speechId": "sp_0007_12", "delta": "我认为 6 号昨天的发言有问题", "index": 0, "final": false
  }
}
```

客户端处理规则：

| 编号 | 规则 |
| --- | --- |
| D-1 | 本帧是非权威预览：不产生事件、不改变 `version`、不写入事件存储与回放。 |
| D-2 | 可丢弃：网络拥塞、`index` 缺号、或 `final` 未到达时，客户端直接丢弃已累积的预览，不重试、不请求补发。 |
| D-3 | 不推进 `lastSeq`，也不参与 `seq <= lastSeq` 的丢弃判断；本帧 `seq` 等于当前的 `lastSeq`。 |
| D-4 | 权威内容只来自 `PLAYER_SPOKE`；收到 `PLAYER_SPOKE` 后按 `speechId` 清空对应预览并写入正式发言。 |
| D-5 | 预览区与正式发言区在界面上可区分，且预览不参与任何计数、不进入回放。 |
| D-6 | AI 与真人在渲染上不做能力区分：真人的发言直接从 `PLAYER_SPOKE` 出现，AI 的发言多一段可丢弃的预览。 |

#### SYNC_REQUIRED

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | 服务端发现客户端无法继续增量接收，需要重建基线 |
| payload | `{gameId, reason, lastSeq, serverSeq}` |

| 字段 | 说明 |
| --- | --- |
| `gameId` | 订阅键 |
| `reason` | `SEQ_GAP`（服务端检测到客户端缺号）、`BACKLOG_EXCEEDED`（积压超过上限）、`EVENT_TRUNCATED`（流内最早版本已晚于 `lastSeq`） |
| `lastSeq` | 服务端记录的该连接已确认的最大 `seq` |
| `serverSeq` | 服务端当前的最大 `seq` |

```json
{
  "v": 1, "id": "s-7", "type": "SYNC_REQUIRED", "ts": 1730000000700, "seq": 200,
  "payload": { "gameId": "g_20240501_0007", "reason": "BACKLOG_EXCEEDED", "lastSeq": 88, "serverSeq": 200 }
}
```

客户端处理规则：停用提交控件，发 `RESUME{gameId, lastSeq}`；`reason` 只用于日志与文案，客户端不对其做分支判断。
`EVENT_TRUNCATED` 与 `BACKLOG_EXCEEDED` 之后通常会收到 `GAME_SNAPSHOT_SYNC`。

#### ROOM_SNAPSHOT

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | `ROOM_SUBSCRIBE` 被接受；之后该房间每次提交房间命令（入座、离开、准备、AI 补位、开局冻结、关闭）后一帧 |
| payload | `{roomId, version, room}` |

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `roomId` | 字符串 | 订阅键 |
| `version` | 整数 | 房间事件流版本，读取 `room` 时的版本；同一房间单调不减 |
| `room` | 对象 | 与 `GET /rooms/{roomId}` 的响应字段一致（`RoomView`） |

```json
{
  "v": 1, "id": "room_snapshot_3", "type": "ROOM_SNAPSHOT", "ts": 1730000000850, "seq": 7,
  "payload": {
    "roomId": "r_mv5a1b2c_1", "version": 14,
    "room": { "roomId": "r_mv5a1b2c_1", "status": "RUNNING", "gameId": "g_r_mv5a1b2c_1", "occupied": 9, "...": "..." }
  }
}
```

客户端处理规则：

| 编号 | 规则 |
| --- | --- |
| RS-1 | 每帧都是完整状态，直接替换本地房间；丢弃 `version` 不大于已应用版本的帧。帧 `seq` 是连接内自增号，不参与判断。 |
| RS-2 | 一次命令提交的多条房间事件只推一帧（读的是提交后的当前状态），所以 `version` 可能跳号，这不是缺号，不需要补发。 |
| RS-3 | `room.status = RUNNING` 且 `room.gameId` 非空时进入 `/games/{gameId}`。服务端先发牌、后冻结房间，因此此时对局已存在。 |
| RS-4 | 断线期间的变化不补发：重连并 `AUTH` 后重新 `ROOM_SUBSCRIBE`，订阅应答的快照即为最新状态。 |

#### PONG

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | 收到 `PING` |
| payload | `{serverSeq, serverTime}` |

```json
{
  "v": 1, "id": "s-8", "type": "PONG", "ts": 1730000000800, "seq": 123,
  "payload": { "serverSeq": 123, "serverTime": 1730000000800 }
}
```

客户端处理规则：刷新时钟偏移，清除心跳应答计时。`serverSeq` 与本地 `lastSeq` 的差值是"落后程度"，
可用于界面提示，但补发的触发条件是缺号而不是差值。

#### ERROR

| 项 | 内容 |
| --- | --- |
| 方向 | 服务端到客户端 |
| 触发 | 客户端帧非法、无权限、超限，或服务端处理该帧失败 |
| payload | `{code, message, details[], traceId}` |

| 字段 | 说明 |
| --- | --- |
| `code` | 与 REST 对齐的错误码，见 §10 |
| `message` | 面向人的说明，作为补充文案 |
| `details[]` | 结构化补充；处理客户端帧失败时，其中一项为触发错误的客户端帧 `id` |
| `traceId` | 追踪标识，客户端在错误提示中展示并可复制 |

```json
{
  "v": 1, "id": "s-9", "type": "ERROR", "ts": 1730000000900, "seq": 123,
  "payload": { "code": "FORBIDDEN", "message": "参与者不能订阅本局的观战视图", "details": ["c-7"], "traceId": "tr_5b21e0" }
}
```

客户端处理规则：`ERROR` 不断开连接，除非服务端随后关闭；按 §10 的重试分类处理；
不要用 `message` 做判断分支，分支只依据 `code`。

## 6. 订阅与可见性

### 6.1 订阅参数与身份判定

| 项 | 规则 |
| --- | --- |
| 订阅键 | 对局用 `gameId`；等待房用 `roomId`（§5.1 `ROOM_SUBSCRIBE`，只推房间快照，不涉及对局可见性）；不为大厅、用户开设订阅键 |
| 角色身份 | 由服务端判定。`SUBSCRIBE` 不带角色、阵营、座位、观战标记等参数 |
| 身份来源 | 会话令牌对应的 `userId`，与服务端保存的对局参与者关系比对（`Player.controller.refId`） |
| 订阅结果 | `SUBSCRIBED.scope`：命中参与者关系则为 `PLAYER`，否则按观战授权判定为 `SPECTATOR` |
| 重复订阅 | 同一连接重复 `SUBSCRIBE` 同一 `gameId` 只回一次 `SUBSCRIBED`，不重复补发历史 |

### 6.2 VisibilityFilter 的过滤位置

```
事件流 game:{id}:events
        │
        ▼
   cg-broadcast 消费组
        │
        ▼
   VisibilityFilter（唯一实现，event-design.md §4）
        │  输入：事件信封 + 订阅者身份（`me` 与订阅范围）
        ▼
   每连接的推送队列 ──▶ 本连接可见的帧
```

| 编号 | 规则 |
| --- | --- |
| V-1 | 过滤在服务端完成，且只有一份实现。推送层、`QueryGameStateTool`、`QueryHistoryTool`、回放接口共用它。 |
| V-2 | 前端不做 `visibility` 过滤；前端收到什么就展示什么，未收到只表示不可见。 |
| V-3 | `visibility` 是事件的属性，不是连接或订阅的属性；同一连接上的不同对局各自过滤。 |
| V-4 | 过滤结果不写回事件流，也不改变事件的 `version`；客户端看到的 `seq` 序列可以出现"版本跳跃"，这是正常现象。 |

V-4 的直接后果：参与者收到的事件 `version` 可能不连续（例如 `1、2、12、13`），因为中间的 `WOLF` 或 `PRIVATE`
事件对他不可见。客户端不得把版本跳跃当作缺号，缺号判定只看已推送帧的 `seq` 是否连续。

### 6.3 按可见性的分发规则

| `visibility` | 分发对象 | 典型事件 | 参与者是否可见 |
| --- | --- | --- | --- |
| `PUBLIC` | 该对局的全部订阅者 | `PHASE_CHANGED`、`PLAYER_SPOKE`、`PLAYER_VOTED` | 可见 |
| `WOLF` | 进行中仅该对局的狼人玩家；赛后全部读取者 | `WOLF_KILL_NOMINATED`、`WOLF_KILL_SELECTED`、`WOLF_CHAT_SENT` | 仅狼人阵营 |
| `PRIVATE` | 进行中仅事件指定的玩家；赛后全部读取者 | `ROLE_ASSIGNED`、`SEER_INSPECTED`、`SEER_SKIPPED`、`WITCH_SAVED` | 仅本人 |
| `JUDGE` | 系统与赛后读取者 | `PLAYER_KILLED`、`GAME_RECOVERED` | 不可见 |

### 6.4 公开观战与赛后披露的授权条件

| 条件 | 要求 |
| --- | --- |
| 对局状态 | 进行中仅公开记录，结束后完整披露（2026-10-12 所有者批准） |
| 身份 | 服务端反查参与者关系：参与者只授予 PLAYER，本局其他登录用户授予 SPECTATOR；客户端不能指定 scope |
| 账号 | 已登录；未登录连接在 `AUTH` 阶段就被 4401 拒绝 |
| 结果 | 非参与者 `SUBSCRIBED.scope = SPECTATOR`，进行中推送仅包含 PUBLIC；终局后 REST 历史读取及补发允许四种事件 |
| 拒绝 | 不通过时返回 `ERROR{code:"FORBIDDEN"}` 并关闭 4403 |
| 行为限制 | 观战者不能提交任何行动；REST 侧的 `POST /games/{gameId}/actions` 同样拒绝 |

### 6.5 AI Agent 不获得额外可见性

| 编号 | 规则 |
| --- | --- |
| A-1 | AI Agent 的可见性与它扮演的玩家完全一致，不允许上帝视角（[event-design.md](../architecture/event-design.md) §4）。 |
| A-2 | Agent 通过 `GameViewQueryPort` 拿到的是同一份 `VisibilityFilter` 过滤后的 `GameView`；若 Agent 侧使用本通道，其 `scope` 只能是 `PLAYER`。 |
| A-3 | `AGENT_` 前缀的决策事件写入 `game:{id}:agent:{playerId}`，不进入 `game:{id}:events`，也不通过本通道推送。 |
| A-4 | 前端不区分"AI 的帧"与"真人的帧"：两者都是 `GAME_EVENT`，唯一差别是 AI 发言可能先有一段 `AGENT_SPEECH_DELTA` 预览。 |
| A-5 | 前端不调用任何 AI 接口改变对局状态；AI 的发言与投票同样以事件形式到达（[page-design.md](page-design.md) §4.4）。 |

## 7. 事件推送映射表

### 7.1 Game 聚合事件（`game:{id}:events`）

| 领域事件类型 | 推送帧 | `visibility` | 客户端动作 |
| --- | --- | --- | --- |
| `GAME_STARTED` | `GAME_EVENT` | `PUBLIC` | 初始化座位、`turnOrder`、`roleComposition` 展示；跳转到游戏页面 |
| `ROLE_ASSIGNED` | `GAME_EVENT` | `PRIVATE`（本人） | 写入 `me.role` 与 `me.camp`；不渲染他人角色 |
| `PHASE_CHANGED` | `GAME_EVENT` | `PUBLIC` | 更新阶段指示器；`resumePhase` 只用于提示"稍后回到某阶段" |
| `TURN_GRANTED` | `GAME_EVENT` | `PUBLIC` | 启动倒计时（`deadline` 来源）；高亮当前发言者；启用本人行动区 |
| `WOLF_KILL_NOMINATED` | `GAME_EVENT` | `WOLF` | 狼人夜间面板显示提名；非狼人阵营收不到 |
| `WOLF_KILL_SELECTED` | `GAME_EVENT` | `WOLF` | 狼人面板显示狼刀定案与提名集合；不显示给其他阵营 |
| `WOLF_CHAT_SENT` | `GAME_EVENT` | `WOLF` | 狼人频道追加一条夜聊；同时刷新视图以更新 `WOLF_CHAT.remaining`；非狼人阵营收不到 |
| `SEER_INSPECTED` | `GAME_EVENT` | `PRIVATE`（预言家） | 本人查验结果面板追加一条 |
| `SEER_SKIPPED` | `GAME_EVENT` | `PRIVATE`（预言家） | 本人夜间面板显示"本夜未查验"，不渲染为错误 |
| `WITCH_SAVED` | `GAME_EVENT` | `PRIVATE`（女巫） | 本人夜间面板标记解药已用 |
| `WITCH_POISONED` | `GAME_EVENT` | `PRIVATE`（女巫） | 本人夜间面板标记毒药已用 |
| `WITCH_PASSED` | `GAME_EVENT` | `PRIVATE`（女巫） | 本人夜间面板显示"本夜不用药" |
| `HUNTER_SHOT` | `GAME_EVENT` | `PUBLIC` | 公共时间线插入开枪记录；被带走座位在收到 `DEATH_ANNOUNCED` 后置为出局 |
| `HUNTER_SKIPPED` | `GAME_EVENT` | `PUBLIC` | 公共时间线插入"猎人放弃开枪"记录，不渲染为错误 |
| `PLAYER_SPOKE` | `GAME_EVENT` | `PUBLIC` | 发言区写入权威内容；按 `speechId` 清空对应预览 |
| `SPEECH_SKIPPED` | `GAME_EVENT` | `PUBLIC` | 发言区插入一条跳过记录，不渲染为错误 |
| `PLAYER_VOTED` | `GAME_EVENT` | `PUBLIC` | 逐条展示投票流向；不累加票数、不推断结果 |
| `VOTE_TALLY_RESOLVED` | `GAME_EVENT` | `PUBLIC` | 按 `tally[]`、`maxVotes`、`result` 展示计票结果 |
| `PLAYER_KILLED` | `GAME_EVENT` | `JUDGE` | 进行中观战与参与者均收不到；赛后完整记录才显示死亡事实与死因 |
| `DEATH_ANNOUNCED` | `GAME_EVENT` | `PUBLIC` | 按 `deaths[]` 把座位置为出局；不显示死因 |
| `GAME_FINISHED` | `GAME_EVENT` | `PUBLIC` | 打开结算面板，提供回放与分析入口 |
| `GAME_ABORTED` | `GAME_EVENT` | `PUBLIC` | 打开中止面板，提供回放入口 |
| `GAME_RECOVERED` | `GAME_EVENT` | `JUDGE` | 进行中观战与参与者均收不到；赛后记录可展示恢复事实 |

### 7.2 不在本通道推送的事件

| 领域事件类型 | 所属聚合 | 推送帧 | 原因与替代路径 |
| --- | --- | --- | --- |
| Room 聚合全部事件 | Room | `ROOM_SNAPSHOT` | 不逐条推事件，而是推房间当前快照，只发给 `ROOM_SUBSCRIBE` 了该房间的连接（§5.2）；对局开始后座位卡上的"AI 接管"标记来自 `GameView.players[].controller.type` |
| `USER_REGISTERED` | User | 无 | 账号事件 `visibility` 为 `JUDGE`，只用于审计，不面向浏览器推送 |
| `USER_LOGGED_IN` | User | 无 | 同上 |
| `USER_LOGGED_OUT` | User | 无 | 同上 |
| `AGENT_*`（决策事件） | Agent | 无 | 写入 `game:{id}:agent:{playerId}`，仅归档与调试使用，AI 分析页经 REST 读取 |

## 8. 断线补偿

### 8.1 `lastSeq` 语义

| 编号 | 规则 |
| --- | --- |
| SEQ-1 | `lastSeq` 是客户端已成功归约的最大 `seq`，初始为 `0`。 |
| SEQ-2 | 只有 `GAME_EVENT` 与 `GAME_SNAPSHOT_SYNC` 推进 `lastSeq`。 |
| SEQ-3 | 客户端丢弃 `seq <= lastSeq` 的权威状态帧，不重复归约、不重复播放动效。 |
| SEQ-4 | `AGENT_SPEECH_DELTA`、`ROOM_SNAPSHOT`、`PONG`、`ERROR`、`SUBSCRIBED`、`UNSUBSCRIBED`、`AUTH_OK` 不推进 `lastSeq`；其中 `AGENT_SPEECH_DELTA` 的 `seq` 等于当前 `lastSeq`，不参与 SEQ-3 的丢弃判断，因为它不携带任何状态。 |
| SEQ-5 | 出现 `seq > lastSeq + 1` 时视为缺号：先缓存该帧，立即发 `RESUME{gameId, lastSeq}`。 |
| SEQ-6 | `version` 在事件流内部连续，但推送的 `seq` 对某个订阅者可以跳跃（V-4）；跳跃不是缺号。 |

### 8.2 RESUME 流程

```
客户端                          服务端                           Redis Stream
  │ RESUME{gameId, lastSeq:88}     │                                  │
  ├───────────────────────────────▶│ 校验订阅权限与身份                │
  │                                ├── XRANGE game:{id}:events (88-0 + │
  │                                │      ← 按 VisibilityFilter 过滤   │
  │                                │                                  │
  │                                │ 流内最早版本 > 88？               │
  │                                │   是 → truncated = true           │
  │                                │   否 → truncated = false          │
  │◀── SUBSCRIBED{from,to,truncated}┤                                 │
  │                                │                                  │
  │  truncated = false：            │                                  │
  │◀── GAME_EVENT × N（按 seq 升序）┤ 补发与实时推送共用同一队列，按 seq 排序 │
  │  truncated = true：             │                                  │
  │◀── GAME_SNAPSHOT_SYNC ──────────┤ 改发全量同步，不补发历史事件       │
  │                                │                                  │
  │ ACK{seq}                        │                                  │
  ├───────────────────────────────▶│ 释放已确认的积压                  │
```

补发读取命令固定为 `XRANGE game:{id}:events (lastSeq-0 +`，与 [event-design.md](../architecture/event-design.md) §3.1
的显式条目 ID 设计配套：条目 ID 就是 `version-0`，因此"从 `lastSeq` 之后读取"不需要额外的偏移换算。

### 8.3 `truncated` 处理

| 情况 | 服务端 | 客户端 |
| --- | --- | --- |
| `lastSeq` 在流内最早版本之后 | `truncated = false`，按 `seq` 升序补发 | 逐帧归约，`lastSeq` 追平后恢复在线 |
| `lastSeq` 早于流内最早版本 | `truncated = true`，不补发，直接发 `GAME_SNAPSHOT_SYNC` | 丢弃本地投影并以同步帧重建，随后只接收 `seq` 更大的事件 |
| `lastSeq` 为 `0` 或缺省 | 不尝试补发，先发 `GAME_SNAPSHOT_SYNC` 建立基线 | 同上一行 |
| 事件流已按保留策略过期 | 流为空，`truncated = true` | 重建后若仍无内容，展示"对局数据已归档"，提供回放入口 |

### 8.4 `GAME_SNAPSHOT_SYNC` 与事件重放的关系

| 项 | 规则 |
| --- | --- |
| 事实地位 | 事件流是唯一真相，同步帧是投影（INV-15）。同步帧的 `version` 必须能由 `EventStorePort.read(1..version)` 推导出来。 |
| `source` | `SNAPSHOT` 表示基线取自快照加增量重放；`FULL_REPLAY` 表示从版本 1 全量重放。客户端只记录，不区分渲染。 |
| 与重放接口的区别 | `GET /games/{gameId}/replay?from=&to=` 是展示重放，面向回放页面；`GAME_SNAPSHOT_SYNC` 是当前状态同步，面向实时页面。 |
| 覆盖语义 | 同步帧整帧覆盖本地投影：帧中未出现的字段表示该信息对当前订阅者不可见，而不是"保持旧值"。 |
| 版本定位 | 同步帧的 `seq` 等于 `replayedTo`；客户端以此为新的 `lastSeq`。 |

### 8.5 重连期间的重复与乱序

| 场景 | 处理 |
| --- | --- |
| 补发帧与实时帧交叉到达 | 一律按 `seq` 排序后归约；`seq <= lastSeq` 丢弃；不允许按到达顺序归约 |
| 同一条事件被推送两次 | `seq` 去重丢弃第二次；界面不重复播放入场动效 |
| 补发期间事件流继续增长 | 服务端按 `seq` 升序合并补发与实时推送，客户端不需要额外同步 |
| 一次 `RESUME` 未追平 | 客户端再次发 `RESUME{gameId, lastSeq}`；服务端可升级为 `SYNC_REQUIRED` 加 `GAME_SNAPSHOT_SYNC` |
| 同步帧与旧事件交叉 | 客户端以同步点为界：`seq` 小于等于同步点的 `GAME_EVENT` 一律丢弃 |

## 9. 背压与限流

| 项 | 设计值 | 行为 |
| --- | --- | --- |
| 单连接积压上限 | 200 帧 | 待发送帧超过该值时按优先级丢弃，见下表 |
| 单帧消息上限 | 64KB | 超出上限的内容服务端不发送该帧；客户端发送的帧超限时返回 `ERROR` |
| 心跳间隔 | 15s | 客户端主动发送 |
| 空闲阈值 | 45s | 服务端关闭 4408 |
| 订阅数上限 | 由运行配置给出 | 前端每次只订阅一个 `gameId`，离开页面即 `UNSUBSCRIBE`，正常使用不会逼近上限 |
| 发送速率限制 | 由服务端执行 | 超限返回 `ERROR{code:"RATE_LIMITED"}`；持续超限关闭 4429 |

丢弃优先级：

| 优先级 | 帧类型 | 处理 |
| --- | --- | --- |
| 最先丢弃 | `AGENT_SPEECH_DELTA` | 非权威预览，可直接丢弃；客户端可凭 `final` 缺失判断预览不完整并清空 |
| 其次丢弃 | `PONG` 的重复应答 | 只保留最新一条，心跳不需要逐条保留 |
| 不丢弃 | `GAME_EVENT` | 权威事件绝不丢弃。积压到上限时发 `SYNC_REQUIRED` 并关闭连接，由重连后的同步帧重建 |
| 不丢弃 | `GAME_SNAPSHOT_SYNC` | 同步帧是重建投影的唯一依据，宁可关闭连接也不丢 |
| 不丢弃 | `SUBSCRIBED`、`UNSUBSCRIBED`、`AUTH_OK`、`ERROR` | 连接与订阅状态机依赖这些帧 |

超限处理流程：

```
积压帧数 > 200
   │
   ├─ 丢弃队列中的全部 AGENT_SPEECH_DELTA
   │
   ├─ 仍超限？
   │     ├─ 否 → 继续正常推送
   │     └─ 是 → 发 SYNC_REQUIRED{reason:"BACKLOG_EXCEEDED"} → 关闭连接（1011）
   │
   └─ 客户端重连 → AUTH → RESUME{gameId, lastSeq}
         └─ 补发仍不可行 → GAME_SNAPSHOT_SYNC 重建投影
```

客户端侧约束：

| 编号 | 约束 |
| --- | --- |
| BP-1 | 客户端不为每条事件发送 `ACK`，按批合并，避免自身成为限流对象。 |
| BP-2 | 客户端不发送心跳之外的周期性帧；发言与行动走 REST，不占用连接预算。 |
| BP-3 | 收到 `RATE_LIMITED` 时提高退避起点并降低帧频率，不立即重发。 |
| BP-4 | 客户端不因为收到大量事件而自行降低渲染帧率到"跳过事件"的程度；跳过的只有预览帧。 |

## 10. 错误处理

### 10.1 `ERROR` 帧结构与处理顺序

```
收到 ERROR{code, message, details[], traceId}
   │
   ├─ 按 code 分支（不用 message 做判断）
   │
   ├─ 可重试？ ── 是 → 按退避重试，复用同一语义（RESUME 用同一 lastSeq）
   │             └─ 否 → 收起相关控件，展示原因与 traceId
   │
   └─ ERROR 本身不断开连接；若随后收到关闭帧，按 §3.6 的关闭码处理
```

### 10.2 错误码表（与 REST 对齐）

| `code` | 帧级含义 | 客户端处理 | 可重试 |
| --- | --- | --- | --- |
| `AUTH_REQUIRED` | `AUTH` 缺失、令牌无效或会话过期 | 清空会话，关闭连接，跳登录 | 否（重新登录后新建连接） |
| `FORBIDDEN` | 订阅或帧操作无权限，例如参与者订阅观战视图 | 移除该订阅，展示拒绝原因 | 否 |
| `GAME_NOT_FOUND` | `gameId` 不存在或已归档不可订阅 | 移除订阅，提供返回大厅入口 | 否 |
| `ROOM_NOT_FOUND` | `ROOM_SUBSCRIBE` 的 `roomId` 不存在 | 不建立房间订阅，等待页按 `GET /rooms/{roomId}` 的结果展示 | 否 |
| `GAME_VERSION_CONFLICT` | 客户端引用的版本与服务端不一致 | 发 `RESUME{gameId, lastSeq}` 重新同步 | 是 |
| `ACTION_NOT_ALLOWED` | 该身份在当前阶段不允许该操作 | 收起对应控件 | 否 |
| `ACTION_INVALID_TARGET` | 目标不合法 | 清除选中目标，等待用户重选 | 是 |
| `TURN_NOT_YOURS` | 不是该玩家的回合 | 禁用行动区并触发同步 | 是 |
| `GAME_BUSY` | 对局正在处理其他写入 | 用同一幂等键退避重试，最多 3 次（与 api-spec §1 一致，2026-10-12 所有者统一） | 是 |
| `GAME_ALREADY_FINISHED` | 对局已结束 | 隐藏行动控件，进入只读展示 | 否 |
| `GAME_RECOVERING` | 对局正在恢复，写请求被拒 | 显示恢复提示，等待后重试 | 是 |
| `IDEMPOTENCY_REPLAY` | 幂等命中，返回首次结果 | 按首次结果处理，不重复提示 | 不适用 |
| `SERVICE_DEGRADED` | 服务降级 | 全局横幅；写操作禁用，读操作保留 | 是 |
| `RATE_LIMITED` | 帧或命令频率超限 | 提高退避起点，降低帧频率 | 是 |

分类汇总：

| 分类 | 错误码 | 处理原则 |
| --- | --- | --- |
| 会话类 | `AUTH_REQUIRED` | 不重试连接，重新登录 |
| 权限类 | `FORBIDDEN`、`ACTION_NOT_ALLOWED`、`GAME_ALREADY_FINISHED` | 不重试，改界面状态 |
| 数据类 | `GAME_NOT_FOUND`、`ROOM_NOT_FOUND` | 不重试，离开该对局或房间 |
| 并发类 | `GAME_VERSION_CONFLICT`、`TURN_NOT_YOURS`、`GAME_BUSY` | 先同步再重试，或退避重试 |
| 参数类 | `ACTION_INVALID_TARGET` | 由用户修正后重试 |
| 可用性类 | `GAME_RECOVERING`、`SERVICE_DEGRADED`、`RATE_LIMITED` | 退避重试，展示降级状态 |
| 幂等类 | `IDEMPOTENCY_REPLAY` | 视为成功，不产生额外界面变化 |

## 11. 安全

| 面 | 措施 |
| --- | --- |
| 令牌传递 | 令牌只在 `AUTH` 帧的 `payload.token` 中出现一次；连接建立后不再重复发送 |
| 令牌不落 URL | 端点固定为 `/ws`，URL 查询串不携带令牌、`sessionId`、`userId`、`gameId` 之外的标识 |
| 令牌不进日志 | 服务端与客户端的连接日志记录连接 id、`userId`、订阅键与错误码，不记录 `payload.token` |
| 跨域与来源 | 服务端在握手阶段校验来源，只接受本站来源的连接；不满足时拒绝握手 |
| 越权订阅 | `SUBSCRIBE` 不带任何身份参数，身份由会话决定；无权限时返回 `ERROR{code:"FORBIDDEN"}` 并关闭 4403 |
| 参与者冒充观战者 | 参与者订阅本局时服务端授予 `PLAYER` 语义，不提供上帝视角；不通过则拒绝订阅 |
| 行动提交 | 本通道不能提交行动；`POST /games/{gameId}/actions` 另行校验"提交者是否是该座位的控制者" |
| 提示注入 | 玩家发言只作为数据进入界面与其他 Agent 上下文，不作为指令；渲染时按纯文本处理，不解析为标记或脚本 |
| 内容长度 | 发言最多 500 字符，由领域校验；超长内容不会通过 REST 进入事件流，也就不会出现在推送中 |
| 帧大小 | 单帧消息上限 64KB，超限的客户端帧返回 `ERROR` |
| 频率 | 按连接与按账号限制提交与帧频率，超限返回 `RATE_LIMITED` 或关闭 4429 |
| 审计 | 连接与订阅关系可追溯；每次命令处理记录 `correlationId`、`causationId`、`gameId`、`version` |

## 12. 版本演进

| 项 | 规则 |
| --- | --- |
| `v` 字段 | 表示信封结构版本，当前为 `1`。只有信封结构变更才递增，新增帧类型与新增字段不递增 |
| 新增帧类型 | 允许。旧客户端必须忽略不认识的 `type`，不报错、不断连、不改变状态 |
| 新增字段 | 允许。只增不删，不改语义；客户端忽略未知字段 |
| 删除或改名 | 禁止。需要替换时新增字段或新增帧类型，旧字段保留 |
| 事件载荷版本 | 事件的 `schemaVersion` 与信封 `v` 相互独立，按 [event-design.md](../architecture/event-design.md) §6 演进 |
| 旧客户端降级 | 服务端始终推送 `AUTH_OK`、`GAME_EVENT`、`GAME_SNAPSHOT_SYNC` 这三条基线帧；只依赖扩展帧的功能在旧客户端上不出现，功能不完整但不报错 |
| 必填字段缺失 | 客户端以"该信息不可用"降级渲染，不崩溃、不使用占位数字 |
| 未知枚举值 | 客户端按字面展示或折叠为"未知"分类，不映射到已知枚举 |
| 兼容性验证 | 新旧客户端同时连接同一对局，两边都能完成"进入、接收、重连、补发"四步 |

## 13. 一致性校验清单

- [ ] 帧信封字段与 §4 一致：服务端方向含 `v`、`id`、`type`、`ts`、`seq`、`payload`；客户端方向含 `id`、`type`、`payload`。
- [ ] 帧类型集合与 §5 目录完全一致，客户端帧八个、服务端帧十个，没有目录外的帧类型。
- [ ] 等待房订阅：订阅后立即收到 `ROOM_SNAPSHOT`；房间每次提交变化各推一帧；开局后的快照带 `RUNNING` 与 `gameId`；退订后不再推送。
- [ ] `GAME_EVENT.payload` 的字段与 [event-design.md](../architecture/event-design.md) §2 的事件信封逐字一致。
- [ ] 任意一条对局的推送中，权威状态帧的 `seq` 单调递增，且等于事件的 `version` 或同步点版本。
- [ ] `AGENT_SPEECH_DELTA` 不推进 `lastSeq`，不进入事件流，不参与丢弃判断，权威内容只来自 `PLAYER_SPOKE`。
- [ ] 参与者订阅的推送中不出现 `WOLF`（非狼人阵营）、`PRIVATE`（非本人）、`JUDGE` 事件。
- [ ] 观战者订阅进行中对局仅收到 PUBLIC，`SUBSCRIBED.scope = SPECTATOR`；角色、阵营与库存为空；终局后完整历史包含四种 visibility。
- [ ] 本局参与者订阅保持 PLAYER，无法指定 SPECTATOR 或获得其他角色的私有事件。
- [ ] 补发命令固定为 `XRANGE game:{id}:events (lastSeq-0 +`，并按 `VisibilityFilter` 过滤后发送。
- [ ] `lastSeq` 早于流内最早版本时返回 `truncated:true` 且改发 `GAME_SNAPSHOT_SYNC`，不补发历史事件。
- [ ] 积压超过 200 帧时先丢 `AGENT_SPEECH_DELTA`，绝不丢 `GAME_EVENT`，仍超限则发 `SYNC_REQUIRED` 并关闭。
- [ ] 连接后 3 秒内未 `AUTH` 关闭 4408；45 秒无客户端帧关闭 4408；关闭码与 §3.6 表一致。
- [ ] 令牌不出现在 URL、`details[]`、日志与错误上报中，只在 `AUTH` 帧出现一次。
- [ ] `SYNC_REQUIRED`、`GAME_SNAPSHOT_SYNC`、`RESUME` 三个环节能把任意断线场景恢复到与 `GET /games/{gameId}` 一致的状态。
- [ ] 全文没有 `DESIGN.md` 之外的技术选型，没有引入第三方实时协议与中间件。
