# 审计C：前端页面 / REST 适配器 / WebSocket 协议与设计契约的偏差

- 审计对象：`frontend/**`（`src`、`tests`、`package.json`、`vite.config.ts`、`vitest*.config.ts`）
- 对照基线：`AGENTS.md` §9、`docs/frontend/page-design.md`、`docs/frontend/websocket-protocol.md`、
  `docs/frontend/phase4-acceptance.md`、`docs/backend/api-spec.md`、`docs/ops/deployment.md`
- 方法：读文档 + 读源码 + grep 定位；所有结论给出「文档出处（章节/行）」与「代码证据 file:line」两侧。
  测试与类型检查由本审计员亲自运行（见 §0.2）。**本次审计为只读**：除本文件外未修改任何仓库文件；
  为验证生产构建的 env 内联结果，另外在 `%TEMP%\wm-audit-dist` 生成过一次性构建产物（仓库外）。
- 未采信 `TASKS.md` 的 `DONE` 标记，只采信文档条款与当前代码/实测结果。

---

## 0. 结论摘要（含 P0/P1/P2/P3 计数 + 实测测试数字）

### 0.1 计数与最严重结论

| 严重度 | 条数 | 编号 |
| --- | --- | --- |
| P0 | 1 | C-01 |
| P1 | 2 | C-02、C-03 |
| P2 | 8 | C-04 ～ C-11 |
| P3 | 14 | C-12 ～ C-25 |
| 合计 | 25 | — |

三条最严重：

1. **C-01（P0）**：`deploy/Dockerfile.frontend:22-26` 把 `VITE_WS_URL` 显式置为空串，`config.ts:10`
   用 `??` 兜底对空串无效，生产镜像里 `WS_URL === ""`，`new WebSocket("")` 抛异常并被
   `gameSocket.ts:80-85` 静默吞掉 —— **按文档部署的组合下实时链路整体不可用**（未留痕）。
2. **C-02（P1）**：`npm test` 不自洽。带着仓库自带的 `.env.local` 直接跑是
   **9 文件 / 96 用例失败**（实测），根因同 C-01（`config.ts:7` 对空串不兜底）。已在 TASKS.md:37-39 留痕，
   但把"必须覆盖环境变量"写进验收说明，等于承认默认验证命令跑不过。
3. **C-03（P1）**：首页 `HomePage.vue` 完全没有会话面板与个人统计，登录/注册被实现成第九个路由
   `/login`（`router/index.ts:14-20`），与 `page-design.md` RT-4、§4.1、§7 验收项直接冲突（部分留痕）。

### 0.2 本人实测的 typecheck / test 数字（禁止照抄的原始结果）

运行环境：`D:\MyProject\wolfmind\frontend`，node + vitest 5.0.3 / vue-tsc。

| 命令（实跑） | 退出码 | 实测结果 |
| --- | --- | --- |
| `VITE_USE_REAL_API=false`、`VITE_API_BASE_URL=http://127.0.0.1:8080` 后 `npm run typecheck` | 0 | `vue-tsc --noEmit` 无输出，0 error |
| 同上环境变量后 `npm test`（= `vitest run`） | 0 | **Test Files 30 passed (30)；Tests 379 passed (379)；0 failed**；Duration 5.39s |
| 清掉上述两个变量、保留仓库自带 `.env.local`，再 `npm test` | 1 | **Test Files 9 failed / 21 passed (30)；Tests 96 failed / 283 passed (379)** |

未覆盖变量的失败样例（原始断言）：

```
FAIL tests/realRoomAdapter.test.ts > startGame > POST /rooms/{id}/games 并返回 gameId 与版本
AssertionError: expected '/api/v1/rooms/r_1/games' to be 'http://127.0.0.1:8080/api/v1/rooms/r_…'
```

根因：`.env.local:6` 写着 `VITE_API_BASE_URL=`（空串），`config.ts:7` 的
`import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080'` 对"已定义但为空"不生效，
于是测试断言里的绝对地址变成同源相对地址。

测试文件口径（实测 `tests/` 目录）：共 **42** 个测试文件（41 个 `.test.ts` + 1 个 `.test.js`），
其中 `npm test` 只收 **30** 个；`vitest.config.ts:22-23` 显式排除 12 个
`*.integration.test.ts` / `*.pages.test.ts` / `*.ws.test.ts`（需真实后端，本次未运行，见 §3）。

