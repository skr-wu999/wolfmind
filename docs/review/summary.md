# WolfMind 全链路设计符合性审计 — 汇总结论

审计日期：2026-10-13。审计方式：4 条链路只读审计（AI / 领域 / 前端 / 基础设施）+ Lead 对
关键路径（接口层与提交/发布顺序）的独立复核。四条链路报告见本目录同名文件。

本次审计**没有修改任何源码或设计文档**。

后续修复记录（2026-10-13）：P0-1 的 WebSocket 空地址兜底已修复，新增 9 项配置回归测试，
前端 388 项单元测试、类型检查及 Docker 同配置的静态生产构建通过。已有镜像需重建才生效。
下文保留原审计时的证据；本记录不表示其他条目已修复，详情见 TASKS.md。

后续清理记录（2026-10-13）：A2 的旧接口层 GameDriver、注册项和专用投票池已删除，
发言停顿仅保留应用层类型；归档、分析、实验室与重启测试已迁移。正式事件/轮询调度均走应用层。
此项不表示 A3 中旧 AdvancePhaseApplicationService 的接线或其他审计问题已处理。

后续清理记录（2026-10-13）：A3 的闲置 AdvancePhaseApplicationService 已删除，旧测试迁到
正式 TimeoutApplicationService，阶段推进和超时处理仅保留当前调度器使用的用例。
人工强制推进接口未开放，本次未新增该能力。下文继续保留原审计证据。

后续 B1 验证记录（2026-10-13）：已补双引擎同种子整局逐事件对照，四种场景、三个种子，
共十二组，比较全部事件字段、终态与模型调用次数。测试发现并修复了图引擎在修复提案后
重新检查旧输出、额外调用一次模型的问题。运行中后端的认证/房间/WebSocket 通道另作实测；
整局对照使用固定模型替身，不宣称已进行真实模型对局或胜率比较。

后续 B2 修复记录（2026-10-13）：房间开局/恢复与实验室创建模型座位时，
统一通过 TurnBudget.defaultFor 选择原生模式 4 次、JSON 模式 2 次；默认装配也复用该入口。
修复前房间与实验室两项原生预算回归测试均失败（实际 2、期望 4），修复后通过。
显式座位预算保留，查询/两阶段/修复共用总上限，超时和整局费用限制不变；详情见 TASKS.md。

后续保留期与 B3 修复记录（2026-10-14）：未归档对局事件/快照取消自动过期，
启动迁移旧 TTL；完整归档后仍最多保留 7 天。Redis 模式实际仓储加载已接入 SQLite
终局归档校验/全量重放/原子安装路径，保留原始事件，不重跑模型或发布历史行动。
真实 Redis、SQLite、并发、中断和 Spring HTTP 自动恢复测试通过；详情见 TASKS.md。
机器人记忆写入与持久化不在本次范围，运行中未归档对局仍需 Redis 持久化/备份。

后续 B4 修复记录（2026-10-14）：四个消费组已接入，各组独立目录、位点、消费者身份与租约。
cg-projection 与定时补漏共用归档核验服务；Redis 广播替换进程内直接通知；cg-insight 事务维护终局索引和统计，
单局明细/累计重算幂等，晚到痕迹有补算及定时核对。真实 Redis/SQLite/HTTP-WebSocket、重启接管、
事务回滚、重复、坏事件隔离和整局调度测试通过。仍未实现机器人记忆生成，也未归档全部间隔快照。

后续 B5 修复记录（2026-10-14）：统一终局收尾，拒绝新回合并在最后一个已接收回合返回后释放成本账本。
轨迹归档成功释放内存副本，历史分析仍从 SQLite 读取；归档/补算通知失败有每 30 秒重试。
纯内存终局分析及待补存缓存默认最近 20 局、最长 1 小时，活动局不被此策略淘汰。
长期故障超过上限可能丢失未补存摘要，有 WARN/指标；本项不改变对局事实保留，也不实现机器人记忆生成。
验证证据及实际测试结果见 TASKS.md；下文保留原审计证据。

