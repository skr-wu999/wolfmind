# WolfMind

基于 Multi-Agent 的在线狼人杀平台：支持 AI 自主对局、真人与 AI 混合对局、真人在线多人、观战、回放、AI 决策分析与实验室。

**核心分工**：确定性游戏规则属于游戏领域（`Game` 聚合），非确定性推理属于 Agent 子系统。AI 只能提出行动，且必须经应用层与领域校验后才会产生事件——它不能直接改状态（`AGENTS.md` §1、§5）。

## 技术基线

| 领域 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3、模块化单体（`engine` + `server`） |
| 领域 | DDD 风格聚合、确定性状态机、领域事件 |
| AI | LangChain4j、LangGraph4j |
| 前端 | Vue 3、TypeScript、Pinia、Tailwind CSS、Vite |
| 实时 | WebSocket |
| 运行中对局 | Redis（Stream / Hash / Set），`wolfmind.storage=memory` 时退化为进程内 |
| 终局与账号 | SQLite |

## 目录

```text
wolfmind/
├── AGENTS.md                 编码代理的开发规范（架构边界、验收、纪律）
├── DESIGN.md                 产品与架构概览
├── TASKS.md                  里程碑、验收证据与未完成项
├── docs/                     详细契约（architecture / backend / ai / frontend / ops / qa）
├── deploy/                   Docker Compose、两个 Dockerfile、Nginx、.env.example
├── backend/
│   ├── engine/               领域内核，只依赖 Java 标准库
│   └── server/               应用层 + 基础设施层 + 接口层
└── frontend/                 Vue 3 单页应用
```

## 环境要求

- JDK 21、Maven 3.9+
- Node.js 22.12+ / 24.x / 26+，npm（仓库带 `package-lock.json`）
- 可选：Redis（`wolfmind.storage=redis` 时需要）、Docker（部署）

## 后端

```powershell
cd backend
mvn -o -pl server -am test        # 普通测试：Redis 不可达时逐项报告 skipped，并不表示已覆盖
mvn -o -pl server -am "-Dwolfmind.redis.required=true" test package  # 正式验收：Redis 不可达必须失败
java -cp "server/target/classes;engine/target/classes;$(Get-Content cp-e2e.txt)" `
     -Dserver.port=18080 `
     -Dwolfmind.agent.force-rule=true `
     com.wolfmind.WolfMindApplication
```

- 默认内存存储（重启即丢）；加 `-Dwolfmind.storage=redis` 使用 Redis 事件流与快照，终局自动归档 SQLite。
- 不配置 `WOLFMIND_LLM_*` 时 AI 走**规则兜底**，九人局仍能打到终局；配置后走真实模型。
  模板见 `backend/wolfmind-llm.properties.template`（真实密钥不入库）。
- 健康检查：`GET /api/v1/health`（LLM 是 `required=false` 的检查项）。

细节见 [backend/README.md](backend/README.md)。

## 前端

```powershell
cd frontend
npm ci
npm run dev                       # 默认 http://127.0.0.1:5173
```

默认走 **mock 适配器**（后端未启动也能打开页面）。接真实后端时在 `frontend/.env.local` 写：

```dotenv
VITE_USE_REAL_API=true
VITE_API_BASE_URL=
VITE_DEV_API_TARGET=http://127.0.0.1:18080
```

空的 `VITE_API_BASE_URL` 让 REST 与 WebSocket 走前端同源地址，由 Vite 代理到后端（生产由 Nginx 转发同一路径）。

```powershell
npm run typecheck
npm test                          # 单元测试，不需要后端
npm run build
```

需要运行中的后端（默认 `http://127.0.0.1:18080`，以 `-Dwolfmind.agent.force-rule=true` 启动）：

```powershell
npm run e2e:adapters   # 适配器 × 真实后端
npm run e2e:pages      # 页面组件 × 真实后端（jsdom）
npm run e2e:ws         # 真实 WebSocket
npm run e2e:backend-smoke   # 真实注册→建房→开局→订阅→提交行动
npm run e2e:browsers        # Playwright：Chromium / Firefox / WebKit
```

HTTP 部署回归：`cd frontend && npm run e2e:http-uuid` 使用真实 Chromium 与本机非 localhost 的
HTTP 地址验证 UUID 兼容、发言/投票/技能提交（固定适配器，不要求后端或付费模型）。需已安装 Playwright Chromium。
可用 `WOLFMIND_HTTP_CHECK_HOST` 指定本机局域网 IPv4；测试服务器和浏览器结束后自动关闭。

细节见 [frontend/README.md](frontend/README.md)。

## 部署

```bash
cd deploy
cp .env.example .env      # 填模型密钥等；不填也能起（规则兜底）
docker compose up -d --build
```

三个容器：redis（AOF + 命名卷）、backend（Redis + SQLite）、frontend（Nginx 反代 `/api` 与 `/ws`，同源无 CORS）。
对外端口由 `WOLFMIND_HTTP_PORT` 控制（默认 8080）。运维限制与适用边界见 [deployment.md](docs/ops/deployment.md)。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 编码代理规范：架构边界、非可协商规则、质量门禁 |
| [DESIGN.md](DESIGN.md) | 产品定位与总体架构 |
| [TASKS.md](TASKS.md) | 各阶段状态、验收证据、已知限制与待办 |
| [system-design.md](docs/architecture/system-design.md) / [ddd-design.md](docs/architecture/ddd-design.md) / [event-design.md](docs/architecture/event-design.md) | 系统、领域与事件设计 |
| [domain-model.md](docs/backend/domain-model.md) / [api-spec.md](docs/backend/api-spec.md) / [database-schema.md](docs/backend/database-schema.md) / [redis-schema.md](docs/backend/redis-schema.md) | 领域模型与后端契约 |
| [agent-runtime.md](docs/ai/agent-runtime.md) / [strategy-layer.md](docs/ai/strategy-layer.md) / [tool-gateway.md](docs/ai/tool-gateway.md) | AI 子系统设计与约束 |
| [page-design.md](docs/frontend/page-design.md) / [websocket-protocol.md](docs/frontend/websocket-protocol.md) | 页面与实时协议契约 |
| [deployment.md](docs/ops/deployment.md) / [observability.md](docs/ops/observability.md) | 部署与可观测性 |
| [docs/qa/](docs/qa) | 人工体验与测试记录（含复现步骤与截图） |

## 当前状态

Phase 1–5 的主线功能都已实现：领域状态机与重放、Redis/SQLite 持久化与归档、九个 AI 座位的完整对局、在线大厅与对局、观战/回放/分析/实验室、指标与容器部署。可复现的验收证据（测试数量、端到端记录、真实模型实跑记录）集中在 [TASKS.md](TASKS.md)。

**尚未完成的部分同样记在 `TASKS.md` 与 [docs/qa](docs/qa) 里，不要把本文当作"已上线"**，主要包含：

- Redis 消费者组投影、`game_history` / `statistics` 两张表未接入；
- `AGENTS.md` §9 规划的 profile 页面、`GET /users/*` 接口未实现；`/admin/*` 刻意不注册；
- WebSocket 背压（`BACKLOG_EXCEEDED` / 1011）与部分文档化错误码未实现；
- 分析页的胜率曲线与信念估计**没有服务端口径**，页面显示"暂无数据"，不编造数字。