与文档声称数字的差异（按要求逐项指出）：

| 出处 | 声称 | 与实测（30 文件 / 379 用例）的关系 |
| --- | --- | --- |
| `TASKS.md:38`、`TASKS.md:85` | 30 文件 / 379 通过 | **与实测一致**（这两条是当前最新口径） |
| `TASKS.md:107/125/144/166` | 29 文件 / 358～360 通过 | 旧批次，文件数少 1、用例少 19～21 |
| `TASKS.md:604/767/837` | 26 文件 / 301～325 通过 | 更旧批次 |
| `TASKS.md:3367` | 30 文件 / 377 通过 | 用例数少 2 |
| `docs/frontend/phase4-acceptance.md:11/103/201` | 291（25 文件）/ 301（26）/ 304（26） | 均为历史基线，该文档自己在 §"实时观战最终验收"开头声明数字是早期批次 |
| `TASKS.md:166` | `e2e:adapters` 7 文件 / 17 通过、`e2e:pages` 22/22、`e2e:ws` 4/4 | 未复核（需真实后端，见 §3） |

### 0.3 mock 适配器在生产路径是否仍然可达

**可达。** 结论与证据：

1. `config.ts:21` `USE_REAL_API = (import.meta.env.VITE_USE_REAL_API ?? 'false') === 'true'`
   —— 默认 `false`，即**任何未显式传该变量的构建都走 mock**。
2. `adapters.ts:7-11` 静态 import 五个 mock 适配器，`adapters.ts:59/69/82/96/106` 在真实/mock 之间二选一。
3. 生产包**没有把 mock 摇掉**：本人在真实模式产物里搜到 mock 专有数据
   `AI-白狐`、`小林`（`%TEMP%\wm-audit-dist\assets\index-DS0Sy_Cq.js`，以及仓库内既有
   `frontend/dist/assets/index-De--xqcu.js`）。也就是说 mock 代码随生产包一起发布，只是真实模式下不执行。
4. 生产部署这条链路确实传了 `true`（`deploy/docker-compose.yml:78`、`Dockerfile.frontend:23`），
   所以"docker 部署 → mock"不成立；但 `docs/ops/deployment.md:70` 本身就承认
   "`false` → 页面会用 mock 数据，症状是'部署好了但所有对局都看不见'"，即**误配即可静默进入 mock 生产**。
5. `AGENTS.md` §9 要求"use mock adapters until contracts are ready, and remove mocks when integrated"；
   契约已就绪（REST/WS 适配器齐全），mock 至今未移除，属规则未执行的持续偏差（C-11）。

### 0.4 留痕统计

- **已留痕或部分留痕（6 条）**：C-02（`TASKS.md:37-39`）、C-09（`TASKS.md:2537-2538` +
  `frontend/phase4-sync-diagnostic.log`）、C-10（`TASKS.md:3261-3262`，明写"token 在 localStorage"）、
  C-11（部分：`config.ts:15-19` 自述默认 mock、`docs/ops/deployment.md:70` 承认该后果）、
  C-05（部分：`backend/README.md:57` 承认 `/users/me/stats` 未实现）、
  C-03（部分：`TASKS.md:2351/2420/3105` 记录"登录页"这一实现，`page-design.md` §0 第 17 行提到"登录页"）。
- **未留痕的新发现（19 条）**：C-01、C-04、C-06、C-07、C-08、C-12 ～ C-25。
- 需要强调：**留痕 ≠ 设计已同步**。C-02/C-09/C-10 的实现事实分散记在 `TASKS.md` 的实现日志里，
  而对应设计条款（`page-design.md` RT-1/RC-3/§7 验收清单、`websocket-protocol.md` SEQ-5/§5.2）
  至今未被修改，读者按设计文档实现会再次撞上同一个冲突。

---

## 1. 页面与路由覆盖清单

按 `AGENTS.md` §9 的计划路由逐项对照 `frontend/src/router/index.ts` 与 `frontend/src/pages/**`。
"真实/仅 mock"指该页面在**当前代码**下能否读到服务端数据（取决于 `VITE_USE_REAL_API`，默认 mock）。

