# WolfMind 详细设计：Redis 键空间与事件存储

本文细化 `DESIGN.md` §7（Event Store 设计）与 §13（数据设计），并落实
[event-design.md](../architecture/event-design.md)、[ddd-design.md](../architecture/ddd-design.md)、
[system-design.md](../architecture/system-design.md) 已经冻结的契约。

## 1. 定位与约束来源

| 项 | 内容 |
| --- | --- |
| 本文范围 | Redis 键空间、事件流结构、消费组位点、快照、锁、TTL 与内存、键生命周期、反模式、运维检查 |
| 权威来源 | `DESIGN.md` §7 §13（总体数据设计）、[event-design.md](../architecture/event-design.md)（信封、可见性、消费组、快照、保留）、[ddd-design.md](../architecture/ddd-design.md)（聚合字段、不变量、并发）、[system-design.md](../architecture/system-design.md)（并发、事务边界、容量） |
| 键名一致性 | 键名与 `DESIGN.md` 一致，未新增选型。`DESIGN.md` §13 列出的 5 个键全部原样保留 |
| 技术边界 | 只使用 Java 与 Redis 的 Hash、Stream、Set、ZSet、String、消费组、事务、过期；存储层与 `DESIGN.md` 完全一致 |
| 不在本文范围 | 游戏规则（[ddd-design.md](../architecture/ddd-design.md)）、事件类型定义（[event-design.md](../architecture/event-design.md) §7）、SQLite 表结构（[database-schema.md](database-schema.md)） |

三条贯穿全文的定位，任何设计决定都要能对上其中一条：

| 编号 | 定位 | 可检查的表现 |
| --- | --- | --- |
| R-1 | Redis 的 state 是投影不是真相 | `game:{id}:state` 允许滞后；业务判断只读聚合（快照 + 事件增量重放），不读 state |
| R-2 | 写入路径唯一 | 聚合物化 到 事件写入 `game:{id}:events` 到 消费组投影；不存在第二条改状态的路径 |
| R-3 | 所有状态变化必须事件化 | 任何直接改 `game:{id}:state`、`game:{id}:players`、`room:{id}:ready` 而不产生事件的写法都是反模式（§11） |

写入路径（与 [ddd-design.md](../architecture/ddd-design.md) §3.4 同构）：

```
Command（REST 或 Tool Gateway）
   |
   v
Application Service
   |-- 1 幂等查询      HGETALL idem:{gameId}:{idempotencyKey}
   |-- 2 单写者锁      SET game:{id}:lock {owner} NX PX 3000
   |-- 3 加载聚合      GET game:{id}:snapshot + XRANGE game:{id}:events (snapVersion-0 +
   |-- 4 领域校验      INV-1..INV-16，产出 List<DomainEvent>
   |-- 5 事件写入      Lua：版本前置校验 + XADD {version}-0 + SET lastTs + HSET idem
   |-- 6 释放锁        Lua：compare-and-del
   v
game:{id}:events（真相，只追加）
   |
   |-- cg-projection --> game:{id}:state / game:{id}:players / game:{id}:votes + SQLite 归档
   |-- cg-broadcast  --> VisibilityFilter 过滤后 WebSocket 推送
   |-- cg-agent      --> Agent 短期记忆，TURN_GRANTED 触发 AI 回合
   |-- cg-insight    --> games / statistics / 回放索引（SQLite）
```

## 2. 键空间总览

| 键名模式 | 类型 | 用途 | 写入者 | 读取者 | TTL |
| --- | --- | --- | --- | --- | --- |
| `game:{id}:state` | Hash | 对局状态投影（§5.2） | cg-projection | `GetGameViewApplicationService`、崩溃恢复扫描 | 同 events；当前投影在进程内 |
| `game:{id}:events` | Stream | 领域事件流，唯一真相 | 应用层（`EventStorePort`） | 聚合加载、消费组、WebSocket 补发、运维 | 未归档不自动过期，完整归档后最多 7 天 |
| `game:{id}:players` | Hash | 玩家与角色投影（§5.3） | cg-projection | 视图查询、`GameViewQueryPort` | 同 events |
| `game:{id}:votes` | Hash | 当前投票轮投影（§5.4） | cg-projection | 视图查询（投票区） | 同 events |
| `game:{id}:agent:{id}` | Stream | Agent 决策事件，非领域事件 | Agent Runtime | cg-insight、AI 分析页、实验室 | 同 events |
| `game:{id}:agent:{playerId}:session` | Hash | Agent 会话运行态（§5.7） | Agent Runtime | Agent Runtime | 同 events |
| `game:{id}:snapshot` | String | 最新快照，JSON 文本（§7） | 快照生成器（持锁内联） | `RecoverGameApplicationService`、cg-projection | 同 events |
| `game:{id}:snapshots` | ZSet | 快照索引，score = version | 快照生成器 | 恢复流程、保留清理 | 同 events |
| `game:{id}:snapshot:{version}` | String | 历史快照（`snapshots` 的 member） | 快照生成器 | 恢复流程、归档 | 同 events |
| `game:{id}:events:dlq` | Stream | 死信（§6.5） | 消费失败处理路径 | 运维接口（JUDGE 可见）、监控 | 7 天固定 |
| `game:{id}:lock` | String | 单写者锁 | 应用层（`RedisLock`） | 应用层 | 3000 ms，可续租 |
| `game:{id}:lastTs` | String | `occurredAt` 单调性基准 | 事件写入脚本 | 事件写入脚本 | 同 events |
| `idem:{gameId}:{idempotencyKey}` | Hash | 幂等结果 | 事件写入脚本 | 应用层 | 10 分钟 |
| `room:{id}:events` | Stream | 房间事件流 | Room 应用服务 | cg-projection、cg-broadcast | 关闭后 7 天 |
| `room:{id}:meta` | Hash | 房间元数据投影（§5.5） | cg-projection（房间分支） | 大厅页、房间等待页 | 关闭后 7 天 |
| `room:{id}:seats` | Hash | 座位投影，field = `seatNo`（§5.6） | cg-projection（房间分支） | 房间等待页、`StartGameApplicationService` | 关闭后 7 天 |
| `room:{id}:ready` | Set | 已就绪座位，member = `seatNo` | cg-projection（房间分支） | `StartGameApplicationService`（INV-R2） | 关闭后 7 天 |
| `user:{id}:events` | Stream | 账号事件流 | Identity 用例 | 审计、账号投影 | 365 天（设计建议值，见 §9.1） |
| `user:{id}:session:{sessionId}` | Hash | 会话令牌记录（§5.9） | Identity 用例、WebSocketGateway | Identity 用例、WebSocketGateway | 12 小时（设计建议值） |
| `ws:sub:{gameId}` | Set | 对局订阅关系，member = `connId` | WebSocketGateway | cg-broadcast | 45 秒，心跳续期 |
| `ws:conn:{connId}` | Hash | 连接状态与位点（§5.8） | WebSocketGateway | cg-broadcast、断线补偿 | 45 秒，心跳续期 |

对局维度的键统一以 `game:{id}:` 开头，房间与账号维度各自独立前缀。`id` 是 `gameId`，`{id}` 是 `roomId` 或 `userId`，两种写法都来自 `DESIGN.md` §13 与 [event-design.md](../architecture/event-design.md) §3.1，本文不改写。

## 3. DESIGN.md 原始键到详细设计的映射

### 3.1 DESIGN.md §13 固定键（原样保留）

| DESIGN.md 键 | 本文落点 | 类型 | 归属聚合 | 说明 |
| --- | --- | --- | --- | --- |
| `game:{id}:state` | §5.2 字段表 | Hash | Game | 状态投影。不承载真相，不参与业务判断 |
| `game:{id}:events` | §4 结构与协议 | Stream | Game | 事件流的唯一载体。事件类型见 [event-design.md](../architecture/event-design.md) §7.2 |
| `game:{id}:players` | §5.3 字段表 | Hash | Game | 玩家与角色投影。`role` 与 `camp` 属隐藏信息，只在服务端使用 |
| `game:{id}:votes` | §5.4 字段表 | Hash | Game | 当前投票轮投影。field = `seatNo` |
| `game:{id}:agent:{id}` | §6.6 消费说明 | Stream | Agent | Agent 决策事件。类型前缀 `AGENT_`，永不进入 `game:{id}:events` |

### 3.2 已读文档已定义、本文给出结构细节的键

| 键名 | 定义出处 | 类型 | 本文补充的内容 |
| --- | --- | --- | --- |
| `game:{id}:snapshot` | [event-design.md](../architecture/event-design.md) §8、`DESIGN.md` §8 | String | 字段清单、checksum 算法、生成顺序（§7） |
| `game:{id}:snapshots` | [event-design.md](../architecture/event-design.md) §8 | ZSet | score = version，member = 快照键名，保留最近 3 个（§7.4） |
| `game:{id}:events:dlq` | [event-design.md](../architecture/event-design.md) §9 | Stream | 条目字段、进入条件、重放流程（§6.5） |
| `game:{id}:lock` | [ddd-design.md](../architecture/ddd-design.md) §8、[system-design.md](../architecture/system-design.md) §4 | String | 锁值格式、续租脚本、失败语义（§8） |
| `game:{id}:lastTs` | [event-design.md](../architecture/event-design.md) §3.2 | String | 取值规则与写入位置（§4.3） |
| `idem:{gameId}:{idempotencyKey}` | [event-design.md](../architecture/event-design.md) §10 | Hash | 字段与 TTL、与事件写入同批的实现（§4.3、§5.10） |
| `room:{id}:events` | [event-design.md](../architecture/event-design.md) §3.1 §7.1 | Stream | 条目 ID 规则与房间版消费组（§6.7） |
| `user:{id}:events` | [event-design.md](../architecture/event-design.md) §3.1 §7.3 | Stream | 保留策略（§9.1） |
| `ws:sub:{gameId}` | [system-design.md](../architecture/system-design.md) §10 | Set | 成员失效清理（§5.8） |
| `ws:conn:{connId}` | [system-design.md](../architecture/system-design.md) §10 | Hash | 字段表与 `lastSeq` 结构（§5.8） |

