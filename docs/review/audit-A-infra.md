# 审计A：后端基础设施 / 持久化 / 部署链路 与设计基线的偏差

审计员：audit-infra（只读）。范围：`backend/server/src/main/java/com/wolfmind/infrastructure/**`、`backend/server/pom.xml`、`backend/server/src/main/resources/**`、`deploy/**`。
对照：`AGENTS.md` §3 §7 §11、`docs/backend/redis-schema.md`、`docs/backend/database-schema.md`、`docs/architecture/system-design.md`、`docs/architecture/event-design.md`、`docs/backend/api-spec.md` §9、`docs/ops/deployment.md`、`docs/ops/observability.md`。
方法：读文档 + 读代码 + 检查 `target/surefire-reports` 产物；未运行 `mvn`（Lead 已跑），但**检查了那次运行留下的报告文件**。
本报告只读审计；除本文件外未修改任何源码/文档。

---

## 0. 结论摘要（含 P0/P1/P2/P3 计数）

| 严重度 | 计数 | 条目 |
| --- | --- | --- |
| **P0** | **0** | 本链路未发现"立刻导致数据损坏且文档未做任何承认"的缺陷 |
| **P1** | **4** | 2-5（SQLite 重建路径不存在）、6-2（compose 未透传 LLM provider）、7-1（`/health` 不返回 503）、0-1（Redis 验收在最近一次构建中未执行） |
| **P2** | **8** | 2-2、2-4、2-6、2-7、2-8、4-1、4-2、4-3 |
| **P3** | **11** | 1-1、2-1、2-3、2-9、3-1、3-2、4-4、4-5、6-1、6-3、7-2 |
| 符合（无偏差） | 13 | 2-10、2-11、2-12、2-13、4-6、4-7、4-8、4-9、5-1、5-2、6-4、7-3、7-4 |

**最严重的 4 条一句话结论**

1. **`/health` 在必需依赖不健康时永远返回 HTTP 200**（只把 `status` 写成 `DOWN`），而 `api-spec.md` §9 明写要求 503、`Dockerfile.backend` 的 HEALTHCHECK 用 `curl -fsS`（只认非 2xx）——Redis 运行时挂掉后容器仍被判为 healthy，编排层不会摘除/重启这个已经写不进事件的实例。
2. **`deploy/docker-compose.yml` 的 backend 服务没有透传 `WOLFMIND_LLM_PROVIDER`**，而 `.env.example` 与 `deployment.md` §3 都要求填它、且 `LlmSettingsLoader` 把 provider 当作总开关——按文档部署后容器内 `LlmSettings.DISABLED`，模型永不调用、全场规则兜底，而 `/health` 报 `UP`，文档承诺的"填密钥即启用模型"在容器路径上不成立。
3. **`event-design.md` §11 / `database-schema.md` §10.3 的"Redis 事件流丢失后由 SQLite 永久归档重建"不存在**：`RecoverGameApplicationService` 遇到流不存在直接抛 `GAME_NOT_RUNNING` 并注释"归档兜底路径尚未实现"，且该用例**没有装配成 Bean、没有任何生产调用者**——文档"Redis 键可以丢，game_events 是永久归档"这句话在当前实现里为假。
4. **Phase 2 的核心验收（Redis 重启后状态等价、Redis 存储契约等价）在最近一次全量构建里一条都没跑**：`RedisRestartAcceptanceTest` / `RedisStorageIntegrationTest` 的 surefire 报告是 `tests="0"`，原因是 `RedisTestSupport.connect()` 在 Redis 不可达时 `Assumptions.abort`，而 surefire 把这种情况记成 `Tests run: 0, Skipped: 0`——**绿色构建里静默少了 13 个用例，报告上看不出"被跳过"**。

---

## 1. 逐项核对表

编号规则：`X-Y` 中的 X 对应任务书里 7 项核验点，Y 为该项内序号。严重度只对"不符合"标注；符合项写 `—`。