后续 C1/C2 修复记录（2026-10-14）：房间规则创建/开局统一解码校验，部分覆盖默认值并固化进实际对局和开局事件。
投票 SKIP 与 VOTE 共用单次提交检查，主动 SKIP 遵守弃权开关；外部 TIMEOUT 被拒绝，服务端到期仅补未投票者。
补修行动权限投影中写死的可弃票标记，按真实规则返回。历史事件解码/已开局规则保持原状；验收见 TASKS.md。

后续 D3/D4/E1 修复记录（2026-10-14）：必需健康项异常返回 HTTP 503，LLM 可选项异常保持 HTTP 200，JSON 格式不变。
Compose 透传模型供应商与单次超时，默认 none / 30000；测试实际 Compose 解析结果到模型配置读取。
Redis 不可达的普通测试改为逐项 skipped，强制验收仍失败；新增空资源安全清理，避免跳过后误报错误。
离线/强制离线/真实 Redis 的验证证据见 TASKS.md；下文保留原审计证据。

---

## 0. 一句话结论

**项目的分层骨架、领域确定性、事件重放等价、可见性隔离这四件最难的事做到了，而且有真测试；
但「设计里承诺的运行时」与「生产实际跑的运行时」在两个地方对不上——回合调度没走事件驱动
而是接口层轮询，工作流引擎没有走 LangGraph4j 而是顺序引擎——这两处 TASKS.md 都没有留痕。**

按偏差的性质分四类：

| 类别 | 含义 | 例子 |
| --- | --- | --- |
| **A. 承诺的运行时不存在** | 文档/验收句说 A 路，生产实际走 B 路 | 回合调度、LangGraph4j、消费组、SQLite 重建归档 |
| **B. 静默的契约偏差** | 接口收下了参数但不生效，或规则与文档不一致 | 房间 `config.rules` 被丢弃、FUNCTION_CALLING 的 4 次预算 |
| **C. 部署链路断裂** | 按文档部署起来的系统，关键功能不可用 | 前端 WS 地址为空、compose 丢 `LLM_PROVIDER`、`/health` 恒 200 |
| **D. 缺口与死代码** | 文档有、代码没有；或代码写了但没人调用 | `/admin/*`、`statistics`、`RecoverGameApplicationService` |

---

## 1. 严重度汇总

| 链路 | P0 | P1 | P2 | P3 | 符合项 |
| --- | --- | --- | --- | --- | --- |
| 基础设施 / 持久化 / 部署 | 0 | 4 | 8 | 11 | 13 |
| AI 子系统 | 0 | 3 | 5 | 8 | — |
| 前端 / 协议 | 1 | 2 | 8 | 14 | — |
| 领域 / 规则 / 事件 | 0 | 2 | 8 | 16 | 多条（见报告附录 B） |
| **Lead 复核（接口层与写路径）** | 0 | **3** | 0 | 0 | 2 |
| **合计** | **1** | **14** | **29** | **49** | — |

「留痕」的含义：`TASKS.md` / `docs/**` 的「与批准基线的偏差」表里**主动写过**这件事。
本次审计最有价值的产出是**未留痕**的那些条目——14 条 P1 里有 10 条从未被记录。

---

## 2. P0（1 条）

### P0-1 按文档部署的前端镜像里，实时链路整体不可用

- **设计**：`AGENTS.md` §9「WebSocket handles live room/game events」；
  `deploy/Dockerfile.frontend:20-26` 的注释声称「空 base URL 让请求走当前站点，Nginx 的
  `/api` 与 `/ws` 反代」。
- **实现**：`Dockerfile.frontend:22` 显式 `ARG VITE_WS_URL=""`，再 `ENV VITE_WS_URL=${VITE_WS_URL}`
  （第 25 行）。`frontend/src/api/config.ts:10` 的兜底是 `??`，**对空串不生效**：

  ```ts
  export const WS_URL: string = import.meta.env.VITE_WS_URL ?? deriveWsUrl(API_BASE_URL)
  ```

  Vite 把 `VITE_WS_URL=""` 内联为空串，于是 `WS_URL === ""`。
  `frontend/src/api/gameSocket.ts:80` 执行 `new WebSocket("")` 抛 `SyntaxError`，
  被第 81-85 行的 `catch` 静默吞掉并 `setState('closed')`——**没有任何用户可见错误**。
