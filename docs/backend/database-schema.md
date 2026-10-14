# WolfMind 详细设计：SQLite 数据库 Schema

本文细化 `DESIGN.md` §13（数据设计）的 SQLite 部分，并落实
[event-design.md](../architecture/event-design.md)（归档、消费组、保留）、
[ddd-design.md](../architecture/ddd-design.md)（聚合与不变量）、
[system-design.md](../architecture/system-design.md)（事务边界、容量、页面）已经冻结的契约。
键空间与事件流的 Redis 侧结构见 [redis-schema.md](redis-schema.md)。

## 1. 定位与约束来源

| 项 | 内容 |
| --- | --- |
| 本文范围 | 6 张业务表的 DDL、索引、事件归档映射、`agent_memory` 与 `statistics` 设计、读写路径、一致性、反模式、数据库配置与运维 |
| 权威来源 | `DESIGN.md` §13（SQLite 长期数据）、§14（八个页面）、[event-design.md](../architecture/event-design.md) §9 §10 §11 §12、[system-design.md](../architecture/system-design.md) §5 §7 |
| 表清单一致性 | 6 张业务表与 `DESIGN.md` 一致，表名不改写、不合并、不新增业务表 |
| 技术边界 | 只使用 SQLite 的表、索引与 WAL，访问方式为 Java 标准 JDBC。不引入任何 `DESIGN.md` 之外的存储、迁移框架或检索组件 |
| 辅助表 | 额外只有 `schema_version` 一张辅助表，仅用于结构演进记录，不承载业务数据（§4） |

三条贯穿全文的定位：

| 编号 | 定位 | 可检查的表现 |
| --- | --- | --- |
| D-1 | SQLite 是归档与长期数据，不是运行中状态的真相 | 运行中状态的真相是 Redis Stream；表里的数据允许滞后，业务判断不读表 |
| D-2 | 写表的只有消费者 | 5 张领域相关表的写入者是 `cg-projection`、`cg-insight` 与归档分支，没有任何一个领域对象或 REST 端点直接写表 |
| D-3 | 事件归档只追加 | `game_events` 不允许 `UPDATE`、不允许 `DELETE`，结构演进只允许加列与加索引 |

唯一的写入例外在 §8.3 说明：`users` 的凭据列由 Identity 用例写入，原因是口令与令牌不得进入事件流
（[event-design.md](../architecture/event-design.md) §7.3）。

## 2. 表清单与职责

| 表名 | 职责 | 写入者 | 读取者 | 数据量级（设计估算，算式见下） | 保留 |
| --- | --- | --- | --- | --- | --- |
| `users` | 账号、凭据、登录时间 | Identity 用例（注册、登录、登出） | 登录用例、账号服务、`statistics` 的玩家维度展示 | 每账号约 200 字节；1 万账号约 2 MB | 永久 |
| `games` | 对局主记录与终态摘要 | `cg-insight` | 回放页、AI 实验室、统计页、崩溃恢复扫描 | 每行约 300 字节；每日 1000 局约 0.3 MB/日 | 永久 |
| `game_events` | 领域事件永久归档，可重放的真相副本 | `cg-projection` | 回放页、AI 分析页、恢复重建、对账 | 每行约 745 字节；每日约 152 MB | 永久 |
| `game_history` | 快照归档与对局摘要 | `cg-projection`（`SNAPSHOT`、`SUMMARY`） | 回放页首屏、恢复流程 | 每局 3 到 4 行，每行约 4 KB；每日约 16 MB | 永久 |
| `agent_memory` | Agent 记忆与决策痕迹归档 | `cg-insight`（领域事件派生记忆、`DECISION_TRACE`） | AI 分析页、AI 实验室、`cg-agent` 读取长期记忆 | 每局决策痕迹约 612 到 1020 行；每日约 122 MB | 永久（可按年分库，§12.4） |
| `statistics` | 全局、角色、Agent、玩家四个维度的统计 | `cg-insight` | 统计页、AI 分析页胜率区、AI 实验室比较 | 每局约 10 到 30 行明细，累计行数量固定（维度数乘键数） | 永久 |

`game_events` 的数据量算式（设计估算）：

```
单条事件净数据      约 500 字节   （system-design.md §7：每条约 500 字节）
行记录开销          约  25 字节   （记录头、单元指针、页内对齐）
索引开销            约 220 字节   （1 个主键加 2 个唯一索引加 3 个二级索引，估算见 §9 注）
单行合计            约 745 字节
每日写入量          1000 局 乘 204 条 乘 745 字节   约 152 MB/日
每年增长            152 MB 乘 365                   约 55 GB/年
```

`game_events` 是唯一持续高速增长的表，因此 §12 把它的增长速度、对账结果与索引数量列为重点检查项。

## 3. 完整 DDL

### 3.1 连接级设置

```sql
PRAGMA journal_mode = WAL;        -- 库级持久设置，只需执行一次
PRAGMA synchronous = NORMAL;      -- WAL 下的常规选择，兼顾吞吐与耐久
PRAGMA busy_timeout = 5000;       -- 写冲突时等待 5 秒而不是立即失败
PRAGMA foreign_keys = ON;         -- 每个连接建立后都要执行
PRAGMA wal_autocheckpoint = 1000; -- 每约 1000 页触发一次检查点
```

### 3.2 `users`

```sql
CREATE TABLE IF NOT EXISTS users (
  user_id        TEXT    PRIMARY KEY,                       -- UserId 值对象，形如 u_8812，对应事件的 actorId
  username       TEXT    NOT NULL,                          -- 登录名，唯一（INV-U1）
  display_name   TEXT,                                      -- 展示名，可为空
  password_hash  TEXT    NOT NULL,                          -- 口令散列；口令与盐不进入事件流（event-design.md §7.3）
  password_salt  TEXT    NOT NULL,                          -- 每用户独立盐
  status         TEXT    NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','DISABLED')),   -- 账号状态
  created_at     INTEGER NOT NULL,                          -- 毫秒时间戳，取 USER_REGISTERED.occurredAt
  last_login_at  INTEGER,                                   -- 取最后一次 USER_LOGGED_IN.occurredAt
  last_logout_at INTEGER                                    -- 取最后一次 USER_LOGGED_OUT.occurredAt
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username ON users(username);
CREATE INDEX IF NOT EXISTS ix_users_status ON users(status);
```

`password_hash` 的取值形如 `{algorithm}${hash}`（例如 `PBKDF2WithHmacSHA256$...`，散列为 Base64，不含 `$`）。
表里没有单独的算法列，前缀用于将来更换算法时识别旧凭据；读到缺少前缀的值按存储损坏处理。
该表在 `wolfmind.storage=redis` 时由 `SqliteUserRepository` 读写（2026-10-12 起），内存模式下不使用。

### 3.3 `games`

```sql
CREATE TABLE IF NOT EXISTS games (
  game_id          TEXT    PRIMARY KEY,                     -- GameId 值对象，与 game:{id}:events 的 id 一致
  room_id          TEXT    NOT NULL DEFAULT '',             -- GAME_STARTED.payload.roomId；实验室对局为空串
  status           TEXT    NOT NULL
                   CHECK (status IN ('RUNNING','FINISHED','ABORTED')),
  phase            TEXT    NOT NULL,                        -- 最后已知 GamePhase，取值见 ddd-design.md §3.2
  round            INTEGER NOT NULL DEFAULT 1,              -- 最后已知轮次
  version          INTEGER NOT NULL DEFAULT 0,              -- 已归档的最大 version，对账水位
  player_count     INTEGER NOT NULL,                        -- GAME_STARTED.payload.seats 的长度
  role_composition TEXT    NOT NULL,                        -- JSON，GAME_STARTED.payload.roleComposition
  winner           TEXT CHECK (winner IN ('WOLF','VILLAGE')),-- GAME_FINISHED.payload.winner，未结束为 NULL
  finish_reason    TEXT,                                    -- GAME_FINISHED.reason 或 GAME_ABORTED.reason
  origin           TEXT    NOT NULL DEFAULT 'ONLINE'
                   CHECK (origin IN ('ONLINE','LAB')),      -- 真人局或实验室批量局
  lab_batch_id     TEXT,                                    -- origin='LAB' 时的批次标识
  started_at       INTEGER NOT NULL,                        -- GAME_STARTED.occurredAt
  finished_at      INTEGER,                                 -- GAME_FINISHED / GAME_ABORTED.occurredAt
  events_archived  INTEGER NOT NULL DEFAULT 0,              -- 已归档事件条数，与 version 一起用于对账
  updated_at       INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_games_status_started ON games(status, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_games_finished       ON games(finished_at DESC) WHERE finished_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_games_room           ON games(room_id) WHERE room_id <> '';
CREATE INDEX IF NOT EXISTS ix_games_lab_batch      ON games(lab_batch_id) WHERE lab_batch_id IS NOT NULL;
```

