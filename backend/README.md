# WolfMind 后端

狼人杀在线平台的后端。本目录是 `AGENTS.md` §4 中"Backend agent"的归属范围。

## 模块结构

| 模块 | 内容 | 约束 |
| --- | --- | --- |
| `engine` | 领域内核：`Game` 聚合、角色、阶段状态机、领域服务、领域事件、视图投影 | **只依赖 Java 标准库**，不含 Spring / Redis / SQLite / LLM（`ddd-design.md` DEP-1、DEP-2） |
| `server` | 应用层（用例编排）、基础设施层（端口实现）、接口层（REST/JSON） | 依赖 `engine`；只有 `infrastructure` 可以依赖第三方 SDK（DEP-5） |

接口层的实际目录是 `server/src/main/java/com/wolfmind/interfaces/`，包名 `com.wolfmind.interfaces.*`：
`interface` 是 Java 关键字，不能做包名段，因此目录与包名统一用可编译的复数形式
（`ddd-design.md` §5.2 里的层名仍叫 Interface）。

## 构建与测试

后端包职责与九个装配配置类见 [包结构说明](../docs/backend/package-layout.md)。
开局编排和运行中 Agent 恢复位于 `application.room`；框架装配位于 `infrastructure.config`，
业务类通过端口协作，不引用控制器或 Spring 配置类。

```bash
cd backend
mvn -pl engine -am test      # 只跑领域（快，无需任何外部依赖）
mvn -pl server -am test      # 跑全 reactor：领域 + 应用层 + 基础设施 + 接口层
mvn -pl server -am clean test # 建议在切换分支或多人在同一目录编辑后先 clean
```

- 需要 JDK 21 与 Maven 3.9+。
- 测试默认不调用付费模型。领域和多数用例使用固定控制器或内存端口，SQLite 使用临时文件库；
  HTTP/WS 契约测试启动随机端口的 Spring Boot 服务。
- Redis 集成测试需要隔离的 Redis；普通模式不可达时逐项报告跳过，正式验收使用
  `-Dwolfmind.redis.required=true`，不可达即失败。测试库为 DB15，不应使用业务数据做验收。
- 首次构建需要下载 Spring Boot、AI SDK、SQLite 驱动等 Maven 依赖。

## 本地启动

```powershell
cd backend
mvn -o -pl server -am test        # 构建并跑全量测试
java -cp "server/target/classes;engine/target/classes;$(Get-Content cp-e2e.txt)" `
     -Dserver.port=18080 `
     -Dwolfmind.agent.force-rule=true `
     com.wolfmind.WolfMindApplication
```

- `cp-e2e.txt` 是本机依赖类路径（指向本地 Maven 仓库已缓存的 jar），已随仓库提供，因此离线也能起服务。
- 默认存储是**内存**（重启即丢）；`-Dwolfmind.storage=redis` 时事件流、快照、锁、幂等、会话与房间索引进 Redis，
  账号与终局归档进 SQLite。Redis 不可达时 Redis 集成用例会**自动跳过**（`RedisTestSupport`），不会伪装成通过。
- 需要真实模型时配置 `WOLFMIND_LLM_PROVIDER` / `WOLFMIND_LLM_API_KEY` / `WOLFMIND_LLM_MODEL`
  （模板见 `wolfmind-llm.properties.template`）。不配置时 AI 走规则兜底，对局仍能打到终局，
  `GET /api/v1/health` 会把 `llm` 报成"未配置"且 `required=false`。

## 当前实现边界（诚实标注，勿当作最终形态）

| 项 | 现状 | 尚未做到的部分 |
| --- | --- | --- |
| 事件存储 | 内存实现；`-Dwolfmind.storage=redis` 时为 Redis Stream（Lua 原子追加 + 快照 + 锁 + 幂等 + 会话 + 房间索引） | 消费者组投影、`game_history` / `statistics` 两张表仍未接入（`redis-schema.md` §14.3） |
| 归档访问 | 原生 JDBC（`SqliteArchiveRepository`、`SqliteDecisionTraceRepository`） | `AGENTS.md` §3 基线要求 MyBatis / MyBatis-Plus；替换只动这两个类 |
| REST 层 | Spring Boot 3.5，7 个控制器（`com.wolfmind.interfaces.rest`） | 最初的 `com.sun.net.httpserver` 骨架已被替换 |
| WebSocket | 已实现（`interfaces/ws`：8 个客户端帧 / 10 个服务端帧，含认证、订阅、`RESUME`、房间快照、限速） | 背压（`BACKLOG_EXCEEDED` / 1011）与部分文档化错误码仍未实现（`websocket-protocol.md` §9） |
| 认证 | 已实现（注册 / 登录 / 登出 / `GET /auth/me`，PBKDF2，Redis 会话） | 管理员角色未实现；`/admin/*` 刻意不注册 |
| 房间 / 用户聚合 | 房间聚合、用例与 REST 已实现 | `GET /users/{userId}`、`GET /users/me/stats` 仍未实现（api-spec §2 已列出这两条） |
| AI Agent | 已实现（Agent Runtime、Strategy Layer、Tool Gateway、LangChain4j、LangGraph4j，九 AI 端到端） | 真实模型需配置 `WOLFMIND_LLM_*`；未配置时九个座位走规则兜底，对局仍能打到终局 |

已实现的部分与验收证据见根目录 `TASKS.md`。

## 三条实现纪律（评审会检查）

1. **领域不碰基础设施**：`engine` 不出现 Redis、SQLite、LLM、`Clock`、`Random`、`UUID`。
   这条由 `DependencyRulesTest`（DEP-1..DEP-7）机器校验，不靠自觉。
2. **状态只能由事件修改**：`Game` 的所有可变字段只在 `apply(event)` 里赋值，命令路径与重放路径
   共用同一批 `apply`。任何在 `apply` 之外改状态、或命令路径与重放路径分别赋值派生字段的写法，
   都会被"整局实时处理 vs 全量重放逐字段一致"的用例抓到（INV-13、INV-16）。
3. **可见性只有一份实现**：`VisibilityFilter` 与 `GameViewProjector`（`domain.game.view`）。
   推送层、回放接口、AI 工具全部复用它；另写一份过滤逻辑属于缺陷（AP-6）。

## 上手阅读顺序

1. `engine/.../domain/game/model/Game.java` —— 聚合与状态机（先看 `handle` 与 `runUntilStable`）。
2. `engine/.../domain/game/service/` —— 规则判定，都是无状态纯函数。
3. `engine/.../domain/game/event/` —— 事件契约；`EventType` 是字符串契约的唯一定义处。
4. `server/.../application/game/SubmitActionApplicationService.java` —— 命令处理的编排顺序。
5. `server/.../infrastructure/memory/InMemoryGameRepository.java` —— 加载 = 快照 + 增量重放。