- **实证**：本机已提交的构建产物 `frontend/dist/assets/index-De--xqcu.js` 中，
  WS 地址常量被编译成 `Al="",Of=wf(Al)`（`deriveWsUrl` 根本没有被调用），
  调用点是 `new WebSocket(Of)`。即当前 `dist` 就是坏的那一份。
- **影响**：`docker compose up` 起来的生产环境里，大厅/等待房/对局页全部收不到实时帧，
  等待房不刷新、对局页停在开局快照、流式发言不出现。同一台机器上按 TASKS.md 的
  「浏览器整局验收」是用 Vite dev 服务器做的，所以这条路径**从未被容器验收覆盖**。
- **未留痕**：TASKS.md 无任何记录。
- **最小修法**：`config.ts` 改用 `|| ` 或显式判空；或删掉 `Dockerfile.frontend` 的
  `VITE_WS_URL` 那一行（同源部署本来就该走推导）。

---

## 3. P1（14 条，按影响排序）

### 3.1 架构级：写路径与派发路径不在设计指定的层

| 编号 | 设计要求（出处） | 实现 | 影响 | 留痕 |
| --- | --- | --- | --- | --- |
| **A1** | 回合调度器「由 `cg-agent` 消费 `TURN_GRANTED` 触发，**事件驱动，不轮询**」（`system-design.md:62`、`agent-runtime.md` §3） | 生产没有任何消费组；`WolfMindConfiguration.java:537` 用 `scheduleWithFixedDelay` **每 200 ms 轮询** `listGames("RUNNING")`，再在 `GameDriver.step` 里用 `Game.snapshot()` **反推**谁该行动 | 「事件驱动」的运行时不存在；`publish("cg-agent", …)` 在 Redis/内存两种模式下都无人 `XREADGROUP`/消费，是一句空承诺。轮询在单实例下能跑，但多实例会重复派发（靠事件存储版本冲突兜底） | **未留痕** |
| **A2** | Interface 层职责只有「REST API、WebSocket、参数转换、鉴权、DTO 映射」，**禁止直接依赖 Domain 内部对象做规则判断**；超时看护的「判断是否到期」**归应用层**（`ddd-design.md:404`、`:306-313`） | 驱动与回合派发整块实现放在 `com.wolfmind.interfaces.rest.GameDriver`（633 行）：`actorToAct` 逐阶段推导「谁该行动」、`phaseDeadlinePassed` 判断是否到期、`skipTimedOut`/`advancePhase` 提交领域行动、`persist` 直接 `repository.commit` | 规则与编排逻辑落在接口层，与 `ddd-design.md §5.2` 的包结构（该层只列 Controller）和 §5.1 的禁止项都冲突。任何「接口层只做转换」的后续重构都要先搬走这个类 | **未留痕**（TASKS.md 只把它当实现细节记述） |
| **A3** | `AdvancePhaseApplicationService` 是「阶段推进用例，由超时看护与运维触发」（`ddd-design.md:419-421` 包结构明确列出） | 该类**在生产零调用者**（全仓只被自己的测试实例化，`WolfMindConfiguration` 无对应 `@Bean`）；生产阶段推进走 `GameDriver.advancePhase` 的**复制品**：同样是 `skipTimedOut` 然后 `game.advance(ctx)` | 同一套用例逻辑存在两份实现（接口层 + 应用层），且生产用的是没有单写者锁、不调 `EventPublication` 的那一份。`RecoverGameApplicationService` 同样是零调用者（见 D2） | 部分（`/admin/*` 未注册路由有留痕，但没写「用例根本没接线」） |

**A1/A2/A3 的最小修法**（不需要新概念）：把 `GameDriver` 整体移到
`com.wolfmind.application.game`（改包名 + 注入），让 `advancePhase`/`skipTimedOut`
委托给已经写好的 `AdvancePhaseApplicationService`，并在 `persist` 里改调
`EventPublication.publish`，使驱动产出的 `TURN_GRANTED` 与玩家行动走同一条发布路径。

