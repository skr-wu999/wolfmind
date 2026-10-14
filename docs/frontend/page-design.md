# WolfMind 详细设计：前端页面设计

本文细化 `DESIGN.md` §14（前端设计）的八个页面，为每个页面固定路由、数据来源、状态分支、交互映射与可见信息边界，
使实现者可以照着写页面而不需要再问规则问题。本文不改变 `DESIGN.md` 的分层、技术选型与架构风格，
全部内容落在 [system-design.md](../architecture/system-design.md) §1 逻辑架构中 "Frontend (Vue3)" 这一个方框之内。

## 0. 约束来源

2026-10-15 所有者选定以首页为基准统一内页：观战、回放、分析和实验室沿用 FogVillageScene、
米白纸面与方角像素面板；相关侧栏内容合并外框，内部用留白和分隔线，取消侧栏拉伸与负外边距错位。
这一视觉要求替代历史记录中的旧木框和旧村庄背景表述，保持既有功能与数据可见范围。
ENERGY 2 / RHYTHM 2 / MOTION 1 沿用，不新增素材、依赖或动画。
实现与验收见 [内页样式统一](../../frontend/docs/frontend/home-style-consolidation.md)。

2026-10-14 规则展示补充：等待房读取完整 RoomView.rules，对局页读取 GameView.rules；两处复用规则说明组件。
SIDE_KILL 明确说明平民或神职一边全灭即可狼人胜，WOLVES_OUTNUMBER 明确说明狼人数量达到好人数量即胜。
同时说明女巫每瓶药整局一次、同夜不可双药，自救限制按本局数据展示。缺失/未知胜利规则显示尚未同步，不猜默认规则。
沿用原木框弹窗、字体和关闭操作，仅增加真实规则说明；视觉强度沿用 ENERGY 2 / RHYTHM 2 / MOTION 1。

2026-10-14 房间退出：对局页结算留在原页，提供退出房间、回放及 AI 分析；运行中退出先提示 AI 接管与最后真人退出会中止。
等待 REST 确认后才返回大厅，失败可在弹窗内重试。大厅摘要同时显示座位占用、真人与 AI 数，每 15 秒刷新可见列表。
沿用像素村庄、木框、原按钮及原生 dialog 的键盘/焦点行为；退出按钮保持原返回入口位置与棕色，避免用户把导航误当退出。
此次界面变化沿用 ENERGY 2 / RHYTHM 2 / MOTION 1：状态与操作是视觉重点，不新增装饰、素材或动画。
服务端宽限/关闭/中止规则见 [房间生命周期](../backend/room-lifecycle.md)。

2026-10-12 实现接续：真实对局适配器通过已认证 REST 读取视图和授权回放，
只映射已提交事实，不在客户端重建游戏规则。回放按 500 个版本分段读取；
连接断开或收到 `SYNC_REQUIRED` 时行动控件停用，`RESUME` 后完成订阅与权威刷新才恢复。
截断恢复使用 `GAME_SNAPSHOT_SYNC` 重设水位并刷新视图；隐藏角色的技能库存也由服务端裁剪。
阶段计时来自已提交事件，回合计时来自 `currentTurn`，时钟偏移使用 WebSocket 的 `ts`。
首次订阅的 `lastSeq=0` 先发送授权 `GAME_SNAPSHOT_SYNC` 建立基线，不补发历史。
等待房开局使用已有 `POST /rooms/{roomId}/games`，房主可选择 AI 补齐空位；
成员刷新到已绑定的 `gameId` 后进入同一对局。在线模式隐藏本地场景与演示入口。
女巫目标取服务端权限，遗言与猎人开枪分别显示。REST 401 与 WebSocket 4401 清除对应会话并返回登录页，保留站内回跳地址。
真实适配器按会话与订阅生命周期丢弃迟到请求和旧 socket 回调，不跨账号复用私有投影缓存。
本地开发通过 Vite 同源代理转发 REST 与 WebSocket，配置见 `frontend/README.md`。
观战采用所有者于 2026-10-12 批准的进行中公开、赛后完整政策：已登录其他账号通过 WS 持续同步；匿名可读取公开 REST 记录并手动刷新。终局重新读取完整记录后停止订阅，页面状态由授权快照决定。大厅使用房间摘要中的 gameId 提供观战入口。

| 编号 | 约束 | 出处 |
| --- | --- | --- |
| C-1 | 不改变已有架构（DDD + Event Driven + Multi-Agent） | `DESIGN.md` §3 |
| C-2 | 不新增技术选型 | `DESIGN.md` §3 §14 |
| C-3 | 符合 DDD：前端只与 REST 命令/查询接口和 WebSocket 推送交互，不感知聚合与领域对象 | `DESIGN.md` §4 §5、[ddd-design.md](../architecture/ddd-design.md) §1.2 |
| C-4 | 所有状态变化事件化：前端不产生状态，只消费事件与投影 | `DESIGN.md` §6 §7、[event-design.md](../architecture/event-design.md) §1 |
| C-5 | AI 不得直接操作 Game Domain：AI 的发言与投票同样以事件形式到达前端，与真人无区别 | `DESIGN.md` §9 §12、[ddd-design.md](../architecture/ddd-design.md) §7 |

## 1. 定位与范围

`DESIGN.md` §14 列出八个核心页面：首页、游戏大厅、房间等待页、游戏页面（核心）、AI观战页面、游戏回放、
AI分析页面、AI实验室。本文逐页实现这八个，不增不减，顺序与 §14 一致。

技术名词白名单：Vue3（单文件组件、组合式 API）、WebSocket、REST、浏览器原生能力（fetch、WebSocket、
KeyboardEvent、focus、matchMedia 等）。需要路由时说"前端路由"，需要状态管理时说"应用级状态容器"，
需要图表时说"前端图表组件"，需要类型约束时说"前端类型校验"。

**前端不引入 `DESIGN.md` 之外的新依赖。**

范围边界：

| 在范围内 | 在范围外 |
| --- | --- |
| 八个页面的路由、组件结构、状态分支、交互到接口/帧的映射 | 后端应用服务、聚合、领域服务、事件的实现 |
| 前端如何消费 `GameView` 与事件 DTO | 可见性过滤算法本身（只由服务端 `VisibilityFilter` 实现） |
| 断线重连、补发、倒计时展示、错误码转文案 | 回合超时判定与 `SkipAction` 的提交（服务端超时看护负责） |
| 响应式断点与无障碍要点 | 视觉规范（配色、字号、图标资产的最终取值） |

## 2. 前端分层

### 2.1 五层与依赖方向

```
视图层（单文件组件）
   │  只读投影、派发用户意图
   ▼
应用级状态容器（组合式 API 组织的响应式投影）
   │  调用客户端、按事件归约
   ├───────────────┐
   ▼               ▼
REST 客户端     WebSocket 客户端
（fetch）        （浏览器原生 WebSocket）
   │               │
   └───────┬───────┘
           ▼
   类型与校验（前端类型校验）
```

依赖只向下，视图层不直接出现 fetch 与 WebSocket 调用。

| 层 | 职责 | 允许 | 禁止 |
| --- | --- | --- | --- |
| 视图层 | 渲染、绑定交互、无障碍属性、焦点管理 | 读应用级状态容器的只读投影、派发意图 | 直接发请求、订阅帧、判定规则、写共享状态 |
| 应用级状态容器 | 保存 `GameView`、事件投影、会话、连接状态、订阅清单、提示队列 | 调用两个客户端、按事件类型归约、维护 `lastSeq` | 存放游戏规则、计算胜负/票数/存活、过滤 `visibility` |
| REST 客户端 | 统一 `/api/v1` 请求、请求头、错误响应解析、状态码分类 | 浏览器原生 `fetch`、构造 `Idempotency-Key` | 重试非幂等命令、拼接可见性参数、缓存他人私有信息 |
| WebSocket 客户端 | 建连、`AUTH`、`SUBSCRIBE`/`UNSUBSCRIBE`/`RESUME`、`PING`、`ACK`、`seq` 去重、断线退避 | 浏览器原生 `WebSocket`、定时心跳、序号比对 | 按 `visibility` 二次过滤、解析领域规则、伪造事件 |
| 类型与校验 | DTO 形状定义、入参校验、响应体形状校验 | 手写校验函数、枚举白名单 | 依赖后端生成的类型包、用校验结果改写状态真相 |

