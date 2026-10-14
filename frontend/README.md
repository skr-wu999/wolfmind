# WolfMind 前端

核心游戏页最新布局为“游戏桌 + 实时群聊 + 阶段操作栏”，支持内联发言、流式气泡、历史阅读提醒、权限频道与手机面板切换。实现、接口边界与验证命令见 [核心群聊改版](docs/frontend/core-game-chat.md)。

Vue 3 + TypeScript + Vite 脚手架，已接入 Vue Router、Pinia、Tailwind CSS 和 Vitest。原 `homepage-demo/` 的像素村庄迁入首页 `/`，保留昼夜循环、人物对话、音效、弹窗和本地投票演示。

游戏大厅 `/lobby` 按参考图实现木框房间列表和右侧创建、加入面板，复用村庄场景、导航、昼夜控制及房屋绘制。支持筛选、分页、创建房间、列表或房间号加入。

成功后进入 `/rooms/:roomId` 房间等待页，支持入座、准备、AI 座位管理、复制房间号和离开。在线模式下，房主可选择 AI 补齐空位并开局，其他成员刷新房间后进入同一对局。

核心游戏页复用像素村庄，提供九席游戏桌、公开发言、投票和职业技能。示例模式中的 `/games/demo` 提供场景切换与模拟断线。在线模式中的 `/games/{gameId}` 使用真实授权视图、计时、行动权限和结果，隐藏演示入口。实现与验收见 [核心游戏页说明](docs/frontend/core-game-page.md)。

## Phase 4 真实后端联调

大厅、等待房与对局已有真实 REST / WebSocket 适配器，登录使用后端会话。
本轮已验证全 AI 与 1 真人 + 8 AI 到达终局，并通过订阅回调同步最终阶段和版本。
固定示例需使用 `VITE_USE_REAL_API=false`，在线模式使用服务端生成的对局地址。当前状态与待办见根目录 [TASKS.md](../TASKS.md) 的最新 Phase 4 接续节。

浏览器本地联调在 `frontend/.env.local` 中配置，再运行 `npm run dev`：

```dotenv
VITE_USE_REAL_API=true
VITE_API_BASE_URL=
VITE_DEV_API_TARGET=http://127.0.0.1:18080
```

空的 API 地址让 REST 与 WebSocket 使用前端同源地址，Vite 将 `/api` 和 `/ws` 转发到后端。
这样无需为本地浏览器放宽后端 CORS。`VITE_DEV_API_TARGET` 未设置时默认后端端口 8080。
代理仅作用于开发服务；生产环境需由 Nginx 转发同一路径。显式使用跨源 `VITE_API_BASE_URL` 时需另行配置后端允许的来源。

先以 `-Dwolfmind.agent.force-rule=true` 启动后端，默认联调地址为 `http://127.0.0.1:18080`，
然后在本目录执行：

```powershell
npm run e2e:adapters
npm run e2e:pages
npm run e2e:ws
```

`fullMatch.integration.test.ts` 覆盖真实调度、真人命令、REST 终局、WebSocket 最终版本同步及回放事件去重。
这些测试独立于 `npm test`，需要运行中的后端；规则模式不调用付费 LLM。
真人发言订阅已使用确定性命令验证。走真实模型时，服务端用 LangChain4j 流式输出按句发送
`AGENT_SPEECH_DELTA` 预览，最终仍只提交一次 `PLAYER_SPOKE`；规则模式与自动化测试只发送已提交整段的展示帧。

后端加 `-Dwolfmind.storage=redis` 时，对局、房间、会话存 Redis，账号凭据存 SQLite，重启后可恢复（见
[redis-schema.md §14](../docs/backend/redis-schema.md)）。Redis 模式下联调建议用一个干净的库
（如 `-Dspring.data.redis.database=3`），否则旧的进行中对局会被后端继续调度，拖慢测试。
提交命令遇到 `409 GAME_BUSY` 时，适配器用同一 `Idempotency-Key` 退避重试（200/400/800 ms，最多 3 次）。

跨浏览器冒烟用 Playwright（Chromium / Firefox / WebKit）。首次运行先下载浏览器，后端照上面的方式启动后执行：

```powershell
npx playwright install chromium firefox webkit
npm run e2e:browsers
```

它会自己在 5180 端口起一个真实适配器模式的 Vite；后端地址默认 `http://127.0.0.1:18080`，可用 `WOLFMIND_E2E_API` 覆盖。

## 开发与验证

AI 实验室 `/lab` 按参考图提供 Agent 配置、营火工作台、刷新批次进度、模型排序比较、结果概览和 JSON 导出；大厅有入口。真实模式走 `/lab/agent-configs`、`/lab/batches`、`/lab/comparisons`：批次对局由服务端用与真人对局相同的驱动推进，进度需显式刷新；配置与批次按账号隔离。默认 mock 模式保留已标注的手工样例。说明见 [AI 实验室说明](docs/frontend/ai-lab-page.md)。

AI 分析 `/games/:gameId/analysis` 按参考图展示对局信息、关键事件、营火游戏桌、Agent 策略、决策摘要与胜率图表。支持 Agent 切换、全局/阵营/单 Agent 视角、事件跳转、播放控制和等价数据表；大厅、观战与回放导航均有入口。真实模式走 `GET /games/{id}/analysis/summary` 与 `/agents/{playerId}`：服务端只给运行时可观察的事实（统计、候选与打分、工具调用、token 与耗时、是否兜底），**胜率曲线与信念估计没有服务端口径，页面显示"暂无数据"而不是编一组数字**。默认 mock 模式保留已标注的赛后九人本地示例。说明见 [AI 分析页面](docs/frontend/ai-analysis-page.md)。