### 3.4 `game_events`

```sql
CREATE TABLE IF NOT EXISTS game_events (
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,         -- 归档自增序号，只用于定位物理行，不参与领域语义
  event_id       TEXT    NOT NULL,                          -- 信封 eventId
  game_id        TEXT    NOT NULL,                          -- 信封 gameId
  version        INTEGER NOT NULL,                          -- 信封 version，同一 game_id 内从 1 连续
  type           TEXT    NOT NULL,                          -- 信封 type，取值见 event-design.md §7
  schema_version INTEGER NOT NULL,                          -- 信封 schemaVersion
  occurred_at    INTEGER NOT NULL,                          -- 信封 occurredAt，毫秒
  phase          TEXT    NOT NULL,                          -- 信封 phase
  round          INTEGER NOT NULL,                          -- 信封 round
  actor_id       TEXT    NOT NULL,                          -- 信封 actorId
  visibility     TEXT    NOT NULL
                 CHECK (visibility IN ('PUBLIC','WOLF','PRIVATE','JUDGE')),
  causation_id   TEXT    NOT NULL,                          -- 信封 causationId
  correlation_id TEXT    NOT NULL,                           -- 信封 correlationId
  payload        TEXT    NOT NULL,                          -- 信封 payload，单行 JSON 文本
  entry_id       TEXT    NOT NULL,                          -- Redis Stream 条目 ID，形如 123-0
  archived_at    INTEGER NOT NULL                           -- 归档写入时间，非事件时间
);

-- (game_id, version) 与 event_id 双唯一：消费层幂等去重的依据（event-design.md §10）
CREATE UNIQUE INDEX IF NOT EXISTS ux_game_events_game_version ON game_events(game_id, version);
CREATE UNIQUE INDEX IF NOT EXISTS ux_game_events_event_id     ON game_events(event_id);

CREATE INDEX IF NOT EXISTS ix_game_events_game_occurred ON game_events(game_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_game_events_game_type     ON game_events(game_id, type, version);
CREATE INDEX IF NOT EXISTS ix_game_events_game_round    ON game_events(game_id, round, version);
```

写入语句固定使用 `INSERT OR IGNORE`，让唯一索引承担去重：

```sql
INSERT OR IGNORE INTO game_events
  (event_id, game_id, version, type, schema_version, occurred_at, phase, round,
   actor_id, visibility, causation_id, correlation_id, payload, entry_id, archived_at)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);
```

`INSERT OR IGNORE` 影响行数为 0 表示该事件已归档，属于正常重复投递，不报错、不计入失败次数。

### 3.5 `game_history`

```sql
CREATE TABLE IF NOT EXISTS game_history (
  history_id  INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id     TEXT    NOT NULL,                             -- 对局
  kind        TEXT    NOT NULL
              CHECK (kind IN ('SNAPSHOT','SUMMARY')),        -- 快照归档或对局摘要
  version     INTEGER NOT NULL,                             -- SNAPSHOT：快照版本；SUMMARY：归档到的最大版本
  phase       TEXT,                                         -- 快照或摘要时刻的阶段
  round       INTEGER,                                      -- 快照或摘要时刻的轮次
  content     TEXT    NOT NULL,                             -- 单行 JSON 文本，结构随 kind 变化
  checksum    TEXT,                                         -- SNAPSHOT 的状态校验和，与 Redis 快照一致
  occurred_at INTEGER NOT NULL,                             -- 产生该归档的领域事件时间
  created_at  INTEGER NOT NULL                              -- 写入时间
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_game_history_snapshot
  ON game_history(game_id, version) WHERE kind = 'SNAPSHOT';
CREATE UNIQUE INDEX IF NOT EXISTS ux_game_history_summary
  ON game_history(game_id) WHERE kind = 'SUMMARY';
CREATE INDEX IF NOT EXISTS ix_game_history_game_kind ON game_history(game_id, kind, version);
```

`content` 的两种结构：

| kind | content 字段 | 用途 |
| --- | --- | --- |
| `SNAPSHOT` | 与 [redis-schema.md](redis-schema.md) §7.1 的快照字段完全相同 | 恢复时校验事件流重建结果；`DESIGN.md` §8 要求快照可归档 |
| `SUMMARY` | `players[{playerId, seatNo, role, camp, controller, alive, deathCause}], winner, rounds, keyVersions{firstEventVersion, lastEventVersion, voteResolutions[]}` | 回放页首屏：一次读取即可渲染座位与角色，不必解析 204 条 `payload` |

### 3.6 `agent_memory`

```sql
CREATE TABLE IF NOT EXISTS agent_memory (
  memory_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id        TEXT    NOT NULL,                          -- Agent 实例标识，对应 PlayerController.refId（AI）
  player_id       TEXT,                                      -- 对局内玩家；GAME 维度必填
  user_id         TEXT,                                      -- 该座位操作者（HUMAN 控制时非空），玩家维度检索用
  game_id         TEXT,                                      -- 对局维度；CROSS_GAME 记忆为空
  scope           TEXT    NOT NULL
                  CHECK (scope IN ('GAME','CROSS_GAME')),
  memory_type     TEXT    NOT NULL
                  CHECK (memory_type IN ('EPISODE','FACT','STRATEGY','REFLECTION','DECISION_TRACE')),
  role            TEXT CHECK (role IN ('WEREWOLF','VILLAGER','SEER','WITCH','HUNTER')),
  phase           TEXT,                                      -- 对应事件的 phase
  round           INTEGER,                                   -- 对应事件的 round
  version         INTEGER,                                   -- 对应聚合版本或决策事件序号
  visibility      TEXT CHECK (visibility IN ('PUBLIC','WOLF','PRIVATE','JUDGE')),
  content         TEXT    NOT NULL,                          -- 记忆正文：纯文本或单行 JSON
  keywords        TEXT,                                      -- 关键词，JSON 数组文本，第一阶段检索用
  importance      REAL    NOT NULL DEFAULT 0.5
                  CHECK (importance >= 0 AND importance <= 1),
  source_event_id TEXT,                                      -- 产生该记忆的领域事件或决策事件 eventId
  source_type     TEXT,                                      -- 事件类型，领域事件类型或 AGENT_ 前缀类型
  embedding       BLOB,                                      -- 预留列：第一阶段不写入（§6.6）
  embedding_model TEXT,                                      -- 预留列：第一阶段不写入（§6.6）
  occurred_at     INTEGER NOT NULL,                          -- 来源事件时间
  created_at      INTEGER NOT NULL                           -- 归档写入时间
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_memory_trace
  ON agent_memory(game_id, agent_id, source_event_id) WHERE memory_type = 'DECISION_TRACE';
CREATE INDEX IF NOT EXISTS ix_agent_memory_trace_order
  ON agent_memory(game_id, memory_type, version);
CREATE INDEX IF NOT EXISTS ix_agent_memory_agent
  ON agent_memory(agent_id, game_id, memory_type);
CREATE INDEX IF NOT EXISTS ix_agent_memory_cross
  ON agent_memory(scope, memory_type, importance DESC) WHERE game_id IS NULL;
CREATE INDEX IF NOT EXISTS ix_agent_memory_player
  ON agent_memory(user_id, game_id) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_agent_memory_type_time
  ON agent_memory(source_type, occurred_at DESC) WHERE memory_type = 'DECISION_TRACE';
```

`ux_agent_memory_trace` 是决策痕迹的幂等保证：同一 `game_id`、同一 `agent_id`、同一决策事件的重复投递只会留下一行。

### 3.7 `statistics`