| # | AGENTS.md §9 / page-design.md §3 计划页面 | 路由 | 页面文件 | 已实现？ | 真实数据来源 | 仅 mock 时的行为 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | landing（§9）/ 首页（§3 `/`） | `/` | `pages/HomePage.vue` | ✅ 有页面 | 无（纯本地动画/脚本；`GET /auth/me` 只由 `main.ts:38` 全局调用一次） | 与真实模式**无差别**（首页不消费任何适配器） |
| 2 | authentication（§9） | `/login` | `pages/LoginPage.vue` | ✅ 有页面（但是**第 9 个路由**，见 C-03） | `POST /auth/register`、`POST /auth/login`、`GET /auth/me` | mock 下仍打真实 REST（`stores/auth.ts` 不经适配器） |
| 3 | lobby（§9）/ 游戏大厅（§3 `/lobby`） | `/lobby` | `pages/LobbyPage.vue` | ✅ | `GET /rooms`、`POST /rooms` | `features/lobby/mockRoomAdapter.ts`（sessionStorage） |
| 4 | room creation（§9） | 无独立路由 | `LobbyPage.vue` 内 `CreateRoomDialog` | ✅ 以对话框实现 | `POST /rooms` | mock 建房 |
| 5 | waiting room（§9）/ 房间等待页（§3 `/rooms/{roomId}`） | `/rooms/:roomId` | `pages/RoomWaitingPage.vue` | ✅ | `GET /rooms/{id}`、join/leave/take/ai/ready、`POST /rooms/{id}/games`、`ROOM_SUBSCRIBE`→`ROOM_SNAPSHOT` | mock 房间 + 不建 WS（`RoomWaitingPage.vue:86`） |
| 6 | live game（§9）/ 游戏页面（§3 `/games/{gameId}`） | `/games/:gameId` | `pages/GamePage.vue` | ✅ | `GET /games/{id}`、`POST /games/{id}/actions`、`GAME_EVENT` | `features/game/mockGameAdapter.ts`（含场景切换） |
| 7 | AI spectator arena（§9）/ AI观战页（§3 `/watch/{gameId}`） | `/watch/:gameId` | `pages/AIWatchPage.vue` → `ObservationPage.vue(mode=watch)` | ✅ | `GET /games/{id}` + `/replay?from&to`、WS 订阅 | `features/observation/mockObservationAdapter.ts` |
| 8 | replay（§9）/ 游戏回放（§3 `/games/{gameId}/replay`） | `/games/:gameId/replay` | `pages/ReplayPage.vue` → `ObservationPage.vue(mode=replay)` | ✅ | 同上（用 `?atVersion=` 取服务端重建棋盘） | mock 事件脚本 |
| 9 | post-game analysis（§9）/ AI分析页（§3 `/games/{gameId}/analysis`） | `/games/:gameId/analysis` | `pages/AnalysisPage.vue` | ✅ | `/analysis/summary`、`/analysis/agents/{playerId}` | `features/analysis/mockAnalysisAdapter.ts` |
| 10 | AI lab（§9）/ AI实验室（§3 `/lab`） | `/lab` | `pages/LabPage.vue` | ✅ | `/lab/agent-configs`、`/lab/batches`、`/lab/comparisons` | `features/lab/mockLabAdapter.ts` |
| 11 | **profile（§9）** | — | — | ❌ **缺失** | — | — |

补充说明：

- `page-design.md` §1/§3 只定义 8 个页面（与 `DESIGN.md` §14 一致），**没有 profile**；`AGENTS.md` §9
  列了 profile。因此第 11 项是"设计文档之间不一致 + 未实现"，不是单纯漏做。
- `/games/demo`、`/watch/demo`、`/games/demo/replay` 不是独立路由，而是被 `/games/:gameId`、`/watch/:gameId`
  动态匹配（这也是页面测试里出现 `No match found for location with path "/watch/demo"` 警告的原因）。
- 第 2 项与 `page-design.md` §7 验收清单第 906 行"登录与注册以首页会话面板实现"冲突（C-03）。

---

## 2. 逐项核对表