### 3.3 细节扩展键与理由

以下 6 个键名在四份已读文档中没有给出结构，属于本文的细节扩展。每个扩展都必须说明为什么已有键无法表达同一事实。

| 键名 | 类型 | 为什么不能用已有键表达 |
| --- | --- | --- |
| `room:{id}:meta` | Hash | 房间聚合没有状态投影键。`game:{id}:state` 属于 Game 聚合，字段集是 `phase` / `round` / `status` / `turnIndex` 这类对局字段，与房间元数据无关；`room:{id}:events` 是流而不是投影，大厅页列出房间需要逐房间重放全部房间事件，读放大不可接受。房间与对局生命周期不同（对局开始后房间仍存在），也不能复用对局键的 TTL |
| `room:{id}:seats` | Hash | `game:{id}:players` 的字段含 `role`、`camp`、`skillState`，是开局后才存在的私有信息；房间阶段没有角色，且入座与离开的发生频率远高于开局。复用 `players` 会在开局前写入语义为空的角色字段，破坏字段含义 |
| `room:{id}:ready` | Set | 就绪判定是集合成员关系（INV-R2：全部座位就绪才能开局）。用 `room:{id}:seats` 的布尔字段表达时需要 `HGETALL` 后逐条解析 JSON，无法用一次 `SCARD` 与座位数比较。Set 的成员语义与不变量一一对应，判定只写一处 |
| `user:{id}:session:{sessionId}` | Hash | 口令与令牌不进入事件流（[event-design.md](../architecture/event-design.md) §7.3 尾注）。`user:{id}:events` 是长期留存的数据，用它保存会话等同于把令牌写进长期归档；会话需要可撤销与自动过期，只有独立键加过期能表达 |
| `game:{id}:agent:{playerId}:session` | Hash | Agent 会话是可变运行态（当前回合、token 消耗、失败次数）。`game:{id}:agent:{playerId}` 只能追加，无法原地更新；SQLite `agent_memory` 是归档，把回合级高频写入放进归档表会让它成为热表，也违反 [database-schema.md](database-schema.md) §10 的 archive-first 顺序 |
| `game:{id}:snapshot:{version}` | String | [event-design.md](../architecture/event-design.md) §8 规定 `game:{id}:snapshots` 的 member 是快照键名，历史快照必须可按版本寻址。`game:{id}:snapshot` 只保留最新一个；`game:{id}:state` 是投影且不含 `checksum`，不能替代快照参与恢复校验 |

`room:{id}:meta`、`room:{id}:seats`、`room:{id}:ready`、`user:{id}:session:{sessionId}`、`game:{id}:agent:{playerId}:session` 都只承载投影或运行态，不承载领域事实。它们的真相仍在各自的事件流里：房间事实在 `room:{id}:events`，账号事实在 `user:{id}:events`，Agent 决策事实在 `game:{id}:agent:{playerId}`。

## 4. 事件流详细结构

### 4.1 Stream 条目字段

字段与 [event-design.md](../architecture/event-design.md) §2 的信封逐字对应，`payload` 序列化为单字段 JSON 字符串。

| field | 类型 | 必填 | 来源 | 说明 |
| --- | --- | --- | --- | --- |
| `eventId` | string | 是 | 应用层 | 全局唯一，时间前缀加序列形式，可排序 |
| `gameId` | string | 是 | 命令 | 与键名冗余，使单条目自解释，便于归档与 DLQ 搬运 |
| `version` | string | 是 | 聚合 | 同一 `gameId` 内从 1 严格加 1，等于已产生事件数 |
| `type` | string | 是 | 领域 | 大写下划线。取值见 [event-design.md](../architecture/event-design.md) §7 |
| `schemaVersion` | string | 是 | 领域 | 该 `type` 的载荷版本，初始为 `1` |
| `occurredAt` | string | 是 | 应用层 | 毫秒时间戳，来自 `ActionContext`，重放不重新取值 |
| `phase` | string | 是 | 聚合 | `GamePhase` 取值，回放时间线分组依据 |
| `round` | string | 是 | 聚合 | 第几轮，`PHASE_CHANGED` 之外的事件也带 |
| `actorId` | string | 是 | 应用层 | `userId`（真人）、`playerId`（AI、Bot）或 `SYSTEM` |
| `visibility` | string | 是 | 领域 | `PUBLIC` / `WOLF` / `PRIVATE` / `JUDGE` |
| `causationId` | string | 是 | 应用层 | 直接触发本次事件的命令 id 或上游 `eventId` |
| `correlationId` | string | 是 | 应用层 | 一次完整用例的关联 id |
| `payload` | string | 是 | 领域 | 单字段 JSON 字符串，字段名与统一语言一致 |

序列化约定：

| 约定 | 内容 |
| --- | --- |
| 类型 | Stream 字段没有类型，全部以裸字符串写入。数值用十进制字符串，布尔用 `true` / `false` |
| 缺失 | 信封字段不允许缺失，不用默认值兜底（[event-design.md](../architecture/event-design.md) §2）。无值场景写空串 |
| `payload` | 单行 JSON（不换行、不缩进），键顺序为领域字段声明顺序，UTF-8 |
| 时间 | 一律毫秒整数，不用日期字符串 |
| 枚举 | 一律大写下划线，与 [ddd-design.md](../architecture/ddd-design.md) §1.3 统一语言一致 |

### 4.2 显式条目 ID 规则

| 规则 | 内容 |
| --- | --- |
| ID 形式 | `XADD game:{id}:events {version}-0 ...`，序列部分恒为 `0` |
| 为什么不用 `*` | Redis 拒绝小于等于当前最大 ID 的写入，应用层用 `expectedVersion + 1` 作为 ID，Redis 自身成为并发仲裁者，不需要额外的版本比对往返 |
| 首条 ID | 必须是 `1-0`。Redis 不接受 `0-0`，因此聚合 `version` 从 1 开始与首条 ID 一致 |
| 物理顺序 | 条目 ID 与聚合版本一一对应，`XRANGE game:{id}:events (50-0 +` 就是"重放 50 之后的事件" |
| 时钟无关 | `occurredAt` 只是字段，流顺序由版本决定，时钟回拨不破坏顺序（[event-design.md](../architecture/event-design.md) §3.2） |

写入结果与错误码：

| 情况 | Redis 表现 | 返回值 | 客户端动作 |
| --- | --- | --- | --- |
| 正常追加 | 全部事件写入 | `APPENDED` | 返回 `{version, eventIds}` |
| 版本落后 | 脚本内前置校验失败 | `GAME_VERSION_CONFLICT` | 先拉取最新视图再重试（[system-design.md](../architecture/system-design.md) §4） |
| 重复命令 | 幂等键命中 | `REPLAYED` | 直接返回首次结果，不产生新事件 |
| 写请求在恢复中 | 恢复流程持有锁 | `GAME_RECOVERING` | 退避重试（[event-design.md](../architecture/event-design.md) §11） |
| 锁被他人持有 | `SET NX` 失败 | `GAME_BUSY` | 指数退避重试 |
| 对局已终态 | 领域校验拒绝 | `GAME_ALREADY_FINISHED` | 不再重试（INV-12） |

### 4.3 写入协议

按顺序执行，顺序不可调换（先持久化后发布）：

| 步 | 动作 | 命令 | 失败处理 |
| --- | --- | --- | --- |
| 1 | 幂等快速路径 | `HGETALL idem:{gameId}:{idempotencyKey}` | 命中则返回 `REPLAYED`，结束 |
| 2 | 获取单写者锁 | `SET game:{id}:lock {owner} NX PX 3000` | 失败返回 `GAME_BUSY` |
| 3 | 读取版本水位 | `XREVRANGE game:{id}:events + - COUNT 1` 与快照 `version` | 不一致时以流为准 |
| 4 | 加载聚合 | `GET game:{id}:snapshot` 加 `XRANGE game:{id}:events (snapVersion-0 +` | 快照缺失或 checksum 失败则全量重放 |
| 5 | 构造 `ActionContext` | 时间取 `max(now, GET game:{id}:lastTs)`，随机种子仅在开局使用 | 无 |
| 6 | 领域校验 | `game.handle(action, ctx)` | 领域异常映射为结构化错误码，不写事件 |
| 7 | 事件写入 | §4.4 的 Lua 脚本 | `VERSION_CONFLICT` 转 `GAME_VERSION_CONFLICT` |
| 8 | 快照判断 | `version % 50 == 0` 或终态时生成（§7.2） | 失败不回滚事件，下次触发补做 |
| 9 | 释放锁 | Lua compare-and-del | 锁 TTL 到期自动释放 |
| 10 | 发布 | 消费组自行 `XREADGROUP`，无独立发布动作 | 发布失败由事件存留与位点补偿 |

第 7 步是原子追加的边界。允许一条命令产生多个事件（开局产生 `GAME_STARTED` 加 9 条 `ROLE_ASSIGNED` 加 1 条 `PHASE_CHANGED`，共 11 条），全部事件必须连续写入，中途失败不允许留下版本空洞。

### 4.4 原子追加脚本