### 3.2 承诺存在、实现不存在的运行时能力

| 编号 | 设计要求（出处） | 实现 | 影响 | 留痕 |
| --- | --- | --- | --- | --- |
| **B1** | 「LangGraph4j（工作流执行）」是 Agent Runtime 的执行接口（`agent-runtime.md:19,28,52,100,239`；`AGENTS.md` §3 技术基线）；`TASKS.md:303,312` 更进一步写「**九人局跑在 LangGraph4j 图引擎上**，`Phase3AgentAssembly` 默认注入」 | `WolfMindConfiguration.java:253-265` 给 `AgentRuntimeImpl` 传 **`workflowEngineFactory = null`**（第 262 行），`AgentWorkflow.java:382-386` 因此回退 `new SequentialWorkflowEngine(...)`；唯一注入 LangGraph4j 的 `Phase3AgentAssembly` 在 `main` **零调用者**（只有测试用），九人局 E2E `NineAiGameRunner` 也走顺序引擎 | 生产从未执行 LangGraph4j 代码。**图引擎本身不是没测**：`LangGraph4jWorkflowEngineTest` 有 8 个用例逐项对比两条路径（含真实节点表跑完一个回合、模型不可用、网关拒绝、空候选、校验修复、泄流重写、最多提交一次），所以「图能跑对一回合」是有证据的。缺的是**整局**：没有「同 seed 同替身模型、两引擎各跑完九人局再比对事件流」的对照，而 `TASKS.md:312` 的验收句「九人局跑在 LangGraph4j 图引擎上」在 E2E 里也不成立（`NineAiGameRunner` 同样走顺序引擎） | **未留痕**（且与留痕句相反） |
| **B2** | FUNCTION_CALLING 为默认模式时每回合 **4 次模型调用 / 6 次工具**（`agent-runtime.md` §9、`docs/ai/function-calling-migration.md` §5、`TASKS.md:22`） | 生产两条建座位路径都复制规则基线的 `TurnBudget.DEFAULT`（**2 次**）：`StartRoomGameApplicationService.java:226-242`、`LabBatchRunner.java:300`；唯一给 4 的 `Phase3AgentAssembly.java:157-159` 生产未用 | 原生工具调用的「最多两轮授权查询 + 查询预留提交与兜底额度」在实际部署里额度不足：查询循环与「提交被拒后换候选修复」都会更快耗尽预算，行为退化为兜底。离线测试用的是构造出来的 4，所以测不出 | **未留痕** |
| **B3** | Redis 事件流丢失后可由 SQLite `game_events` **永久归档重建**（`event-design.md` §11、`database-schema.md` §10.3 与 §13 自检） | `RecoverGameApplicationService.java:77-82`：流不存在直接抛 `GAME_NOT_RUNNING`，注释自认「归档兜底路径尚未实现」；文件内也不含 `ReplayArchivePort` | 「Redis 键可以丢、可重建」这条 Phase 2 论证为假。已归档的**终局**对局仍可回放，但按 §10.3 处置流丢失事故会直接失败 | **未留痕** |
| **B4** | 四个消费组 `cg-projection`/`cg-broadcast`/`cg-agent`/`cg-insight`、ACK 时机、DLQ、`XAUTOCLAIM`（`redis-schema.md` §6、`event-design.md` §9） | 全仓无 `XGROUP`/`XREADGROUP`/`XACK`/`XTRIM`/`XDEL`；发布走进程内 `InMemoryEventPublisher`（Redis 模式也一样，`RedisStorageConfiguration.java:40-41` 自己承认） | 投递不持久；多实例会重复/漏推；无 DLQ。单实例可用 | 已留痕 |
| **B5** | `agent-runtime.md` §3 的 Agent 生命周期：`createAgent` / `destroyAgent` | `AIControllerAdapter.onGameFinished` / `onSeatTakenOverByHuman` 全仓**零调用**；`AgentRegistry`、`LlmCostGuard.forget`、`InMemoryAgentTrace.clearGame`、`MemoryPort.clearShortTerm` 在长跑实例上只增不减 | 长跑实例（实验室连续批次）Agent 实例与轨迹只增不减，内存随对局数线性增长；文档描述的生命周期语义不存在 | **未留痕** |