```sql
CREATE TABLE IF NOT EXISTS statistics (
  stat_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  scope         TEXT    NOT NULL CHECK (scope IN ('GLOBAL','ROLE','AGENT','PLAYER')),
  scope_key     TEXT    NOT NULL,                            -- 见 §7.1 的取值表
  game_id       TEXT    REFERENCES games(game_id),           -- NULL 表示累计行；非 NULL 表示单局明细行
  camp          TEXT CHECK (camp IN ('WOLF','VILLAGE')),      -- 该维度在该局所属阵营
  games_played  INTEGER NOT NULL DEFAULT 0,                   -- 参与局数
  games_won     INTEGER NOT NULL DEFAULT 0,                   -- 获胜局数
  win_rate      REAL    NOT NULL DEFAULT 0,                   -- games_won / games_played，games_played 为 0 时写 0
  rounds_total  INTEGER NOT NULL DEFAULT 0,                   -- 累计轮次，用于计算平均回合
  survivors     INTEGER NOT NULL DEFAULT 0,                   -- 累计存活人数
  token_total   INTEGER NOT NULL DEFAULT 0,                   -- AI 维度：累计 token（AGENT_LLM_INVOKED）
  llm_calls     INTEGER NOT NULL DEFAULT 0,                   -- AI 维度：LLM 调用次数
  llm_latency_ms_total INTEGER NOT NULL DEFAULT 0,            -- AI 维度：累计延迟，用于算平均
  fallback_total INTEGER NOT NULL DEFAULT 0,                  -- AI 维度：兜底次数（AGENT_TURN_TIMEOUT 等）
  rebuilt_at    INTEGER,                                      -- 最近一次从事件重放重建的时间
  updated_at    INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_statistics_agg
  ON statistics(scope, scope_key) WHERE game_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_statistics_game
  ON statistics(scope, scope_key, game_id) WHERE game_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_statistics_lookup ON statistics(scope, scope_key, game_id);
CREATE INDEX IF NOT EXISTS ix_statistics_game   ON statistics(game_id) WHERE game_id IS NOT NULL;
```

累计行用 `game_id IS NULL` 表示，而不是用空串占位。这样外键只在明细行上生效，累计行因 `NULL` 不受外键约束，唯一性由两个部分索引分别保证。

### 3.8 扩展列汇总与理由

以下列不在 `DESIGN.md` 与三份详细设计文档的字段清单里，属于本文为可执行性补齐的列。每列都说明为什么无法从已有数据推导。

| 表 | 列 | 为什么需要 |
| --- | --- | --- |
| `game_events` | `seq` | SQLite 需要稳定的物理定位键。不能用 `(game_id, version)` 做主键，因为对账与分页查询要按 `event_id` 单列定位 |
| `game_events` | `entry_id` | 与 Redis Stream 条目逐条比对，重建时用它还原条目 ID `{version}-0` |
| `game_events` | `archived_at` | 归档延迟监控需要写入时间；`occurred_at` 是事件时间，无法体现消费者滞后 |
| `games` | `origin`、`lab_batch_id` | AI 实验室按批次比较（`DESIGN.md` §14 AI 实验室：批量测试、模型比较）。批次标识不在事件信封中，无法从事件推导 |
| `games` | `events_archived` | 对账需要一个 O(1) 的水位列，避免每次都 `COUNT(*)` |
| `game_history` | 整表 | `DESIGN.md` §13 已固定该表名；`kind` 区分快照归档与对局摘要，摘要用于回放页首屏，避免解析 204 条 `payload` |
| `agent_memory` | `user_id` | 玩家维度检索需要"这个人的对局记忆"。座位到操作者的映射来自 `GAME_STARTED` 的 `controller.refId`，每次查询都重放代价过高 |
| `agent_memory` | `keywords`、`importance` | 第一阶段检索按结构化条件与关键词排序（§6.5），关键词需要落库，否则每次都要对 `content` 分词 |
| `agent_memory` | `embedding`、`embedding_model` | 预留列。第一阶段一律为 `NULL`（§6.6） |
| `statistics` | `llm_calls`、`llm_latency_ms_total` | AI 维度指标来自 `AGENT_LLM_INVOKED`，不是领域事件，无法从 `game_events` 推导，必须落在统计表 |
| `statistics` | `rebuilt_at` | 重建后需要标记数据来源时间，供运维判断统计的新鲜度 |
| 全部表 | 毫秒整数时间列 | 事件信封用毫秒整数（[event-design.md](../architecture/event-design.md) §2），表列与之保持一致，避免时区与格式转换 |

## 4. 辅助表 `schema_version`

```sql
CREATE TABLE IF NOT EXISTS schema_version (
  version    INTEGER PRIMARY KEY,        -- 结构版本号，从 1 开始递增
  applied_at INTEGER NOT NULL,           -- 应用时间，毫秒
  note       TEXT NOT NULL               -- 本次结构变化的说明
);
```

这张表不属于 `DESIGN.md` §13 的业务表清单，只记录结构演进历史（哪一版加了哪一列、哪一个索引）。它不做数据变更，不承载业务语义，也不承担任何"迁移框架"的职责：结构变更由发布流程手工执行 `ALTER TABLE` 后插入一行记录，`CREATE TABLE IF NOT EXISTS` 幂等建表保证重复启动安全。

初始版本行**不写在 `schema.sql` 里**，而在建表后由代码侧以真实毫秒写入（`INSERT OR IGNORE`，`version = 1`，`applied_at = 当前时间`）。理由：脚本里写死 `applied_at` 常量会让"何时被应用"这一列失去含义，而这正是该表存在的唯一用途。`INSERT OR IGNORE` 同时保证重复启动不产生重复行。

## 5. 事件归档与事件信封的映射

| 信封字段 | 列 | 列类型 | 说明 |
| --- | --- | --- | --- |
| `eventId` | `event_id` | TEXT | 唯一索引，消费层幂等依据 |
| `gameId` | `game_id` | TEXT | 与 `version` 组成唯一索引 |
| `version` | `version` | INTEGER | 与 `game_id` 组成唯一索引，重放排序依据 |
| `type` | `type` | TEXT | 事件类型，取值不在此表约束，由代码侧枚举校验 |
| `schemaVersion` | `schema_version` | INTEGER | upcaster 按它判断是否需要补默认值 |
| `occurredAt` | `occurred_at` | INTEGER | 毫秒 |
| `phase` | `phase` | TEXT | 回放页分组 |
| `round` | `round` | INTEGER | 回放页分组 |
| `actorId` | `actor_id` | TEXT | 审计 |
| `visibility` | `visibility` | TEXT | `CHECK` 约束与 [event-design.md](../architecture/event-design.md) §4 一致 |
| `causationId` | `causation_id` | TEXT | 因果追溯 |
| `correlationId` | `correlation_id` | TEXT | 用例追溯 |
| `payload` | `payload` | TEXT | 单行 JSON 文本 |
| 无（归档元数据） | `entry_id` | TEXT | Stream 条目 ID |
| 无（归档元数据） | `archived_at` | INTEGER | 归档时间 |
| 无（物理定位） | `seq` | INTEGER | 自增主键 |

`payload` 以 JSON 文本存储，理由如下：

| 理由 | 说明 |
| --- | --- |
| 契约稳定 | 信封字段是稳定契约，`payload` 因 `type` 而异。`PLAYER_VOTED` 与 `WOLF_KILL_SELECTED` 的字段集不同，拆成列会产生大量稀疏列 |
| 与 Redis 一致 | Redis Stream 条目里 `payload` 就是单字段 JSON 字符串，逐字段比对与重建不需要转换 |
| 演进安全 | 新增可选字段不改变表结构（`schemaVersion` 不变），避免为了加字段做 `ALTER TABLE` |
| 可查询 | SQLite 自带 JSON 函数，需要按 `payload` 过滤时用 `json_extract(payload, '$.playerId')`，不需要引入额外组件 |
| 不拆列的原因 | 把 `payload` 拆成列会让"事件是原子的"这条语义丢失：部分列写入成功、部分失败的中间态无法表达 |

`schema_version` 在表里冗余存一列，upcaster 在读取路径执行（[event-design.md](../architecture/event-design.md) §6）。写入路径永远写最新版本，读取路径按 `type` 与 `schema_version` 组合决定是否升级，upcaster 必须是纯函数。

## 6. `agent_memory` 设计

### 6.1 `memory_type` 取值