| 编号 | 设计要求（出处） | 实现现状（file:line） | 符合? | 严重度 | 是否已留痕 | 影响 |
| --- | --- | --- | --- | --- | --- | --- |
| 0-1 | Phase 2 验收必须由真实执行给出证据：重启后恢复状态等价、Redis 与内存实现契约等价（`AGENTS.md`:198-200 §11；`TASKS.md`:255 的验收句） | 最近一次构建的 surefire 产物里 `RedisRestartAcceptanceTest` / `RedisStorageIntegrationTest` 均为 `tests="0"` 且无 `<testcase>`（`backend/server/target/surefire-reports/TEST-com.wolfmind.infrastructure.redis.*.xml`，写入时间 2026-10-13 15:51:36）；原因是 `RedisTestSupport.java`:38-43 在 Redis 不可达时 `Assumptions.abort`，surefire 记成 `Tests run: 0, Skipped: 0`；本次实测 127.0.0.1:6379 无监听 | 否 | **P1** | **未留痕（新发现）**：`TASKS.md`:268、:278、:603 都声称 Redis 重启恢复已有自动化验收、"1 跳过" | 13 个 Redis 用例（含重启验收与并发仲裁）在绿色构建中被静默丢掉，报告里看不出"跳过"；Phase 2 最核心的判据在当前环境**没有可复现证据**，且任何一次 CI 在没有 Redis 的机器上都会得到同样"全绿"的结果 |
| 1-1 | "Persistence access: MyBatis or MyBatis-Plus, subject to SQLite compatibility"（`AGENTS.md`:45） | `pom.xml`:58-64 只有 `org.xerial:sqlite-jdbc`，无 mybatis 依赖；`SqliteArchiveRepository`:37-42 手写 `PreparedStatement` SQL；`SqliteSupport`:48-60 `DriverManager` + `SQLiteConfig` | 否 | P3 | 已留痕：`TASKS.md`:279「归档访问｜原生 JDBC｜目标 MyBatis/MyBatis-Plus」；设计侧亦明示（`database-schema.md`:16「访问方式为 Java 标准 JDBC」） | 无功能缺陷（反而少一层映射漂移）；但 `AGENTS.md` §3 基线未被按字面执行，替换 ORM 需重写整个归档仓储；两条基线（AGENTS §3 与 database-schema §1）互相冲突，需所有者裁决 |
| 2-1 | 增量重放起点为快照版本之后：`XRANGE game:{id}:events (50-0 +`（`redis-schema.md`:281、:334） | `RedisEventStore.read` 的下界是**闭区间** `Range.Bound.inclusive(from + "-0")`（`RedisEventStore.java`:152）；调用方一律自己 +1：`InMemoryGameRepository.java`:131、:144、:207、`RecoverGameApplicationService.java`:137 | 部分 | P3 | 未留痕（新发现）——`EventStorePort.java`:36 只写"起始版本（含）"，与文档的半开表达不一致 | 当前调用方都正确，无行为缺陷；但接口语义与文档表达式不同，未来按文档写 `read(gameId, snapVersion, ...)` 会重复重放一条事件（同一事件重放两次会被 `Game.replay` 判为乱序而失败，症状是"恢复随机失败"） |
| 2-2 | 幂等记录与事件写入同批（`redis-schema.md`:252-255 的 §4.4 脚本第 5 步；`system-design.md`:202「幂等记录｜与事件写入同批」） | `RedisEventStore.java`:51-79 的 Lua 只做版本校验 + XADD + EXPIRE，**不含 idem 键**；幂等由 `RedisIdempotencyPort` 在 `SubmitActionApplicationService.java`:123 单独 `record` | 否 | P2 | 已留痕：`redis-schema.md`:878「幂等记录｜在追加脚本之外单独写入」、`TASKS.md`:282 | commit 成功而进程在 `record` 前崩溃时，同一幂等键再次提交不会命中幂等，会重新加载聚合再 `handle`——是否产生重复事件取决于领域校验是否覆盖该动作（`domain-model.md` §6.1 的 V6 只兜底计票类）。文档承诺的"结果可取回"存在极窄缺口 |
| 2-3 | 幂等键字段 `{eventId, version, resultHash, createdAt}`（`redis-schema.md`:471-479 §5.10、`event-design.md`:311） | 实际写入 `version / eventIds / eventTypes / resultDigest`，**没有 `eventId`、`resultHash`、`createdAt`**（`RedisIdempotencyPort.java`:30-32、:95-104） | 否 | P3 | 未留痕（新发现）：§14.3 只说"在脚本外写入"，没说字段改名 | 运维按 §5.10/OP-R13 的定义读 `idem:` 键时字段对不上；`createdAt` 缺失使"这个幂等键是什么时候产生的"无法从数据回答（只能看 TTL 倒推） |
| 2-4 | 未归档事件不得过期；"Mark an archive complete only after SQLite transaction success; set Redis TTL/cleanup only afterward"（`AGENTS.md`:122、:135） | `RedisEventStore.ttlSeconds` 对**所有**对局流（含 RUNNING）一律给 30 天（`RedisEventStore.java`:214-222）；只有终局对局会被 `TerminalGameArchiver` 归档（`TerminalGameArchiver.java`:56、:87-126），归档核对通过后才 `shortenAfterArchive` 缩到 7 天（`TerminalGameArchiver.java`:122、`RedisEventStore.java`:228-242） | 部分 | P2 | 部分留痕：`event-design.md`:350 与 `redis-schema.md`:691 把"未结束 30 天上限"写成设计；但 TASKS 未点明"非终局流没有任何归档路径，30 天后永久丢失" | 永不进入终态的对局（服务长期停机 >30 天、`wolfmind.game.driver.enabled=false`、被遗弃且无人推进的局）在 30 天后事件整条消失且**没有 SQLite 副本**；`AGENTS.md` §7 的字面要求（不得过期未归档事件）未被满足 |
| 2-5 | "Redis 事件流丢失时的兜底：SQLite `game_events` 是永久归档，`RecoverGameApplicationService` 可以只依赖 SQLite 重建…再重新 XADD 回 Redis Stream"（`event-design.md`:341-344；`database-schema.md`:737-752 §10.3；`database-schema.md`:852 自检） | `RecoverGameApplicationService.java`:77-82：流不存在时抛 `GAME_NOT_RUNNING`，注释「归档兜底路径尚未实现」；全文无 `ReplayArchivePort`/SQLite 读取（该文件:45-64 的端口列表里没有归档端口） | 否 | **P1** | **未留痕（新发现）**：`TASKS.md` 只有 `/admin/*` 未注册路由的留痕（:671），没有任何"重建路径未实现"的记录 | 文档承诺的兜底能力实际不存在。已归档的终局对局尚可走回放（`SqliteReplayArchive`），但"Redis 键可以丢、可重建"这条 Phase 2 论证为假；运维按 §10.3 处置流丢失事故会直接失败 |
| 2-6 | 四个消费组 `cg-projection` / `cg-broadcast` / `cg-agent` / `cg-insight`，ACK 时机、DLQ、`XAUTOCLAIM` 排空（`redis-schema.md`:491-521 §6、:546-565 §6.5；`event-design.md`:291-305 §9） | 全仓无 `XGROUP` / `XREADGROUP` / `XACK` / `XTRIM` / `XDEL`（对 `backend/**/*.java` 全量搜索零命中）；发布走进程内 `InMemoryEventPublisher`（`RedisStorageConfiguration.java`:40-41 也承认） | 否 | P2 | 已留痕：`redis-schema.md`:874「消费组（§6）｜未建 cg-*」、`TASKS.md`:672、:743 | 单实例下功能可用，但：投递不持久（进程崩溃即丢推送，靠客户端重连补齐）；多实例部署会重复推送/漏推送；**没有 DLQ 与归档死信重放**，归档失败只靠 30s 轮询重试（`RedisStorageConfiguration.java`:195-201），失败原因只进日志 |
| 2-7 | 归档失败必须可重试且幂等：`INSERT OR IGNORE` + 唯一索引、失败重做（`AGENTS.md`:135；`database-schema.md`:648-675 §10.1、:152-161） | `SqliteArchiveRepository.java`:37-42 + :66-104（200 条/批，`BEGIN IMMEDIATE`，整批回滚）；`schema.sql`:96-97 两条唯一索引；`TerminalGameArchiver.java`:114-121 归档→核对→写 `games` 行 | 是（顺序与幂等都满足） | — | — | 正面：归档真幂等、真可重试，"归档先于过期"在终局对局上成立。唯一遗留是 §2-4 的非终局情形 |
| 2-8 | 恢复用例必须能被触发：崩溃恢复扫描（`system-design.md`:168-179 §3.6、`redis-schema.md`:7 §1 写入路径） | `RecoverGameApplicationService` **没有 @Bean、没有控制器调用**（全仓搜索只命中该文件自身与 `RecoverGameApplicationServiceTest.java`:28-140）；生产里只有 `RunningGameAgentRecovery`（只补建 Agent，不产 `GAME_RECOVERED`、不推进超时回合） | 否 | P2 | 部分留痕：`TASKS.md`:671「`/admin/*`（recover、DLQ）未注册路由」；未说明"连用例都没接线，崩溃恢复根本不会发生" | 崩溃/重启后不会产生 `GAME_RECOVERED`（可追溯的恢复事实缺失），也不会执行 §3.6 第 6 步"超时则提交 SkipAction"；文档描述的三条恢复语义里"重建重放"完全缺席 |
| 2-9 | 快照保留最近 3 个；淘汰前先归档 `game_history`（`redis-schema.md`:639-646 §7.4、`event-design.md`:352） | `RedisSnapshotStore.java`:62-69 超出 3 个直接 `delete` + `ZREM`，**没有 game_history 写入**（类注释:20 自认）；`schema.sql` 里根本没有 `game_history` 表 | 否 | P3 | 已留痕：`redis-schema.md`:877、`TASKS.md`:672、`schema.sql`:8-9 | 当前无功能后果（快照可由事件流重算，`game_history` 无读取者）；一旦接入 `game_history`（database-schema §3.5/AP-D13），被淘汰的快照将永久缺失，"快照可归档"这条 DESIGN §8 要求落空 |
| 2-10 | 追加必须以 `expectedVersion+1` 起始、整批成或整批败（`EventStorePort.java`:15-28、`redis-schema.md`:189-258 §4.4、`AGENTS.md`:102-110） | `RedisEventStore.java`:51-79 单 Lua 脚本内做版本前置校验 + 连续 `XADD {version}-0`；调用前还做参数内联校验（:114-124）；`read`/`currentVersion`/`exists` 齐备（:139-181） | 是 | — | — | 正面：Redis 自身是并发仲裁者，锁过期后的双写会得到 `GAME_VERSION_CONFLICT`（:132-135），不会留下版本空洞 |
| 2-11 | 条目 ID 显式 `{version}-0`，禁用 `XADD *`（`redis-schema.md`:149-157 §4.2、AP-R4:795） | `RedisEventStore.java`:75 `XADD stream, version .. '-0'` | 是 | — | — | 正面 |
| 2-12 | 每次追加刷新 TTL，不得只在创建时设一次（AP-R13，`redis-schema.md`:804、:691） | `RedisEventStore.java`:77 脚本内 `EXPIRE stream ttl` | 是 | — | — | 正面（运行中流不会因不刷新而中途清空） |
| 2-13 | 归档完成后才缩短保留期（`AGENTS.md`:135） | `TerminalGameArchiver.java`:118-122：核对条数与空洞通过 → `upsertGame` → 才 `afterArchived.accept` | 是 | — | — | 正面 |
| 3-1 | `game:{id}:state` / `players` / `votes` 由 cg-projection 维护，业务判断只读聚合不读投影（R-1，`redis-schema.md`:21、:51-54、AP-8） | 这三个键在 Redis 模式下**不存在**；`GameStateCachePort` 由 `InMemoryGameStateCache` 提供（`WolfMindConfiguration.java`:140-142），只被 `InMemoryGameRepository` 在生成快照时 `put`（:250、:276、:283），生产代码无任何 `get` | 部分 | P3 | 已留痕：`redis-schema.md`:875、`TASKS.md`:281 | 正面（关于 AP-8）：投影确实不参与业务判断，读路径一律走 `load()`，R-1 的核心约束成立；负面：文档 §5.2-§5.4 的键表在实现里不存在，按文档写运维脚本（`HGETALL game:{id}:state`）会全部取空 |
| 3-2 | 投影写入语义：每个事件后更新投影（`redis-schema.md`:22 R-2、:495 cg-projection） | `InMemoryGameRepository.commit` 只在 `snapshotNeeded`（50 的倍数或终态）时写投影（`InMemoryGameRepository.java`:240-254） | 否 | P3 | 已留痕：`TASKS.md`:281「只由 commit 在生成快照时写入」 | 投影滞后到"没有消费者语义"（进程内、不持久、非快照点不更新）；当前无读取者所以无可见影响，但"重启后由事件流重建"的代价是每局全量重放 |
| 4-1 | 6 张业务表全建：`users`/`games`/`game_events`/`game_history`/`agent_memory`/`statistics`（`database-schema.md`:32-39 §2、:844 §13 自检、`DESIGN.md` §13） | `schema.sql` 实际只有 `schema_version`(25-29) / `users`(31-45) / `games`(47-71) / `game_events`(74-101) / `agent_memory`(111-152)；**缺 `game_history`、`statistics`** | 否 | P2 | 已留痕：`schema.sql`:8-9 注释、`database-schema.md`:672-676 §10.1「当前实现」、`TASKS.md`:283、:672、:673 | `database-schema.md` §7 的四个统计维度（GLOBAL/ROLE/AGENT/PLAYER）与 §3.5 的 `SUMMARY` 首屏数据不存在；分析/实验室统计改为按需重算（`AnalysisQueryService.java`:210-260、`LabQueryService.java`:105-163），没有跨局累计口径，失败后也无法从事件重放重建统计 |
| 4-2 | `games.origin` / `games.lab_batch_id` 记录真人局与实验室批次（`database-schema.md`:105-107、:281 扩展列理由、:576-600 §9.3 批次比较查询） | 列与索引建了（`schema.sql`:59-61、:71），但写入语句**不包含这两列**（`SqliteArchiveRepository.java`:130-140、:146-164；`TerminalGameArchiver.GameRecord` 也没有这两个字段，:143-156），因此恒为默认 `'ONLINE'`/`NULL` | 否 | P2 | **未留痕（新发现）**：`TASKS.md`:673 只留痕 statistics 未建，未提 origin/lab_batch_id 恒空 | `ix_games_lab_batch` 是死索引；§9.3 的"按批次比较"在 SQLite 侧永远返回空；实验室比较实际依赖 Redis 批次 JSON（`LabQueryService.java`:114-139），而 Redis 批次记录按 §15 不设 TTL 也不落 SQLite——**"胜率的分母"没有长期副本**，Redis 数据丢失后所有实验结论不可重建 |
| 4-3 | `agent_memory` 五类记忆（EPISODE/FACT/STRATEGY/REFLECTION/DECISION_TRACE）；`agent_id` 是 Agent 实例标识（`PlayerController.refId`）（`database-schema.md`:196-238 §3.6、:342-348 §6.1、`AGENTS.md`:137 区域） | 只有 `DECISION_TRACE` 有写入者（`SqliteDecisionTraceRepository.java`:82-131）；其余四类无写入者（`schema.sql`:106-107 自认）。且 `agent_id` 写入的是 `event.playerId()`（`SqliteDecisionTraceRepository.java`:98），不是 Agent 配置 id | 否 | P2 | 已留痕（部分）：四类无写入者见 `database-schema.md`:707、`TASKS.md`:708-709；**`agent_id` 语义偏差未留痕（新发现）** | 跨局 Agent 维度检索（§6.4「某 Agent 的历史表现」、`ix_agent_memory_agent`）实际按**座位号**聚合：同一 Agent 配置在不同座位/不同局的痕迹被拆开，不同 Agent 同座位号的痕迹被混在一起——AI 分析页与实验室按 Agent 归因的数字不可信；"Agent 记忆随重启恢复"仍不存在 |
| 4-4 | 决策痕迹幂等键 `source_event_id = sha1(type\|playerId\|version\|phase\|round\|occurredAt\|payloadJson)` 前 16 字节（`database-schema.md`:704 §10.1.1） | 实现用 **SHA-256** 且材料为 `wireType\|playerId\|version\|phase\|round\|occurredAt\|contentJson` 前 16 字节，前缀 `dt_`（`SqliteDecisionTraceRepository.java`:250-259）；类注释:26-27 又写成 sha1 与不含 playerId 的版本 | 否 | P3 | 未留痕（新发现） | 幂等性本身成立（内容寻址），但哈希算法/材料与文档、与自身注释三方不一致；运维按文档复算 `source_event_id` 会算出不同值，对账脚本失效 |
| 4-5 | 读连接与写连接分离：页面查询走只读连接，不与归档写锁争抢（`database-schema.md`:797 §12.1） | 只有一个写连接：`SqliteArchiveRepository.java`:44 单 `Connection`；`SqliteReplayArchive` 直接复用同一仓储（`RedisStorageConfiguration.java`:141-144 注释承认"再开一条只读连接只会多一个需要关闭的资源"）；回放/分析查询与归档写入共用连接 | 否 | P3 | 未留痕（新发现） | 长回放查询会占住唯一的写连接，归档 `BEGIN IMMEDIATE` 只能等 `busy_timeout=5000`（`SqliteSupport.java`:51）；高并发查询下归档延迟上涨甚至超时失败（下一轮重试，不丢数据） |
| 4-6 | `game_events` 列/唯一索引/二级索引与文档逐项一致，`INSERT OR IGNORE` 去重（`database-schema.md`:122-161 §3.4） | `schema.sql`:74-101 与文档 DDL 逐字段一致；`SqliteArchiveRepository.java`:37-42 固定 `INSERT OR IGNORE` | 是 | — | — | 正面：`ux_game_events_event_id` + `ux_game_events_game_version` 双唯一索引构成完整幂等 |
| 4-7 | `users` 表与凭据写入例外（`database-schema.md`:68-88 §3.2、:516 §8.3） | `schema.sql`:31-45 与文档一致；`SqliteUserRepository.java`:153-179 upsert（`{algorithm}${hash}` 格式见:171、:117-123 校验前缀） | 是 | — | — | 正面 |
| 4-8 | `embedding` / `embedding_model` 恒为 NULL（`database-schema.md`:406-413 §6.6、OP-D10） | 写入恒 `setNull`（`SqliteDecisionTraceRepository.java`:116-117），并有巡检方法 `reservedColumnsUsed()`（:179-189） | 是 | — | — | 正面 |
| 4-9 | `game_events` 只追加，不允许 UPDATE/DELETE（`database-schema.md`:25 D-3、AP-D4:773） | 全仓无 `UPDATE game_events` / `DELETE FROM game_events`（搜索零命中）；只有 `INSERT OR IGNORE` 与 SELECT | 是 | — | — | 正面 |
| 5-1 | 归档顺序不可交换：入库 → 核对 → 写完成标记 → 才缩 TTL（`database-schema.md`:650-675 §10.1、`AGENTS.md`:135） | `TerminalGameArchiver.java`:98-126 严格按此顺序；已有 `games` 行且水位追上时只补做 TTL 缩短（:92-96） | 是 | — | — | 正面 |
| 5-2 | 归档重试安全：失败只记日志、下一轮重做（`database-schema.md`:675） | `TerminalGameArchiver.java`:64-72 单局异常只 WARN 并继续下一局；`RedisStorageConfiguration.java`:195-201 扫描异常也只 WARN，定时任务不会因异常被取消 | 是 | — | — | 正面 |
| 6-1 | Redis 持久化假设与配置一致：AOF everysec + 命名卷 + 数据丢失窗口（`redis-schema.md`:883-894 §14.4、`deployment.md`:134 §5） | `docker-compose.yml`:18-30（`--appendonly yes --appendfsync everysec --save ""` + `wolfmind-redis-data:/data` + healthcheck）与文档一致；`docker-compose.yml`:59-60 backend 挂 `wolfmind-data:/app/data`，`WOLFMIND_SQLITE_PATH=/app/data/wolfmind.db`(:47) | 部分 | P3 | 未留痕（新发现） | 配置本身一致；但 `deploy/` 同一目录里 `Dockerfile.backend`:7-8 声称"基础镜像与工具版本都钉住、浮动标签会让昨天能构建今天不能"，而 `docker-compose.yml`:19 用 **`redis:8-alpine`**（浮动）、`Dockerfile.frontend`:12、:32 用 `node:22-alpine` / `nginx:1.27-alpine`——"部署可复现"弱于文档承诺，镜像内容随上游漂移 |
| 6-2 | 模型配置路径：`.env.example`:14-26 与 `deployment.md`:97 要求配 `WOLFMIND_LLM_PROVIDER`（外加 BASE_URL/API_KEY/MODEL），"三项都填齐才会真正调用模型（provider 是开关）" | `docker-compose.yml`:40-58 的 backend `environment` 只透传 `WOLFMIND_LLM_API_KEY` / `_BASE_URL` / `_MODEL` / `_THINKING` / `_INTERACTION_MODE` / `_MAX_TOKENS_PER_GAME`，**没有 `WOLFMIND_LLM_PROVIDER`**（也没有 `WOLFMIND_LLM_TIMEOUT_MS`）；而 `LlmSettingsLoader.java`:112-116 把 provider 空值判为 `LlmSettings.DISABLED` | 否 | **P1** | **未留痕（新发现）**：`TASKS.md`:612 只写"容器内实测（未配置模型）"，未记录 compose 丢键 | 按文档 `cp .env.example .env` 填好 provider/密钥后 `docker compose up -d`，容器内 provider 恒为空 → 所有 AI 座位走规则兜底、**模型永远不会被调用**，而 `/health` 报 `UP`（`WolfMindConfiguration.java`:67-78，llm required=false）、`/metrics` 的 `llmCost.calls` 恒为 0——"模型不工作"与"模型没花钱"在现有观测面上无法区分，排查方向会被引向供应商侧 |
| 6-3 | 配置项文档完备（`deployment.md`:92-104 §3 表） | 代码实际读取但文档未列的键：`wolfmind.archive.enabled` / `wolfmind.archive.interval-ms`（`RedisStorageConfiguration.java`:191-194）、`wolfmind.auth.pbkdf2-iterations`（`WolfMindConfiguration.java`:414-417）、`wolfmind.game.driver.enabled`(:526-527)、`wolfmind.game.speech-pacing.*`(:483-493)、`wolfmind.lab.tick-interval-ms`(:663)、`spring.data.redis.database` | 否 | P3 | 未留痕（新发现） | 运维无法从 deployment.md 得知归档开关与间隔（`WOLFMIND_ARCHIVE_ENABLED=false` 会让终局流保持 30 天 TTL，是有意义的运维杆）；"部署可复现"的可配置面不完整 |
| 6-4 | 重启后端后为 Redis 中运行中的对局补建 Agent（`deployment.md`:175-176 §6） | `RunningGameAgentRecovery.java`:41-72 + `WolfMindConfiguration.java`:375-383（ApplicationRunner）；只补建 Agent、不写事件（类注释:19-20） | 是 | — | — | 正面 |
| 7-1 | `required` 项不健康时 `/health` 返回 **503**（`api-spec.md`:570；`HealthIndicator.java`:6 注释同）；LLM 不健康不算不健康 | `HealthController.java`:34-55 直接返回 `Map`（`status` 写 `DOWN`），无 `ResponseEntity`/`@ResponseStatus`，**HTTP 恒 200**；`Dockerfile.backend`:65-66 的 HEALTHCHECK 是 `curl -fsS`（只把非 2xx 当失败）；测试只覆盖 UP 分支（`Phase5ApiContractTest.java`:124-145 断言 200、`RestApiContractTest.java`:59-61） | 否 | **P1** | **未留痕（新发现）**：`TASKS.md`:609 的验收只写"Redis 停掉才可能 DOWN"，未提 503；`deployment.md`:121 也只写 `→ DOWN`，与 api-spec 冲突 | Redis 运行时挂掉后：写请求全线失败，但容器 HEALTHCHECK 仍通过、编排层不会摘除或重启该实例，负载均衡继续把流量打到它；文档 §5 的 P0 告警"`status == DOWN`"只对看 body 的人/抓取器有效，对 docker 自身的健康判据无效。DOWN 分支**零测试覆盖** |
| 7-2 | 会话记录字段 `{userId, sessionId, secretHash, issuedAt, lastSeenAt, clientHint}`；TTL 12 小时、登录与心跳时刷新（`redis-schema.md`:451-468 §5.9、:701 §9.1） | `RedisSessionStore.java`:33-41 只写 4 个字段（无 `lastSeenAt`/`clientHint`），TTL 只在 `issue` 时设一次；`verify`（:44-59）不刷新 TTL；`SessionPort.java`:16-39 也无 touch/refresh 方法 | 否 | P3 | 未留痕（新发现）：`TASKS.md`:3094 只留痕"存 secret 的 SHA-256" | 会话是**绝对 12 小时**而不是滑动 12 小时：正在使用的用户也会在第 12 小时被登出，前端只会看到 401/4401 并跳回登录；文档承诺的"心跳续期"不存在 |
| 7-3 | `/metrics` 永远 200，输出 `{since, uptimeMillis, counters, gauges, timers, llmCost, llmBudget}`（`api-spec.md`:571、`deployment.md`:122、`observability.md`:33-53） | `MetricsController.java`:43-72 结构与字段逐项一致；`llmBudget` 来自 `LlmCostGuard.report()`（:92-101，含 `maxTokensPerGame/trackedGames/blockedGames`） | 是 | — | — | 正面 |
| 7-4 | 指标名目录与 observability 表一致（`observability.md`:58-70 §2.1） | `MetricsPort.Names`:96-112 的 12 个名字与文档逐字一致，`/metrics/names` 输出同一集合（`MetricsController.java`:101-117） | 是 | — | — | 正面 |