### 2.2 页面不推导规则、只消费事件与视图

| 编号 | 规则 |
| --- | --- |
| F-1 | 页面渲染所需的每一个领域事实都必须来自 `GameView` 或某条已收到的事件；两者都没有的事实，页面不显示。 |
| F-2 | 阶段、回合、存活、票数、胜负、技能可用性一律取自服务端；前端最多做"字段是否存在"的判断。 |
| F-3 | 前端不承担授权过滤；进行中观战仅 PUBLIC，赛后按服务端状态展示完整记录与原始可见性标签。 |
| F-4 | 前端不调用任何 AI 接口改变对局状态；AI 的动作与真人一样表现为 `PLAYER_SPOKE`、`PLAYER_VOTED` 等事件。 |
| F-5 | 组件显隐（技能按钮、投票面板）只是体验优化，最终校验在服务端的 `ActionValidator` 与工具网关权限矩阵。 |

### 2.3 GameView 字段约定

`GET /games/{gameId}` 返回按调用者角色过滤后的 `GameView`（[ddd-design.md](../architecture/ddd-design.md) §1.3）。
字段名沿用 [ddd-design.md](../architecture/ddd-design.md) §2.2 的聚合状态字段名，只做可见性删减，不另起命名。

| 字段 | 含义 | 可见性 | 前端用途 |
| --- | --- | --- | --- |
| `gameId` | 对局标识 | 全部订阅者 | 路由参数校验、订阅键 |
| `version` | 聚合版本 | 全部订阅者 | `POST /games/{gameId}/actions` 的 `expectedVersion` |
| `phase` | 当前 `GamePhase` | 全部订阅者 | 阶段指示器 |
| `round` | 轮次 | 全部订阅者 | 轮次徽记 |
| `status` | `INIT` / `RUNNING` / `FINISHED` / `ABORTED` | 全部订阅者 | 结算面板与只读模式 |
| `players[]` | `{playerId, seatNo, alive, controller{type, refId}, ready}` | 全部订阅者 | 座位卡 |
| `me` | `{playerId, seatNo, role, camp, alive, skillState}` | 仅本人 | 技能按钮显隐、本人座位高亮 |
| `turnOrder[]` | 发言顺序 | 全部订阅者 | 发言区顺序提示 |
| `turnIndex` | 当前发言序号 | 全部订阅者 | 当前发言者标记 |
| `currentTurn` | `{playerId, seatNo, phase, turnIndex, deadline}` | 全部订阅者 | 回合横幅与倒计时 |
| `votes` | 当前投票轮的公开票 | 全部订阅者 | 投票区"我的票"回显 |
| `deathQueue`、`nightActions` | 聚合内部中间状态 | 不下发 | 无，前端不得据此渲染 |

`deathQueue` 与 `nightActions` 是尚未公布的中间状态，对外表达只允许通过事件（`DEATH_ANNOUNCED`、`WOLF_KILL_SELECTED` 等），
因此 `GameView` 不下发它们，防止"服务端不给我就看得到"的隐藏信息在前端重新出现。

## 3. 路由与页面清单

| 路由 | 页面 | 对应 `DESIGN.md` 章节 | 主要数据来源 | 是否需登录 | 是否实时 |
| --- | --- | --- | --- | --- | --- |
| `/` | 首页 | §14 首页 | `GET /auth/me`、`GET /users/me/stats` | 否 | 否 |
| `/lobby` | 游戏大厅 | §14 游戏大厅 | `GET /rooms`、`POST /rooms` | 是 | 否（见 §4.2） |
| `/rooms/{roomId}` | 房间等待页 | §14 房间等待页 | `GET /rooms/{roomId}` 与 §4.3 交互清单中的房间命令接口 | 是 | 否（见 §4.3） |
| `/games/{gameId}` | 游戏页面（核心） | §14 游戏页面（核心） | `GET /games/{gameId}`、`POST /games/{gameId}/actions`、订阅对局推送 | 是 | 是 |
| `/watch/{gameId}` | AI观战页面 | §14 AI观战页面 | `GET /games/{gameId}`（观战者视图）、订阅对局推送 | 是 | 是 |
| `/games/{gameId}/replay` | 游戏回放 | §14 游戏回放 | `GET /games/{gameId}/replay?from=&to=` | 是 | 否 |
| `/games/{gameId}/analysis` | AI分析页面 | §14 AI分析页面 | `GET /games/{gameId}/analysis/agents/{playerId}`、`GET /games/{gameId}/analysis/summary` | 是 | 否 |
| `/lab` | AI实验室 | §14 AI实验室 | `GET /lab/agent-configs`、`POST /lab/agent-configs`、`POST /lab/batches`、`GET /lab/batches/{batchId}`、`GET /lab/comparisons` | 是 | 否（见 §4.8） |

路由规则：

| 编号 | 规则 |
| --- | --- |
| RT-1 | 令牌只存放在应用级状态容器的内存中，URL 查询串与路径参数里不出现令牌、会话 id、`playerId`。 |
| RT-2 | 未登录访问需登录路由时，跳转 `/` 并自动展开会话面板；命中 `AUTH_REQUIRED` 时同样处理。 |
| RT-3 | 路由参数只承载 `roomId`、`gameId` 与分页区间 `from`、`to`，不承载角色、座位、可见性等影响判定的参数。 |
| RT-4 | 登录与注册不是第九个页面，作为首页内的会话面板实现，因为 `DESIGN.md` §14 只定义八个页面。 |
| RT-5 | 页面离开时由 WebSocket 客户端发出 `UNSUBSCRIBE`；再次进入同一 `gameId` 时用本地 `lastSeq` 发 `RESUME`。 |

## 4. 逐页设计

### 4.1 首页

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 展示项目介绍与"开始游戏"入口，承载登录、注册、登出，展示本人统计 |
| 入口 | 路由 `/`；任何页面遇到 `AUTH_REQUIRED` 时回到此处 |
| 出口 | "开始游戏"到 `/lobby`；已在大厅中的对局以 `gameId` 形式给出直达 `/games/{gameId}` |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `POST /auth/register` | 注册 |
| REST | `POST /auth/login` | 登录 |
| REST | `POST /auth/logout` | 登出 |
| REST | `GET /auth/me` | 恢复会话、取 `userId` 与 `username` |
| REST | `GET /users/me/stats` | 本人统计 |
| WebSocket | 无 | 首页不建立订阅 |

**页面结构**

```
HomePage
 ├─ IntroBlock            项目介绍（文案取自 DESIGN.md §1 项目定位）
 ├─ PrimaryAction         "开始游戏"（真实路由跳转）
 ├─ SessionPanel          登录 / 注册 / 登出（未登录时展开）
 └─ PersonalStats         本人统计（已登录时渲染，字段缺失显示"暂无数据"）
```

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 会话恢复中显示骨架，不用数字占位 | `GET /auth/me` 返回 |
| 空 | 未登录：统计区隐藏；已登录但统计为空：显示"暂无数据" | 有数据后正常渲染 |
| 错误 | 请求失败显示错误条与 `traceId`，提供"重试"按钮 | 重试成功 |
| 无权限 | 不适用（不需要登录） | 无 |
| 断线重连 | 不适用（无订阅） | 无 |
| 对局已结束 | 不适用 | 无 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 开始游戏 | 点击 | 有会话：前端路由到 `/lobby`；无会话：展开会话面板 | 无网络调用，不失败 |
| 登录 | 表单提交 | `POST /auth/login` | 失败显示 `message` 与 `traceId`；`RATE_LIMITED` 显示退避提示 |
| 注册 | 表单提交 | `POST /auth/register` | 同上；用户名重复按服务端 `message` 展示 |
| 登出 | 点击 | `POST /auth/logout` | 失败仍清除本地会话并提示 |
| 查看我的对局统计 | 点击 | `GET /users/me/stats` | 失败显示错误条；不展示任何本地推算值 |