| 取值 | 内容 | 来源 | 维度 |
| --- | --- | --- | --- |
| `EPISODE` | 对局内的情节记忆：某轮发生的可观察事实与 Agent 的观察结果 | 领域事件（`PUBLIC` 与本人可见的 `PRIVATE`） | GAME |
| `FACT` | 已确证的事实：自己的角色、查验结果、公开死亡信息、票型结论 | 领域事件（`SEER_INSPECTED`、`ROLE_ASSIGNED`、`VOTE_TALLY_RESOLVED`） | GAME |
| `STRATEGY` | 策略层产出的可复用策略片段（例如"狼队首夜优先刀预言家"） | `AGENT_STRATEGY_DECIDED` 汇总 | GAME 与 CROSS_GAME |
| `REFLECTION` | 对局结束后的复盘结论与改进点 | `GAME_FINISHED` 触发，`cg-agent` 在写长期记忆时产出 | CROSS_GAME |
| `DECISION_TRACE` | `AGENT_` 前缀决策事件的逐条归档 | `game:{id}:agent:{playerId}` 流 | GAME |

### 6.2 `scope` 与维度

| scope | `game_id` | `player_id` | `user_id` | 说明 |
| --- | --- | --- | --- | --- |
| `GAME` | 必填 | 必填 | 可空 | 对局内记忆，随对局结束归档，读取时按对局过滤 |
| `CROSS_GAME` | 为空 | 可空 | 可空 | 跨局记忆，按 `agent_id` 与 `memory_type` 检索，是 `cg-agent` 在下一局装配上下文时的输入 |

维度关系：

| 维度 | 列 | 用途 |
| --- | --- | --- |
| Agent 维度 | `agent_id` | 跨局记忆与实验室比较的主键。一个 Agent 可控制多个玩家 |
| 玩家维度 | `player_id` | 对局内记忆。与 `game:{id}:players` 的 `playerId` 一致 |
| 操作者维度 | `user_id` | 真人操作者。`AI` 与 `BOT` 控制的座位此列为空 |
| 对局维度 | `game_id` | AI 分析页与回放页的过滤主键 |

### 6.3 `DECISION_TRACE` 与 AI 分析页

`DECISION_TRACE` 存 `AGENT_` 前缀决策事件的归档，是 `DESIGN.md` §14 AI 分析页与 AI 实验室的数据源。归档规则：

| 决策事件类型 | 归档要点 |
| --- | --- |
| `AGENT_TURN_STARTED` | `version` 取决策事件序号，`content` 存 `agentId, playerId, version, deadline` |
| `AGENT_CONTEXT_BUILT` | 存 `eventRange, tokenEstimate, memoryHits`，用于分析上下文是否过大 |
| `AGENT_STRATEGY_DECIDED` | 存 `candidateActions[], scores[], hardConstraints[]`，策略页的主要数据 |
| `AGENT_LLM_INVOKED` | 存 `model, promptTokens, completionTokens, latencyMs`，同时汇总进 `statistics` 的 AI 维度 |
| `AGENT_OUTPUT_REJECTED` | 存 `stage, reason, rawOutputRef`，用于统计守卫拒绝率 |
| `AGENT_TOOL_CALLED` | 存 `tool, argsDigest, decision, resultingEventId`，与领域事件通过 `resultingEventId` 关联 |
| `AGENT_TURN_FINISHED` | 存 `chosenAction, submittedEventId, fallbackUsed` |
| `AGENT_TURN_TIMEOUT` | 存 `deadline, fallbackAction`，计入 `fallback_total` |
| `AGENT_TURN_FAILED` | 存 `stage, errorCode`，用于失败归因 |

写入者：`cg-insight` 在 `game:{id}:agent:{playerId}` 上消费并归档。这些行永远不出现在 `game_events` 中，两条数据源在表层面就分开，AI 的推理痕迹无法污染对局事件归档。

### 6.4 检索范围与索引的对应

| 检索场景 | 条件 | 支撑索引 |
| --- | --- | --- |
| 一次回合的决策链 | `game_id` 加 `agent_id` 加 `memory_type='DECISION_TRACE'`，按 `version` 排序 | `ux_agent_memory_trace`、`ix_agent_memory_trace_order` |
| 某 Agent 的历史表现 | `agent_id` 加 `memory_type`，跨局 | `ix_agent_memory_agent` |
| 跨局记忆装配 | `scope='CROSS_GAME'` 加 `memory_type`，按 `importance` 排序 | `ix_agent_memory_cross` |
| 玩家维度 | `user_id` 加 `game_id` | `ix_agent_memory_player` |
| 失败与兜底清单 | `source_type IN (...)` 加 `memory_type='DECISION_TRACE'` | `ix_agent_memory_type_time` |

### 6.5 第一阶段检索方式

第一阶段以结构化条件与关键词为主，不做语义检索：

| 手段 | 实现 |
| --- | --- |
| 结构化过滤 | `scope`、`memory_type`、`game_id`、`agent_id`、`player_id`、`role`、`round`、`phase` 全部走索引等值或范围条件 |
| 关键词 | `keywords` 列存 JSON 数组文本；检索时用 `content LIKE '%关键词%'` 或对 `keywords` 做 `LIKE`，范围先由 `game_id` 或 `agent_id` 限定 |
| 排序 | `importance DESC, occurred_at DESC`，`importance` 由写入时按事件类型给固定值（例如 `AGENT_TURN_FAILED` 给 0.9，`AGENT_CONTEXT_BUILT` 给 0.2） |
| 上限 | 单次检索返回不超过 200 行，装配上下文时再做二次截断（`AGENT_CONTEXT_BUILT.tokenEstimate` 记录截断结果） |
| 边界 | 关键词扫描不建额外索引，先按维度把候选集压到单局或单 Agent 范围，再在 Java 侧过滤 |

### 6.6 `embedding` 预留列

| 项 | 规定 |
| --- | --- |
| 列存在 | `embedding BLOB` 与 `embedding_model TEXT` 保留在表结构中，避免后期加列 |
| 第一阶段不写入 | 两列恒为 `NULL`。任何写入这两列的实现都视为引入 `DESIGN.md` 之外的存储与检索选型 |
| 检查 | 运维检查项 OP-D10 固定校验 `SELECT COUNT(*) FROM agent_memory WHERE embedding IS NOT NULL` 必须为 0 |
| 检索不变 | 即使后期启用，检索入口仍是 `agent_memory` 表本身，不新增存储组件 |

## 7. `statistics` 设计

### 7.1 统计维度

| scope | `scope_key` 取值 | 语义 | `camp` | 明细来源 |
| --- | --- | --- | --- | --- |
| `GLOBAL` | `ALL` | 全局对局数与阵营胜率 | 为空（全局不分阵营，按 `games.winner` 分别累计到 `GLOBAL` 明细行） | `GAME_FINISHED`、`GAME_ABORTED` |
| `ROLE` | `WEREWOLF` / `VILLAGER` / `SEER` / `WITCH` / `HUNTER` | 角色胜率与参与局数 | 该角色的 `camp` | `GAME_FINISHED` 加 `ROLE_ASSIGNED` |
| `AGENT` | `agent_id` | Agent 胜率、平均回合数、token 与兜底 | 该 Agent 所控玩家的 `camp` | `GAME_FINISHED` 加 `ROLE_ASSIGNED` 加 `AGENT_LLM_INVOKED` |
| `PLAYER` | `user_id` | 玩家胜率与参与局数 | 该玩家座位的 `camp` | `GAME_FINISHED` 加 `GAME_STARTED` 的 `controller` |

### 7.2 胜率与参与局数

2026-10-14 房间生命周期补充：因 `NO_HUMANS` 或 `ROOM_CLOSED` 中止的对局不计胜负。
这些局仍写统计明细与历史，保留模型成本、回放和存活状态，但 `games_played=0`、`games_won=0`，累计胜率的分母不增加。
其他原因的既有统计口径保持不变；累计校验须 SUM 明细的 games_played，不能用明细行数 COUNT。