---

## 2. 未判定项

| 编号 | 未判定内容 | 为什么无法判定 | 需要什么证据 |
| --- | --- | --- | --- |
| U-1 | Redis 侧 AOF 是否真的生效（`redis-schema.md`:885-887 §14.4 声称实测 `CONFIG GET appendonly = yes`） | 本机 6379 **当前无监听**（`Test-NetConnection 127.0.0.1:6379` → False），`docker ps` 不可用（本会话无可用 docker 守护进程），无法起容器复现 | 在 `deploy/` 下 `docker compose up -d redis` 后执行 `redis-cli CONFIG GET appendonly appendfsync`、`INFO persistence`、`docker volume inspect wolfmind-redis-data` |
| U-2 | `deployment.md` §2/§5 与 `TASKS.md`:606-612 的"三容器 healthy / 容器内实测"是否仍可复现 | 同上，无 docker 环境；镜像构建依赖网络（Maven Central 403 问题在文档里自述） | `docker compose build && docker compose up -d && docker compose ps` 的真实输出 |
| U-3 | `AGENTS.md` §7"Redis-only active matches 的 acceptable data-loss/recovery behavior"是否已完整成文 | `redis-schema.md` §14.4 写了 AOF 窗口，但没有写"运行中对局永不归档 + 30 天后即永久丢失"这一条（见 2-4） | 所有者对 §14.4 的补充确认；或补一条"非终局流的数据丢失窗口"说明 |
| U-4 | 2-8 的潜伏缺陷可达性：`GAME_RECOVERED` 作为流尾事件会让归档永久失败（`TerminalGameArchiver.summarize` 在 :135-138 要求最后一条必须是 `GAME_FINISHED`/`GAME_ABORTED`，而 `RecoverGameApplicationService.java`:106-123 会给已终局对局追加 `GAME_RECOVERED`），该局将永不进 SQLite 并在 30 天后被清掉 | 当前 `RecoverGameApplicationService` 无生产调用者（见 2-8），无路由可触发，所以现在不可达 | 确认 `/admin/recover` 是否会开放；若会，需先修 `summarize`（允许尾部的 `GAME_RECOVERED`）或禁止对终局对局执行 recover |
| U-5 | 性能基线数字（`deployment.md`:143-161 §5.1）在当前代码上是否仍成立 | `PerformanceBenchmarkTest` 受 `@EnabledIfSystemProperty` 控制，本次报告未运行（Lead 运行的报告里它记为 1 skipped） | `mvn -o -pl server -am test -Dwolfmind.perf.enabled=true -Dwolfmind.perf.games=5 -Dtest=PerformanceBenchmarkTest` 的输出与 `backend/server/perf-report.json` |

