-- WolfMind SQLite 结构：归档所需的最小表集合。
-- 依据 docs/backend/database-schema.md §3.2 / §3.3 / §3.4 / §3.6 / §4，字段名与列语义逐一对齐。
--
-- 表集合随写入者接入而增长，不先建空表：
--   v1：schema_version / users / games / game_events（事件永久归档与对局主记录）
--   v2：agent_memory（仅 DECISION_TRACE 行；写入者是 AgentTracePort 的归档装饰器，
--       读取者是 GET /games/{id}/analysis/agents/{playerId} —— Phase 5 AI 分析）
--   v3：game_history / statistics（cg-insight 的回放索引和幂等统计）
--
-- 三条不可违反的约束：
--   D-1 SQLite 是归档与长期数据，不是运行中状态的真相；
--   D-2 写表的只有消费者，领域对象与 REST 端点不直接写表；
--   D-3 game_events 只追加，不允许 UPDATE / DELETE（唯一例外见 database-schema.md §8.3）。
--
-- 本文件只含 DDL 与 PRAGMA，不含数据写入：schema_version 的版本行由 SqliteSupport 在
-- 应用结构后以真实毫秒时间插入，避免脚本里出现写死的 applied_at。

PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;
PRAGMA foreign_keys = ON;
PRAGMA wal_autocheckpoint = 1000;

CREATE TABLE IF NOT EXISTS schema_version (
  version    INTEGER PRIMARY KEY,        -- 结构版本号，从 1 开始递增
  applied_at INTEGER NOT NULL,           -- 应用时间，毫秒
  note       TEXT    NOT NULL            -- 本次结构变化的说明
);