| 指标 | 定义 |
| --- | --- |
| `games_played` | 该维度在该局有参与的计数：`ROLE` 维度要求该角色在本局被分配；`PLAYER` 维度要求该 `userId` 占一个座位；`AGENT` 维度要求 `controller.type='AI'`；`GLOBAL` 每局计 1 |
| `games_won` | 该维度的 `camp` 等于 `GAME_FINISHED.payload.winner` 时加 1。`GAME_ABORTED` 只计入 `games_played`，不计入 `games_won` |
| `win_rate` | `games_won / games_played`，`games_played` 为 0 时写 0。它必须能由前两列重算，重算结果与存储值不一致属于缺陷（OP-D09 检查） |
| `rounds_total` | 累加 `GAME_FINISHED.payload.rounds`，平均回合数由 `rounds_total / games_played` 得出，不单独存平均列 |
| `token_total`、`llm_calls`、`llm_latency_ms_total`、`fallback_total` | 只对 `AGENT` 维度有意义，来源是 `AGENT_LLM_INVOKED`、`AGENT_TURN_TIMEOUT` 与 `AGENT_TURN_FINISHED.fallbackUsed` 的归档行 |

### 7.3 写入者与幂等

写入者是 `cg-insight`（`InsightProjector`）。写入分两层：

| 层 | 行 | 语句 | 幂等保证 |
| --- | --- | --- | --- |
| 明细层 | `game_id` 非空，每个维度键一行 | `INSERT ... ON CONFLICT(scope, scope_key, game_id) DO UPDATE SET ...` | `ux_statistics_game`。重复投递只会重写同一行，不累加 |
| 累计层 | `game_id IS NULL`，每个维度键一行 | 先 `DELETE FROM statistics WHERE scope=? AND scope_key=? AND game_id IS NULL`，再由明细行聚合重算后插入 | 累计值由明细行聚合得出，不做 `games_played = games_played + 1` 这类累加 |

累计层的重算语句：

```sql
INSERT INTO statistics (scope, scope_key, game_id, camp, games_played, games_won, win_rate,
                        rounds_total, survivors, token_total, llm_calls,
                        llm_latency_ms_total, fallback_total, updated_at)
SELECT d.scope, d.scope_key, NULL, NULL,
       COUNT(*),
       SUM(CASE WHEN d.camp = g.winner THEN 1 ELSE 0 END),
       CASE WHEN COUNT(*) = 0 THEN 0
            ELSE CAST(SUM(CASE WHEN d.camp = g.winner THEN 1 ELSE 0 END) AS REAL) / COUNT(*) END,
       SUM(g.round), SUM(d.survivors), SUM(d.token_total), SUM(d.llm_calls),
       SUM(d.llm_latency_ms_total), SUM(d.fallback_total), ?
FROM statistics d
JOIN games g ON g.game_id = d.game_id
WHERE d.game_id IS NOT NULL AND d.scope = ? AND d.scope_key = ?
GROUP BY d.scope, d.scope_key;
```

两层写入放在同一个事务里提交：先写明细，再重算累计。中途失败时明细可能已写入而累计未更新，下一次投递会重跑同一段逻辑并修正，因此不需要回滚保证。

### 7.4 从事件重放重建

| 步 | 动作 |
| --- | --- |
| 1 | `DELETE FROM statistics;` 清空两张层级的行 |
| 2 | 按 `game_id` 分组扫描 `game_events`，取 `GAME_STARTED`、`ROLE_ASSIGNED`、`GAME_FINISHED`、`GAME_ABORTED`，重建明细行 |
| 3 | 扫描 `agent_memory` 中 `memory_type='DECISION_TRACE'` 的行，按 `game_id` 与 `agent_id` 汇总 `token_total`、`llm_calls`、`llm_latency_ms_total`、`fallback_total`，补进 `AGENT` 明细行 |
| 4 | 按 §7.3 的语句重算累计行 |
| 5 | 写 `rebuilt_at`，记录重建水位 |

第 3 步依赖 `agent_memory` 已经归档完整。若决策痕迹缺失，只影响 AI 维度的成本指标，胜负指标仍由领域事件完整重建，因此重建结果按维度分别标注新鲜度。

## 8. 读写路径

### 8.1 写入者

| 写入者 | 表 | 触发 | 批次 | 顺序约束 |
| --- | --- | --- | --- | --- |
| `cg-projection` | `game_events` | 消费 `game:{id}:events` | 单批不超过 200 条（[system-design.md](../architecture/system-design.md) §5） | 事务提交成功后才 `XACK` |
| `cg-projection`（归档分支） | `game_history`（`SNAPSHOT`） | `version % 50 == 0` 且 Redis 侧生成了快照 | 单条 | 写入成功后才 `DEL` 旧的 `game:{id}:snapshot:{version}` |
| `cg-projection`（归档分支） | `game_history`（`SUMMARY`） | `GAME_FINISHED` / `GAME_ABORTED` | 单条 | 与 `game_events` 的终态事件同批提交 |
| `cg-insight` | `games` | `GAME_STARTED` 建行，终态事件更新行 | 每局 1 行 | 与 `game_events` 无跨消费者顺序依赖，用 `game_id` 对账 |
| `cg-insight` | `statistics` | 终态事件与决策事件归档后 | 每局 10 到 30 行明细加若干累计行 | 同一事务内先明细后累计 |
| `cg-insight` | `agent_memory`（`DECISION_TRACE`） | 消费 `game:{id}:agent:{playerId}` | 单批不超过 200 条 | 提交成功后才 `XACK` |
| `cg-agent` | `agent_memory`（`EPISODE` / `FACT` / `STRATEGY` / `REFLECTION`） | 领域事件与 `GAME_FINISHED` | 单批不超过 200 条 | 记忆写入后才 `XACK`，失败不回退位点并告警 |
| Identity 用例 | `users` | 注册、登录、登出 | 单行 | 见 §8.3 |

### 8.2 读取者

| 读取者（页面或服务） | 表 | 说明 |
| --- | --- | --- |
| `ReplayController`（游戏回放页） | `games`、`game_events`、`game_history` | 时间线来自 `game_events`，座位与角色来自 `game_history` 的 `SUMMARY` |
| `AnalysisController`（AI 分析页） | `agent_memory`、`game_events`、`statistics` | 决策链来自 `DECISION_TRACE`，胜率区来自 `statistics` |
| `LabController`（AI 实验室） | `games`、`statistics`、`agent_memory` | 按 `lab_batch_id` 分组比较，模型差异来自 `agent_memory` 的 `AGENT_LLM_INVOKED` 行 |
| 统计页 | `statistics`、`games` | 四个 `scope` 的累计行加近期对局列表 |
| 首页 | `statistics`（可选） | 展示规模数字，无个性化数据 |
| 游戏大厅、房间等待页 | 不读 SQLite | 房间数据是 Redis 投影（`room:{id}:meta`、`room:{id}:seats`、`room:{id}:ready`），见 [redis-schema.md](redis-schema.md) §5 |
| 游戏页面、AI 观战页面 | 不读 SQLite | 进行中对局的视图来自 Redis 投影与 WebSocket 推送，读表会引入秒级延迟 |
| `UserController`、登录用例 | `users` | 凭据校验与账号展示 |
| `RecoverGameApplicationService` | `game_events`、`game_history` | Redis 事件流丢失时的重建来源 |
| 运维对账 | `game_events`、`games`、`agent_memory`、`statistics` | §10.2 与 §12.4 的检查语句 |

### 8.3 为什么不允许领域层直接写表

| 依据 | 内容 |
| --- | --- |
| DEP-1、DEP-2（[ddd-design.md](../architecture/ddd-design.md) §5.3） | `domain..` 不得依赖 `infrastructure..`，不得出现 SQLite 相关引用。领域层拿不到连接，物理上无法写表 |
| 应用层职责边界（[ddd-design.md](../architecture/ddd-design.md) §5.1） | Application 层禁止"不产生事件的状态写入"。表是投影，直写表就是绕过事件化（R-3） |
| 真相位置（[event-design.md](../architecture/event-design.md) §1） | 只有领域事件能改变状态，改变状态的唯一路径是事件被重放。写表不会改变聚合状态，只会产生与事件流分叉的副本 |
| 一致性（[system-design.md](../architecture/system-design.md) §5） | 归档必须在消费组 `ACK` 之前完成。直写表没有消费组上下文，无法参与 archive-first 顺序 |
| 唯一例外 | `users` 表的 `password_hash`、`password_salt` 由 Identity 用例写入。理由是口令与令牌不进入事件流（[event-design.md](../architecture/event-design.md) §7.3 尾注），凭据不是可事件化的状态事实，而是身份验证材料。例外范围仅限这两列，账号的其余状态变化仍产生 `USER_REGISTERED`、`USER_LOGGED_IN`、`USER_LOGGED_OUT` 事件（INV-U2） |