| 编号 | 设计要求（出处） | 实现现状（file:line） | 符合? | 严重度 | 是否已留痕 | 影响 |
| --- | --- | --- | --- | --- | --- | --- |
| C-01 | 同源部署下前端应把 REST/WS 打到当前站点：`page-design.md` §0 第 19 行"本地开发通过 Vite 同源代理转发 REST 与 WebSocket"；`docs/ops/deployment.md` §2.2 第 63-70 行"容器部署唯一正确的组合"；`websocket-protocol.md` §1 第 27 行端点 `/ws`；`frontend/README.md:27`"空的 API 地址让 REST 与 WebSocket 使用前端同源地址" | `src/api/config.ts:10` `WS_URL = import.meta.env.VITE_WS_URL ?? deriveWsUrl(API_BASE_URL)`；`deploy/Dockerfile.frontend:22` `ARG VITE_WS_URL=""`、`:24-26` `ENV ... VITE_WS_URL=${VITE_WS_URL}`；`deploy/docker-compose.yml:74-78` 只传 `VITE_API_BASE_URL`/`VITE_USE_REAL_API`；`src/api/gameSocket.ts:78-85` `new WebSocket(WS_URL)` 抛错仅 `setState('closed')` | ❌ | **P0** | 否 | 生产镜像里 `WS_URL === ""`，实时帧全部不可达：对局页只靠提交后的 REST 刷新、等待房无 `ROOM_SNAPSHOT`、观战页停在"连接已断开"且不重连。实测证据：① 用 Dockerfile 同款 env 调 `vite resolveConfig`，`VITE_WS_URL` 存在且为 `""`；② 同款 env 构建产物中常量段为 `bf="",Of="",Cf=!0,ir="wolfmind.sessionToken"`，且 `wss://` 字符串整体缺失（`deriveWsUrl` 被摇掉）——证明 `??` 未兜底 |
| C-02 | `frontend/README.md:79` 与 `AGENTS.md` §10 要求"运行相关测试并报告实际结果"；验收命令必须默认可用 | `src/api/config.ts:7` `API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080'`；`frontend/.env.local:5-6` 设 `VITE_USE_REAL_API=true`、`VITE_API_BASE_URL=`（空串） | ⚠️ | **P1** | 是（`TASKS.md:37-39` 记载了失败与覆盖办法） | 直接 `npm test` 实测 9 文件 / 96 用例失败（283/379 通过）；任何 CI 或未读 TASKS.md 的开发者会把环境噪声当回归 |
| C-03 | `page-design.md` RT-4 第 133 行"登录与注册不是第九个页面，作为首页内的会话面板实现"；§4.1 第 148-197 行（首页承载登录/注册/登出、本人统计）；§7 第 906 行；RT-2 第 131 行"未登录…跳转 `/` 并自动展开会话面板" | `src/pages/HomePage.vue` 全文无 auth/统计逻辑（import 段 13-16 无 `useAuthStore`）；`src/router/index.ts:14-20` 新增 `/login` 路由；`:64-69` 守卫改跳 `{ name: 'login', query: { redirect } }` | ❌ | **P1** | 部分（`TASKS.md:2351/2420/3105` 记录了"登录页"这一实现；`page-design.md` §0 第 17 行提到"返回登录页"；但 RT-4 与 §7 未修改） | 首页 4 项文档化数据来源（`POST /auth/register|login|logout`、`GET /auth/me`、`GET /users/me/stats`）不在首页；RT-2 的"展开会话面板"路径不存在；设计文档与实际路由形态长期分叉 |
| C-04 | `page-design.md` §4.1 第 187 行"登出｜点击｜`POST /auth/logout`｜失败仍清除本地会话并提示" | `src/stores/auth.ts:114-131` 实现了 `logout()`；但 `src/**` 中无任何调用（仅 `stores/auth.ts`、`api/config.ts` 注释提到"登出"；`main.ts:38` 只调 `me()`） | ❌ | P2 | 否 | 用户没有任何登出入口，只能清 localStorage；会话无法按设计撤销 |
| C-05 | `page-design.md` §3 第 117 行、§4.1 第 156/166/188 行：首页需 `GET /users/me/stats` 并渲染 `PersonalStats`（缺失显示"暂无数据"） | 前端全仓无 `/users/me/stats` 调用（grep 结果仅文档命中）；`src/pages/HomePage.vue` 无统计区；后端亦未实现（`backend/README.md:57`） | ❌ | P2 | 部分（`backend/README.md:57` 承认未实现） | 首页"本人统计"整块能力缺失 |
| C-06 | `page-design.md` §4.3 第 345 行"只显示服务端给出的展示名"；§4.4 第 403 行"展示名｜服务端返回的展示名｜空位显示'空位'，**不生成占位昵称**" | `src/api/mapRoom.ts:50` `name: seat.playerId ? \`玩家 ${seat.seatNo}\` : null`；`src/api/mapGame.ts:112` `name: \`玩家 ${player.seatNo}\``；`src/features/observation/realObservationAdapter.ts:100` `name: p.playerId \|\| \`${p.seatNo} 号\``。而契约里确实没有该字段：`api-spec.md` §4 房间视图、§5.2 `players[]` 均无展示名 | ❌ | P2 | 否 | 界面显示的是前端编的占位名，与"不生成占位昵称"直接冲突；跨设计-契约缺口（page-design 要求服务端字段，api-spec 未定义） |
| C-07 | `page-design.md` RC-5 第 845 行"关闭码 4401 与 4403 不重连，分别表示未认证与无权限"；`websocket-protocol.md` §3.4 第 114 行、§3.6 第 134 行"4403 → 展示拒绝原因，不重连该订阅" | `src/api/gameSocket.ts:110-114` 只处理 `4401`；其余关闭码一律 `scheduleReconnect()`（`:206-215`）；全仓无 `4403` 字样 | ❌ | P2 | 否 | 无权限订阅会按退避无限重连，界面既不进只读分支也不告诉用户原因 |
| C-08 | `page-design.md` §5.1 第 805-820 行：15 个错误码各自的界面行为（`GAME_VERSION_CONFLICT` 自动 `RESUME`、`TURN_NOT_YOURS` 禁用行动区并同步、`ACTION_NOT_ALLOWED` 收起面板、`RATE_LIMITED` 退避倒计时、`SERVICE_DEGRADED` 全局横幅、`GAME_RECOVERING` 提示、`IDEMPOTENCY_REPLAY` 不产生界面变化） | 仅 3 处有专门分支：`src/api/apiClient.ts:111`（401 清会话）、`src/api/realGameAdapter.ts:341-345`（`GAME_BUSY` 200/400/800ms 重试 3 次）、`src/api/gameSocket.ts:181-185`（WS `AUTH_REQUIRED`/`RATE_LIMITED`）。其余一律落到 `src/stores/game.ts:67-71` 的通用分支：写 `error.value = message` + 重新 `adapter.get()`。提交成功响应体（含 `replayed`）被丢弃：`realGameAdapter.ts:329` | ⚠️ | P2 | 否 | 版本冲突不会触发 `RESUME`（只多打一次 REST）；`TURN_NOT_YOURS`/`ACTION_NOT_ALLOWED`/`RATE_LIMITED`/`SERVICE_DEGRADED` 没有规定的差异化处理；`IDEMPOTENCY_REPLAY` 会重复提示"已提交" |
| C-09 | `websocket-protocol.md` §8.1 SEQ-5 第 734 行"出现 `seq > lastSeq + 1` 时视为缺号：先缓存该帧，立即发 `RESUME`"；`page-design.md` RC-3 第 843 行同义、§7 第 918 行验收项 | `src/api/gameStream.ts:164-170` 明确把不连续当正常（注释第 168-170 行），仅 `SYNC_REQUIRED` 触发重建（`realGameAdapter.ts:286-297`）；`gameSocket.ts:256-258` 的 `resume()` 只由重连与 `SYNC_REQUIRED` 调用 | ❌ | P2 | 是（`TASKS.md:2537-2538` 记录了"客户端绝不能把 seq 不连续当成错误"，另有 `frontend/phase4-sync-diagnostic.log` 诊断记录） | 客户端不再自己发现缺口，完全依赖服务端发 `SYNC_REQUIRED`；若该帧丢失，缺口不会被自愈。SEQ-5 与 SEQ-6（第 735 行"跳跃不是缺号"）本身互斥，客户端选择了 SEQ-6，但设计文档未裁决 |
| C-10 | `page-design.md` RT-1 第 130 行"令牌只存放在应用级状态容器的内存中"；§6 D-6 第 894 行"不在 URL、日志、错误上报中放令牌" | `src/api/config.ts:34` `SESSION_TOKEN_KEY = 'wolfmind.sessionToken'`、`:37-54` 读写 `localStorage`；`router/index.ts:3` 直接 import `sessionToken` 作为守卫判据 | ❌ | P2 | 是（`TASKS.md:3261-3262` 明确写了"token 在 localStorage，跨刷新存活"） | 持久化令牌可被同源 JS 读取（XSS 放大面），且与 RT-1 文字冲突；设计条款未改 |
| C-11 | `AGENTS.md` §9"use mock adapters until contracts are ready, and **remove mocks when integrated**"；`docs/ops/deployment.md:70` 承认 `false` 会静默走 mock | `src/api/config.ts:15-21` 默认 `false`；`src/api/adapters.ts:7-11` 静态 import 全部 mock；实测生产包仍含 mock 数据 `AI-白狐`/`小林`（`frontend/dist/assets/index-De--xqcu.js`；`%TEMP%\wm-audit-dist\assets\index-DS0Sy_Cq.js`） | ⚠️ | P2 | 部分（`config.ts` 注释与 deployment.md 自述了默认值取舍） | 契约就绪后 mock 未下线：真实模式下是死代码随包发布；任何漏传 `VITE_USE_REAL_API=true` 的构建会以 mock 数据对外服务且界面只以文案标注"示例" |
| C-12 | `page-design.md` §4.4 第 485-487 行（按 `SUBSCRIBED.scope = PLAYER/SPECTATOR` 决定观战标记）、§4.5 第 526 行 `SpectatorBadge`；`websocket-protocol.md` §5.2 第 386 行"`scope` 为 `SPECTATOR` 时显示'公开观战'" | `SUBSCRIBED` 的 `scope` 字段从未被读取（全仓无 `SPECTATOR` 消费点）；徽标改由客户端状态推导：`src/pages/ObservationPage.vue:25` `fullRecords = !online \|\| player.match?.snapshot?.finished`，`:130` 据此渲染"公开观战/赛后完整记录" | ⚠️ | P3 | 否 | 用户可见文案基本正确（参与者会被适配器拦下，见 `realObservationAdapter.ts:117`），但判据不是服务端下发的订阅身份，协议字段形同虚设 |
| C-13 | `websocket-protocol.md` §5.2 第 359 行"保存 `serverTime` 与本地时间之差…**按服务端下发的间隔启动心跳，不使用写死的间隔**" | `AUTH_OK` 分支只重置计数并 `onReady()`（`src/api/gameSocket.ts:169-176`）；心跳间隔/空闲阈值是模块常量 `gameSocket.ts:37-38`（15s/45s），`:194-196` 使用常量 | ❌ | P3 | 否 | 服务端调整心跳/空闲参数时客户端不会跟随（当前值恰好相同，故无即刻故障） |
| C-14 | `websocket-protocol.md` §3.3 第 103 行"连续两次心跳未得到 `PONG`（即 30s）时视为连接可疑｜客户端主动重连，不等 45s" | `gameSocket.ts:177-178` 收到 `PONG` 直接 return，无任何丢失计数或主动重连路径 | ❌ | P3 | 否 | 半开连接（无 onclose）只能等服务端 4408/网络超时，最长比设计多等一个空闲周期 |
| C-15 | `page-design.md` RT-5 第 134 行"页面离开时由 WebSocket 客户端发出 `UNSUBSCRIBE`"；`websocket-protocol.md` §3.5 第 124 行 | `src/api/realGameAdapter.ts:379-401` 清理时直接 `sockets.get(gameId)?.close()`（`GameSocket` 无 `unsubscribe` 调用）；只有观战路径发了：`realObservationAdapter.ts:231` `socket.unsubscribe(); socket.close()` | ⚠️ | P3 | 否 | 对局页退订靠 TCP 断开兜底（`websocket-protocol.md` §3.5 第 127 行允许），但两条链路行为不一致，订阅计数与审计不精确 |
| C-16 | `api-spec.md` §1 第 19 行"所有 POST 写接口要求 `Idempotency-Key` 请求头" | `src/stores/auth.ts:51-55`（register）、`:69-73`（login）未传 `idempotencyKey`；logout 传了（`:118-122`） | ❌ | P3 | 否 | 纯契约偏差：后端 `AuthController.java:41/56` 未强制该头，故当前无运行时影响；但契约字面与实现不符 |
| C-17 | `api-spec.md` §4 第 153 行 `GET /rooms ?status=WAITING\|PLAYING&page=&pageSize=` | `src/api/realRoomAdapter.ts:48` 请求 `/api/v1/rooms?page=1&pageSize=50`（无 status），`:53` 在客户端过滤 `WAITING/RUNNING` | ⚠️ | P3 | 否 | 客户端过滤 + 固定单页 50 条：房间超过 50 个时列表静默丢数据，`total` 与展示不一致 |
| C-18 | `api-spec.md` §6 第 358-367 行：`GET /games/{id}/replay?from=&to=[&atVersion=]`，单次上限 500，未定义 `limit` | `src/features/observation/realObservationAdapter.ts:122` 追加 `&limit=500`；`:170` 用 `from=to=atVersion` 取快照 | ⚠️ | P3 | 否 | 多传一个未登记参数（后端若严格校验未知查询参数会 400）；对局页的 `/replay` 调用（`realGameAdapter.ts:88-91`）与契约一致 |
| C-19 | `api-spec.md` §8 第 492 行 `GET /lab/comparisons?agentConfigIds=a,b&role=`；§8.3 第 560 行"`provenance` 里必须写明口径与样本量提醒" | `src/features/lab/realLabAdapter.ts:301` 无参调用、`:304-307` 本地按配置过滤；未读取响应的 `provenance`；样本量提示改为前端硬编码 `src/pages/LabPage.vue:142` | ⚠️ | P3 | 否 | 契约要求的过滤参数与 `provenance` 字段被绕过；提示文案存在但与服务端口径无关联，服务端改口径时前端不会同步 |
| C-20 | `api-spec.md` §8.2 第 547 行批次内对局 `status` 含 `FAILED`，服务端状态应如实展示 | `src/features/lab/realLabAdapter.ts:190` `status: raw.status === 'COMPLETED' ? 'COMPLETED' : 'RUNNING'`（后端批次枚举为 `RUNNING/COMPLETED/FAILED`，见 `backend/.../application/lab/LabBatch.java:31-33`）；`LabPage.vue:39` 显示态取自本地 `lab.running` | ❌ | P3 | 否 | 失败批次在界面上显示为"批次进行中"，用户看不到 `FAILED` |
| C-21 | `api-spec.md` §7.1 第 387-390 行：`analysis/summary` 的 `players[]` 已下发 `playerId` | `src/features/analysis/realAnalysisAdapter.ts:108-110` 自行构造 `playerId = \`p_${seatNo}\``，并在 `:227/:249` 用它请求 `/analysis/agents/{playerId}`；模型的 `playerId` 字段被忽略 | ⚠️ | P3 | 否 | 与后端当前规则（`PlayerId.java:18` 生成 `p_{seat}`）恰好一致，但把服务端内部标识格式固化进前端；标识规则一变即 404 |
| C-22 | `api-spec.md` §5.2 第 216-228 行 `GameView` 字段表：`deaths[]`、`spectator`、`me.skillState`；`page-design.md` §4.4 第 407 行"技能可用性 ← `GameView.me.skillState`" | `src/api/restTypes.ts:110-128` 的 `GameView` 无 `deaths`/`spectator`，`GamePlayerView`（:99-107）无 `skillState`；技能/目标改由 `permissions[]` 驱动（`mapGame.ts:214-216`） | ⚠️ | P3 | 否 | 实现事实上遵守了 `api-spec.md` §5.2 第 230-234 行"前端不得按 `phase`+`role` 自行推导权限"，但 `deaths[]`/`spectator` 两个契约字段无人消费，`me.skillState` 在 page-design 与 api-spec 之间自相矛盾 |
| C-23 | `api-spec.md` §5.2 第 223 行 `me` 为服务端下发的"仅本人"字段 | 多处注释断言服务端没有"我"：`src/api/adapters.ts:47-51`、`:65-66`、`src/stores/session.ts:12-14`、`src/pages/RoomWaitingPage.vue:65-72`；代码本身已优先用 `view.me`（`mapGame.ts:132-139`） | ⚠️ | P3 | 否 | 注释与契约冲突，后续维护者可能据此删掉 `me` 的消费或重复实现座位反查逻辑 |
| C-24 | `src/api/adapters.ts:16-29` 自述"适配器注册表：**唯一的接入选择点**"，`page-design.md` §2.1 第 70-78 行要求状态容器统一走两个客户端 | `src/pages/ObservationPage.vue:18` 直接 `import { USE_REAL_API } from '@/api/config'`，`:24` `online = USE_REAL_API` | ⚠️ | P3 | 否 | 只是文案判据，但破坏了"选择点唯一"的约定：若注册表选择逻辑变化，观战页文案与真实数据源可能不一致 |
| C-25 | `websocket-protocol.md` §5.2 R-2 第 307 行：`ROOM_SUBSCRIBE` 的 `roomId` 不存在时回 `ERROR{code:"ROOM_NOT_FOUND"}`，不建立订阅；`page-design.md` §4.3 状态表第 323-326 行 | `src/api/gameSocket.ts:331-333` 提供 `onRoomNotFound`；`src/pages/RoomWaitingPage.vue:87-91` 构造 `RoomSocket` 时未传该回调 | ⚠️ | P3 | 否 | 帧级 `ROOM_NOT_FOUND` 无人处理，只靠 `GET /rooms/{id}` 的 404 兜底；WS 分支等于静默 |