### 3.3 静默的契约偏差（接口收下了，但不生效）

| 编号 | 设计要求（出处） | 实现 | 影响 | 留痕 |
| --- | --- | --- | --- | --- |
| **C1** | `POST /rooms` 的 `config.rules`「会被复制进 `GAME_STARTED.rules`」（`api-spec.md:152,162`） | `StartRoomGameApplicationService.java:118` 写死 `GameRules rules = GameRules.DEFAULT;`；`RoomController.java:96` 接收并回显 config | 调用方拿到 `201`、**无错无日志**，对局却按另一套规则跑。任何「房间自定义时限/胜负条件」的调用都是静默无效 | **未留痕** |
| **C2** | `INV-5`：同一回合窗口内同一玩家对同一动作类型只接受一次；`domain-model.md` §5.4「投票提交后不可改」 | `ActionValidator.java:124` 允许 `SkipAction` 进入任何 `acceptsActions()` 阶段（含 `DAY_VOTE`），而 `validateNotYetSubmitted`（:163-197）**没有 `SkipAction` 分支**；`Game.handleSkip` :482 在 `DAY_VOTE` 调用 `recordVote(..., Vote.abstain(...))`，`Game.java:1322` 直接 `votes.put(...)` 覆盖 | 「先投票、再 `SKIP`」产生第二条 `PLAYER_VOTED`，把已投票改成弃权，并绕过 `abstainAllowed=false`。REST 是唯一写入口且不查 `ActionPermissions`；`ActionPermissions` 在 `DAY_VOTE` 只暴露 `VOTE`，所以前端不会给出这个按钮，但手工构造请求可以 | **未留痕**，且无任何测试覆盖这个组合 |

### 3.4 部署链路断裂（按文档部署即失败）

| 编号 | 设计要求（出处） | 实现 | 影响 | 留痕 |
| --- | --- | --- | --- | --- |
| **D3** | `required` 依赖不健康时 `/health` 返回 **503**（`api-spec.md:570`、`HealthIndicator.java:6` 注释同） | `HealthController.java:34-55` 直接返回 `Map`（只把 body 的 `status` 写成 `DOWN`），**HTTP 恒 200**；`Dockerfile.backend:65-66` 的 HEALTHCHECK 是 `curl -fsS`（只认非 2xx）；DOWN 分支**零测试** | Redis 运行时挂掉后：写请求全线失败，但容器 HEALTHCHECK 仍通过、编排层不会摘除或重启它，负载均衡继续打流量。文档里「`status == DOWN` 告警」只对看 body 的人有效 | **未留痕** |
| **D4** | `deploy/.env.example:17` 与 `deployment.md` §3 要求填 `WOLFMIND_LLM_PROVIDER`，并明写「provider 是开关」 | `docker-compose.yml:40-58` 的 backend `environment` 透传了 API_KEY / BASE_URL / MODEL / THINKING / INTERACTION_MODE，**唯独没有 `WOLFMIND_LLM_PROVIDER`**（也没有 `_TIMEOUT_MS`）；`LlmSettingsLoader.java:112-116` 把 provider 空值判为 `LlmSettings.DISABLED` | 按文档 `cp .env.example .env` 填好后，容器内模型**永不调用**、全场规则兜底，而 `/health` 报 `UP`、`/metrics` 的 `llmCost.calls` 恒 0——「模型不工作」与「没花钱」在观测面上无法区分 | **未留痕** |

### 3.5 验收证据不成立