**隐藏信息处理**

| 能看到 | 看不到 |
| --- | --- |
| 本人 `userId`、`username`、本人统计 | 任何对局的角色、发言、票型 |
| 服务端返回的统计数据逐字段 | 本地合并、推算或补全的统计口径 |

首页不得展示任何未由服务端返回的数字。统计字段缺失时显示"暂无数据"，不使用示例值、示意值或占位数字。

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 单列布局；窄视口下会话面板占满宽度，输入框与按钮不横向溢出 |
| 键盘 | 全流程可 Tab 到达；会话面板首字段自动获得焦点；Enter 提交，Escape 关闭面板 |
| 焦点 | 提交失败后焦点回到第一个出错字段；面板关闭后焦点回到"开始游戏" |
| 对比度 | 正文与背景对比度不低于 4.5:1，大号文本不低于 3:1 |
| 不只靠颜色 | 表单错误同时给出图标与文本，不只用红色边框 |

### 4.2 游戏大厅

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 房间列表、创建房间、加入游戏 |
| 入口 | 路由 `/lobby`；首页"开始游戏"进入 |
| 出口 | 加入或创建成功后进入 `/rooms/{roomId}` |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `GET /rooms` | 房间列表 |
| REST | `POST /rooms` | 创建房间，返回 `roomId` |
| REST | `GET /rooms/{roomId}` | 进入等待页前的存在性与权限校验 |
| WebSocket | 无 | 契约未给房间列表订阅键，大厅不使用推送 |

大厅列表不使用定时器轮询。列表在进入页面、点击刷新、窗口重新获得焦点（浏览器原生 `visibilitychange`）时各查询一次；
若后续需要大厅实时化，做法是在协议中新增订阅键，属于协议扩展，不在本文范围。

**页面结构**

```
LobbyPage
 ├─ RoomToolbar          刷新、创建房间
 ├─ RoomList             房间列表
 │   └─ RoomRow{roomId, name, 座位占用, 是否可加入}
 └─ CreateRoomDialog     房间配置表单
```

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 列表骨架，行数与上次结果一致以免跳动 | `GET /rooms` 返回 |
| 空 | "当前没有房间"，并列创建房间主操作 | 出现房间或创建成功 |
| 错误 | 错误条加 `traceId`，保留刷新按钮 | 重试成功 |
| 无权限 | `AUTH_REQUIRED` 时跳 `/` 并展开会话面板 | 登录完成 |
| 断线重连 | 不适用（无订阅）；网络恢复后按钮重新可用 | 网络恢复 |
| 对局已结束 | 不适用 | 无 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 刷新列表 | 点击 | `GET /rooms` | 失败显示错误条；不自动重试 |
| 创建房间 | 提交表单 | `POST /rooms` | 失败显示 `message`；成功后前端路由到 `/rooms/{roomId}` |
| 房间行 | 点击 | `GET /rooms/{roomId}` 校验后前端路由到 `/rooms/{roomId}` | `GAME_NOT_FOUND` 语义的 404 显示"房间已关闭"并从列表移除该行 |
| 加入游戏 | 点击 | 进入等待页后由 §4.3 的"入座"完成 | 见 §4.3 |

**隐藏信息处理**

| 能看到 | 看不到 |
| --- | --- |
| 房间名称、座位占用、是否可加入 | 房间内玩家的角色、是否 AI（`controller.type` 在等待页才展示） |
| 列表接口返回的字段 | 其他房间的历史对局内容 |

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 宽视口表格，窄视口卡片列表；两者信息字段一致，不隐藏可加入状态 |
| 键盘 | 房间行可获得焦点，Enter 进入；创建对话框焦点陷阱并支持 Escape 关闭 |
| 焦点 | 列表刷新后焦点保持在原行，不跳到列表头部 |
| 对比度 | "可加入/已满"标签文本对比度不低于 4.5:1 |
| 不只靠颜色 | "可加入"同时有文本标签，不只靠绿色圆点 |

### 4.3 房间等待页

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 玩家列表、AI座位、准备状态；集齐后开局 |
| 入口 | 路由 `/rooms/{roomId}`；大厅房间行进入 |
| 出口 | `POST /rooms/{roomId}/games` 成功并拿到 `gameId` 后进入 `/games/{gameId}` |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `GET /rooms/{roomId}` | 房间快照：座位、`controller.type`、`ready` |
| REST | `POST /rooms/{roomId}/join` | 加入房间 |
| REST | `POST /rooms/{roomId}/leave` | 离开房间 |
| REST | `POST /rooms/{roomId}/seats/{seatNo}/take` | 入座 `seatNo`（1..9） |
| REST | `POST /rooms/{roomId}/seats/{seatNo}/ai` | 该座位设为 AI 接管 |
| REST | `DELETE /rooms/{roomId}/seats/{seatNo}/ai` | 取消 AI 接管 |
| REST | `POST /rooms/{roomId}/ready` | 标记本人就绪 |
| REST | `POST /rooms/{roomId}/games` | 开局，返回 `gameId` |
| WebSocket | `ROOM_SUBSCRIBE{roomId}` → `ROOM_SNAPSHOT` | 其他人入座、离开、准备、AI 补位与开局实时到达（websocket-protocol.md §5，2026-10-12 所有者批准） |

刷新时机：进入页面、窗口重新获得焦点、以及每一次房间操作成功后各重新查询一次 `GET /rooms/{roomId}`；
其余变化由 `ROOM_SNAPSHOT` 推送，丢弃 `version` 不大于已应用版本的快照。快照显示 `RUNNING` 且带 `gameId` 时，
所有在页成员（不只房主）进入 `/games/{gameId}`。离开本页发 `ROOM_UNSUBSCRIBE` 并关闭连接。
本页不设置定时器，不用轮询代替 WebSocket；连接断开期间仍可用"刷新房间"手动查询。

**页面结构**

```
RoomPage
 ├─ SeatGrid             9 个 SeatCard{seatNo, 昵称或空位, controller.type, ready}
 ├─ RoomActions          入座 / 离开座位 / 设为 AI / 取消 AI / 准备
 ├─ StartGameButton      开局（房主可见）
 └─ RoomNotice           规则说明与错误提示
```

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 座位网格骨架，保留九宫格形状 | `GET /rooms/{roomId}` 返回 |
| 空 | 空座位显示座位号与"空位"，不显示占位昵称 | 有人入座 |
| 错误 | 操作失败显示 `message` 与 `traceId`，并立即重查房间快照 | 重查成功 |
| 无权限 | 非房间成员且房间未满时仍可加入；不在房间且不可加入时返回大厅并提示 | 加入成功或返回大厅 |
| 断线重连 | 无订阅，无补发；网络恢复后提示"点击刷新" | 手动刷新成功 |
| 对局已结束 | 房间已开局时收到 `roomId` 已绑定 `gameId` 的响应，跳到 `/games/{gameId}` | 跳转完成 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 空座位卡 | 点击 | `POST /rooms/{roomId}/seats/{seatNo}/take` | 座位被占时提示并重查快照 |
| 我的座位卡 | 点击"离开座位" | `POST /rooms/{roomId}/leave` | 失败提示并重查快照 |
| 空座位卡的"设为 AI" | 点击 | `POST /rooms/{roomId}/seats/{seatNo}/ai` | 失败提示并重查快照 |
| AI 座位的"取消 AI" | 点击 | `DELETE /rooms/{roomId}/seats/{seatNo}/ai` | 失败提示并重查快照 |
| 准备按钮 | 点击 | `POST /rooms/{roomId}/ready` | 未入座时按钮禁用并给出原因文案 |
| 开局按钮 | 点击 | `POST /rooms/{roomId}/games` | 未满足条件时显示服务端 `message`；成功后前端路由到 `/games/{gameId}` |
| 返回大厅 | 点击 | 前端路由到 `/lobby` | 未离座时先提示是否需要 `POST /rooms/{roomId}/leave` |

