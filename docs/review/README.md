# 全链路设计符合性审计（2026-10-13）

本目录是**只读审计**的产物：把「代码实际做的」与 `DESIGN.md` / `AGENTS.md` / `docs/**` 里
「当初设计的」逐条对照，标出偏差、严重度与「是否已留痕」。

**审计未修改任何源码、设计文档或部署文件**；只新增本目录下的报告。

| 文件 | 链路 | 审计员 |
| --- | --- | --- |
| [audit-A-infra.md](audit-A-infra.md) | 后端基础设施 / Redis / SQLite / 部署 | audit-infra |
| [audit-B-ai.md](audit-B-ai.md) | Agent Runtime / Strategy Layer / Tool Gateway / LLM 接入 | audit-ai |
| [audit-C-frontend.md](audit-C-frontend.md) | 前端页面 / REST 适配器 / WebSocket 协议 | audit-frontend |
| [audit-D-domain.md](audit-D-domain.md) | 领域规则 / 状态机 / 事件 / 不变量 | audit-domain |
| [summary.md](summary.md) | **汇总结论、架构级偏差、修复优先级** | Lead |

## 复现基线（Lead 实跑，2026-10-13）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && mvn -o -pl server -am test` | engine **118** + server **644**，0 失败 1 跳过，BUILD SUCCESS |
| 编码 | 517 个源/文档文件首字节扫描 | UTF-8 BOM **0** |
| 前端类型 | `npm run typecheck`（覆盖 `VITE_USE_REAL_API=false`） | 退出 0 |
| 前端用例 | `npm test`（同上覆盖） | 30 文件 / 379 用例通过 |

注意：`mvn` 全量在**没有 Redis** 的机器上也会「全绿」——13 个 Redis 用例被
`Assumptions.abort` 静默跳过（见 [audit-A-infra.md](audit-A-infra.md) 条目 0-1）。