## 9. 索引与查询模式

索引只保留被下面查询实际使用的部分。新增索引前先对照本表，没有对应查询的索引会拖慢归档写入。

### 9.1 回放页

```sql
-- 时间线全量，按版本分段加载（首屏取前 200 条）
SELECT version, type, occurred_at, phase, round, actor_id, visibility, payload
FROM game_events
WHERE game_id = ? AND version > ?
ORDER BY version
LIMIT 200;

-- 按轮筛选
SELECT version, type, occurred_at, actor_id, visibility, payload
FROM game_events
WHERE game_id = ? AND round = ?
ORDER BY version;

-- 只取决策类事件（投票、计票、死亡）
SELECT version, type, occurred_at, payload
FROM game_events
WHERE game_id = ? AND type IN ('PLAYER_VOTED','VOTE_TALLY_RESOLVED','PLAYER_KILLED','DEATH_ANNOUNCED')
ORDER BY version;
```

支撑索引：`ux_game_events_game_version`（主体，同时满足排序）、`ix_game_events_game_round`、`ix_game_events_game_type`。

### 9.2 AI 分析页

```sql
-- 一次 Agent 回合的完整决策链
SELECT version, source_type, content, occurred_at
FROM agent_memory
WHERE game_id = ? AND agent_id = ? AND memory_type = 'DECISION_TRACE'
ORDER BY version;

-- 全局失败与兜底清单
SELECT game_id, agent_id, version, source_type,
       json_extract(content, '$.stage')     AS stage,
       json_extract(content, '$.errorCode') AS error_code
FROM agent_memory
WHERE memory_type = 'DECISION_TRACE'
  AND source_type IN ('AGENT_TURN_FAILED','AGENT_TURN_TIMEOUT','AGENT_OUTPUT_REJECTED')
ORDER BY occurred_at DESC
LIMIT 200;

-- 某个玩家的对局记忆
SELECT game_id, memory_type, round, content
FROM agent_memory
WHERE user_id = ? AND memory_type IN ('EPISODE','FACT')
ORDER BY occurred_at DESC
LIMIT 200;
```

支撑索引：`ux_agent_memory_trace`、`ix_agent_memory_trace_order`、`ix_agent_memory_type_time`、`ix_agent_memory_player`。

### 9.3 AI 实验室比较

```sql
-- 批次内各 Agent 的胜率、平均回合、token 与兜底率
SELECT s.scope_key                                        AS agent_id,
       SUM(s.games_played)                                AS games,
       SUM(s.games_won)                                   AS wins,
       CAST(SUM(s.games_won) AS REAL) / SUM(s.games_played) AS win_rate,
       CAST(SUM(s.rounds_total) AS REAL) / SUM(s.games_played) AS avg_rounds,
       SUM(s.token_total)                                 AS tokens,
       SUM(s.fallback_total)                              AS fallbacks
FROM statistics s
JOIN games g ON g.game_id = s.game_id
WHERE s.scope = 'AGENT' AND g.lab_batch_id = ?
GROUP BY s.scope_key
ORDER BY win_rate DESC;

-- 批次内逐局结果，用于配对比较
SELECT g.game_id, g.round, g.winner, g.finish_reason
FROM games g
WHERE g.lab_batch_id = ?
ORDER BY g.started_at;
```

支撑索引：`ix_games_lab_batch`、`ux_statistics_game`、`ix_statistics_game`。

### 9.4 统计页

```sql
-- 四个维度的累计行
SELECT scope, scope_key, games_played, games_won, win_rate, rounds_total, updated_at
FROM statistics
WHERE game_id IS NULL
ORDER BY scope, games_played DESC;

-- 单角色胜率
SELECT scope_key, games_played, games_won, win_rate
FROM statistics
WHERE scope = 'ROLE' AND game_id IS NULL
ORDER BY win_rate DESC;

-- 玩家个人战绩
SELECT games_played, games_won, win_rate, rounds_total
FROM statistics
WHERE scope = 'PLAYER' AND scope_key = ? AND game_id IS NULL;
```

支撑索引：`ux_statistics_agg`、`ix_statistics_lookup`。

### 9.5 账号与对局列表

```sql
-- 登录校验
SELECT user_id, password_hash, password_salt, status
FROM users WHERE username = ?;

-- 近期已结束对局（回放入口）
SELECT game_id, phase, round, winner, player_count, finished_at
FROM games
WHERE status IN ('FINISHED','ABORTED')
ORDER BY finished_at DESC
LIMIT 50;

-- 进行中对局扫描（崩溃恢复，system-design.md §3.6）
SELECT game_id, version, events_archived
FROM games
WHERE status = 'RUNNING'
ORDER BY started_at;
```

支撑索引：`ux_users_username`、`ix_users_status`、`ix_games_finished`、`ix_games_status_started`。

## 10. 一致性、archive-first 与重建

### 10.1 archive-first 顺序

```
XREADGROUP game:{id}:events cg-projection COUNT 200
   |
   |-- BEGIN IMMEDIATE
   |     INSERT OR IGNORE INTO game_events ...（最多 200 行）
   |     INSERT OR IGNORE INTO game_history ...（仅快照与摘要时）
   |   COMMIT                       <-- 归档落地
   |
   |-- 更新 Redis 投影（MULTI / EXEC：state、players、votes）
   |
   |-- XACK game:{id}:events cg-projection {entryId...}
```

| 顺序 | 保证 |
| --- | --- |
| SQLite 提交先于 `XACK` | Redis 键过期或被清理时，归档必然已经完成（[event-design.md](../architecture/event-design.md) §12） |
| 归档先于投影可见 | 不出现"页面能看到状态但归档里没有该事件"的窗口 |
| 投影失败不回滚归档 | 归档是真相副本，投影可从归档重建；回滚归档反而会丢数据 |
| 单批上限 200 | 避免长事务阻塞其他写入（[system-design.md](../architecture/system-design.md) §5） |

**当前实现（2026-10-12，尚未接入消费组）**：`TerminalGameArchiver` 只归档已终局对局，顺序为
整流 `INSERT OR IGNORE`（每批 200 行）→ 按 §10.2 核对条数与空洞 → upsert `games` 行（`version` 与
`events_archived` 均等于流尾版本即为完成标记）→ 把 Redis 事件流与快照键缩到 7 天。顺序保证与上表相同：
SQLite 提交并核对通过之前，Redis 侧保持 30 天，不会先过期。启动时补扫一轮，此后定时扫描，失败下一轮重做。
2026-10-14：cg-projection 已接入正常归档及 Redis 状态投影，与定时补漏共用 GameArchivalService。
cg-insight 接入 game_history 终局 SNAPSHOT/SUMMARY 和 statistics 明细/累计重算；事务失败均回滚。
game_history 当前保存终局完整快照和关键投票版本索引，并非每个 Redis 间隔快照的永久归档。
其他历史版本仍由完整事件重放得到，后续间隔快照归档不在本轮范围。

### 10.1.2 结构版本

`schema_version` 当前有三条记录，由 `SqliteSupport.applySchema` 在应用脚本后写入
（`INSERT OR IGNORE`，重复启动不会重复插入）：

| version | note | 内容 |
| --- | --- | --- |
| 1 | 初始结构 | `schema_version` / `users` / `games` / `game_events` |
| 2 | agent_memory | AI 分析的决策痕迹归档（§3.6、§10.1.1，Phase 5） |
| 3 | game_history/statistics | cg-insight 的回放索引与幂等统计，按 §3.5/§3.7 已批准结构建表 |

新增表用 `CREATE TABLE IF NOT EXISTS` 表达，对已有库也是安全的，因此不需要独立迁移脚本；
补一条版本记录是为了让"这个库被谁改过"能从表里查出来，而不是只能靠读代码。

`event_id` 全局唯一是归档正确性的前提：同一 `event_id` 出现在两局时，后到的一局会被唯一索引静默忽略，
表现为核对时的版本空洞。对局驱动生成的事件曾只用版本号派生 `event_id`（`ev_driver_{version}`），
联调中实际触发了这一问题；现已改为带 `gameId`。