**隐藏信息处理**

| 能看到 | 看不到 |
| --- | --- |
| 座位号、座位是否有人、该座位是否 AI 接管、各座位 `ready` | 角色、阵营、任何对局内信息（对局尚未开始） |
| 房间配置（服务端返回的部分） | 其他玩家的账号信息，只显示服务端给出的展示名 |

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 宽视口九宫格，窄视口改为可滚动座位列表；座位号始终可见 |
| 键盘 | 座位卡可 Tab 到达并用方向键在网格内移动；操作按钮在座位卡内按 Enter 展开 |
| 焦点 | 操作成功后焦点回到该座位卡；开局跳转前不做焦点重置 |
| 对比度 | 座位状态标签与卡片背景对比度不低于 4.5:1 |
| 不只靠颜色 | "已就绪/未就绪/AI 接管"同时有文字标签与形状差异 |

### 4.4 游戏页面（核心）

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 游戏桌、玩家卡牌、AI发言、投票区域、技能按钮 |
| 入口 | 路由 `/games/{gameId}`；房间开局后进入；对局中刷新或重连后回到此处 |
| 出口 | 结算面板提供的 `/games/{gameId}/replay` 与 `/games/{gameId}/analysis` |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `GET /games/{gameId}` | 初始与恢复用的 `GameView` |
| REST | `POST /games/{gameId}/actions` | 全部行动提交（`actionType` + `params` + `expectedVersion` + `Idempotency-Key`） |
| WS | `SUBSCRIBED` | 订阅结果，`from`、`truncated` 决定是否需要快照同步 |
| WS | `GAME_SNAPSHOT_SYNC` | 全量视图同步，刷新或 `truncated:true` 时到达 |
| WS | `GAME_EVENT` | 逐条领域事件，页面唯一的状态推进来源 |
| WS | `AGENT_SPEECH_DELTA` | AI 发言流式预览，非权威、可丢弃 |
| WS | `SYNC_REQUIRED` | 需要重新同步，客户端发 `RESUME{gameId,lastSeq}` |
| WS | `PONG` | 心跳应答，用于判定连接活跃 |
| WS | `ERROR` | 帧级错误，按错误码表处理 |

**页面结构**

```
GamePage
 ├─ PhaseBar              阶段指示器（来自 GameView.phase 与 PHASE_CHANGED）
 ├─ TurnBanner            当前回合 + 倒计时（deadline 来自 TURN_GRANTED）
 ├─ TableSeats            游戏桌座位卡 × 9
 │   └─ SeatCard{seatNo, 展示名, alive, controller.type, 选择态}
 ├─ SpeechFeed            发言区（PLAYER_SPOKE、SPEECH_SKIPPED、AGENT_SPEECH_DELTA 预览）
 ├─ VotePanel             投票区（目标选择、我的票、VOTE_TALLY_RESOLVED 结果）
 ├─ SkillDock             技能按钮（按角色与阶段显隐）
 ├─ NightPanel            狼刀 / 查验 / 用药的目标选择
 ├─ SpectatorBadge        观战与上帝视图标记（仅观战订阅显示）
 ├─ ResultPanel           结算面板（GAME_FINISHED / GAME_ABORTED）
 └─ ConnectionBanner      连接状态、重连倒计时、需要同步提示
```

**座位卡字段与渲染规则**

| 字段 | 来源 | 规则 |
| --- | --- | --- |
| `seatNo` | `GameView.players[].seatNo` | 固定 1..9，位置不随存活变化重排 |
| 展示名 | 服务端返回的展示名 | 空位显示"空位"，不生成占位昵称 |
| `alive` | `GameView.players[].alive`，更新由 `DEATH_ANNOUNCED` 触发 | 前端不判定死亡，不播放"推测死亡"动画 |
| `controller.type` | `GameView.players[].controller.type` | 仅用于显示"AI 接管"标记，不做能力区分 |
| 选择态 | 本地交互态 | 纯展示，提交后以事件为准 |
| 技能可用性 | `GameView.me.skillState` | 只控制按钮显隐与禁用，不作为合法性结论 |

**AI 与真人的前端同构**

| 规则 | 说明 |
| --- | --- |
| 视觉不做能力区分 | 座位卡不为 AI 与真人使用不同的布局、动效、字号或配色方案 |
| 只显示接管状态 | 唯一差异是一个中性的"AI 接管"文字标记，来源是 `controller.type`，不是行为推断 |
| 发言同构 | AI 的发言只通过 `PLAYER_SPOKE` 到达，与真人同一条渲染路径；`AGENT_SPEECH_DELTA` 只是预览 |
| 不推测操作者 | 事件的 `actorId` 不用于在界面上标注"这是 AI 投的票" |

**阶段与技能按钮显隐（仅体验，最终校验在服务端）**

| 阶段 | `actionType` | 按钮可见条件 | 服务端仍会校验 |
| --- | --- | --- | --- |
| `NIGHT_WOLF` | `WOLF_KILL` | `me.role = WEREWOLF` 且存活 | 角色、阶段、目标存活、目标非狼人阵营（INV-6） |
| `NIGHT_SEER` | `SEER_INSPECT` | `me.role = SEER` 且存活 | 角色、阶段、目标存活、次数 |
| `NIGHT_WITCH` | `WITCH_SAVE` / `WITCH_POISON` / `WITCH_PASS` | `me.role = WITCH` 且存活，`skillState` 对应项可用 | 同夜不得同用解药与毒药（INV-7） |
| `DAY_DISCUSS`、`LAST_WORDS` | `SPEAK` | `currentTurn.playerId = me.playerId` | 回合归属（`TURN_NOT_YOURS`）、长度 1..500 字符 |
| `DAY_VOTE` | `VOTE` | `me.alive = true` 且本轮尚未投票（该阶段无回合窗口） | 存活、目标存活、一人一票、弃权用 `params.abstain = true`（INV-10） |
| `HUNTER_SHOOT` | `HUNTER_SHOOT` | `me.role = HUNTER` 且 `skillState` 允许开枪 | 被毒不可开枪（INV-8） |
| 任意本人回合 | `SKIP` | 回合归属本人 | 服务端决定该阶段产生 `SPEECH_SKIPPED` 或对应事件，前端不预测 |

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 座位网格与阶段条骨架，倒计时不显示假值 | `GET /games/{gameId}` 或 `GAME_SNAPSHOT_SYNC` 到达 |
| 空 | 发言区无内容时显示"等待发言"，不显示示例文本 | 首条 `PLAYER_SPOKE` 到达 |
| 错误 | 命令失败按 §5.1 映射文案，保留用户输入 | 用户重试或同步完成 |
| 无权限 | `FORBIDDEN` 或 `ACTION_NOT_ALLOWED` 时进入只读展示，隐藏全部提交控件 | 重新进入有权限的对局 |
| 断线重连 | 顶部横幅显示"重连中"，输入与按钮禁用，保留已渲染内容 | `SUBSCRIBED` 与补发完成 |
| 对局已结束 | `status` 为 `FINISHED` 或 `ABORTED` 时隐藏全部行动控件，展示结算面板 | 用户离开页面 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 发言输入框 | 输入 | 无网络调用；本地长度校验不超过 500 字符 | 超长禁用提交并显示剩余字数 |
| 发送发言 | 点击或 Enter | `POST /games/{gameId}/actions`，`actionType=SPEAK`，带 `expectedVersion` 与 `Idempotency-Key` | `TURN_NOT_YOURS` 提示回合已过并触发 `RESUME` |
| 座位卡 | 点击选择目标 | 无网络调用，仅本地选择态 | 已死座位不可选，仅体验限制 |
| 投票确认 | 点击 | `POST .../actions`，`actionType=VOTE`，`params.targetSeatNo` | `ACTION_INVALID_TARGET` 保留面板并提示目标无效 |
| 弃权 | 点击 | `POST .../actions`，`actionType=VOTE`，`params.abstain=true` | 同上 |
| 技能按钮 | 点击 | `POST .../actions`，`actionType` 取 `WOLF_KILL` / `SEER_INSPECT` / `WITCH_SAVE` / `WITCH_POISON` / `WITCH_PASS` / `HUNTER_SHOOT` | `ACTION_NOT_ALLOWED` 隐藏该按钮并提示原因 |
| 跳过 | 点击 | `POST .../actions`，`actionType=SKIP` | 同上 |
| 结算面板·查看回放 | 点击 | 前端路由到 `/games/{gameId}/replay` | 无网络调用 |
| 结算面板·查看分析 | 点击 | 前端路由到 `/games/{gameId}/analysis` | 无网络调用 |
| 连接横幅·立即重连 | 点击 | WebSocket 客户端重连并发 `RESUME{gameId, lastSeq}` | 连续失败显示退避剩余时间 |