---

## 3. 关键证据摘录

### 3.1 原子追加脚本确实存在，但比文档少了幂等与 lastTs（2-2 / 2-3 / 2-4 的对照面）

`RedisEventStore.java`:51-79（Lua 全文的核心）：

```lua
local tail = redis.call('XREVRANGE', stream, '+', '-', 'COUNT', 1)
local current = 0
if #tail > 0 then
  current = tonumber(string.match(tail[1][1], '^(%d+)%-'))
end
if current ~= expected then
  return {'VERSION_CONFLICT', tostring(current)}
end
...
  redis.call('XADD', stream, version .. '-0', unpack(args))
...
redis.call('EXPIRE', stream, ttl)
```

对比 `redis-schema.md`:191-258 §4.4 的脚本：文档版有 `KEYS[2] = game:{id}:lastTs`、`KEYS[3] = idem:...`，脚本内 `SET lastTs` 与 `HSET idemKey`；实现版只有 `KEYS[1]`，没有 lastTs、没有 idem 键。差异在类注释 `RedisEventStore.java`:33-35 与 `redis-schema.md`:878-879 §14.3 中被承认。

### 3.2 归档"先 SQLite 成功再缩 TTL"（5-1，正面证据）

`TerminalGameArchiver.java`:114-124：

```java
archive.archive(rows);
List<String> gaps = archive.versionGaps(gameId);
int count = archive.archivedCount(gameId);
if (!gaps.isEmpty() || count != streamVersion) {
    throw new IllegalStateException("归档核对失败：条数 " + count + "，流尾 " + streamVersion + "，空洞 " + gaps);
}
archive.upsertGame(record, now);
afterArchived.accept(gameId);   // → RedisEventStore.shortenAfterArchive
settled.add(gameId);
```