```lua
-- KEYS[1] = game:{id}:events
-- KEYS[2] = game:{id}:lastTs
-- KEYS[3] = idem:{gameId}:{idempotencyKey}
-- ARGV[1] = expectedVersion
-- ARGV[2] = occurredAt（应用层取 max(now, GET lastTs) 的结果）
-- ARGV[3] = n（本批事件条数）
-- ARGV[4] = 幂等键 TTL 秒（idempotency.ttlMinutes 乘 60）
-- ARGV[5] = 事件流 TTL 秒（未归档为 0，表示 PERSIST；归档核对完成后另设 604800）
-- ARGV[6] = resultHash（用于幂等重放比对）
-- ARGV[7..] = n 组事件字段，每组 26 个元素（13 个字段名加 13 个字段值）

local stream   = KEYS[1]
local lastTs   = KEYS[2]
local idemKey  = KEYS[3]
local expected = tonumber(ARGV[1])
local ts       = tonumber(ARGV[2])
local n        = tonumber(ARGV[3])
local idemTtl  = tonumber(ARGV[4])
local streamTtl = tonumber(ARGV[5])
local resultHash = ARGV[6]

-- 1. 幂等命中：返回首次结果，不重复追加
local hit = redis.call('HMGET', idemKey, 'eventId', 'version', 'resultHash')
if hit[1] then
  return { 'REPLAYED', hit[1], hit[2], hit[3] }
end

-- 2. 版本前置校验：让错误早于 XADD 出现，返回体可读
local tail = redis.call('XREVRANGE', stream, '+', '-', 'COUNT', 1)
local current = 0
if #tail > 0 then
  current = tonumber(string.match(tail[1][1], '^(%d+)%-'))
end
if current ~= expected then
  return { 'VERSION_CONFLICT', tostring(current) }
end

-- 3. 连续追加，ID = {version}-0；version 字段由脚本强制写入，避免与条目 ID 不一致
local cursor = 7
local firstEventId = ''
for i = 0, n - 1 do
  local c = {}
  for f = 1, 13 do
    c[f] = ARGV[cursor + 1]
    cursor = cursor + 2
  end
  if i == 0 then firstEventId = c[1] end
  local version = expected + 1 + i
  redis.call('XADD', stream, version .. '-0',
    'eventId', c[1], 'gameId', c[2], 'version', tostring(version), 'type', c[4],
    'schemaVersion', c[5], 'occurredAt', c[6], 'phase', c[7], 'round', c[8],
    'actorId', c[9], 'visibility', c[10], 'causationId', c[11],
    'correlationId', c[12], 'payload', c[13])
end

-- 4. 时间戳基准与保留期（未归档必须取消过期）
redis.call('SET', lastTs, ts)
if streamTtl > 0 then
  redis.call('EXPIRE', stream, streamTtl)
  redis.call('EXPIRE', lastTs, streamTtl)
else
  redis.call('PERSIST', stream)
  redis.call('PERSIST', lastTs)
end

-- 5. 幂等记录与事件写入同批（system-design.md §5）
redis.call('HSET', idemKey, 'eventId', firstEventId, 'version', tostring(expected + n),
           'resultHash', resultHash, 'createdAt', ts)
redis.call('EXPIRE', idemKey, idemTtl)

return { 'APPENDED', tostring(expected + n), firstEventId }
```

同一批事件的 `occurredAt` 取同一值：它们由同一条命令在同一时刻因果产生。跨批单调性由 `max(now, lastTs)` 与第 4 步写回保证。

### 4.5 Lua 与事务的分工

Redis 事务没有回滚语义：`MULTI` / `EXEC` 中后续命令失败不会撤销前面的 `XADD`，多事件追加会留下版本空洞。因此分工固定如下：

| 场景 | 手段 | 理由 |
| --- | --- | --- |
| 多事件原子追加 | Lua 脚本 | 需要"先校验后写"，且必须整体成功或整体不写 |
| 幂等与事件同批 | Lua 脚本 | 需要幂等命中短路，属于条件写 |
| 锁释放与续租 | Lua 脚本 | 需要 compare-and-act，避免误删他人锁 |
| 投影多键同批写（state 与 players 与 votes） | `MULTI` / `EXEC` | 全部是无条件写，失败整批重做即可，不需要回滚 |
| 单事件追加 | 直接 `XADD` 显式 ID | 单条写入本身就是原子的，Redis 的 ID 校验即仲裁 |

投影版本前置校验在事务外完成，正确性依赖 [event-design.md](../architecture/event-design.md) §9 的分片规则：同一对局只在一个消费者实例上处理。

### 4.6 读取模式

| 用途 | 命令 | 说明 |
| --- | --- | --- |
| 全量重放 | `XRANGE game:{id}:events - + COUNT 500` | 生产环境按 500 条分批翻页，避免单次响应过大 |
| 增量重放 | `XRANGE game:{id}:events (50-0 +` | 恢复重放，起点为快照 `version` |
| 单条读取 | `XRANGE game:{id}:events 123-0 123-0` | 按版本定位单条事件，用于对账与排障 |
| 尾部读取 | `XREVRANGE game:{id}:events + - COUNT 1` | 版本水位校验 |
| 消费 | `XREADGROUP GROUP cg-projection c1 COUNT 200 BLOCK 2000 STREAMS game:{id}:events >` | 新消息，`>` 表示只取未投递过的条目 |
| 重读自己的 PEL | `XREADGROUP GROUP cg-projection c1 COUNT 200 STREAMS game:{id}:events 0` | 用 ID `0` 读取本消费者未 ACK 的条目 |
| 位点汇总 | `XPENDING game:{id}:events cg-projection` | 返回待处理总数、最小与最大 ID、按消费者分布 |
| 位点明细 | `XPENDING game:{id}:events cg-projection - + 100` | 返回每条待处理条目的 ID、消费者、空闲毫秒、投递次数 |
| 转移滞留条目 | `XAUTOCLAIM game:{id}:events cg-projection c2 60000 0-0 COUNT 100` | 空闲超过 60 秒的条目转移给存活消费者 |
| 确认 | `XACK game:{id}:events cg-projection 123-0` | 语义见 §6.4 |
| 流信息 | `XLEN`、`XINFO STREAM`、`XINFO GROUPS` | 容量与滞后监控（§12） |

读取顺序保证：同一 `gameId` 内全序（单写者锁加单调版本加 Stream 物理顺序），跨 `gameId` 无序（[event-design.md](../architecture/event-design.md) §5）。消费者不得假设"看到了 `PLAYER_VOTED` 就一定看到了 `PHASE_CHANGED`"，需要跨事件判断时按 `version` 做条件等待。

## 5. Hash、Set 与 String 字段表

### 5.1 序列化约定

| 约定 | 内容 |
| --- | --- |
| 值格式 | Hash 值统一为单行 JSON 字符串，与 `payload` 的约定一致，便于与事件条目逐字段比对 |
| 数组 | JSON 数组。空集合写 `[]` 或 `{}`，不用 `HDEL` 删除字段，保证 `HGETALL` 结果稳定 |
| 字段名 | 与 [ddd-design.md](../architecture/ddd-design.md) §2.2 的聚合字段名逐字一致，不改写、不缩写 |
| 数值 | JSON 数值（非字符串），座位号与版本号为整数 |
| 时间 | 毫秒整数 |
| 写入粒度 | 每个事件只更新它影响的字段，不做"整体重写 Hash"（避免读放大与丢字段） |

### 5.2 `game:{id}:state`（Hash）

| 字段 | 类型 | 来源 | 说明 |
| --- | --- | --- | --- |
| `gameId` | string | 聚合 | 与键名冗余，单条 Hash 自解释 |
| `version` | int | 聚合 | 已投影的最大事件版本，重复投递时小于等于该值的事件直接跳过 |
| `phase` | string | 聚合 | `GamePhase` 取值，转换表见 [ddd-design.md](../architecture/ddd-design.md) §3.2 |
| `round` | int | 聚合 | 第几轮，夜加昼为一轮 |
| `status` | string | 聚合 | `INIT` / `RUNNING` / `FINISHED` / `ABORTED` |
| `turnIndex` | int | 聚合 | 当前发言序号 |
| `turnOrder` | int[] | 聚合 | 发言顺序座位号，开局由 `GAME_STARTED` 固化 |
| `currentTurn` | object | 聚合 | `{playerId, seatNo, phase, turnIndex, deadline}`，`Turn` 值对象 |
| `resumePhase` | string | 聚合 | 插入阶段（`HUNTER_SHOOT`）结束后的回到点，无插入阶段时为空串。与 `phase` 并存：`phase` 是当前阶段，`resumePhase` 是回到点 |
| `roleComposition` | object | 聚合 | 角色配置，开局固化，用于回放与实验室对比 |
| `alivePlayers` | int[] | 派生 | 由 `game:{id}:players` 的 `alive` 派生，`DESIGN.md` §8 快照示例使用同名概念 |
| `votes` | object | 聚合 | 当前投票轮，座位号到目标的映射。逐座位点查走 `game:{id}:votes` |
| `nightActions` | object | 聚合 | `{wolfNominations, wolfTarget, seerCheck, witchAction}`，当夜已提交的狼刀、查验、用药 |
| `deathQueue` | object[] | 聚合 | `{playerId, seatNo, cause, round, announced}`，`cause` 取值见 `DeathRecord` |
| `updatedAt` | int | 投影元数据 | 本投影最后一次写入的 `occurredAt` |
| `lastEventId` | string | 投影元数据 | 最后应用的事件 `eventId`，排障时与归档逐条对齐 |

不投影的聚合字段：

| 聚合字段 | 原因 |
| --- | --- |
| `players` | 已由 `game:{id}:players` 承载，展开进 state 会让值随人数膨胀，并重复表达同一事实 |
| `uncommittedEvents` | 单次 `handle` 的中间集合，提交后其内容就是事件流本身，投影它等于重复存储 |

`resumePhase` 与 `deathQueue`、`nightActions` 一样属于聚合内部中间状态：它们必须投影，
否则恢复与视图组装会丢信息；但它们只允许服务端读取，不下发给客户端
（[api-spec.md](api-spec.md) §5.2 的 `GameView` 字段表）。

### 5.3 `game:{id}:players`（Hash）

field = `playerId`，value 为 `Player` 实体的投影。