---

## 3. 未判定项

1. **真实后端联调类测试未运行（12 个文件）**：`npm run e2e:adapters` / `e2e:pages` / `e2e:ws` /
   `e2e:browsers` 需要运行中的后端与 Redis。实测本机 `127.0.0.1:8080`、`:18080`、`:6379` **均未监听**，
   因此 §0.2 表外的这些套件（`fullMatch.integration`、`realAdapters.integration`、`pagesLive.pages`、
   `gameSocketLive.ws` 等 12 个文件）本轮**未复核**，`TASKS.md:166` 与
   `phase4-acceptance.md:13-15/203-206` 声称的 15/22/4 等数字本次无法证实或证伪。
2. **C-01 的运行期后果未在真实浏览器/容器中观测**：结论来自静态分析 + `vite resolveConfig` 实测 +
   一次性构建产物字符串比对（`%TEMP%\wm-audit-dist`）。未启动容器、未用浏览器复现，因此"UI 表现为连接已断开"
   是依据 `gameSocket.ts:80-85` 与 `realGameAdapter.ts:279-284` 推断的行为，未截图取证。
3. **C-06 的责任归属未判定**：`page-design.md` 要求"服务端返回的展示名"，`api-spec.md` 的房间/对局视图
   没有该字段。是"前端未接"还是"契约缺字段"，需要项目所有者裁决，本报告只记录冲突与两侧证据。
4. **C-09 的裁决未判定**：`websocket-protocol.md` SEQ-5 与 SEQ-6 自相矛盾，代码选了 SEQ-6 并在
   `TASKS.md:2537-2538` 留痕。是否把 SEQ-5 从协议中删除（或补"如何区分可见性跳跃与真缺号"的判据），属协议决策，未判定。
5. **可访问性/响应式验收（`page-design.md` §4 各页"响应式与无障碍要点"）未判定**：本轮未做浏览器/缩放/对比度实测，
   `phase4-acceptance.md:251-257` 自述了未验收范围，本报告不重复声称通过或失败。
6. **`GET /games?status=`、`GET /health`、`GET /metrics`、`GET /users/{userId}` 无前端消费点**：
   `page-design.md` 未定义消费它们的页面，故不作为偏差列出；是否需要在观测/运维页面消费，未判定。
7. **C-11 的"生产可达"程度取决于构建方是否传参**：`deploy/docker-compose.yml` 传了 `true`，
   但仓库未提供其他生产构建入口（无 CI 配置可查），"是否存在第三种生产构建"未判定。
