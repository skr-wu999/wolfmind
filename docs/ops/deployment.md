# WolfMind 部署（Docker Compose）

本文回答三件事：**怎么起、起来之后看什么、这套东西的运维边界在哪**。
`AGENTS.md` §11 的 Phase 5 验收要求"部署可复现"与"运维限制成文"，本文就是这两条的证据载体。

范围：单机 Docker Compose（`DESIGN.md` §3 的部署基线）。**这不是生产高可用方案**——
它没有多副本、没有跨机存储、没有 TLS 终止。把这些说清楚，比声称"已可上线"更有用。

---

## 1. 组件与拓扑

```
浏览器 ──HTTP/WS──> frontend (Nginx :80)
                        │  /api/*  → backend:8080
                        │  /ws      → backend:8080（协议升级）
                        ▼
                   backend (Spring Boot :8080)
                        │
        ┌───────────────┴───────────────┐
        ▼                               ▼
   redis:6379                     SQLite 卷 /app/data
   （事件流 / 快照 / 锁 /            （账号 / 归档事件 /
     幂等 / 房间索引 / 会话 /           决策痕迹 agent_memory）
     实验室配置与批次）
```

**只有前端对外暴露端口**。后端不发布端口是有意的：多一条直连路径就多一处需要单独考虑鉴权与 CORS 的入口，
而"同源"是这套部署里最简单也最不容易出错的性质。

| 卷 | 内容 | 丢失后果 |
| --- | --- | --- |
| `wolfmind-data` | SQLite（账号、归档事件、决策痕迹） | 账号与历史全部丢失；进行中的对局仍在 Redis 里，但账号没了就登不回来 |
| `wolfmind-redis-data` | Redis AOF（运行中的对局、会话、实验室配置与批次） | 进行中的对局丢失；已完成且已归档的对局不受影响 |

---

## 2. 起服务

```bash
cd deploy
cp .env.example .env          # 按需填模型密钥与镜像源；不填也能起（规则兜底模式）
docker compose up -d --build
docker compose ps
curl -fsS http://localhost:8080/api/v1/health
curl -fsS http://localhost:8080/api/v1/metrics
```

打开 `http://localhost:8080` 即进入首页。

### 2.1 受限网络下构建失败（403）

镜像构建需要访问 Maven 仓库。部分网络对 `repo.maven.apache.org` 返回 403，症状是
`backend build` 阶段报 `Non-resolvable import POM ... status code: 403`。
在 `deploy/.env` 里指定一个可达的镜像源即可：

```bash
WOLFMIND_MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
```

**这是构建期参数**，改完必须 `docker compose build`（`--no-cache` 不必，但改了参数会触发缓存失效）。

### 2.2 前端后端地址也是构建期参数

`VITE_API_BASE_URL` / `VITE_WS_URL` / `VITE_USE_REAL_API` 会被 Vite **内联进静态产物**，
因此改 `environment:` 无效，必须重新构建前端镜像。本仓库的默认值是**同源 + 真实接口**
（`VITE_API_BASE_URL=""`、`VITE_USE_REAL_API=true`），这是容器部署唯一正确的组合：

- 留空 → 请求走当前站点，由 Nginx 反代；
- `false` → 页面会用 mock 数据，症状是"部署好了但所有对局都看不见"。