| JSON 字段 | 类型 | 来源 | 说明 |
| --- | --- | --- | --- |
| `playerId` | string | `Player` | 对局内参与者标识 |
| `seatNo` | int | `Player` | 1 到 9 |
| `role` | string | `Player` | `WEREWOLF` / `VILLAGER` / `SEER` / `WITCH` / `HUNTER`，开局事件固化 |
| `camp` | string | `Player` | `WOLF` / `VILLAGE`，由 `role` 派生 |
| `alive` | bool | `Player` | 存活标记 |
| `controller` | object | `Player` | `{type, refId}`，`type` 取 `HUMAN` / `AI` / `BOT` |
| `ready` | bool | `Player` | 对局内就绪标记 |
| `skillState` | object | `Player` | `{antidoteAvailable, poisonAvailable, shotAvailable}` |
| `deathCause` | string | 派生 | 由 `PLAYER_KILLED` 写入，取值 `WOLF_KILL` / `WITCH_POISON` / `HUNTER_SHOT` / `EXILE` |
| `deathRound` | int | 派生 | 由 `PLAYER_KILLED` 写入 |

`role`、`camp`、`skillState` 是隐藏信息。这个键只允许服务端代码读取：视图查询用它组装 `GameView`，过滤逻辑只有一份 `VisibilityFilter`（[event-design.md](../architecture/event-design.md) §4 硬规则 2），客户端永远拿不到这个 Hash 的原始内容。

### 5.4 `game:{id}:votes`（Hash）

field = `seatNo`，value 为 JSON。

| JSON 字段 | 类型 | 说明 |
| --- | --- | --- |
| `playerId` | string | 投票人 |
| `seatNo` | int | 与 field 一致 |
| `targetSeat` | int 或 null | 目标座位；弃权时为 null |
| `abstain` | bool | 是否弃权（INV-10），与 `targetSeat` 互斥表达 |
| `eventId` | string | 产生该票的 `PLAYER_VOTED` 事件 |
| `version` | int | 该事件的版本，用于同轮内覆盖判定 |

规则：

| 规则 | 内容 |
| --- | --- |
| 轮次重置 | 每次 `PHASE_CHANGED` 进入 `DAY_VOTE` 时整键删除后重建，避免跨轮残留 |
| 只服务读取 | 计票由领域在聚合状态上完成（`VoteTallyPolicy`），展示层才读这个键。用它做计票判定属于反模式 AP-R9 |
| 与 state 的关系 | `state.votes` 保存同一轮的完整映射；本键提供按座位的 `HGET` 点查，前端投票区逐个刷新时不必拉取整个 state |

### 5.5 `room:{id}:meta`（Hash）

| 字段 | 类型 | 来源事件 | 说明 |
| --- | --- | --- | --- |
| `roomId` | string | `ROOM_CREATED` | 与键名冗余 |
| `name` | string | `ROOM_CREATED` | 房间名 |
| `ownerId` | string | `ROOM_CREATED` | 房主 `userId` |
| `config` | object | `ROOM_CREATED` | 房间配置，含座位数与角色配置 |
| `status` | string | 派生 | `WAITING` / `RUNNING` / `CLOSED` |
| `gameId` | string | 派生 | 开局后回填，由 `GAME_STARTED` 的 `correlationId` 与房间关联 |
| `createdAt` | int | `ROOM_CREATED` | 创建时间 |
| `closedReason` | string | `ROOM_CLOSED` | 关闭原因，未关闭为空串 |
| `updatedAt` | int | 投影元数据 | 最后写入时间 |

`status` 是本文定义的取值集合，它不在已读文档的枚举里；三个取值与事件一一对应：`WAITING` 来自 `ROOM_CREATED`，`RUNNING` 来自 `GAME_STARTED`，`CLOSED` 来自 `ROOM_CLOSED`。

### 5.6 `room:{id}:seats`（Hash）与 `room:{id}:ready`（Set）

`room:{id}:seats`：field = `seatNo`。

| JSON 字段 | 类型 | 来源事件 | 说明 |
| --- | --- | --- | --- |
| `seatNo` | int | `PLAYER_SEATED` | 与 field 一致 |
| `playerId` | string | `PLAYER_SEATED` | 该座位的玩家 |
| `controllerType` | string | `PLAYER_SEATED` | `HUMAN` / `AI` / `BOT` |
| `ready` | bool | `PLAYER_READY` | 单座位读取用 |
| `joinedAt` | int | `PLAYER_SEATED` | 入座时间 |
| `version` | int | 投影元数据 | 该座位最后应用的房间事件版本 |

`room:{id}:ready`：Set，member = `seatNo`。

| 规则 | 内容 |
| --- | --- |
| 写入 | `PLAYER_READY` 时 `SADD`；座位离开或换人时 `SREM` |
| 判定 | 开局前置条件为 `SCARD room:{id}:ready` 等于座位数（INV-R2，AI 座位入座即视为就绪，由投影在 `PLAYER_SEATED` 时一并 `SADD`） |
| 为什么不用 Hash 字段 | 判定是集合关系，`SCARD` 一次完成；用 Hash 需要 `HGETALL` 加逐条 JSON 解析 |
| 重复字段说明 | `ready` 同时存在于 `seats` 与本键，两者由同一投影在同一批写入，前者服务单座位读取，后者服务整体判定，房间阶段数据量小，重复字段的成本可接受 |

### 5.7 `game:{id}:agent:{playerId}:session`（Hash）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `agentId` | string | Agent 实例标识，`PlayerController.refId` 的取值来源 |
| `playerId` | string | 控制的玩家 |
| `gameId` | string | 对局 |
| `seatNo` | int | 座位号 |
| `role` | string | 该 Agent 的角色，与 `game:{id}:players` 一致 |
| `status` | string | `IDLE` / `RUNNING` / `FAILED`，回合执行状态 |
| `turnIndex` | int | 最近一次处理的回合序号 |
| `lastTurnAt` | int | 最近一次 `AGENT_TURN_STARTED` 的时间 |
| `contextVersion` | int | 最近一次装配上下文时的聚合版本，用于判断记忆是否过期 |
| `model` | string | 实验室配置里的模型标识（[ddd-design.md](../architecture/ddd-design.md) §9） |
| `tokenUsed` | int | 本局累计 token，来源是 `AGENT_LLM_INVOKED` 的 `promptTokens` 加 `completionTokens` |
| `fallbackCount` | int | 本局兜底次数，来源是 `AGENT_TURN_TIMEOUT` 与 `AGENT_TURN_FINISHED.fallbackUsed` |
| `updatedAt` | int | 最后写入时间 |

这个键属于 Agent Context 的私有运行态，不承载领域状态。写入者是 Agent Runtime 自身，这不构成第二条领域写入路径（[event-design.md](../architecture/event-design.md) §1：Agent 决策事件永不改变对局状态）。

### 5.8 `ws:conn:{connId}`（Hash）与 `ws:sub:{gameId}`（Set）

| 键 | 结构 | 说明 |
| --- | --- | --- |
| `ws:conn:{connId}` | Hash | `connId`、`instanceId`、`userId`、`sessionId`、`subscribedGames`（JSON 数组）、`lastSeq`（JSON 对象，gameId 到序号的映射）、`openedAt`、`lastHeartbeatAt`、`pendingFrames` |
| `ws:sub:{gameId}` | Set | 订阅该对局的 `connId` 集合 |

| 规则 | 内容 |
| --- | --- |
| 心跳 | 每 `ws.heartbeat.intervalMs`（15000）刷新两个键的 TTL 为 `ws.idle.timeoutMs`（45000） |
| 失效清理 | cg-broadcast 推送前用 `EXISTS ws:conn:{connId}` 判断连接是否还在，失效成员 `SREM` 掉，避免向死连接推送 |
| 位点语义 | `lastSeq` 是连接级推送序号（[system-design.md](../architecture/system-design.md) §3.5），不是消费组位点，两者不能互相替代 |
| 多实例 | 两个键外置后，任意实例都能把事件推给持有连接的实例（[system-design.md](../architecture/system-design.md) §10） |

### 5.9 `user:{id}:session:{sessionId}`（Hash）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | string | 账号标识，与键名冗余 |
| `sessionId` | string | 会话标识，与键名冗余 |
| `secretHash` | string | 令牌密钥的散列，明文不落库、不进日志、不进事件 |
| `issuedAt` | int | 签发时间 |
| `lastSeenAt` | int | 最近一次请求或心跳时间 |
| `clientHint` | string | 客户端标识摘要，仅用于排障展示 |

令牌形态与校验：

| 项 | 设计 |
| --- | --- |
| 令牌形态 | `{userId}.{sessionId}.{secret}` 的不透明串，不含签名结构，不使用自包含签名令牌 |
| 校验 | 拆分后 `HGET user:{id}:session:{sessionId} secretHash`，与 `secret` 的散列比对，再比对 `userId` |
| 传递 | REST 走请求头，WebSocket 走 `AUTH` 帧，不在 URL 中携带（[system-design.md](../architecture/system-design.md) §11） |
| 撤销 | 登出时 `DEL` 该键并追加 `USER_LOGGED_OUT` 到 `user:{id}:events` |

### 5.10 `idem:{gameId}:{idempotencyKey}`（Hash）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `eventId` | string | 首次处理产生的首个事件 `eventId` |
| `version` | int | 首次处理后的聚合版本 |
| `resultHash` | string | 返回体摘要，重复提交时比对，不一致说明客户端参数变了，返回 `IDEMPOTENCY_MISMATCH` |
| `createdAt` | int | 首次处理时间 |

TTL 取 `idempotency.ttlMinutes`（10 分钟，[system-design.md](../architecture/system-design.md) §9）。这个键必须在加载聚合之前可读，因此不能由事件流派生。

### 5.11 `game:{id}:lastTs`（String）与 `game:{id}:lock`（String）

| 键 | 值 | 说明 |
| --- | --- | --- |
| `game:{id}:lastTs` | 毫秒时间戳十进制串 | 事件写入脚本中 `SET`，下一次写入前读取，保证 `occurredAt` 在单局内单调不减 |
| `game:{id}:lock` | `{instanceId}:{threadId}:{ownerToken}` | 锁值必须唯一标识持有者，续租与释放都按值比对 |

## 6. 消费组与位点

### 6.1 四个消费组