每个可点元素都在本表中有对应接口或帧，页面不出现无行为的"死控件"。

**倒计时**

| 项 | 规则 |
| --- | --- |
| 数据来源 | `TURN_GRANTED.payload.deadline`；不使用本地估算的回合时长 |
| 时钟校正 | 用服务端帧的 `ts` 与本地时钟求偏移，剩余时间按校正后计算 |
| 显示值 | 真人回合时限 30s，AI 回合时限 30s，只用于进度条刻度与文案 |
| 归零行为 | 显示"等待服务端推进"，不判定超时、不自动提交 `SKIP`、不本地切换阶段 |
| 与事件的关系 | 阶段推进一律以 `PHASE_CHANGED` 为准，`PHASE_CHANGED.resumePhase` 只用于提示"稍后回到哪个阶段" |

**隐藏信息处理**

| 能看到 | 看不到 | 由谁决定 |
| --- | --- | --- |
| 本人 `me.role`、`me.camp`、`me.skillState` | 其他玩家的角色与阵营 | 服务端 `VisibilityFilter`，`ROLE_ASSIGNED` 为 `PRIVATE` |
| `PUBLIC` 事件：`PHASE_CHANGED`、`TURN_GRANTED`、`PLAYER_SPOKE`、`PLAYER_VOTED`、`VOTE_TALLY_RESOLVED`、`DEATH_ANNOUNCED`、`HUNTER_SHOT`、`HUNTER_SKIPPED` | 夜间未公布的过程与死因 | `PLAYER_KILLED` 为 `JUDGE`，参与者收不到，也看不到 `cause` |
| 狼人阵营玩家的 `WOLF` 事件 | 非狼人阵营看不到 `WOLF_KILL_NOMINATED`、`WOLF_KILL_SELECTED` | 服务端按阵营分发 |
| 本人的 `PRIVATE` 事件 | 他人的 `SEER_INSPECTED`、`SEER_SKIPPED`、`WITCH_SAVED`、`WITCH_POISONED`、`WITCH_PASSED` | 服务端按本人分发 |
| 无 | `GAME_RECOVERED`（`JUDGE`） | 服务端不发 |

两条前端硬规则：第一，前端不用"某个帧没到"推断任何事实，帧未到只表示该信息对本人不可见；第二，
死亡展示只用 `DEATH_ANNOUNCED.payload.deaths[]`，不显示死因，除非当前订阅是观战者且确实收到了 `PLAYER_KILLED`。

**观战与赛后披露标记**

| 场景 | 标记 | 依据 |
| --- | --- | --- |
| 参与者订阅 | 无标记，界面只出现参与者可见内容 | `SUBSCRIBED.scope = PLAYER` |
| 观战者订阅进行中的对局 | 常驻"公开观战"标记，不展示私有事件、角色与库存 | `SUBSCRIBED.scope = SPECTATOR` 与服务端进行中状态 |
| 参与者打开同局观战页面 | 视图中 `me` 非空时提示返回本人对局页；WS 订阅保持 PLAYER，不能提升可见性 | 服务端参与者关系（[system-design.md](../architecture/system-design.md) §11） |

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 宽视口三栏（座位、发言、行动），窄视口单列且"行动区"固定在视口底部可滚动 |
| 键盘 | 座位卡支持 Tab 与方向键；发言框 `Ctrl+Enter` 或按钮提交；技能按钮按 Tab 顺序排在投票区之后 |
| 快捷键 | 使用浏览器原生 `KeyboardEvent` 监听，不拦截浏览器保留快捷键 |
| 焦点 | 新到达的发言不抢焦点；`currentTurn` 变为本人时把焦点移到发言框或行动区首按钮 |
| 对比度 | 发言文本与背景不低于 4.5:1；倒计时数字在剩余量变化时不做低对比度渐变 |
| 不只靠颜色 | 存活与出局用图标、文本与网格位置共同表达；阵营提示不依赖红蓝差异 |
| 动效 | 收到事件时的入场动效可被系统的"减少动态效果"偏好（浏览器原生 `matchMedia`）关闭 |

### 4.5 AI观战页面

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 观看全 AI 或人机对局；进行中仅公开记录，赛后完整记录（2026-10-12 所有者批准） |
| 入口 | 路由 `/watch/{gameId}`；大厅或房间提供的观战入口 |
| 出口 | 结算后进入 `/games/{gameId}/replay` 与 `/games/{gameId}/analysis` |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `GET /games/{gameId}` | 观战者视图的 `GameView` |
| WS | `SUBSCRIBED` | 确认服务端订阅身份与水位；SPECTATOR 不代表私有信息权限 |
| WS | `GAME_SNAPSHOT_SYNC` | 进入页面或 `truncated:true` 时的全量同步 |
| WS | `GAME_EVENT` | 触发授权 REST 视图与历史刷新；进行中仅 PUBLIC，赛后完整 |
| WS | `AGENT_SPEECH_DELTA` | 非权威预览（真实模型下按句流式，规则模式为已提交整段的展示帧）；观战页本轮不渲染预览，只显示已提交发言 |
| WS | `SYNC_REQUIRED`、`PONG`、`ERROR` | 同步、心跳与帧级错误处理 |

**页面结构**

```
SpectatePage
 ├─ SpectatorBadge       公开观战 / 赛后完整记录标记
 ├─ PhaseBar             阶段指示器
 ├─ TableSeats           座位卡（进行中隐藏角色，结束后披露）
 ├─ TimelineFeed         事件时间线（按 version 升序，可见性标签）
 ├─ SpeechFeed           已提交发言；决策分析留待 Phase 5
 └─ ConnectionBanner     连接状态
```

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 座位网格与时间线骨架 | `GAME_SNAPSHOT_SYNC` 到达 |
| 空 | 时间线为空时显示"对局尚未产生事件" | 首条 `GAME_EVENT` 到达 |
| 错误 | 帧级 `ERROR` 显示为非阻塞提示条 | 连接恢复 |
| 参与者 | 服务端返回本人视图 `me`；页面提示返回对局页，不继续观战历史读取或订阅 | 用户返回 `/games/{gameId}` |
| 匿名 | 允许读取公开 REST 记录，提供刷新；提示登录后实时同步，不建立未认证 WS | 登录后回到该页面 |
| 断线重连 | 横幅显示重连状态，时间线保留已渲染内容 | 补发完成 |
| 对局已结束 | 停止订阅并提示"回放已就绪"，提供回放入口 | 用户跳转 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 进入页面 | 路由挂载 | `GET /games/{gameId}` 与 `SUBSCRIBE{gameId}` | 拒绝时展示原因并提供 `/games/{gameId}` 入口 |
| 时间线条目 | 点击 | 展开该事件的 `payload` 详情，不发请求 | 无 |
| 按可见性筛选时间线 | 选择筛选项 | 无网络调用，只对已收到的帧做展示筛选 | 不重新请求、不请求未授权内容 |
| 查看某 AI 的分析 | Phase 5 接入后开放；在线观战当前隐藏该入口 | 待契约批准 | 不提供 mock 冒充真实分析 |
| 查看回放 | 点击 | 前端路由到 `/games/{gameId}/replay` | 无网络调用 |
| 暂停自动滚动 | 点击 | 无网络调用，停止时间线跟随 | 无 |