### 3.3 SQLite 实际建的表（4-1）

`schema.sql` 中 `CREATE TABLE` 只有 5 个：`schema_version`(:25)、`users`(:31)、`games`(:47)、`game_events`(:74)、`agent_memory`(:111)。`schema.sql`:8-9 自述：

> 仍未建：`game_history` / `statistics` 的写入者（cg-projection / cg-insight 的统计分支）未接入，表一旦建出来就会被误用……

### 3.4 `origin` / `lab_batch_id` 建了列却从不写入（4-2）

`schema.sql`:59-61 建列与默认值；`schema.sql`:71 建了 `ix_games_lab_batch`；但 `SqliteArchiveRepository.java`:130-140 的 INSERT 列表是：

```
(game_id, room_id, status, phase, round, version, player_count, role_composition,
 winner, finish_reason, started_at, finished_at, events_archived, updated_at)
```

`origin` 与 `lab_batch_id` 不在其中，`ON CONFLICT DO UPDATE` 也不更新它们。

### 3.5 `agent_id` 被写成 playerId（4-3）

`SqliteDecisionTraceRepository.java`:96-104：

```java
statement.setString(1, event.playerId());   // agent_id
statement.setString(2, event.playerId());   // player_id
statement.setNull(3, java.sql.Types.VARCHAR); // user_id
```