| 消费组 | 消费者类 | 职责 | 起始位点 | 分片键 | 位点语义 |
| --- | --- | --- | --- | --- | --- |
| `cg-projection` | `StateProjectionConsumer` | 更新 `game:{id}:state`、`game:{id}:players`、`game:{id}:votes`，归档到 SQLite `game_events` | `0-0` | `gameId` | 归档事务提交成功后才 `XACK` |
| `cg-broadcast` | `WebSocketBroadcaster` | 按 `VisibilityFilter` 过滤后推送 | `$` | `gameId` | 入队成功后立即 `XACK`，推送失败不回退位点 |
| `cg-agent` | `EventDrivenTurnScheduler` / 记忆更新器 | 规划行动、超时、取消任务；记忆可重建 | `0-0` | `gameId` | 任务可靠登记后 `XACK`；失败保留 PEL；记忆失败不得丢行动触发 |
| `cg-insight` | `InsightProjector` | 写 SQLite `games`、`statistics`、回放索引 | `0-0` | `gameId` | 归档提交成功后才 `XACK`，可从事件重放重建 |

`cg-broadcast` 从 `$` 开始的理由：实时推送只关心连接建立之后的增量，历史补发由订阅方按 `lastSeq` 用 `XRANGE` 完成（[system-design.md](../architecture/system-design.md) §3.5）。其余三组必须从 `0-0` 开始，否则开局事件会永久漏归档。

位点不额外建键。位点保存在 Stream 的消费组内部结构里，用 `XINFO GROUPS` 读取，避免位点与组状态分叉。

### 6.2 启动参数

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `COUNT` | 200 | 与 [system-design.md](../architecture/system-design.md) §5 的单批上限 200 一致 |
| `BLOCK` | 2000 | 毫秒，无消息时的阻塞时长，避免忙轮询 |
| 消费者名 | `{instanceId}-{shardIndex}` | 同一实例重启后名字复用，便于用 `XPENDING` 观察 PEL |
| 排空阈值 | PEL 空闲超过 60000 ms | 触发 `XAUTOCLAIM` 转移 |
| DLQ 阈值 | 失败 3 次 | 与 [event-design.md](../architecture/event-design.md) §9 一致 |

流不存在时由开局流程创建，命令如下：

```
XGROUP CREATE game:{id}:events cg-projection 0-0 MKSTREAM
XGROUP CREATE game:{id}:events cg-agent      0-0
XGROUP CREATE game:{id}:events cg-insight    0-0
XGROUP CREATE game:{id}:events cg-broadcast  $ MKSTREAM
```

消费组名与流键的组合一经发布不得更改，更改等同于全量重放（[event-design.md](../architecture/event-design.md) §9）。

### 6.3 分片规则

| 规则 | 内容 |
| --- | --- |
| 分片单位 | `gameId`。同一对局只在一个消费者实例上处理，组内并行度按对局数扩展 |
| 实例归属 | `shardIndex = (CRC32(gameId) 与 0x7fffffff) % shardCount`，`shardCount` 为实例数 |
| 流归属 | 每个对局有独立流键，实例只对自己负责的对局流执行 `XREADGROUP` |
| 扩缩容 | 归属变化不破坏正确性：`XAUTOCLAIM` 会把滞留条目交给新实例，重复处理由 §6.4 的幂等保证 |
| 禁止 | 同一对局在同一时刻被两个实例消费。破坏这条会让投影版本前置校验失效 |

### 6.4 ACK 语义与失败处理

| 组 | ACK 时机 | 失败处理 | 幂等依据 |
| --- | --- | --- | --- |
| `cg-projection` | SQLite 归档事务提交成功后 | 不 `XACK`，重试 3 次，之后进 DLQ 并 `XACK` | `ux_game_events_event_id`，重复 `INSERT` 被拒绝 |
| `cg-broadcast` | 帧入队后 | 不回退位点，只记连接状态与告警 | 连接级 `seq` 与客户端 `lastSeq` |
| `cg-agent` | 调度任务可靠登记或窗口核实失效后 | 保留 PEL；坏事件 DLQ/blocked，维护任务独立运行 | 稳定 taskId、租约、窗口核验与命令幂等；记忆可重建 |
| `cg-insight` | SQLite 提交成功后 | 不回退位点，可从事件重放重建 | 表上的唯一索引（见 [database-schema.md](database-schema.md) §3） |

进 DLQ 之后必须 `XACK` 原条目。否则该条目永久留在 PEL 中，组位点不再推进，滞后会持续增长。

### 6.5 DLQ 结构

键 `game:{id}:events:dlq`，Stream，条目 ID 用 `*`（DLQ 不承载版本语义）。

| 字段 | 说明 |
| --- | --- |
| 13 个信封字段（含 `payload`） | 原事件逐字段搬运，便于重放 |
| `entryId` | 原条目 ID，形如 `123-0` |
| `groupId` | `cg-projection` / `cg-broadcast` / `cg-agent` / `cg-insight` |
| `consumerId` | 上报失败的消费者名 |
| `failureCount` | 累计失败次数，进 DLQ 时固定为 3 |
| `lastError` | 最后一次错误的结构化摘要，不含 `PRIVATE` 事件的敏感内容 |
| `firstFailedAt` | 首次失败时间 |
| `enteredAt` | 进入 DLQ 时间 |

| 规则 | 内容 |
| --- | --- |
| 保留 | 7 天（[event-design.md](../architecture/event-design.md) §12） |
| 重放 | 走运维接口，`JUDGE` 可见；只重放投影，除 `GAME_RECOVERED` 外不产生游戏事件 |
| 禁止 | 直接编辑 DLQ 条目后搬运回事件流。原条目已经在流里，重复写入会被显式 ID 拒绝 |

### 6.6 Agent 决策流的消费

`game:{id}:agent:{playerId}` 使用与领域事件相同的信封与显式 ID 规则，但 `version` 是该 `(gameId, playerId)` 决策事件序列内的独立序号，从 1 开始，不参与聚合版本。`visibility` 统一写 `JUDGE`：这些痕迹不进入对局推送，由赛后分析读取。

| 项 | 设计 |
| --- | --- |
| 写入者 | Agent Runtime 单写者（一个 Agent 一个实例），无需锁 |
| 消费组 | 只创建 `cg-insight`，归档到 SQLite `agent_memory`（`memory_type='DECISION_TRACE'`） |
| 组名复用 | Stream 的消费组按流键独立，同一组名在不同流上互不影响位点；复用已有组名避免新增契约 |
| 保留 | 对局结束后 7 天，随 `game:{id}:` 前缀键统一过期 |

### 6.7 房间流的消费

| 消费组 | 职责 | 起始位点 |
| --- | --- | --- |
| `cg-projection` | 写 `room:{id}:meta`、`room:{id}:seats`、`room:{id}:ready` | `0-0` |
| `cg-broadcast` | 房间等待页推送（座位、准备状态） | `$` |

`cg-agent` 与 `cg-insight` 不在房间流上创建：房间不承载 AI 决策与统计语义。房间写入不需要独立锁键，复用同一条仲裁规则：聚合版本作 `expectedVersion`，条目 ID 用 `{version}-0`，Redis 拒绝小 ID 即冲突。

## 7. 快照键

### 7.1 内容字段

`game:{id}:snapshot`（String，单行 JSON），字段与 [event-design.md](../architecture/event-design.md) §8 一致。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `gameId` | string | 对局 |
| `version` | int | 快照覆盖到的聚合版本 |
| `phase` | string | 快照时刻的阶段 |
| `round` | int | 快照时刻的轮次 |
| `status` | string | `INIT` / `RUNNING` / `FINISHED` / `ABORTED` |
| `resumePhase` | string | 插入阶段的回到点，无插入时为空串 |
| `players` | object[] | `{playerId, seatNo, role, camp, alive, controller, skillState}` |
| `turnOrder` | int[] | 发言顺序 |
| `turnIndex` | int | 当前发言序号 |
| `currentTurn` | object | 当前回合窗口 |
| `nightActions` | object | 当夜已提交行动 |
| `votes` | object | 当前投票轮 |
| `deathQueue` | object[] | 待公布与待结算的死亡 |
| `wolfChatCounts` | object[] | `{wolfSeatNo, count}`，本夜每名狼人已发送的夜聊条数；进入 `NIGHT_WOLF` 时清空。旧快照缺省按空数组解码 |
| `stateVersion` | int | 最后一条非 `WOLF_CHAT_SENT` 事件的版本，用于 `expectedVersion` 放宽判定（api-spec.md §5.3）。旧快照缺省取 `version`，即不放宽 |
| `checksum` | string | 状态规范化 JSON 的 CRC32 十六进制串 |

`checksum` 用 Java 标准库 `java.util.zip.CRC32` 计算：把上述字段按字典序键排序、去空白后拼成规范 JSON，对字节计算。它只用于检测损坏，不用于安全用途。

### 7.2 生成时机与写入顺序

| 项 | 设计 |
| --- | --- |
| 触发 | `version % 50 == 0`（`game.snapshot.interval`），或 `GAME_FINISHED` / `GAME_ABORTED` / `GAME_RECOVERED` 时强制 |
| 位置 | 写入路径内联，持有 `game:{id}:lock` 时生成，避免出现半个状态（[system-design.md](../architecture/system-design.md) §4） |
| 顺序 | 先写 `game:{id}:snapshot:{version}`，再 `ZADD game:{id}:snapshots {version} game:{id}:snapshot:{version}`，最后 `SET game:{id}:snapshot` |
| 失败 | 不重试、不回滚事件。下一次触发点补做，事件流仍是唯一真相（INV-15） |

### 7.3 读取与校验

```
RecoverGameApplicationService
  1. GET  game:{id}:snapshot                      最新快照，可能缺失
  2. 缺失：XRANGE game:{id}:events - +            全量重放
     存在：校验 checksum，通过后 XRANGE game:{id}:events ({version}-0 +
  3. Game.replay(event) 逐条应用
  4. 校验 version 连续、checksum 一致
     不一致：从 SQLite game_events 重建，告警
  5. 产生 GAME_RECOVERED（visibility=JUDGE）
  6. 释放锁
```