**隐藏信息处理**

| 能看到 | 看不到 |
| --- | --- |
| 进行中对局的 PUBLIC 事件、已公布死亡和公开状态 | 未披露角色、阵营、库存、WOLF / PRIVATE / JUDGE 事件、未公布死亡 |
| 赛后角色与 PUBLIC / WOLF / PRIVATE / JUDGE 完整记录 | 模型私有思维过程与未投影的决策分析字段 |
| 时间线上每条事件的 `visibility` 标签 | 未授权对局的任何内容 |

过滤完全由服务端完成。前端只对已收到的帧做展示筛选，"筛选"不改变收到的数据集合；
前端不提供"查看隐藏事件"的开关，因为不存在可查看的隐藏事件。

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 宽视口座位网格与时间线并排，窄视口上下堆叠，时间线保留最大高度 |
| 键盘 | 时间线条目可 Tab 到达，Enter 展开详情，方向键在条目间移动 |
| 焦点 | 展开详情不移动焦点；"暂停自动滚动"是可聚焦的切换按钮，状态可读 |
| 对比度 | `visibility` 标签在深色与浅色背景上分别校验不低于 4.5:1 |
| 不只靠颜色 | 四种 `visibility` 各有独立文字与形状，不只靠四种颜色 |

### 4.6 游戏回放

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 展示 Event 时间线与游戏过程 |
| 入口 | 路由 `/games/{gameId}/replay?from=&to=`；结算面板与观战页进入 |
| 出口 | 跳到 `/games/{gameId}/analysis` 查看同一局的决策过程 |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `GET /games/{gameId}/replay?from=&to=` | 指定版本区间的展示重放（[event-design.md](../architecture/event-design.md) §11） |
| WS | 无 | 回放为只读查询，不订阅推送 |

**页面结构**

```
ReplayPage
 ├─ ReplayToolbar        区间选择（from、to）、跳到开局、跳到结局
 ├─ ReplayTimeline       事件时间线（按 version 升序）
 ├─ ReplaySeatBoard      座位板（随区间终点状态渲染）
 └─ ReplayDetail         单条事件详情
```

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 时间线与座位板骨架 | 回放接口返回 |
| 空 | 区间内无事件时显示"该区间没有事件" | 调整 `from`、`to` |
| 错误 | 错误条与 `traceId`，保留已加载区间 | 重试成功 |
| 无权限 | `AUTH_REQUIRED` 跳首页会话面板；`FORBIDDEN` 显示无权查看该对局 | 登录或返回 |
| 断线重连 | 不适用（无订阅）；请求失败即错误态 | 重试成功 |
| 对局已结束 | 本页只面向已结束或已归档的对局；对局进行中时接口可返回有限区间，页面提示"对局进行中，回放内容可能不完整" | 对局结束后刷新 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 加载区间 | 提交区间 | `GET /games/{gameId}/replay?from=&to=` | 区间非法时前端先做范围校验，仍以服务端响应为准 |
| 上一条 / 下一条 | 点击 | 无网络调用，在已加载区间内移动游标 | 到边界时按钮禁用并说明原因 |
| 跳到开局 | 点击 | `GET /games/{gameId}/replay?from=1&to=...` | 同上 |
| 时间线条目 | 点击 | 无网络调用，展示事件详情 | 无 |
| 查看分析 | 点击 | 前端路由到 `/games/{gameId}/analysis` | 无网络调用 |
| 复制事件 id | 点击 | 无网络调用，写剪贴板（浏览器原生能力） | 剪贴板不可用时显示可选中文本 |

**隐藏信息处理**

| 能看到 | 看不到 |
| --- | --- |
| 赛后回放的 `PUBLIC`、`WOLF`、`PRIVATE`、`JUDGE` 事件 | 无（赛后回放对任意登录用户开放全部可见性） |
| 死亡事实 `PLAYER_KILLED` 与 `cause` | 未登录用户看不到任何事件 |

回放页展示的是服务端按 `visibility` 过滤后的结果；即使回放允许看到全部内容，
前端也不得依据回放数据反推并缓存其他对局的隐藏信息。

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 时间线在窄视口占满宽度，座位板折叠为可展开区域 |
| 键盘 | 时间线支持上下方向键移动、Home 与 End 跳到首尾 |
| 焦点 | 移动游标时焦点跟随条目并保持可见 |
| 对比度 | 时间线的版本号、阶段名与正文对比度均不低于 4.5:1 |
| 不只靠颜色 | 事件类型用文字标签表达，颜色只是辅助 |

### 4.7 AI分析页面

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 展示 Agent 策略、决策过程、胜率分析 |
| 入口 | 路由 `/games/{gameId}/analysis`；结算面板、回放页、观战页进入 |
| 出口 | 返回 `/games/{gameId}/replay` 或 `/games/{gameId}` |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `GET /games/{gameId}/analysis/agents/{playerId}` | 单个 Agent 的策略与决策过程 |
| REST | `GET /games/{gameId}/analysis/summary` | 对局级汇总与胜率分析 |
| WS | 无 | 分析为只读查询，不订阅推送 |

分析数据来自 `game:{id}:agent:{playerId}` 流的归档（[event-design.md](../architecture/event-design.md) §7.4），
这些 `AGENT_` 前缀事件不进入 `game:{id}:events`，前端也不通过事件通道接收它们。

**页面结构**

```
AnalysisPage
 ├─ SummaryPanel        对局汇总（双方阵营、轮次、结果）
 ├─ AgentSelector       本局 Agent 列表（按座位）
 ├─ StrategyPanel       候选动作、评分、硬约束
 ├─ DecisionTimeline    决策过程时间线
 ├─ CostPanel           模型、token 与延迟字段（字段缺失显示"暂无数据"）
 └─ ChartBlock          前端图表组件渲染的对比图
```

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 面板骨架与图表占位框 | 两个接口之一返回 |
| 空 | 该 `playerId` 没有决策记录时显示"该 Agent 没有决策记录" | 切换 `playerId` |
| 错误 | 错误条与 `traceId`，保留 `summary` 的已有内容 | 重试成功 |
| 无权限 | 对局进行中访问受限数据时显示"对局进行中，分析数据在结束后可用" | 对局结束 |
| 断线重连 | 不适用（无订阅） | 重试成功 |
| 对局已结束 | 正常态；分析页的主要使用场景 | 无 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 切换 Agent | 点击列表项 | `GET /games/{gameId}/analysis/agents/{playerId}` | 失败保留上一个 Agent 的内容并提示 |
| 展开决策步骤 | 点击步骤 | 无网络调用，展开该步骤字段 | 字段缺失显示"暂无数据" |
| 切换图表维度 | 点击 | 无网络调用，用已加载数据重绘前端图表组件 | 无数据时显示空状态而非空坐标系 |
| 跳到时间线 | 点击 | 前端路由到 `/games/{gameId}/replay?from=&to=` | 无网络调用 |
| 返回对局 | 点击 | 前端路由到 `/games/{gameId}` | 无网络调用 |

**隐藏信息处理**

| 能看到 | 看不到 |
| --- | --- |
| 服务端返回的该 Agent 策略、候选集、评分、硬约束 | 其他 Agent 的推理痕迹（需切换 `playerId` 并按服务端授权） |
| 对局级汇总 | 未授权对局的分析数据 |

分析页的数据在赛后可见，因此它不构成对局中的信息优势；页面仍然不提供"用分析结果影响当前对局"的入口。

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 图表在窄视口横向滚动或降级为表格；降级表格与图表数据字段一致 |
| 键盘 | Agent 列表与决策步骤均可 Tab 到达；图表提供等价的表格视图入口 |
| 焦点 | 切换 Agent 后焦点移到"该 Agent 面板"标题，便于屏幕阅读器定位 |
| 对比度 | 图表系列配色与背景对比度不低于 4.5:1，网格线不承载唯一信息 |
| 不只靠颜色 | 图表系列同时使用线型或标记形状，图例带文字标签 |