对比 `database-schema.md`:199「`agent_id`｜Agent 实例标识，对应 `PlayerController.refId`（AI）」。

### 3.6 Redis 流丢失后的恢复路径直接失败（2-5，P1）

`RecoverGameApplicationService.java`:77-82：

```java
if (!eventStore.exists(gameId)) {
    // 事件流不存在：既不是"事件已过期"（归档兜底路径尚未实现），也没有可恢复的状态。
    throw new GameException(
            DomainErrorCode.GAME_NOT_RUNNING,
            "对局 " + gameId + " 的事件流不存在，无法恢复（归档重建路径见 event-design.md §11）");
}
```

同时该类的装配情况（全仓搜索 `RecoverGameApplicationService` 只命中自身与测试）：没有 `@Bean`，没有 REST/WS 调用点。

### 3.7 `/health` 恒 200（7-1，P1）

`HealthController.java`:34-55（无状态码设置，返回 `Map`）：

```java
@GetMapping("/health")
Map<String, Object> health() {
    ...
    body.put("status", requiredDown ? "DOWN" : "UP");
    body.put("dependencies", dependencies);
    return body;
}
```

`Dockerfile.backend`:65-66：

```dockerfile
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1"]
```

`api-spec.md`:570：「`GET /health`｜依赖健康：Redis、SQLite、LLM。`required` 项不健康 → `503`」。