默认 mock 模式的 `/watch/demo` 与 `/games/demo/replay` 保留已标注的九人本地脚本和播放控件，复用村庄、营火桌、头像与木框。在线模式的 `/watch/:gameId` 已接实时观战：进行中公开记录，结束后完整角色和事件；直播不显示本地播放器。功能与原型说明见 [观战与回放说明](docs/frontend/watch-replay-pages.md)，最新实际验收见 [Phase 4 验收](../docs/frontend/phase4-acceptance.md)。

使用 Node.js 22.12+（22 系列）、24 系列或 26+。在项目根目录运行：

```powershell
cd frontend
npm ci
npm run dev
```

打开终端输出的地址，默认是 http://127.0.0.1:5173/。开发服务仅监听本机。

```powershell
npm run typecheck
npm test
npm run build
npm run preview
```

`build` 先进行 Vue / TypeScript 类型检查，再输出 `dist/`。`test` 运行场景、状态容器和页面交互测试；`test:watch` 用于开发时持续测试。生产部署使用 HTML5 history，需要让 Nginx 将前端路径回退到 `index.html`。

## 目录

真实接口模式可设置 `VITE_USE_REAL_API=true` 与 `VITE_API_BASE_URL`。
当前大厅、等待房、对局和实时观战已有 REST / WebSocket 接入。大厅进行中房间提供服务端 gameId 的观战入口；参与者使用本人对局页。匿名可读取公开记录并手动刷新，登录后启用持续同步、自动重连和恢复，终局停止订阅。披露政策已由项目所有者批准；分析与实验室在 Phase 5 已接真实接口，回放的历史棋盘由服务端 `?atVersion=` 快照重建（前端不做可见性或规则演进）。
对局适配器读取授权回放展示发言与技能摘要，计时、权限和胜负均取服务端数据。

联调默认访问 `http://127.0.0.1:18080`，可由 `WOLFMIND_E2E_BASE` 覆盖。
后端需以 `-Dwolfmind.agent.force-rule=true` 启动，避免测试调用付费模型。
使用 `npm run e2e:adapters`、`npm run e2e:pages` 和 `npm run e2e:ws` 运行真实接口测试；
默认 `npm test` 不需要后端。当前交接与实际验收结果见 [TASKS.md](../TASKS.md)。

```text
frontend/
├── public/                 favicon、字体与 OFL 许可
├── src/
│   ├── App.vue             路由视图入口
│   ├── main.ts             注册 Pinia、路由与样式
│   ├── router/index.ts     首页、大厅、房间、游戏、观战、回放、分析与实验室路由
│   ├── pages/              首页、房间、游戏、观战、回放、分析与实验室页面
│   ├── stores/             首页、房间、游戏、播放、分析与实验室状态
│   ├── components/         共享村庄、导航、HUD、插画、弹窗与图标
│   ├── features/home/      Canvas 绘制、类型声明与音效
│   ├── features/lobby/     本地房间适配器与展示类型
│   ├── features/game/      本地对局投影、权限及场景适配器
│   ├── features/observation/ 本地观战/回放事件与只读投影
│   ├── features/analysis/  独立 Agent 摘要与示例估计适配器
│   ├── features/lab/       Agent 配置、批次与比较的本地展示适配器
│   └── assets/             Tailwind 入口、首页、大厅与等待页样式
├── tests/                  场景、Pinia 和页面回归测试
└── docs/frontend/          原型设计、历史 QA 与截图
```

首页和图标使用 TypeScript；原 Canvas 绘制模块保留 JavaScript，公开接口由相邻 `village.d.ts` 定义。`@/` 指向 `src/`。组件局部状态留在页面，演示时钟和投票状态由 Pinia 管理；离开页面时清理动画帧、观察器、音频与定时器，并重置演示状态。

Tailwind 只导入主题与工具类，原 CSS 放在 `components` 层，保留原型的浏览器基础样式，避免迁移造成视觉变化。做法参考 [Tailwind Preflight 文档](https://tailwindcss.com/docs/preflight)。新页面可以使用工具类，当前首页保留原 CSS。

## 首页演示边界

- 昼夜各 15 秒，可手动切换或暂停；尊重系统减少动态效果设置。
- 人物支持鼠标与键盘对话；导航弹窗支持 Escape 和焦点恢复。
- “开始游戏”进入大厅；“探索玩法”中的“去村庄看看”保留本地脚本演示，参与模式可投票，观战模式只读。
- 音效默认关闭，点击后使用浏览器合成音频。

首页 `homeDemo` 仍是本地展示状态，不计算完整游戏规则和胜负。默认 mock 模式下，房间适配器将本地九人房间保存在当前标签页的 `sessionStorage`，存储不可用时使用内存；正式模式使用已接入的 REST / WebSocket 适配器。关闭本地会话后不保证保留演示数据。后续实现遵循[页面设计](../docs/frontend/page-design.md)、[REST 契约](../docs/backend/api-spec.md)和 [WebSocket 协议](../docs/frontend/websocket-protocol.md)。

设计与历史验证记录见 [首页设计](docs/frontend/homepage-demo.md)和 [QA](docs/frontend/homepage-demo-qa.md)。
大厅组件、流程和本次验证记录见 [大厅页面](docs/frontend/lobby-page.md)。
等待页布局、交互边界与验证记录见 [房间等待页](docs/frontend/room-waiting-page.md)。