### 4.8 AI实验室

**目标与入口**

| 项 | 内容 |
| --- | --- |
| 目标 | 配置 Agent、批量测试、模型比较 |
| 入口 | 路由 `/lab` |
| 出口 | 批次详情内跳转到 `/games/{gameId}` 与 `/games/{gameId}/analysis` |

**数据来源**

| 来源 | 接口或帧 | 用途 |
| --- | --- | --- |
| REST | `GET /lab/agent-configs` | Agent 配置列表 |
| REST | `POST /lab/agent-configs` | 新建或更新 Agent 配置 |
| REST | `POST /lab/batches` | 发起批量对局 |
| REST | `GET /lab/batches/{batchId}` | 批次进度与结果 |
| REST | `GET /lab/comparisons` | 模型比较结果 |
| WS | 无 | 批次进度使用显式查询（见下） |

批次并发上限 8（[system-design.md](../architecture/system-design.md) §9），由服务端限制，前端只展示服务端的排队状态。
批次进度在本页使用"提交后按用户操作刷新"的方式获取 `GET /lab/batches/{batchId}`，不使用定时器轮询；
用户可在批次面板点击"刷新进度"。

**页面结构**

```
LabPage
 ├─ AgentConfigList      配置列表
 ├─ AgentConfigForm      配置表单（角色配置、策略参数）
 ├─ BatchPanel           发起批次与进度
 ├─ BatchResultTable     批次结果（每局结果与指标）
 └─ ComparisonChart      前端图表组件渲染的模型比较
```

**状态设计**

| 状态 | 表现 | 退出条件 |
| --- | --- | --- |
| 加载中 | 列表与图表骨架 | 接口返回 |
| 空 | 无配置时显示"还没有 Agent 配置"与新建入口；无批次时显示发起入口 | 创建后 |
| 错误 | 错误条与 `traceId`，表单保留已填内容 | 重试成功 |
| 无权限 | `FORBIDDEN` 显示"没有实验室权限"，隐藏全部提交控件 | 授权变更 |
| 断线重连 | 不适用（无订阅）；提交失败按 §5.1 处理 | 重试成功 |
| 对局已结束 | 批次内单局结束后可跳到回放与分析 | 无 |

**交互清单**

| 元素 | 触发 | 接口或帧 | 失败与边界 |
| --- | --- | --- | --- |
| 新建配置 | 提交表单 | `POST /lab/agent-configs` | 校验失败逐字段提示；`RATE_LIMITED` 显示退避 |
| 编辑配置 | 提交表单 | `POST /lab/agent-configs` | 同上 |
| 发起批次 | 点击 | `POST /lab/batches` | 超过并发上限时展示服务端的排队说明 |
| 刷新进度 | 点击 | `GET /lab/batches/{batchId}` | 失败保留上次进度并提示 |
| 批次内单局 | 点击 | 前端路由到 `/games/{gameId}` | 无网络调用 |
| 查看单局分析 | 点击 | 前端路由到 `/games/{gameId}/analysis` | 无网络调用 |
| 查看比较 | 点击 | `GET /lab/comparisons` | 无数据时显示空状态 |
| 切换比较维度 | 点击 | 无网络调用，重绘前端图表组件 | 无 |

**隐藏信息处理**

| 能看到 | 看不到 |
| --- | --- |
| 本人（或本团队）创建与授权范围内的配置、批次、比较 | 其他账号的配置与批次结果 |
| 批次内对局的公开结果 | 批次内对局的实时隐藏信息（若需查看，走对应对局的观战或回放授权） |

实验室页面不得通过"批量运行"绕过对局可见性；批次结果里出现的对局信息以服务端返回字段为准。

**响应式与无障碍要点**

| 项 | 要求 |
| --- | --- |
| 响应式 | 配置表单与结果表在窄视口改为单列；表格允许横向滚动但首列固定 |
| 键盘 | 表单全流程可 Tab；图表提供表格等价视图 |
| 焦点 | 提交成功后焦点移到结果区标题；错误时焦点移到首个出错字段 |
| 对比度 | 表格斑马纹不降低文本对比度，正文不低于 4.5:1 |
| 不只靠颜色 | 批次状态（排队、运行中、完成、失败）均有文字标签 |

## 5. 跨页通用约定

### 5.1 错误码到用户可见文案的映射

统一错误响应结构为 `{code, message, details[], traceId}`。前端按 `code` 决定行为，`message` 作为补充文案，`details[]` 用于字段级提示，`traceId` 一律在错误条中可复制。

| `code` | HTTP | 用户可见文案 | 前端行为 | 可重试 |
| --- | --- | --- | --- | --- |
| `AUTH_REQUIRED` | 401 | 登录状态已失效，请重新登录 | 清空会话，跳 `/` 并展开会话面板 | 登录后重试 |
| `FORBIDDEN` | 403 | 没有权限执行该操作 | 隐藏对应控件，进入只读展示 | 否 |
| `GAME_NOT_FOUND` | 404 | 对局不存在或已结束归档 | 提供返回大厅入口，清除本地订阅 | 否 |
| `GAME_VERSION_CONFLICT` | 409 | 对局状态已更新，正在同步 | 自动发 `RESUME{gameId,lastSeq}`，等待同步完成后提示重试 | 是 |
| `ACTION_NOT_ALLOWED` | 403 | 当前阶段或角色不能执行该操作 | 收起对应面板，刷新按钮显隐 | 否 |
| `ACTION_INVALID_TARGET` | 400 | 目标无效，请重新选择 | 保留面板与输入，清除选中目标 | 是 |
| `TURN_NOT_YOURS` | 409 | 已经不是你表态的时机 | 禁用行动区并触发同步 | 是 |
| `GAME_BUSY` | 409 | 对局正忙，请稍后重试 | 用同一 `Idempotency-Key` 按 200/400/800 ms 退避，最多重试 3 次（api-spec §1，2026-10-12 所有者统一），按钮显示等待态 | 是 |
| `GAME_ALREADY_FINISHED` | 409 | 本局已经结束 | 隐藏行动控件，展示结算面板与回放入口 | 否 |
| `GAME_RECOVERING` | 503 | 对局正在恢复，请稍候 | 显示恢复提示，禁用手动重试以外的操作 | 是 |
| `IDEMPOTENCY_REPLAY` | 200 | 无提示 | 按首次结果处理，不重复触发提示与动效 | 不适用 |
| `SERVICE_DEGRADED` | 503 | 服务暂时降级，部分功能不可用 | 全局横幅；写操作禁用，读操作保留 | 是 |
| `RATE_LIMITED` | 429 | 操作过于频繁，请稍后再试 | 禁用提交并显示退避剩余时间 | 是 |
| 其他未知 `code` | 任意 | 展示服务端 `message` 与 `traceId` | 不做状态改写，记录 `traceId` 便于排查 | 否 |

### 5.2 重连与补发流程

```
浏览器                     WebSocket 客户端                应用级状态容器                视图层
  │ 网络中断                   │                                │                          │
  │                            ├── 连接关闭（非 4401/4403）────▶│ 连接状态：重连中          │
  │                            │                                ├── 禁用提交 ─────────────▶│ 横幅 + 控件禁用
  │                            ├── 退避等待（1s、2s、4s…上限 15s）│                          │
  │                            ├── 重连成功后发 AUTH{token}       │                          │
  │                            │◀── AUTH_OK ────────────────────┤                          │
  │                            ├── SUBSCRIBE 或 RESUME{gameId,lastSeq}                     │
  │                            │◀── SUBSCRIBED{from,truncated} ─┤                          │
  │                            │     truncated=false：接收补发 GAME_EVENT                 │
  │                            │     truncated=true ：接收 GAME_SNAPSHOT_SYNC 覆盖视图     │
  │                            ├── 恢复完成 ────────────────────▶│ 连接状态：在线 ─────────▶│ 横幅消失
```