### 3.8 compose 丢掉了 provider（6-2，P1）

`docker-compose.yml`:40-58 的 `environment:` 键列表（逐字）：`WOLFMIND_STORAGE`、`SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`WOLFMIND_SQLITE_PATH`、`WOLFMIND_LLM_API_KEY`、`WOLFMIND_LLM_BASE_URL`、`WOLFMIND_LLM_MODEL`、`WOLFMIND_LLM_THINKING`、`WOLFMIND_LLM_INTERACTION_MODE`、`WOLFMIND_LLM_MAX_TOKENS_PER_GAME`、`WOLFMIND_LAB_MAX_CONCURRENT_GAMES`、`WOLFMIND_AGENT_MAX_CONCURRENT_TURNS`、`JAVA_TOOL_OPTIONS`。无 `WOLFMIND_LLM_PROVIDER`。

`LlmSettingsLoader.java`:112-116：

```java
public static LlmSettings toSettings(Map<String, String> values) {
    String provider = trim(values.get("provider"));
    if (provider.isEmpty()) {
        return LlmSettings.DISABLED;
    }
    ...
```

### 3.9 Redis 验收在最近一次构建里 0 执行（0-1，P1）

`target/surefire-reports` 原始产物（本次审计读取，文件时间 2026-10-13 15:51:36）：

```
TEST-...redis.RedisRestartAcceptanceTest.xml   tests="0" failures="0" errors="0" skipped="0"
TEST-...redis.RedisStorageIntegrationTest.xml  tests="0" failures="0" errors="0" skipped="0"
...RedisRestartAcceptanceTest.txt: Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
```

两个 XML 内**没有任何 `<testcase>` 元素**；机制在 `RedisTestSupport.java`:34-43：

```java
try {
    factory.getConnection().ping();
} catch (RuntimeException unavailable) {
    factory.destroy();
    if (Boolean.getBoolean("wolfmind.redis.required")) { throw new AssertionError(...); }
    Assumptions.abort("Redis 不可达（" + host() + ":" + port() + "），跳过 Redis 集成测试");
}
```

本次审计环境实测：`Test-NetConnection 127.0.0.1:6379` → `False`（无 Redis 监听）。同一批报告里 server 侧合计 `tests=644, failures=0, errors=0, skipped=1`（1 个 skipped 是性能基线）。结论：**Phase 2 的验收判据（重启后状态等价、Redis 与内存实现契约等价）在当前绿色构建中没有任何执行证据**，而 `TASKS.md`:603 的"674 通过、0 失败、1 跳过"读起来像全部覆盖。