checksum 失败必须退化为全量重建，不允许"修一下快照继续用"。快照是缓存而不是真相（INV-15）。

### 7.4 保留与归档

| 步 | 动作 |
| --- | --- |
| 1 | 生成新快照后 `ZREMRANGEBYRANK game:{id}:snapshots 0 -4` 取出需要淘汰的成员 |
| 2 | cg-projection 在归档分支把该快照全文写入 SQLite `game_history`（`kind='SNAPSHOT'`） |
| 3 | 只有第 2 步提交成功后，才 `DEL` 对应的 `game:{id}:snapshot:{version}`，保证"归档先于删除" |
| 4 | Redis 侧始终保留最近 3 个快照；SQLite 侧永久保留 |

## 8. 锁与并发

| 项 | 设计 |
| --- | --- |
| 键与类型 | `game:{id}:lock`，String |
| 获取 | `SET game:{id}:lock {owner} NX PX 3000`，TTL 取 `game.lock.ttlMs` |
| 锁值 | `{instanceId}:{threadId}:{ownerToken}`，必须唯一标识持有者 |
| 续租 | 归档批写或快照生成超过 TTL 时续租：`GET` 比对值相等则 `PEXPIRE`，上限 3 次 |
| 释放 | compare-and-del，值不匹配时不删（避免删掉接管者的锁） |
| 获取失败 | 返回 `GAME_BUSY`，客户端指数退避重试 |
| 持有者崩溃 | TTL 到期自动释放，新写者接管 |

锁与版本号的配合：

| 场景 | 手段 | 结果 |
| --- | --- | --- |
| 两个命令同时到达 | 锁串行化加载与 `handle` | 后到者拿到 `GAME_BUSY`，不执行领域逻辑 |
| 锁过期后双写 | 显式条目 ID | 第二个脚本读到新尾部，返回 `GAME_VERSION_CONFLICT`，不产生双写 |
| 快照与写入并发 | 快照只在持锁时生成 | 不会出现半状态 |
| 单次命令过长 | 命令路径禁止调用 LLM（[system-design.md](../architecture/system-design.md) §4） | 锁内耗时保持在 3 秒以内，AI 推理发生在锁外 |

正确性由"显式 ID 加脚本内版本前置校验"保证，锁的作用是减少无效加载与快照竞争。去掉锁仍不会产生版本空洞，但会产生大量冲突重试。

```lua
-- 释放锁（KEYS[1] = game:{id}:lock，ARGV[1] = owner）
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0

-- 续租（ARGV[2] = 新的 TTL 毫秒数）
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 0
```

## 9. TTL 与内存管理

### 9.1 逐键 TTL

| 键 | TTL | 依据与续期 |
| --- | --- | --- |
| `game:{id}:events` | 未归档不自动过期；终局且 SQLite 归档核对通过后最多 7 天 | 每次追加在脚本内 `PERSIST`，并取消旧快照 TTL；7 天只在归档完成后设置。启动时核对旧流完成水位，未归档或水位不符的流和快照取消原 TTL（实现见 §14.3） |
| `game:{id}:lastTs` | 同 `game:{id}:events` | 同一脚本内 `EXPIRE` |
| `game:{id}:state`、`game:{id}:players`、`game:{id}:votes` | 同 `game:{id}:events` | 投影随事件流一起过期，避免留下无源的投影 |
| `game:{id}:snapshot`、`game:{id}:snapshots`、`game:{id}:snapshot:{version}` | 同 `game:{id}:events` | 快照已归档到 `game_history`，Redis 侧可随时丢弃 |
| `game:{id}:agent:{playerId}`、`game:{id}:agent:{playerId}:session` | 同 `game:{id}:events` | 决策事件归档到 `agent_memory` 后即可清理 |
| `game:{id}:events:dlq` | 7 天固定，不续期 | [event-design.md](../architecture/event-design.md) §12 |
| `game:{id}:lock` | 3000 ms，最多续租 3 次 | `game.lock.ttlMs` |
| `idem:{gameId}:{idempotencyKey}` | 10 分钟 | `idempotency.ttlMinutes` |
| `room:{id}:events`、`room:{id}:meta`、`room:{id}:seats`、`room:{id}:ready` | `ROOM_CLOSED` 后 7 天；未关闭房间按最后一次事件起 24 小时 | 7 天对齐对局保留；24 小时为设计建议值，用于清理废弃房间 |
| `user:{id}:events` | 365 天 | [event-design.md](../architecture/event-design.md) §12 未规定账号事件流，取设计建议值 |
| `user:{id}:session:{sessionId}` | 12 小时 | 设计建议值，登录与心跳时刷新，登出时删除 |
| `ws:sub:{gameId}`、`ws:conn:{connId}` | 45 秒，心跳续期 | `ws.idle.timeoutMs` |

TTL 只能通过 `EXPIRE` 设置，禁止用定时扫描逐键删除对局键。清理任务只负责"房间里长时间没有事件的废弃房间"这一类没有自然过期边界的场景。

### 9.2 单局内存估算（设计估算）

以下全部是设计估算，用于设定内存水位与告警阈值，不是实测数据。单条事件约 500 字节来自 [system-design.md](../architecture/system-design.md) §7。

| 组成 | 算式 | 结果 |
| --- | --- | --- |
| 领域事件流字节 | 204 条乘约 500 字节 | 约 100 KB（与 [system-design.md](../architecture/system-design.md) §7 一致） |
| Stream 条目结构开销 | 204 乘约 60 字节（条目 ID、字段计数、Radix 树节点摊销） | 约 12 KB |
| 消费组 PEL | 4 组乘在途条目数乘约 80 字节；正常态在途接近 0，异常态全部 204 条滞留 | 约 0 到 65 KB |
| 快照 | 3 个乘约 4 KB（9 名玩家加投票与夜间行动） | 约 12 KB |
| 状态投影 | 约 33 个字段乘约 120 字节 | 约 4 KB |
| Agent 会话 Hash | 9 乘约 200 字节 | 约 2 KB |
| Agent 决策事件流 | 204 乘 3 到 5 倍乘约 400 字节（[system-design.md](../architecture/system-design.md) §7 的数量关系） | 约 245 到 408 KB |
| 幂等键 | 按 TTL 10 分钟内最多 204 个乘约 60 字节 | 约 12 KB |
| 锁与 `lastTs` | 固定小对象 | 小于 1 KB |
| 单局合计（不含 Agent 决策事件） | 100 加 12 加 65 加 12 加 4 加 2 加 12 加 1 | 约 207 KB |
| 单局合计（含 Agent 决策事件） | 207 加 400 | 约 615 KB |

按 [system-design.md](../architecture/system-design.md) §7 的目标推算：

```
并发对局 200 局每实例（不含 Agent 决策事件）： 200 乘 207 KB 约 41 MB
并发对局 200 局每实例（含 Agent 决策事件）：   200 乘 615 KB 约 120 MB
保留窗口（每日 1000 局，结束后保留 7 天）：     1000 乘 7 乘 615 KB 约 4.1 GB
积压示例（每日 1000 局，30 天均未归档）：      1000 乘 30 乘 615 KB 约 18.5 GB；继续积压仍增长
```

结论与界限：正常完成归档的稳态由 7 天保留期主导，约 4.1 GB；未结束或归档失败的积压没有 TTL 容量上限。
优先修复归档积压，并监控内存；不得靠过期删除未归档事件降低用量。归档后的保留期调整需同步实现与契约，当前为代码常量 7 天。

### 9.3 内存水位

| 检查 | 命令 | 处理 |
| --- | --- | --- |
| 已用内存 | `INFO memory` 的 `used_memory` | 超过 `maxmemory` 的 70% 告警（阈值按 §9.2 的稳态值加并发余量设定） |
| 键数量 | `DBSIZE` | 与"进行中对局数乘单局键数加保留窗口对局数乘单局键数"比对 |
| 对局保留期 | 抽样 `TTL game:{id}:events` | 未归档返回 `-1` 正常；完整归档后应为 0..604800。归档积压用 `wolfmind.archive.pending.games` / `failed.games` 指标检查 |
| 单局键内存 | `MEMORY USAGE game:{id}:events` | 与 §9.2 的算式比对，偏差超过 50% 时排查字段膨胀 |

## 10. 键生命周期

```
T0 创建房间
   ROOM_CREATED          -> XADD room:{id}:events 1-0
                            XGROUP CREATE room:{id}:events cg-projection 0-0 MKSTREAM
                            cg-projection -> HSET room:{id}:meta
T1 入座与就绪
   PLAYER_SEATED         -> HSET room:{id}:seats {seatNo}
                            AI 座位自动 SADD room:{id}:ready
   PLAYER_READY          -> SADD room:{id}:ready
   PLAYER_LEFT           -> HDEL room:{id}:seats 加 SREM room:{id}:ready
T2 开局（应用层，Room 调用 Game）
   GAME_STARTED          -> XADD game:{id}:events 1-0
   ROLE_ASSIGNED 乘 9    -> XADD 2-0 .. 10-0
   PHASE_CHANGED         -> XADD 11-0
   SET game:{id}:lock / SET game:{id}:lastTs
   XGROUP CREATE game:{id}:events 四个组
   cg-broadcast 发 GAME_SNAPSHOT_SYNC，随后逐条推送
   cg-projection -> HSET game:{id}:state / HSET game:{id}:players 乘 9
   cg-insight    -> INSERT games（status=RUNNING）
T3 进行中（每次命令）
   锁 -> 加载（快照 加 增量） -> handle -> Lua 追加 -> PERSIST 保护未归档数据 -> 释放锁
   cg-projection -> 投影批写（MULTI/EXEC）加 SQLite 归档（先归档后 XACK）
   每 50 条 -> 快照写入与旧快照归档
T4 结束
   GAME_FINISHED / GAME_ABORTED -> SQLite 完整归档并核对 -> 事件流/快照 EXPIRE 改为最多 604800 秒
   cg-insight  -> UPDATE games（status/winner/rounds）加 UPSERT statistics
   cg-agent    -> 写长期记忆（agent_memory，scope=CROSS_GAME）
   cg-insight  -> 归档 DECISION_TRACE 到 agent_memory
   cg-projection -> INSERT game_history（SUMMARY）
T5 过期
   TTL 到期，game:{id}: 与 room:{id}: 前缀键整体消失
   SQLite game_events / games / game_history / agent_memory / statistics 永久保留
   此后回放、AI 分析、统计只读 SQLite，Redis 不参与
```