`VITE_WS_URL` 未配置、空串或只有空白时，按 API 地址推导；API 地址也为空时，
使用当前页面的主机/端口与 `/ws` 路径，HTTPS 页面使用 `wss`、HTTP 页面使用 `ws`。
显式 WebSocket 地址优先。修复或修改该配置后，从仓库根目录重新构建并创建前端容器：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --no-deps --build frontend
```

### 2.3 端口被占用

宿主机的 8080 常被本地开发环境（IDEA 里直接跑的 backend）占着，症状是
`ports are not available: ... bind: Only one usage of each socket address`，
而**后端与 Redis 仍然起来了**（只有前端失败）——容易误判成"前端镜像有问题"。
换一个宿主端口即可：

```bash
WOLFMIND_HTTP_PORT=18081 docker compose up -d
```

容器之间用的是服务名与容器端口，不受这个映射影响。

---

## 3. 配置项

Spring Boot 的 relaxed binding 会把 `WOLFMIND_STORAGE` 映射到 `wolfmind.storage`。
唯一需要注意的例外是实验室并发上限（见下表脚注）。

| 环境变量 | 默认 | 作用 |
| --- | --- | --- |
| `WOLFMIND_STORAGE` | `memory` | `redis` 时启用 Redis 事件流 + SQLite 归档；**容器部署必须设为 `redis`** |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | `localhost` / `6379` | Redis 地址 |
| `WOLFMIND_SQLITE_PATH` | `data/wolfmind.db` | SQLite 文件路径（容器里应指向挂载卷） |
| `WOLFMIND_LLM_PROVIDER` | Compose 为 `none` | `openai` / `openai-compatible` / `deepseek` / `ollama`；**空或 none = 不调用模型** |
| `WOLFMIND_LLM_BASE_URL` / `_API_KEY` / `_MODEL` | 空 | 三者与 provider 一起构成"已配置" |
| `WOLFMIND_LLM_TIMEOUT_MS` | `30000` | 单次模型调用超时 |
| `WOLFMIND_LLM_THINKING` | 空 | 推理模式，取值见 [agent-runtime.md §13](../ai/agent-runtime.md) |
| `WOLFMIND_LLM_MAX_TOKENS_PER_GAME` | `0` | 单局 token 上限，`0` = 不限；超限后该座位转规则兜底 |
| `WOLFMIND_LAB_MAX_CONCURRENT_GAMES` | `8` | 实验室全局并发局数 |
| `WOLFMIND_AGENT_MAX_CONCURRENT_TURNS` | `8` | 投票阶段的并发派发线程数 |
| `WOLFMIND_HTTP_PORT` | `8080` | 前端对外端口（仅 compose 使用） |

**模型成本默认不设上限。** 这是刻意的：一个没被验证过的默认上限会在真实对局里悄悄改变行为
（AI 中途全部降级成规则基线），而那种变化看起来像"模型变笨了"。要省钱必须显式配置，
并且能在 `/api/v1/metrics` 的 `llmCost` 与 `llmBudget` 上看到它是否生效。

> 脚注：`lab.maxConcurrentGames`（api-spec.md §8 的写法）对应的环境变量是
> `LAB_MAXCONCURRENTGAMES`——Spring 对环境变量做的是键名转换而不是模糊匹配。
> 代码同时接受 `WOLFMIND_LAB_MAX_CONCURRENT_GAMES`（`wolfmind.lab.max-concurrent-games`），
> 两种写法都生效。

---

## 4. 健康与可观测

| 端点 | 用途 | 语义 |
| --- | --- | --- |
| `GET /api/v1/health` | 依赖健康 | `required` 项不健康 → HTTP 503 / `DOWN`；必需项健康 → HTTP 200 / `UP`，LLM 不可用仍可规则兜底 |
| `GET /api/v1/metrics` | 运行指标 | **永远 200**：它回答"跑得怎么样"，5xx 会让编排层重启一个正常工作的实例 |
| `GET /api/v1/metrics/names` | 指标名目录 | 便于面板对齐名字 |

指标的读法与告警建议见 [observability.md](observability.md)。

Compose 已透传 `WOLFMIND_LLM_PROVIDER` 和 `WOLFMIND_LLM_TIMEOUT_MS`，分别默认 none / 30000；
与模型名、地址、密钥一起进入实际 LlmSettingsLoader。修改 deploy/.env 后须重新创建后端容器才能加载新环境变量，
仅 restart 不更新容器环境。后端健康检查镜像内的 `curl -fsS` 可按 HTTP 503 识别必需依赖异常；响应 JSON 字段保持原格式。

### 4.1 Redis 测试是否真正执行

普通 `mvn -o -pl server -am test` 允许 Redis 离线：每项集成测试标记 skipped，并在日志说明 Redis 不可达，
跳过数不代表覆盖成功。强制验收用 `mvn -o -pl server -am "-Dwolfmind.redis.required=true" test package`：
Redis 不可达时必须 BUILD FAILURE。真实测试使用 DB15，不能指向业务 DB0。
新增测试应沿用 RedisTestSupport.connect 的可空结果、测试开始时 flush 的可达性检查和空资源安全的清理。
不能把跳过判断重新放回 BeforeAll，让报告出现执行 0 / 跳过 0。
Compose 配置契约测试调用 docker compose config，只解析配置，不启动容器或调用模型；缺 Docker CLI 时逐项报告跳过。

---

## 5. 运维限制（成文，不是"以后再补"）

| 限制 | 现状 | 后果与应对 |
| --- | --- | --- |
| 单实例 | backend 只有 1 个副本 | 进程重启期间对局停止推进（Redis 里的状态不丢）。扩容需先设计消费器身份、广播与分片；当前不宣称多实例支持 |
| Redis 单点 | 单容器、AOF everysec | 容器/宿主机故障最多丢约 1 秒写入。运行中的对局可能丢失最后若干事件；已归档的对局不受影响 |
| Redis 内存上限 | 未设 `maxmemory` | 未归档事件不自动过期，归档核对成功后保留最多 7 天；归档积压会持续占用内存。上线前应配 `maxmemory` + `noeviction` 并加内存与归档积压告警 |

Redis 模式四组运行说明（2026-10-14）：cg-agent 沿用调度器；cg-projection/broadcast/insight 默认各自启动后台线程。
`wolfmind.consumer.enabled=false` 只供测试/排障，会关闭正常 Redis 广播、归档与统计，不作为上线配置。
`wolfmind.archive.enabled` 仅控制归档定时补漏，正常归档由 cg-projection 进行；统计缺漏每 30 秒补查。
新增组接管阈值 `wolfmind.consumer.claim-idle-ms` 默认为 60000，重启后 PEL 由新 UUID 消费者接管。
SQLite 新增 game_history/statistics 并记录 schema_version=3，CREATE IF NOT EXISTS 保留已有数据。
当前广播面向单后端部署，多副本连接扇出需要另行设计；本轮未增加基础设施或修改 WebSocket 信封。
| SQLite 单写者 | WAL 模式，写连接串行 | 归档与账号写入是低频的，当前规模足够；不要把它当高并发主库 |
| 无 TLS | Nginx 只监听 80 | 上线必须由外层（云 LB / 反向代理）终止 TLS；会话令牌是 Bearer，明文传输等于泄漏 |
| 无备份 | 卷没有自动备份 | 需要运维侧对 `wolfmind-data` 卷做快照。**SQLite 的 WAL 文件必须一起备份**，只拷 `.db` 会丢最后若干事务 |
| 日志无采集 | 只输出到容器 stdout | 需要时接 docker logging driver。日志正文是 UTF-8（`application.properties` 显式设置），中文不会乱码 |
| 模型成本无硬上限（默认） | 见上 | 必须显式配置 `WOLFMIND_LLM_MAX_TOKENS_PER_GAME` 或依赖供应商侧限额 |
| 实验室并发上限是进程内的 | `LabBatchRunner` 用进程内计数 | 多实例部署时每个实例各自计数；当前单实例部署下无影响 |

### 5.1 性能基线（真实测量，非估算）

以下是旧同步驱动时期规则基线九人局（**不调用模型**）的历史实测，用于参考规则处理成本。
旧 GameDriver 已移除；这些步数与耗时不代表当前异步调度器性能，需另行重测：

| 指标 | 实测值 | 说明 |
| --- | --- | --- |
| 单局步数 | 20–38 步 | 历史同步驱动的一次调用为一个回合或一次阶段推进 |
| 单局事件数 | 75–138 条 | 含开局 11 条 |
| 单局墙钟时间 | 63–147 ms | 规则基线；含模型的真实对局由模型延迟主导（实测 3–10 分钟/局） |
| 单步 p50 / p95 | 2.3 ms / 14.8 ms | 已排除 JIT 预热局 |
| 事件吞吐 | ≈ 990 条/秒 | 单线程推进 |
| 全量重放（单局） | < 1 ms | 恢复流程里最坏的那条路径 |

复现：`cd backend && mvn -o -pl server -am test -Dwolfmind.perf.enabled=true -Dwolfmind.perf.games=5 -Dtest=PerformanceBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false`
（报告写入 `backend/server/perf-report.json`）。

**这些数字的边界**：它们是"规则基线"的基线，不含模型调用、不含 WebSocket 扇出、不含多对局并发。
含模型的对局成本看 `/api/v1/metrics` 的 `llmCost` 与 `llm.latency`。
判据 `p95 < 100 ms` 与 `max < 1 s` 是**防退化的粗界**（抓"推进路径里插进了阻塞 I/O"），不是性能承诺。

---

## 6. 常用操作

### 6.1 AI 工作流引擎回退

部署默认 `WOLFMIND_AGENT_WORKFLOW_ENGINE=LANGGRAPH4J`。启动日志应包含
`AI workflow engine: LANGGRAPH4J (startup validation passed)`；图校验失败会阻止启动。
严重运行异常交给既有规则/超时处理，不能在当前回合自动换引擎重跑。

若持续出现图执行问题，在 `deploy/.env` 设置 `WOLFMIND_AGENT_WORKFLOW_ENGINE=SEQUENTIAL`。
重新创建后端容器才能加载新环境变量：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --no-deps --force-recreate backend
```