| 编号 | 设计要求（出处） | 实现 | 影响 | 留痕 |
| --- | --- | --- | --- | --- |
| **E1** | Phase 2 验收必须由真实执行给出证据：重启后状态等价、Redis 与内存实现契约等价（`AGENTS.md` §11、`TASKS.md:255`） | 最近一次全量构建的 surefire 报告里 `RedisRestartAcceptanceTest` / `RedisStorageIntegrationTest` 均为 `tests="0"`、无 `<testcase>`；原因是 `RedisTestSupport.java:38-43` 在 Redis 不可达时 `Assumptions.abort`，surefire 记成 `Tests run: 0, Skipped: 0` | **绿色构建里静默少了 13 个用例，报告上看不出「被跳过」**。任何没有 Redis 的 CI 都会得到同样「全绿」的结果。Lead 本次实跑复现了这一点（server 644 通过 / 1 跳过，其中 Redis 两类各 0 条） | 部分（TASKS.md 声称已有自动化验收，与实际执行状态不符） |

---

## 4. 值得肯定的部分（这些是真做到了的）

审计不只找问题。以下结论有代码与测试双重支撑，且不是「文档抄代码」：

1. **领域规则与文档 100% 对齐**：`domain-model.md` §3 的 18 项规则参数默认值与
   `GameRules.DEFAULT` 逐项一致；TASKS.md 记录过的 6 次规则变更
   （60 秒回合、女巫首夜自救、屠边胜负、`dayShotLastWords`、夜间猎人先公布死讯、平票去偏置）
   **文档侧全部同步改了**——不存在「只改代码不改文档」。
2. **阶段转换表逐条一致**，而且是**从文档解析**来校验的（`PhaseTransitionTableTest`），
   这是本仓最扎实的一处机制性保障。24 条事件的 `visibility` 与 `event-design.md` §7.2 逐条一致。
3. **先提交后发布的顺序是对的**：`SubmitActionApplicationService.java:114-126`
   严格 `commit` → `record` 幂等 → `EventPublication.publish`；`EventPublication.java:32-43`
   逐消费组隔离异常。`AGENTS.md` §7 的「never broadcast before durable persist」成立。
4. **Redis 原子追加是真的**：`RedisEventStore.java:51-79` 单个 Lua 脚本内做
   `XREVRANGE` 版本前置校验 + 连续 `XADD {version}-0` + `EXPIRE`，
   条目 ID 显式而非 `XADD *`；锁过期后的双写会被版本校验拒绝。
5. **归档顺序与幂等是真的**：`TerminalGameArchiver` 严格「入库 → 核对条数与空洞 → 写 `games` 行 →
   才缩 TTL」；`schema.sql:96-97` 的 `(game_id, version)` 与 `event_id` 双唯一索引
   让重试天然安全。
6. **领域零外部依赖**：逐文件扫描确认 `engine` 无 Spring / Redis / LLM / WebSocket / 时钟 /
   随机数引用，`engine/pom.xml` 只有 junit（test）。
7. **跨座位私有信息泄漏：确认 0 条**（AI 链路静态审计结论）。
8. **编码纪律成立**：517 个源/文档文件 UTF-8 BOM **0**。

---

## 5. 依赖规则测试（DEP-1..7）本身的两个盲区

审计 D 指出，`DependencyRulesTest` 是架构的「长期保障」（`ddd-design.md` §5.3 原话），
但有两处它证明不了自己声称的内容：

- **DEP-5 空转**：白名单只有 `org.xerial`（server 版另加 `io.lettuce`/`spring-data-redis`），
  而 `server/pom.xml:30-86` 的真实第三方坐标含 `spring-boot-starter-web/websocket/data-redis`、
  `langchain4j` ×3、`langgraph4j`。**按真实坐标集合，`WolfMindApplication`、`interfaces.rest.*`
  等 14 个文件都在 `infrastructure` 之外引入了第三方 SDK**——规则没拦，因为规则认不出它们。
- **DEP-6 空转**：`domain.game.repository` 包**根本不存在**，断言恒真。
- **DEP-2 有漏**：禁词表没有 `org.springframework`、`jakarta`、`java.net.http`、
  `LocalDateTime.now`、`ThreadLocalRandom`。