生命周期中两个不可颠倒的顺序：

| 顺序 | 内容 |
| --- | --- |
| 事件先于投影 | 任何投影键的写入都必须发生在其源事件写入成功之后。Redis 无法跨键保证原子性，顺序错了就会产生无源投影 |
| 归档先于过期 | cg-projection 与 cg-insight 在 SQLite 提交成功后才 `XACK`，因此 Redis 键过期时归档必然已完成 |

## 11. 反模式清单

| 编号 | 反模式 | 后果 |
| --- | --- | --- |
| AP-R1 | 直接 `HSET game:{id}:state phase ...` 改状态而不产生事件 | 状态与事件流分叉，恢复后状态被覆盖，写进去的值消失 |
| AP-R2 | 把 `game:{id}:state` 当真相做规则判断（例如读它判断该谁发言） | 读到滞后投影，规则被绕过（INV-13） |
| AP-R3 | 用 `HSET game:{id}:players` 直接改角色或存活状态 | 角色只能由 `ROLE_ASSIGNED` 固化，存活只能由 `PLAYER_KILLED` 改变 |
| AP-R4 | `XADD game:{id}:events *` 让 Redis 生成 ID | 条目 ID 与聚合版本脱钩，版本仲裁失效，重放区间不可表达 |
| AP-R5 | 用 `MULTI` / `EXEC` 做多事件追加 | Redis 事务无回滚，中途失败留下版本空洞（§4.5） |
| AP-R6 | 用 `XDEL` 或 `XTRIM` 清理进行中的事件流 | 破坏 INV-14 与恢复能力，清理只能靠键过期 |
| AP-R7 | 把 `AGENT_` 前缀事件写进 `game:{id}:events` | 污染领域事件流，可见性与版本号都被破坏 |
| AP-R8 | 用 `game:{id}:votes` 做计票判断 | 投影不完整时计票错误，计票属于 `VoteTallyPolicy` |
| AP-R9 | 用 `ws:sub:{gameId}` 代替消费组位点，或反之 | 订阅是连接级状态、位点是处理进度，混用会导致漏推或重复归档 |
| AP-R10 | 进 DLQ 后不 `XACK` 原条目 | PEL 永久堆积，组位点不再推进，滞后持续增长 |
| AP-R11 | 对局结束时 `DEL game:{id}:events` 而不检查归档 | 归档未完成就删流，事件永久丢失，`DESIGN.md` §13 的"可恢复"不再成立 |
| AP-R12 | 不用 `game:{id}:lock` 直接加载聚合并写入 | 并发命令产生大量版本冲突；快照生成与写入交错，可能写出半个状态 |
| AP-R13 | 给未归档对局的事件流或快照设自动过期 | 活跃或归档失败的对局可能被中途清空 |
| AP-R14 | 在推送层另写一份可见性判断 | 隐藏信息泄漏（[event-design.md](../architecture/event-design.md) §4 硬规则 2） |

## 12. 运维检查项

| 编号 | 检查 | 命令 | 期望与阈值 | 异常处理 |
| --- | --- | --- | --- | --- |
| OP-R1 | 内存水位 | `INFO memory` | `used_memory` 低于 `maxmemory` 的 70% | 按 §9.2 的顺序收紧保留策略 |
| OP-R2 | 单局事件流长度 | `XLEN game:{id}:events` | 与 SQLite `SELECT COUNT(*) FROM game_events WHERE game_id=?` 一致 | 不一致时按 [database-schema.md](database-schema.md) §10 对账重建 |
| OP-R3 | 版本连续性 | `XRANGE game:{id}:events - + COUNT 1` 加 `XREVRANGE ... COUNT 1` 与 `XLEN` 比对 | 首条 `1-0`，末条版本等于 `XLEN` | 出现空洞立即停止该对局写入并告警 |
| OP-R4 | PEL 积压 | `XPENDING game:{id}:events cg-projection` | 待处理数接近 0；空闲超过 60000 ms 的条目为 0 | `XAUTOCLAIM` 转移；持续失败查消费者日志 |
| OP-R5 | 消费组滞后 | `XINFO GROUPS game:{id}:events` 的 `lag` | 各组的 `lag` 小于 200，且不持续增长 | 滞后超过阈值时暂停该对局新写请求（[system-design.md](../architecture/system-design.md) §6） |
| OP-R6 | DLQ | `XLEN game:{id}:events:dlq` | 为 0 | 大于 0 时读取条目定位失败原因，走运维接口重放 |
| OP-R7 | 锁残留 | `EXISTS game:{id}:lock` 与 `TTL game:{id}:lock` | 空闲对局应为 0；存在时 `TTL` 应在 0 到 3000 ms 之间 | 长时间存在说明持有者未释放或续租失控，等 TTL 自然到期后排查代码路径 |
| OP-R8 | 保留期异常 | `TTL game:{id}:events` | 未归档 `-1`，完整归档后 0..604800 | 未归档出现正 TTL 按缺陷处理；已归档无 TTL 检查保留期更新是否失败 |
| OP-R9 | 快照可用性 | `GET game:{id}:snapshot` 后校验 `checksum` | 校验通过 | 失败则从 SQLite 重建并告警（§7.3） |
| OP-R10 | 快照数量 | `ZCARD game:{id}:snapshots` | 小于等于 3 | 大于 3 说明归档清理路径未执行 |
| OP-R11 | Agent 决策流放大 | `XLEN game:{id}:agent:{playerId}` 与 `XLEN game:{id}:events` 比值 | 3 到 5 之间 | 超出说明出现了重复写或重试放大，排查 Agent Runtime 的重试策略 |
| OP-R12 | 订阅残留 | `SCARD ws:sub:{gameId}` 与在线连接数比对 | 差值小于在线数的 5% | 差值过大说明 `ws:conn` 心跳续期或失效清理异常 |
| OP-R13 | 幂等键泄漏 | `DBSIZE` 中 `idem:` 前缀数量 | 接近 0（TTL 10 分钟） | 持续偏高说明 TTL 未写入 |
| OP-R14 | 无主投影 | 存在 `game:{id}:state` 而 `EXISTS game:{id}:events` 为 0 | 为 0 | 存在说明投影生命周期与事件流不一致 |

## 13. 与 DESIGN.md 的对应自检

| DESIGN.md 要求 | 本文落点 | 是否新增技术选型 |
| --- | --- | --- |
| §7 Redis Stream 作为第一阶段 Event Store | §4 事件流结构、§4.4 追加脚本 | 否 |
| §7 事件结构 `{eventId, gameId, version, type, payload}` | §4.1 字段表（四个必填字段原样保留） | 否 |
| §8 每 50 个事件生成一次快照 | §7 快照键与生成时机 | 否 |
| §8 恢复等于快照加重放 | §7.3 读取与校验 | 否 |
| §13 Redis 保存运行中的游戏，5 个键 | §3.1 映射表，5 个键全部原样保留 | 否 |
| §13 SQLite 保存长期数据 | §10 生命周期 T5，归档留在 SQLite | 否 |
| §6 状态变化产生事件后再到消费者 | §1 写入路径、§4.3 协议 | 否 |
| §15 Phase 2 游戏可恢复 | §7 快照、§6.4 归档先于 ACK | 否 |

事件类型、`visibility` 取值、消费组名、错误码全部引用
[event-design.md](../architecture/event-design.md)，本文不新增事件类型，也不改名。

## 14. 第一版实现状态与运维假设（2026-10-12）

### 14.1 启用方式

`wolfmind.storage=redis` 时 `RedisStorageConfiguration` 接管存储端口；缺省（`memory`）仍用进程内实现，
自动化测试默认不依赖 Redis。连接参数沿用 Spring Boot 的 `spring.data.redis.host/port/database`。
Redis 模式下账号凭据落 SQLite（`wolfmind.sqlite.path`，缺省 `data/wolfmind.db`，见
[database-schema.md](database-schema.md)），会话与事件流仍在 Redis。

```text
java ... -Dwolfmind.storage=redis -Dspring.data.redis.host=127.0.0.1 -Dspring.data.redis.port=6379 \
     -Dwolfmind.sqlite.path=data/wolfmind.db com.wolfmind.WolfMindApplication
```

### 14.2 已落地的键

| 键 | 实现 | 说明 |
| --- | --- | --- |
| `game:{id}:events`、`room:{id}:events`、`user:{id}:events` | `RedisEventStore` | 条目 ID `{version}-0`；Lua 原子校验版本并追加；对局流追加时取消过期，归档完成后 `shortenAfterArchive` 设最多 7 天；开放房间 24 小时、关闭房间 7 天、账号 365 天 |
| `game:{id}:snapshot`、`game:{id}:snapshots`、`game:{id}:snapshot:{version}` | `RedisSnapshotStore` | 保留最近 3 个版本，TTL 与事件流对齐 |
| `game:{id}:lock`（房间锁同形） | `RedisLock` | `SET NX PX 3000`，compare-and-del 释放；获取失败即 `GAME_BUSY` |
| `idem:{gameId}:{key}` | `RedisIdempotencyPort` | TTL 10 分钟 |
| `user:{id}:session:{sessionId}` | `RedisSessionStore` | 只存 secret 的 SHA-256，过期交给键 TTL |
| `wolfmind:lab:config:{id}`、`wolfmind:lab:configs` | `RedisLabStore` | 实验室 Agent 配置（String + 集合索引）；**不设 TTL**，见 §15 |
| `wolfmind:lab:batch:{id}`、`wolfmind:lab:batches` | `RedisLabStore` | 实验室批次记录（String + 集合索引）；**不设 TTL**，见 §15 |