CREATE TABLE IF NOT EXISTS users (
  user_id        TEXT    PRIMARY KEY,                       -- UserId 值对象，形如 u_8812，对应事件的 actorId
  username       TEXT    NOT NULL,                          -- 登录名，唯一（INV-U1）
  display_name   TEXT,                                      -- 展示名，可为空
  password_hash  TEXT    NOT NULL,                          -- 口令散列；口令与盐不进入事件流
  password_salt  TEXT    NOT NULL,                          -- 每用户独立盐
  status         TEXT    NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','DISABLED')),   -- 账号状态
  created_at     INTEGER NOT NULL,                          -- 毫秒时间戳，取 USER_REGISTERED.occurredAt
  last_login_at  INTEGER,                                   -- 取最后一次 USER_LOGGED_IN.occurredAt
  last_logout_at INTEGER                                    -- 取最后一次 USER_LOGGED_OUT.occurredAt
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username ON users(username);
CREATE INDEX IF NOT EXISTS ix_users_status ON users(status);

CREATE TABLE IF NOT EXISTS games (
  game_id          TEXT    PRIMARY KEY,                     -- GameId 值对象，与 game:{id}:events 的 id 一致
  room_id          TEXT    NOT NULL DEFAULT '',             -- GAME_STARTED.payload.roomId；实验室对局为空串
  status           TEXT    NOT NULL
                   CHECK (status IN ('RUNNING','FINISHED','ABORTED')),
  phase            TEXT    NOT NULL,                        -- 最后已知 GamePhase
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

-- 事件永久归档：可重放的真相副本。只追加，只允许加列与加索引（D-3）。
CREATE TABLE IF NOT EXISTS game_events (
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,         -- 归档自增序号，只用于定位物理行，不参与领域语义
  event_id       TEXT    NOT NULL,                          -- 信封 eventId
  game_id        TEXT    NOT NULL,                          -- 信封 gameId
  version        INTEGER NOT NULL,                          -- 信封 version，同一 game_id 内从 1 连续
  type           TEXT    NOT NULL,                          -- 信封 type
  schema_version INTEGER NOT NULL,                          -- 信封 schemaVersion
  occurred_at    INTEGER NOT NULL,                          -- 信封 occurredAt，毫秒
  phase          TEXT    NOT NULL,                          -- 信封 phase
  round          INTEGER NOT NULL,                          -- 信封 round
  actor_id       TEXT    NOT NULL,                          -- 信封 actorId
  visibility     TEXT    NOT NULL
                 CHECK (visibility IN ('PUBLIC','WOLF','PRIVATE','JUDGE')),
  causation_id   TEXT    NOT NULL,                          -- 信封 causationId
  correlation_id TEXT    NOT NULL,                          -- 信封 correlationId
  payload        TEXT    NOT NULL,                          -- 信封 payload，单行 JSON 文本
  entry_id       TEXT    NOT NULL,                          -- Redis Stream 条目 ID，形如 123-0
  archived_at    INTEGER NOT NULL                           -- 归档写入时间，非事件时间
);

-- (game_id, version) 与 event_id 双唯一：消费层幂等去重的依据（event-design.md §10）。
-- 两条唯一索引就是"至少一次投递不会重复累加"的全部保证，应用层不需要先查后写。
CREATE UNIQUE INDEX IF NOT EXISTS ux_game_events_game_version ON game_events(game_id, version);
CREATE UNIQUE INDEX IF NOT EXISTS ux_game_events_event_id     ON game_events(event_id);

CREATE INDEX IF NOT EXISTS ix_game_events_game_occurred ON game_events(game_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_game_events_game_type     ON game_events(game_id, type, version);
CREATE INDEX IF NOT EXISTS ix_game_events_game_round    ON game_events(game_id, round, version);

-- 决策痕迹归档（database-schema.md §3.6）：AI 分析页的数据源。
--
-- 本阶段只写 memory_type='DECISION_TRACE' 的行，其 content 是 AGENT_ 事件的 payload 摘要。
-- EPISODE / FACT / STRATEGY / REFLECTION 四类的写入者是 cg-agent 的 MemoryUpdater，
-- 尚未接入，因此这里不预留任何"待填"语义；embedding / embedding_model 按 §6.6 恒为 NULL。
--
-- 隐私底线（§6 / AP-D12）：content 只存结构化摘要与工具参数摘要，
-- 绝不存完整 prompt、私有事件 payload、口令或令牌。
CREATE TABLE IF NOT EXISTS agent_memory (
  memory_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id        TEXT    NOT NULL,                          -- Agent 实例标识，对应 PlayerController.refId（AI）
  player_id       TEXT,                                      -- 对局内玩家；GAME 维度必填
  user_id         TEXT,                                      -- 该座位操作者（HUMAN 控制时非空）
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
  keywords        TEXT,                                      -- 关键词，JSON 数组文本
  importance      REAL    NOT NULL DEFAULT 0.5
                  CHECK (importance >= 0 AND importance <= 1),
  source_event_id TEXT,                                      -- 产生该记忆的领域事件或决策事件 eventId
  source_type     TEXT,                                      -- 事件类型，领域事件类型或 AGENT_ 前缀类型
  embedding       BLOB,                                      -- 预留列：第一阶段不写入（§6.6）
  embedding_model TEXT,                                      -- 预留列：第一阶段不写入（§6.6）
  occurred_at     INTEGER NOT NULL,                          -- 来源事件时间
  created_at      INTEGER NOT NULL                           -- 归档写入时间
);

-- 决策痕迹的幂等保证：重复投递同一 (game_id, agent_id, source_event_id) 只留一行。
-- 轨迹事件本身没有 eventId，用 (source_type, version, occurred_at, content) 的摘要
-- 作为 source_event_id —— 理由见 SqliteDecisionTraceRepository 的类注释。
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

CREATE TABLE IF NOT EXISTS game_history (
  history_id INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('SNAPSHOT','SUMMARY')),
  version INTEGER NOT NULL,
  phase TEXT,
  round INTEGER,
  content TEXT NOT NULL,
  checksum TEXT,
  occurred_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_game_history_snapshot
  ON game_history(game_id, version) WHERE kind = 'SNAPSHOT';
CREATE UNIQUE INDEX IF NOT EXISTS ux_game_history_summary
  ON game_history(game_id) WHERE kind = 'SUMMARY';
CREATE INDEX IF NOT EXISTS ix_game_history_game_kind ON game_history(game_id, kind, version);

CREATE TABLE IF NOT EXISTS statistics (
  stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
  scope TEXT NOT NULL CHECK (scope IN ('GLOBAL','ROLE','AGENT','PLAYER')),
  scope_key TEXT NOT NULL,
  game_id TEXT REFERENCES games(game_id),
  camp TEXT CHECK (camp IN ('WOLF','VILLAGE')),
  games_played INTEGER NOT NULL DEFAULT 0,
  games_won INTEGER NOT NULL DEFAULT 0,
  win_rate REAL NOT NULL DEFAULT 0,
  rounds_total INTEGER NOT NULL DEFAULT 0,
  survivors INTEGER NOT NULL DEFAULT 0,
  token_total INTEGER NOT NULL DEFAULT 0,
  llm_calls INTEGER NOT NULL DEFAULT 0,
  llm_latency_ms_total INTEGER NOT NULL DEFAULT 0,
  fallback_total INTEGER NOT NULL DEFAULT 0,
  rebuilt_at INTEGER,
  updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_statistics_agg ON statistics(scope, scope_key) WHERE game_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_statistics_game ON statistics(scope, scope_key, game_id) WHERE game_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_statistics_lookup ON statistics(scope, scope_key, game_id);
CREATE INDEX IF NOT EXISTS ix_statistics_game ON statistics(game_id) WHERE game_id IS NOT NULL;