| 规则 | 内容 |
| --- | --- |
| RC-1 | `lastSeq` 只由应用级状态容器维护，取值是已成功归约的最大 `seq`。 |
| RC-2 | 收到 `seq <= lastSeq` 的帧直接丢弃，不重复归约、不重复播放动效。 |
| RC-3 | 收到 `seq > lastSeq + 1` 的帧时先缓存，并立即发 `RESUME{gameId, lastSeq}`。 |
| RC-4 | 收到 `SYNC_REQUIRED` 时停用提交，发 `RESUME`；若应答 `truncated:true`，用 `GAME_SNAPSHOT_SYNC` 重建整个投影。 |
| RC-5 | 关闭码 4401 与 4403 不重连，分别表示未认证与无权限，走 §5.1 的登录与只读分支。 |
| RC-6 | 重连期间不重放用户的未确认提交；提交重试由用户点击触发，并复用同一 `Idempotency-Key`。 |

### 5.3 乐观更新策略

| 允许的乐观更新 | 说明 |
| --- | --- |
| 输入框回显、字数计数 | 纯本地展示，与领域状态无关 |
| 目标选中态、面板展开收起 | 纯本地交互态 |
| 按钮的"提交中"禁用与等待指示 | 只表示请求在途，不表示操作已生效 |

| 禁止的乐观更新 | 原因 |
| --- | --- |
| 提前把座位标为出局 | 存活状态只能由 `DEATH_ANNOUNCED` 决定 |
| 提前累加票数或宣布投票结果 | 计票结果是 `VOTE_TALLY_RESOLVED` 的事实 |
| 提前切换阶段或推进轮次 | 阶段只由 `PHASE_CHANGED` 决定 |
| 提前显示技能已使用 | 技能消耗以 `WITCH_SAVED`、`WITCH_POISONED`、`HUNTER_SHOT` 等事件为准 |
| 提前写入发言到权威发言区 | 只有 `PLAYER_SPOKE` 是权威内容 |

规则：命令返回的 `version` 与事件不是同一个东西时，一律以事件为准；`IDEMPOTENCY_REPLAY` 的返回不产生任何界面变化。

### 5.4 倒计时与超时的展示

| 场景 | 展示 | 禁止 |
| --- | --- | --- |
| 有 `currentTurn` | 显示倒计时与进度，刻度按 30s（真人或 AI） | 用本地时长推断实际配置 |
| 倒计时归零 | 显示"等待服务端推进" | 本地判定超时、自动发 `SKIP`、自动切阶段 |
| 收到 `SPEECH_SKIPPED` | 在发言区插入一条"已跳过"记录 | 把它渲染成错误或断线 |
| 无 `currentTurn` | 不显示倒计时，不显示 0 秒 | 显示无来源的倒数 |

### 5.5 对局结束后的跳转

| 触发 | 展示 | 提供入口 |
| --- | --- | --- |
| 收到 `GAME_FINISHED` | 结算面板展示 `winner`、`reason`、`rounds`、`survivors[]` | `/games/{gameId}/replay`、`/games/{gameId}/analysis` |
| 收到 `GAME_ABORTED` | 中止面板展示 `reason` 与 `atVersion` | `/games/{gameId}/replay` |
| `GameView.status` 为 `FINISHED` 或 `ABORTED` | 直接进入只读模式并展示同一结算面板 | 同上 |

不自动跳转。结算面板停留在原路由，由用户点击进入回放或分析，避免打断遗言、结算信息的阅读。

## 6. 前端不做的事

| 编号 | 不做的事 | 依据或后果 |
| --- | --- | --- |
| D-1 | 不判定胜负，不计算 `winner` | 胜负只来自 `GAME_FINISHED`（INV-11、C-4） |
| D-2 | 不计算票数，不宣布出局或平票 | 计票结果只来自 `VOTE_TALLY_RESOLVED` 的 `tally[]`、`maxVotes`、`result` |
| D-3 | 不判定存活与死亡，不做本地复活或移除 | 死亡公布只来自 `DEATH_ANNOUNCED` |
| D-4 | 不缓存他人私有信息，不把收到的 `PRIVATE` 内容写入跨对局的持久存储 | 防止隐藏信息在前端沉淀（[system-design.md](../architecture/system-design.md) §11） |
| D-5 | 不用前端过滤代替服务端可见性过滤 | 服务端过滤是唯一实现（`VisibilityFilter`），前端二次过滤会造成泄漏假象 |
| D-6 | 不在 URL、日志、错误上报中放令牌与会话 id | 令牌只在 REST 请求头与 WebSocket 的 `AUTH` 帧中出现 |
| D-7 | 不用定时器轮询代替 WebSocket 推送 | 对局与房间的实时状态只走推送；列表类页面用显式查询刷新 |
| D-8 | 不推导 AI 行为，不标注"AI 一定会如何" | AI 的动作以事件形式到达，与真人同构（C-5） |
| D-9 | 不自行判定回合超时或代替用户提交 `SKIP` | 超时由服务端超时看护提交 `SkipAction` |
| D-10 | 不按 `visibility` 决定是否请求数据 | 请求不带可见性参数，服务端决定返回什么 |
| D-11 | 不在前端做规则预校验并阻止合法操作 | 前端只做形状校验与长度上限，合法性由 `ActionValidator` 判定 |
| D-12 | 不产生任何状态变化 | 页面没有写事件的能力，只有提交命令与消费事件两条路径 |
| D-13 | 不展示未由服务端返回的统计、胜率或指标 | 缺失时显示"暂无数据" |
| D-14 | 不直接调用任何 AI 接口改变对局状态 | 前端不感知 Agent Runtime 与工具网关 |

## 7. 验收清单

- [ ] 八个页面的路由与 `DESIGN.md` §14 顺序一致，无新增页面，登录与注册以首页会话面板实现。
- [ ] 每个页面的每个可点元素都能在 §4 的交互清单中找到对应的接口或帧，页面中没有死控件。
- [ ] 全局搜索不到任何具体第三方库名；前端依赖集合与 `DESIGN.md` 一致，没有新增依赖。
- [ ] 令牌不出现在 URL、路径参数、查询串、日志与错误上报中。
- [ ] 每个页面的"加载中、空、错误、无权限、断线重连、对局已结束"六种状态都有可渲染分支。
- [ ] `GameView` 的 `deathQueue` 与 `nightActions` 没有出现在任何页面的渲染输入中。
- [ ] 参与者订阅的页面上不出现 `PLAYER_KILLED`、`GAME_RECOVERED` 与任何 `cause` 字段。
- [ ] 观战页面明确标记进行中公开记录、赛后完整记录；不根据 SPECTATOR 自动放开角色。
- [ ] 参与者打开同局观战时提示回本人对局页；服务端订阅仍按 PLAYER 权限过滤。
- [ ] 倒计时数据全部来自 `TURN_GRANTED.payload.deadline`，归零后只有"等待服务端推进"文案，没有自动 `SKIP`。
- [ ] 技能按钮的显隐只依赖 `me.role`、`me.skillState` 与当前阶段，且提交失败时按错误码收起或提示。
- [ ] 发言输入前端限制不超过 500 字符，超长时禁用提交并显示剩余字数。
- [ ] `seq <= lastSeq` 的帧被丢弃，缺号触发 `RESUME`，`truncated:true` 触发 `GAME_SNAPSHOT_SYNC` 重建投影。
- [ ] 关闭码 4401 与 4403 不触发重连，分别走登录与只读分支。
- [ ] 错误码全部命中 §5.1 的映射表，未知 `code` 走兜底分支并展示 `traceId`。
- [ ] 每个页面通过键盘可完成主要流程，焦点可见，状态不只靠颜色表达，文本对比度不低于 4.5:1。
- [ ] 结算面板不自动跳转，回放与分析入口均由用户点击触发。