房间索引与对局枚举不另建键，直接 `SCAN room:*:events` / `game:*:events`，因此索引不会与事件流分叉。

### 14.3 与本文设计的差异（有意保留，勿当作最终形态）

| 项 | 现状 | 影响与后续 |
| --- | --- | --- |
| 消费组（§6） | cg-agent/projection/broadcast/insight 均有独立 Redis 组与目录；Redis 模式广播走消费者 | 内存模式保留直接投递；详见 event-consumers.md |
| `game:{id}:state` 等投影 Hash（§5.2–§5.4） | state Hash 存单调 version 和完整 state JSON；players/votes 独立 Hash 未接入 | API 仍经聚合/领域投影保证可见性，查询投影不参与规则判断 |
| SQLite 归档（§10 T5） | cg-projection 正常消费；TerminalGameArchiver 每 30 秒检查终局补漏；二者复用 GameArchivalService | 事件 ID/版本/内容与重放核验后标记完成，再设置事件流、快照和状态投影最多 7 天 TTL |
| 快照淘汰（§7.4） | 超出 3 个的旧快照直接删除 | 事件流仍完整，恢复不受影响 |
| 幂等记录 | 在追加脚本之外单独写入 | 与内存实现相同的极窄崩溃窗口，由领域 V6 兜底（见 `TASKS.md` Phase 2 已知限制） |
| `game:{id}:lastTs` | 未写入 | 时间单调性由应用层时钟保证 |
| Agent 短期记忆 | 进程内 | 重启后 `RunningGameAgentRecovery` 为运行中对局重建 Agent，但此前的记忆不恢复 |
| 对局枚举 | 列表接口仍可 `SCAN`；事件驱动正常路径使用持久化流目录，启动一次迁移旧目录 | 普通回合不枚举 RUNNING 对局；显式 POLLING 回退才扫描 |

### 14.4 持久化与数据丢失假设

归档恢复（2026-10-14）：Redis 模式仓储加载/指定版本加载在流缺失时，经
`RestoreArchivedGameApplicationService` 验证 SQLite 完成标记、事件 ID/版本连续性及重放终态。
`RedisEventStore.restoreAbsent` 在临时流构建完整历史，再通过 Lua 检查目标不存在并原子安装，
保留原版本/时间戳/载荷/可见性，设 7 天 TTL，清除旧孤立快照，不发布历史事件或唤醒 AI。
构建期临时流前缀为 `wolfmind:archive-restore:`，10 分钟 TTL，正常返回/异常由 finally 清理；
已有正式流不覆盖，没有完整终局归档不恢复。运行中未归档对局不能靠 SQLite 此路径补救。
启动时还通过 `reconcileRetention` 核对旧对局流，未归档或水位不符者取消旧 TTL。
只读历史回放仍可直接使用 SQLite，当前状态与指定版本视图则复用仓储恢复路径。

验收包括 FINISHED/ABORTED 状态等价、逐事件字段等价、旧 TTL 迁移、损坏/不完整归档拒绝、
并发仅一次安装、安装中断后无半份正式流且可重试、真实 Spring Redis 装配经 HTTP 自动恢复。

当前联调使用的容器为 `redis:8.10.1`，2026-10-12 按所有者决定重建：
`redis-server --appendonly yes --appendfsync everysec`，数据目录挂命名卷 `wolfmind-redis-data:/data`
（实测 `CONFIG GET appendonly` 为 `yes`）。因此：

- 后端进程重启：对局、房间、账号、会话从 Redis 与 SQLite 完整恢复（`RedisRestartAcceptanceTest`
  与浏览器中途重启验收均已验证）。
- Redis 进程崩溃：AOF 每秒刷盘，最多丢失约 1 秒的写入；丢失的是事件流尾部，恢复出的对局停在较早版本，
  已发出的 WebSocket 帧可能领先于存储。
- 删除并重建容器：数据在命名卷里，挂回同一个卷即可恢复；删除卷才会丢失全部 Redis 数据。
  已归档的对局仍可从 SQLite `game_events` 读回（§10.3）。

---

## 15. 实验室键空间（Phase 5 追加，2026-10-12）

| 键 | 类型 | 内容 | TTL |
| --- | --- | --- | --- |
| `wolfmind:lab:config:{agentConfigId}` | String | `LabAgentConfig` 的单行 JSON | 无 |
| `wolfmind:lab:configs` | Set | 全部配置 id | 无 |
| `wolfmind:lab:batch:{batchId}` | String | `LabBatch` 的单行 JSON（含逐局结果与角色绑定） | 无 |
| `wolfmind:lab:batches` | Set | 全部批次 id | 无 |

**为什么不设 TTL。** 与进行中对局的键不同，实验配置是**账号资产**：过期删除会让
"我上周存的配置不见了"变成一个无法解释的现象。批次记录同理——它是"这份配置跑过多少局"的唯一来源，
而胜率的分母就来自它。

**为什么集合里可能留下已删除的 id。** 写入是 `SET` + `SADD` 两步，没有事务；
读取时若值不存在就把该 id 从集合里清掉（自愈）。这与事件流的做法一致：
索引最终一致，真相在值里。

**为什么按账号过滤在应用层而不是键名里。** 键名不含 ownerId，因为"改主人"是一个会发生的操作
（配置转移），而键名一旦含 ownerId 就得先删后建——那会出现"删了新的、建的时候失败、
配置消失"的窗口。过滤发生在 `LabQueryService`，读取路径只有一个。

**规模限制。** 每个账号的配置数没有硬上限（界面限制 30 份）；批次记录随实验次数线性增长，
且 `GET /lab/comparisons` 会扫描该账号的全部批次（最多 200 个）。超过这个规模需要
把批次摘要落 SQLite（`games.lab_batch_id` 已经能把对局关联回批次），
那时比较就不必解析整份批次 JSON。

## 16. 事件驱动回合任务（2026-10-13 所有者批准）

首版单实例，领域流与公开事件载荷不变。调度存储是可恢复任务，不是游戏状态真相。

| 键 | 类型 | 内容与生命周期 |
| --- | --- | --- |
| `wolfmind:scheduler:streams` | Set | 待消费的 gameId；事件追加同一 Lua 登记，终局事件排空后移除 |
| `wolfmind:scheduler:directory-initialized` | String | 历史流首次迁移完成标记；避免重启把已排空终局流重新注册 |
| `wolfmind:scheduler:tasks` | Hash | taskId → TurnTask JSON；终局清理该局字段 |
| `game:{id}:scheduler:tasks` | Set | 本局任务 ID 索引；终局清理 |
| `wolfmind:scheduler:ready` | ZSet | ACTION/ADVANCE 就绪任务，score=readyAt |
| `wolfmind:scheduler:deadlines` | ZSet | TIMEOUT 就绪任务，score=deadline |
| `wolfmind:scheduler:running` | ZSet | RUNNING 租约，score=leaseUntil |
| `wolfmind:scheduler:budgets` | Hash | `{taskId}:MODEL/TOOL` 计数及 `:limit`；先计数再调用；终局清理 |
| `wolfmind:scheduler:event-failures` | Hash | `{gameId}:{entryId}` 消费/解码失败计数，供故障排查 |
| `wolfmind:scheduler:blocked` | Hash | gameId → 安全错误码与 entryId；坏事件三次失败后阻塞该局消费，修复后手动清除 |
| `game:{id}:events:dlq` | Stream | 坏事件/规划失败摘要及原位置，仅服务端运维读取，7 天保留；原条目保留在 PEL |

任务字段：taskId、gameId、kind、playerId、seatNo、phase、round、windowId、controllerGeneration、
controllerRef、controllerType、dispatchVersion、readyAt、deadline、status、attempts、leaseOwner、
leaseToken、leaseUntil、resultEventIds、lastErrorCode。任务不存隐藏角色全集、完整 prompt 或模型私有推理。

任务 ID 为 `{gameId}:{windowId}:{kind}:{playerId}:{controllerGeneration}`；窗口锚点来自已提交
TURN_GRANTED 或 PHASE_CHANGED 的事件版本。首次 dispatchVersion 固定供预算与提交身份使用。
reconcile Lua 用 HSETNX 幂等登记，取消失效任务并维护就绪/到期索引；随后单独 XACK。
两步间崩溃会重投事件，不能重建已存在任务或重置预算。领取、完成、预算预扣与过期恢复使用 Lua。
ACTION 的中断恢复转 WAITING_DEADLINE；TIMEOUT/ADVANCE 可重新领取。完成只接受匹配的 owner/token。

四组消费器使用各自 UUID 消费者名；组内单局租约串行处理前缀，启动重读 PEL，异名闲置 60s 用 XCLAIM 接管。
cg-agent 等待最多 500ms，本地追加立即唤醒；任务/到期索引每 500ms 补偿；目录校对不扫描运行中聚合。
启动一次 GameIdCatalog 迁移历史流，之后目录校对不加载所有游戏聚合。新局追加通知加快注册。
一个对局的坏事件不会阻塞其他对局；blocked 指标及键用于暴露需要人工修复的状态。
新增三组由 ReliableEventConsumers 管理，目录为 wolfmind:consumer:{group}:streams，blocked/failures/lease 相互独立。
cg-agent 保留原调度目录，终局退组只删除本组目录；终局归档恢复不重新登记广播目录。
cg-insight 补做收据在 repairs Hash 中使用 UUID，确认时 compare-and-delete，避免吞掉更新的补算请求。
统计组每 30 秒核对 SQLite 缺失明细/晚到痕迹，弥补跨存储通知失败；过期流的派生组可读取完整 SQLite 归档。