该命令从仓库根目录执行；镜像必须包含本次代码，首次升级需先构建后端镜像。
只运行 `docker compose restart` 不会重新加载 `.env` 环境变量。
本地 Java 进程可用 `-Dwolfmind.agent.workflow-engine=SEQUENTIAL`，参数放在 `-jar` 前。
启动日志和 Agent 内部 Trace 的 engine 字段可确认实际选择；恢复图引擎同样改配置并重启。
两种引擎沿用同一任务身份、预算、命令校验和超时机制，不改变游戏规则。

### 6.2 B 两阶段发言

部署默认 `WOLFMIND_AGENT_SPEECH_MODE=CONFIG`，遵循每个座位的 AgentConfig.mode（既有配置默认 A）。
要启用 B，在 `deploy/.env` 设置 `WOLFMIND_AGENT_SPEECH_MODE=B`，用 §6.1 的命令重新创建后端容器。
本地启动参数为 `-Dwolfmind.agent.speech-mode=B`，放在 `-jar` 前；启动日志显示 `AI speech mode: B`。
恢复 A 用 `A`，恢复按座位配置用 `CONFIG`，未知值阻止启动。

B 仅在白天讨论/遗言增加计划与表达两个阶段；投票、夜间技能仍沿用原流程。
原生工具模式正常发言需两次模型调用，查询及修复仍共用回合总上限；不会自动扩大预算或延长回合。
未接入模型时继续规则兜底。图引擎和顺序引擎均可运行 B。
当前 B 只使用公开证据表达立场，不自动公开本人角色或私人技能结果；自然语言一致性检查为有限规则。
模型费用和耗时应通过对局统计比较；这次实现不包含付费胜率实验。