### 10.1.1 决策痕迹归档（Phase 5 追加，2026-10-12）

`agent_memory` 的 `DECISION_TRACE` 行**不走上面的整流管线**，因为它的来源不是对局事件流：

| 项 | 实现 | 理由 |
| --- | --- | --- |
| 写入者 | `ArchivingAgentTrace`（`AgentTracePort` 的双写装饰器）→ `SqliteDecisionTraceRepository` | 轨迹不进入 `game:{id}:events`（C-4/C-5），因此消费组看不到它；它是 Agent 的旁路记录 |
| 写入时机 | 每次 `record` 同步写一行 | 一个九人局约 600–1000 条轨迹，分布在数分钟内，单行写入的开销可忽略；批次扫描会丢掉"重启前那几分钟"的轨迹 |
| 失败处理 | 只记 WARN，**不影响对局** | 归档是真相副本（D-1）。让它抛异常等于"SQLite 写失败 → 对局停住" |
| 幂等键 | `source_event_id` = `sha1(type\|playerId\|version\|phase\|round\|occurredAt\|payloadJson)` 的前 16 字节 | 轨迹事件**没有 eventId**（它不是事件信封）。内容寻址保证重复投递被 `ux_agent_memory_trace` 忽略，而语义不同的两条不会因为序号相同互相覆盖 |
| 只接受 `AGENT_` | 实现里再判一次类型前缀 | 一旦接受领域事件，`agent_memory` 就变成对局事件的第二份副本，而 §8.3 说这是刻意隔离 |
| 读取者 | `GET /games/{id}/analysis/agents/{playerId}`（[api-spec.md](api-spec.md) §7.2） | 进程内轨迹优先、归档兜底：进程内是本次运行的权威，归档供重启后与隔日的分析页 |
| 其他 `memory_type` | 不写入 | `EPISODE`/`FACT`/`STRATEGY`/`REFLECTION` 的写入者（`cg-agent` 的 MemoryUpdater）尚未接入；表建出来但不预留"待填"语义 |

**隐私底线（AP-D12）**：`content` 只存结构化摘要（候选集、工具名与参数摘要、token 与耗时、
拒绝码），不存完整 prompt、不存私有事件 payload、不存口令与令牌。
预留列 `embedding` / `embedding_model` 恒为 `NULL`（§6.6、OP-D10），有回归用例守着。

**进程内轨迹的窗口**：Redis 模式下 `ArchivingAgentTrace` 同时写进程内存储与 SQLite；
内存模式没有 SQLite，轨迹只活在进程内（重启即丢）。这是"内存模式不是生产形态"的又一个具体后果。

### 10.2 对账

```sql
-- 归档完整性与版本水位
SELECT COUNT(*) AS archived, MIN(version) AS min_version, MAX(version) AS max_version,
       COUNT(DISTINCT version) AS distinct_version
FROM game_events WHERE game_id = ?;

-- 版本空洞检测（窗口函数）
SELECT version + 1 AS gap_start, next_version - 1 AS gap_end
FROM (SELECT version, LEAD(version) OVER (ORDER BY version) AS next_version
      FROM game_events WHERE game_id = ?)
WHERE next_version IS NOT NULL AND next_version <> version + 1;

-- 重复 event_id 检测（应恒为 0 行，唯一索引已拦截，这里做兜底巡检）
SELECT event_id, COUNT(*) AS c
FROM game_events GROUP BY event_id HAVING c > 1;
```

对账口径：`COUNT(*)` 应等于 `version`（`version` 从 1 连续），`MIN(version)` 为 1，`max_version` 等于 `games.version`，`distinct_version` 等于 `COUNT(*)`。

### 10.3 Redis 事件流丢失后的重建

```
RecoverGameApplicationService（[redis-schema.md](redis-schema.md) §7.3）
  1. SELECT version, type, schema_version, occurred_at, phase, round, actor_id,
            visibility, causation_id, correlation_id, payload
     FROM game_events WHERE game_id = ? ORDER BY version
  2. 逐条 Game.replay(event)（[ddd-design.md](../architecture/ddd-design.md) §2.2）
     聚合侧只认领域对象，不感知数据来自 Redis 还是 SQLite
  3. 校验 version 连续与状态 checksum；异常则停止并告警
  4. 重新 XADD game:{id}:events，条目 ID 用原 version（{version}-0），
     重建消费组（XGROUP CREATE ... 0-0）
  5. 重新生成最新快照并 SET game:{id}:snapshot
  6. 产生 GAME_RECOVERED（visibility=JUDGE）
  7. 释放 game:{id}:lock；重建期间的写请求返回 GAME_RECOVERING
```

这条路径让 `DESIGN.md` §13 的"Redis 用于运行中的游戏"与 §15 Phase 2 的"游戏可恢复"同时成立：Redis 键可以丢，`game_events` 是永久归档。

### 10.4 跨表一致性

| 关系 | 保证方式 |
| --- | --- |
| `game_events` 与 `games` | 由两个消费者分别写入，不做外键约束。用 `games.version` 与 `MAX(game_events.version)` 对账，不一致时以 `game_events` 为准重算 `games` |
| `game_history` 与 `game_events` | `SNAPSHOT.version` 必须是 `game_events` 中存在的版本；`SUMMARY.version` 等于该局 `MAX(version)` |
| `statistics` 与 `games` | 明细行有外键，`game_id` 必须存在。累计行由明细重算（§7.3） |
| `agent_memory` 与 `game_events` | 通过 `source_event_id` 关联；`DECISION_TRACE` 行的 `source_event_id` 属于决策事件流，与 `game_events` 无交集，这是刻意的隔离 |
| 外键策略 | 只声明 `statistics.game_id` 一个外键。`game_events`、`game_history`、`agent_memory` 不声明外键，因为它们的父行由另一个消费者写入，写入顺序没有保证，外键失败会让归档整体失败（丢数据远比暂时缺少父行严重） |

## 11. 反模式清单

| 编号 | 反模式 | 后果 |
| --- | --- | --- |
| AP-D1 | 领域对象或聚合内部执行 SQL 写表 | 违反 DEP-1、DEP-2，规则与存储耦合，重放不再唯一 |
| AP-D2 | 用 `games` 或 `statistics` 的行做游戏规则判断 | 表是投影，滞后时会做出错误判断（D-1） |
| AP-D3 | 先 `XACK` 再写 `game_events` | 破坏 archive-first。Redis 键过期后事件永久丢失 |
| AP-D4 | `UPDATE game_events SET ...` 或 `DELETE FROM game_events` | 破坏 INV-14，历史不可信，重放结果与原状态分叉 |
| AP-D5 | 用 `seq` 或 `payload` 里的字段做幂等去重 | `seq` 是物理序号，重复投递会产生新行；幂等只能靠 `event_id` 与 `(game_id, version)` 双唯一索引 |
| AP-D6 | `statistics` 用 `games_played = games_played + 1` 累加 | 重复投递会重复计数。累计行必须由明细行聚合重算 |
| AP-D7 | 把 `AGENT_` 前缀事件写入 `game_events` | 污染领域事件归档，回放时会多出无法重放的事件 |
| AP-D8 | 把 `payload` 拆成多个列或改写字段名 | 信封映射失效，upcaster 与逐字段比对无法进行，稀疏列随事件类型膨胀 |
| AP-D9 | 一个事务里跨多个 `game_id` 批量提交 | 长事务阻塞其他归档写入，失败时整批重做，放大延迟 |
| AP-D10 | 第一阶段写入 `embedding` 或 `embedding_model` | 引入 `DESIGN.md` 之外的存储与检索选型 |
| AP-D11 | 为清理容量删除历史事件行 | 事件归档永久保留。容量问题用按年分库解决，不用删除 |
| AP-D12 | 在 `agent_memory` 里存口令、令牌或完整 LLM 原始响应 | 敏感值不得进入长期数据；`content` 只存结构化摘要与引用（`rawOutputRef`） |
| AP-D13 | 让 `game_history.content` 与快照字段不一致（少字段或多字段） | 恢复时 checksum 校验失败，快照归档失去比对价值 |
| AP-D14 | 直接改 `users` 的展示列而不产生账号事件 | 违反 INV-U2。展示列与事件流分叉（凭据两列除外，见 §8.3） |