修法很小：把第三方坐标从 `pom.xml` 里读出来当白名单来源，而不是手写一个 token 列表；
或改用 ArchUnit（但那会引入新依赖，需要所有者裁决）。

---

## 6. 建议的处理顺序

### 第一批：按文档部署就能用（4 项，都是小改）

1. `frontend/src/api/config.ts` 的空串兜底（P0-1）。
2. `deploy/docker-compose.yml` 补 `WOLFMIND_LLM_PROVIDER`（+ `WOLFMIND_LLM_TIMEOUT_MS`）（D4）。
3. `HealthController` 改 `ResponseEntity`，`required` 不健康返回 503，并补 DOWN 分支测试（D3）。
4. `RedisTestSupport` 的静默跳过改为**显式失败或显式 `@Disabled` + 报告可见**，
   让「没有 Redis」这件事在绿色构建里看得见（E1）。

### 第二批：把承诺的运行时接上（3 项，改动中等，需所有者确认方向）

5. `WolfMindConfiguration` 不再传 `null`，让生产走 LangGraph4j（B1）；
   若决定**不**用图引擎，则应改 `AGENTS.md` §3、`agent-runtime.md`、`TASKS.md:303,312`
   的表述——两种处理都可以，但「文档说 A、跑的是 B」不行。
6. `GameDriver` 搬进 `application.game`，并把 `advancePhase`/`skipTimedOut` 委托给
   已有的 `AdvancePhaseApplicationService`（A1/A2/A3）。
7. 建座位的两条路径改用 FUNCTION_CALLING 的 4 次预算（B2）。

### 第三批：领域契约收紧（需所有者批准，因为动规则边界）

8. `DAY_VOTE` 的 `SkipAction` 加守卫：`validateNotYetSubmitted` 增加 `SkipAction` 分支，
   `DAY_VOTE` 下若 `votes` 已有该座位则拒绝，或干脆只允许超时跳过（C2）。
9. `config.rules` 要么真正生效，要么在 `api-spec.md` 里删掉该承诺并对非法配置返回 400（C1）。

### 第四批：文档与测试口径

10. `TASKS.md` 补写所有「未留痕」条目到偏差表；把「已验收」的表述限定在**实际执行过的路径**上
    （容器内实测 vs Vite dev、图引擎 vs 顺序引擎、Redis 用例是否真的跑了）。
11. `DependencyRulesTest` 的白名单改成从 `pom.xml` 派生（§5）。
12. `/admin/*`、`statistics`、`game_history`、`agent_memory` 四类记忆、SQLite 重建归档
    （B3）——按 `TASKS.md` 已有的「尚未解决」清单继续挂账，不要写成已完成。

---

## 7. 需要所有者裁决的三个问题

1. **回合派发要不要真的做成事件驱动？** 现状（接口层 200 ms 轮询 + 状态反推）在单实例下可用，
   但 `system-design.md:62` 明写「事件驱动，不轮询」。改与不改都要改文档——现在两边不一致。
2. **LangGraph4j 是必须的还是可选的？** 它是 `AGENTS.md` §3 的技术基线之一，
   但当前生产链路完全没用到。要么接上，要么把基线改成「LangChain4j 必需、LangGraph4j 可选」。
3. **`/health` 的判据是什么？** `api-spec` 要求 503、`deployment.md` 只写 `→ DOWN`、
   实现恒 200。三者取一，因为容器编排是唯一会消费这个信号的东西。

---

## 8. 审计方法与本报告的边界

- 四条链路各自独立审计，报告的每条结论都要求「文档出处 + `file:line`」两侧。
- Lead 复核了其中影响最大的 12 条（P0-1、A1-A3、B1-B3、C1-C2、D3-D4、E1），
  全部在自己的独立读取中复现。
- **未验证**的部分：任何需要 Redis / Docker / 真实模型密钥才能复现的声明
  （AOF 是否生效、三容器 healthy、容器内端到端、性能基线数字、真实模型对局）。
  这些在各自报告的「未判定项」里逐条写明了「需要什么证据」。
- 本审计**没有运行**任何写操作、没有重启服务、没有改任何配置文件。