```bash
docker compose logs -f backend          # 跟日志
docker compose restart backend          # 重启后端（Redis 里的对局会在启动时补建 Agent）
docker compose down                     # 停服务，保留卷
docker compose down -v                  # **连卷一起删**：账号与历史全部丢失
docker compose build --no-cache backend # 完全重建后端镜像
```

重启后端后，`RunningGameAgentRecovery` 会为 Redis 里仍在进行的对局补建 AI Agent
（`AgentRuntime` 的实例只在进程内）；未补建时对局只能靠超时跳过推进。

### 6.3 终局 Agent 分析缓存

默认 `wolfmind.agent.trace.finished-max-games=20`、`wolfmind.agent.trace.finished-retention-ms=3600000`，
两者必须为正数。可通过 JVM `-D` 属性或 Spring 环境变量配置，重启后生效。
纯内存模式的已结束分析超过任一上限即释放；Redis 模式归档成功立即释放，仅失败的终局保留待重试。
每 30 秒维护一次，缓存不跨重启；长期归档故障超过缓存上限可丢失部分分析摘要，
通过 `wolfmind.agent.trace.archive.dropped-games` 与 WARN 观察。
这两个配置不控制 Redis 对局事件 TTL，也不删除 SQLite 历史档案。升级需要加载新后端包。