## 12. 数据库配置建议与运维检查

### 12.1 连接与 PRAGMA

| 配置 | 值 | 说明 |
| --- | --- | --- |
| `journal_mode` | `WAL` | 读写并发：归档写入不阻塞查询读取（`DESIGN.md` §13 的长期数据与页面查询并行） |
| `synchronous` | `NORMAL` | WAL 下的常规选择，兼顾吞吐与耐久 |
| `busy_timeout` | `5000` | 写冲突等待 5 秒，避免立即抛错。SQLite 在 WAL 下仍是单写者 |
| `foreign_keys` | `ON` | 每个连接都要设置，逐连接生效。只对 `statistics.game_id` 生效 |
| `wal_autocheckpoint` | `1000` | 每约 1000 页触发检查点，控制 WAL 文件大小 |
| 写连接 | 1 个专用连接，串行提交 | 归档只有少量写入者，单写连接避免写锁竞争，也让批大小可控 |
| 读连接 | 若干只读连接 | 页面查询走只读连接，不与归档写入争抢写锁 |

### 12.2 批处理

| 项 | 值 | 依据 |
| --- | --- | --- |
| 单批事件上限 | 200 | [system-design.md](../architecture/system-design.md) §5 |
| 提交粒度 | 一批一个事务 | 失败整批重做，`INSERT OR IGNORE` 保证重做安全 |
| 事务模式 | `BEGIN IMMEDIATE` | 提前拿写锁，避免升级锁时失败 |
| 快照与摘要 | 与同批事件一起提交 | `version % 50 == 0` 的事件与快照归档同批 |
| 累计统计 | 与明细行同一事务 | §7.3 |

### 12.3 备份与完整性

| 项 | 做法 |
| --- | --- |
| 在线备份 | `VACUUM INTO 'backup/wolfmind-{yyyyMMddHHmm}.db';`。它在一个读事务里生成一致的副本，不需要停机。需要 SQLite 3.27 及以上 |
| 低版本替代 | 先 `PRAGMA wal_checkpoint(TRUNCATE);`，再对主库文件与 `-wal` 文件做一次文件级拷贝 |
| 备份保留 | 保留最近 7 份，每日一份（设计建议值） |
| 完整性自检 | 每次备份后对新副本执行 `PRAGMA quick_check;`，每周对主库执行一次 `PRAGMA integrity_check;` |
| 空间回收 | 归档表只增不删，`VACUUM` 收益有限。空间规划按 §2 的 55 GB/年 估算，见 §12.4 的分库路径 |
| 结构演进 | 手工 `ALTER TABLE ... ADD COLUMN` 加一条 `schema_version` 记录。不允许改写事件归档列的语义 |

### 12.4 运维检查项

| 编号 | 检查 | 语句或方法 | 期望与阈值 | 异常处理 |
| --- | --- | --- | --- | --- |
| OP-D01 | 数据库与 WAL 大小 | `SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size();` 与 WAL 文件大小 | WAL 小于主库的 20% | WAL 过大时执行 `PRAGMA wal_checkpoint(TRUNCATE);` |
| OP-D02 | 归档延迟 | `SELECT MAX(archived_at) FROM game_events;` 与当前时间差 | 小于 5 秒 | 持续偏大说明 `cg-projection` 滞后，检查 PEL（[redis-schema.md](redis-schema.md) OP-R4） |
| OP-D03 | 版本空洞 | §10.2 的窗口函数查询 | 返回 0 行 | 出现空洞立即告警，从 `game_history` 与 Redis 双向补齐 |
| OP-D04 | 事件重复 | §10.2 的 `event_id` 分组查询 | 返回 0 行 | 大于 0 说明唯一索引缺失，按缺陷处理 |
| OP-D05 | 归档水位一致 | `SELECT game_id, version, events_archived FROM games` 与 `MAX(game_events.version)` 逐局比对 | `version` 等于 `MAX(version)` | 不一致时以 `game_events` 为准重算 `games` 行 |
| OP-D06 | 未归档对局 | Redis 存在 `game:{id}:events` 而 `game_events` 无对应 `game_id` | 为 0 | 存在说明消费者未启动或位点未推进 |
| OP-D07 | 索引使用 | 对 §9 的每条查询执行 `EXPLAIN QUERY PLAN` | 命中对应索引，不出现全表扫描 | 缺索引时先确认查询本身是否必要，再决定加索引 |
| OP-D08 | 统计累计行与明细行一致 | `SELECT a.scope, a.scope_key FROM statistics a WHERE a.game_id IS NULL AND a.games_played <> (SELECT COALESCE(SUM(d.games_played),0) FROM statistics d WHERE d.scope=a.scope AND d.scope_key=a.scope_key AND d.game_id IS NOT NULL);` | 返回 0 行 | 不一致时按 §7.3 重算累计层 |
| OP-D09 | 胜率可重算 | `SELECT COUNT(*) FROM statistics WHERE games_played > 0 AND ABS(win_rate - CAST(games_won AS REAL)/games_played) > 0.0001;` | 返回 0 行 | 大于 0 说明写入路径算了别的口径，按 §7.2 修正 |
| OP-D10 | 预留列未启用 | `SELECT COUNT(*) FROM agent_memory WHERE embedding IS NOT NULL OR embedding_model IS NOT NULL;` | 为 0 | 大于 0 属于违反 §6.6，立即回退并向评审报备 |
| OP-D11 | 决策痕迹放大 | `SELECT game_id, COUNT(*) FROM agent_memory WHERE memory_type='DECISION_TRACE' GROUP BY game_id;` | 单局 612 到 1020 行 | 超出说明重复归档或重试放大，检查 `ux_agent_memory_trace` 是否生效 |
| OP-D12 | 备份新鲜度 | 最新备份文件时间与 `PRAGMA quick_check` 结果 | 24 小时内且检查通过 | 超期或失败时立即重做备份并排查磁盘 |
| OP-D13 | 外键开关 | `PRAGMA foreign_keys;` | 返回 1 | 返回 0 说明连接未设置，`statistics` 的明细行可能指向不存在的对局 |
| OP-D14 | 结构版本一致 | `SELECT MAX(version) FROM schema_version;` 与发布清单比对 | 一致 | 不一致说明有结构变更未记录，补记录后再继续 |
| OP-D15 | 归档表增长速度 | 每日 `COUNT(*)` 与 `MAX(archived_at)` | 与 §2 的 152 MB/日 估算偏差在 50% 以内 | 偏差过大时排查事件量或索引数量变化。单库超过运行盘阈值时按年拆分独立 SQLite 数据库文件，仍属 SQLite，不引入新选型 |

## 13. 与 DESIGN.md 的对应自检

| DESIGN.md 要求 | 本文落点 | 是否新增技术选型 |
| --- | --- | --- |
| §13 SQLite 保存 users、games、game_events、game_history、agent_memory、statistics | §3 完整 DDL，6 张表名称与列语义逐一对齐 | 否 |
| §13 Redis 用于运行中的游戏，SQLite 用于长期数据 | §1 D-1、§8.2 读取者表（进行中页面不读表） | 否 |
| §7 Event Store 的事件结构 | §5 信封到列的映射表 | 否 |
| §8 快照可归档 | §3.5 `game_history` 的 `SNAPSHOT` | 否 |
| §6 状态变化产生事件后到消费者 | §8.1 写入者表，§10.1 archive-first 顺序 | 否 |
| §14 AI 分析页面（Agent 策略、决策过程、胜率分析） | §6.3 `DECISION_TRACE`、§7.4 重建 | 否 |
| §14 AI 实验室（配置 Agent、批量测试、模型比较） | §3.3 `origin` 与 `lab_batch_id`、§9.3 比较查询 | 否 |
| §14 游戏回放（Event 时间线、游戏过程） | §9.1 回放查询、§3.5 `SUMMARY` | 否 |
| §15 Phase 2 游戏可恢复 | §10.3 从归档重建 Redis | 否 |

事件类型、`visibility` 取值、`GamePhase` 取值、消费组名全部引用
[event-design.md](../architecture/event-design.md) 与 [ddd-design.md](../architecture/ddd-design.md)，
本文不新增事件类型、不新增业务表、不改写字段语义。
