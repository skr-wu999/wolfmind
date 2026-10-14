# Phase 4 本轮验收记录

日期：2026-10-12。范围：在线大厅、等待房开局、对局权限与技能交互、会话过期、首次订阅基线，以及本轮实时观战。
保留已批准的像素村庄视觉方向。本报告不代表赛后分析、实验室、完整回放或全产品的视觉验收。

## 自动化证据

| 命令 | 结果 | 日志 |
| --- | --- | --- |
| `mvn -o -pl server -am test`（backend） | 553 通过，0 失败、0 跳过 | [后端日志](../../backend/phase4-final-backend.log) |
| `npm test`（frontend） | 291 通过，25 个文件 | [单测日志](../../frontend/phase4-final-unit.log) |
| `npm run build` | 类型检查与构建成功 | [构建日志](../../frontend/phase4-final-build.log) |
| `npm run e2e:adapters` | 15 通过，0 跳过 | [适配器日志](../../frontend/phase4-final-adapters.log) |
| `npm run e2e:pages` | 19 通过，0 跳过 | [页面日志](../../frontend/phase4-final-pages.log) |
| `npm run e2e:ws` | 4 通过，0 跳过 | [协议日志](../../frontend/phase4-final-ws.log) |

联调连接本地规则模式后端，真人 fixture 使用真实认证、房间、命令与事件，不调用付费模型。
jsdom 页面测试包含实际 HTTP / WebSocket，但不用于宣称真实浏览器视觉验收。

## 交互记录

| 控件或场景 | 实际验证 | 验证方式 |
| --- | --- | --- |
| 登录 | 开发同源代理登录成功；过期后回到登录页；不存在的旧对局返回明确错误及大厅入口 | 真实浏览器；失效分支另有真实网络页面测试 |
| 创建房间 | 输入“在线收尾验收”，创建并进入服务端分配的等待房 | 真实浏览器 |
| 准备 | Enter 后准备状态更新，开局条件同步 | 真实浏览器 |
| 用 AI 补齐空位 | Space 选中后显示补齐 8 个座位，开始按钮启用 | 真实浏览器 |
| 开始游戏 | Enter 调用真实开局接口，进入 1 真人 + 8 AI 的九人对局 | 真实浏览器 |
| 在线终局 | 同一人机对局达到第 3 天 `GAME_OVER`；刷新后显示狼人胜利、中文结算原因和停用行动。本人的未提交回合由服务端超时处理 | 真实浏览器；真人主动完整交互另由真实网络测试覆盖 |
| 另一成员进入同局 | 刷新已开局房间，路由进入相同 `gameId`，本人座位为 2 | 真实网络页面测试 |
| 规则弹窗 | Enter 打开，初始焦点在关闭按钮；Escape 关闭并返回“查看规则” | 真实浏览器 |
| 狼人目标与确认 | 目标选择后提交，权限随权威视图消失，未泄漏给其他角色 | 真实网络页面测试及服务端可见性测试 |
| 预言家查验与确认 | 提交后本人私有记录出现被查验目标与身份 | 真实网络页面测试 |
| 女巫救人 | 说明和确认目标取当前刀口；按钮提交正确目标 | 真实网络页面测试 |
| 女巫毒药与不用药 | 两条命令均经真实规则处理；禁止自救时目标为空、救人禁用、仍可不用药 | 真实网络页面测试及领域测试 |
| 猎人开枪与放弃 | 开枪后死亡更新；放弃后不重复开枪，可进入遗言 | 真实网络页面测试 |
| 遗言与白天发言 | 遗言显示“遗言”，死者可提交；白天发言按当前发言者授权 | 真实网络页面测试 |
| 投票与弃票 | 提交合法目标或空票，提交后不可重复操作 | 真实网络页面测试 |
| 重试 | 初次网络失败显示错误与重试，恢复网络后读取九人权威视图 | 真实网络页面测试 |
| 初次订阅 | 收到授权快照，`seq = replayedTo`，基线前无历史 `GAME_EVENT` | 服务端测试及真实 WS 测试 |
| 切换账号后的迟到响应 | 延迟旧账号的较高版本响应，再读取新账号视图；旧请求拒绝写回，新账号角色与投票权限保持正确 | 可控延迟单元测试；修复前已复现失败 |
| 断线恢复与终局 | 物理断线、`SYNC_REQUIRED` 后恢复，终局版本与 REST 一致 | 真实网络页面与适配器测试 |

浏览器验收对局：`g_r_mv45148l_1g`。fixture 来自本轮临时后端，关闭后不保留对局。
手机 viewport 390 × 844：文档可用宽 375、scrollWidth 375。
桌面 viewport 1440 × 900：文档可用宽 1425、scrollWidth 1425。
两者均无横向溢出，实际显示九个座位、本人身份及服务端计时。

截图：[手机](../../frontend/test-artifacts/phase4-online-mobile.png)、[桌面](../../frontend/test-artifacts/phase4-online-desktop.png)。
手机截图记录第一天投票；桌面截图记录最终代码下第三天终局，赛后角色披露来自服务端。

## antislop Delivery Gate

模式：during，用户本次会话选择。以下 PASS 限于本轮改动，保留的其他页面没有被纳入全面审查。

设计方向：沿用像素村庄、木框、纸色信息板，行动区域为焦点；ENERGY 2 / RHYTHM 1 / MOTION 1。

- Hard Gate / R-02 PASS：本轮页面可见文本无破折号；等待房空身份改为“空缺”。
- Hard Gate / R-03 PASS：390 与 1440 两个 viewport 的 scrollWidth 均等于文档可用宽度；对局 ID 可换行。
- Hard Gate / R-17、R-18、R-36、R-38 PASS：人数、角色、目标、准备状态均来自实际 fixture 服务端；未新增统计、证言或模型能力声称。
- Hard Gate / R-23 PASS：未新增 logo、头像或导航结构，沿用现有资产；截图记录实际运行页面。
- Hard Gate / R-24 PASS：在线模式隐藏 demo 路径；登录回跳、大厅、等待房和真实 gameId 导航由上述页面测试及浏览器走通。
- Hard Gate / R-25 PASS：沿用文字颜色的实测对比度为 11.65、5.79、5.17、5.87，均高于 4.5；分别对应正文、次要文本、强调文字及深色按钮文字。
- Hard Gate / R-26 PASS：新增 AI 补位控件改变真实开局参数，开始按钮调用真实接口；各技能与重试行为见逐项交互表。
- Hard Gate / R-27 PASS：加载时行动停用，网络错误有重试及大厅入口；准备不足、空座位、技能无合法目标均有明确状态，真实网络页面测试通过。
- Hard Gate / R-28 PASS：没有新增 FAQ。
- Hard Gate / R-32 PASS：实际使用 Enter 准备/开局、Space 补位、Enter/Escape 开关规则弹窗；焦点返回触发按钮，补位 checkbox 有可见焦点边框。
- Hard Gate / R-33 PASS：改动直接写入 Vue、TypeScript、CSS 源码，未用外部脚本补丁注入功能或样式。
- Hard Gate / R-34 PASS：未新增主题切换；昼夜使用已有村庄样式，不改变主题模型。
- Hard Gate / R-35 PASS：实际启动前后端、运行构建并完成上表逐项交互；浏览器与自动化证据分别标记。
- Hard Gate / R-37 PASS：编辑前已声明保留原视觉方向与三项 dials，未重做页面。
- Purpose-Gate / R-01、R-07、R-10、R-12、R-13 PASS：没有新增渐变、网格、玻璃或发光；木框和村庄背景沿用既有环境，用于区分游戏桌与操作区域。
- Purpose-Gate / R-04、R-06、R-22 PASS：现有像素图标与字体对应村庄主题及角色操作；未引入通用插画或图标库。
- Purpose-Gate / R-08、R-09 PASS：保留大厅返回箭头用于方向导航，AI / 本人标签对应实际控制者与身份，无装饰性营销徽章。
- Purpose-Gate / R-14、R-19 PASS：九个座位同尺寸便于比较，操作区与日志分层；未新增动画，沿用现有反馈过渡。
- Liveliness / Dials PASS：ENERGY 2 / RHYTHM 1 / MOTION 1 与截图的像素场景、稳定区域和少量过渡一致。
- Liveliness / Focal point PASS：等待房以准备与开局为操作终点；对局桌与右侧目标面板构成主次。
- Liveliness / Whitespace PASS：桌面两列分隔桌与行动，手机纵向重排，信息板内部留白区分目标、说明与提交。
- Liveliness / Accent PASS：沿用红棕色作为当前行动按钮的强调色，未增加新强调色。
- Liveliness / Identity PASS：木框、像素头像和纸色面板在大厅、等待房和对局页连续使用。
- Liveliness / Design Read PASS：方向在编辑前已声明，未用技能默认模板覆盖已批准页面。
- Craftsmanship / C-1、C-3、R-05、R-20、R-30、R-31 PASS：布局沿用产品的九人桌和像素村庄；本轮只修复真实行为及说明，没有加入模板填充区块。
- Craftsmanship / C-2 PASS：新增开局行为和补位选择均经实际点击；修改的权限和确认流程有真实网络页面证据。
- Craftsmanship / C-4 PASS：本轮涉及的加载、错误、认证失效、无权限、手机/桌面及键盘状态由上表验证；多账号跨浏览器整局仍列为后续验收，不作已验证声称。
- Craftsmanship / C-5 PASS：计时、存活和权限以服务端数据为准；不把规则模式或完整发言展示称为真实模型流式推理。
- Quality Locks / R-11、R-15、R-16 PASS：沿用现有方形按钮；文案使用“准备”“开始游戏”“重试”“用 AI 补齐空位”，未新增泛化 CTA 或营销词。
- Quality Locks / R-21、R-29 PASS：保留已有昼夜场景和纸色正文；新控件复用既有红棕强调色，无新增配色体系。

## 未验收范围

各角色的完整浏览器键盘流程、跨浏览器及 200% 缩放尚未逐项验收。多账号真实浏览器联合整局已在 Redis 模式下补做，
见文末“Redis 模式与多账号浏览器联合整局”。
实时观战已按所有者批准的“进行中公开记录，赛后完整记录”完成本轮收尾，详见下节。
模型流式发言已在服务端实现并用真实模型做了协议层验收（见文末），浏览器截图未取得；分析与实验室仍属于 Phase 5。

## 实时观战最终验收

以下为恢复任务后最终版本的证据；上方 553 / 291 / 19 等数字保留为较早批次基线。

| 检查 | 结果 | 日志 |
| --- | --- | --- |
| 后端全套 | **554 通过，0 失败、0 跳过**：engine 97、server 457 | [后端](../../backend/phase4-spectator-backend.log) |
| 前端单测 | **301 通过**，26 个文件 | [单测](../../frontend/phase4-spectator-unit.log) |
| 类型检查与构建 | 成功 | [构建](../../frontend/phase4-spectator-build.log) |
| 真实适配器 | **15 通过，0 跳过** | [适配器](../../frontend/phase4-spectator-adapters.log) |
| 真实网络页面 | **22 通过，0 跳过**，含观战 3 例 | [页面](../../frontend/phase4-spectator-pages.log) |
| 真实 WebSocket | **4 通过，0 跳过** | [协议](../../frontend/phase4-spectator-ws.log) |
| Java 编码 | 348 个文件，BOM 0 | 字节扫描 |

新增观战单测验证会话切换、取消旧请求、版本窗口分页、快照权威状态、匿名登录提示、参与者返回本人对局、终局停订阅、恢复基线倒退与读取历史期间发生终局。后端修复夜间死亡过早标为 announced；猎人窗口结束时发布尚未公布的死讯，测试在公布前后分别验证重放等价。（2026-10-12 规则调整：夜里出局的猎人改为先公布当夜死讯再开枪，见 `domain-model.md` §4。）

真实网络观战页面测试连接真实后端，验证 1 真人 + 8 AI、另一账号观战、物理断线后自动恢复、公开事件与隐藏角色、终局完整角色/私有事件、版本一致及去重。规则模式不调用付费模型。

浏览器 fixture `g_r_mv4ra8m4_1` 使用九名真人账号与独立观战账号。九名真人的实际命令及服务端超时推进对局，最后 49 条合法命令由本地验收驱动提交；观战浏览器持续接收公开状态，结束后显示第三天好人获胜、九个角色披露、UI / REST 均为 v147。这不等于多个浏览器同时操控九个真人座位。

| 控件 | 浏览器实际结果 |
| --- | --- |
| 大厅观战入口 | 登录独立测试账号，分页后 Enter 打开服务端 gameId 的观战页面 |
| 进行中 | 常驻公开观战说明，九个座位无角色标签；事件筛选只提供公开范围；没有播放/进度/假决策摘要控件 |
| 事件方向键与 Enter | ArrowDown 从首事件移到下一事件，Enter 打开该事件详情 |
| 复制 / Escape | 显示“事件 ID 已复制”；Escape 关闭后焦点返回原事件按钮 |
| 座位详情 | 进行中显示真人、存活和“当前视角不展示角色”，没有猜测身份 |
| 终局 | 自动显示结束、v147、九个角色与完整记录；测试证明终局停止 socket |
| 可见性筛选 | 赛后选择 WOLF 后只显示狼人事件，恢复 all 显示完整已授权记录 |
| 观战视角 | 切为 public 后角色标签计数为 0，切回 all 恢复完整角色 |
| 自动跟随 | Enter 切换开/关，aria-pressed 与按钮文案同步；不暂停服务器对局 |
| 刷新 | 请求最新授权快照，仍显示终局 v147 |
| 发言日期 | 选择第一天，仅显示第一天提交记录；恢复全部天数 |
| 玩法 / 关于 / 音效 | 规则说明与公开政策一致；关于说明已接在线数据；音效开关有实际 pressed 状态 |

响应式检查：390 × 844 可用宽 375 / scrollWidth 375；900 × 900 可用宽 885 / scrollWidth 885；1440 × 900 可用宽与 scrollWidth 均不超视口。手机为单列与双列座位卡，中屏为桌/信息两列，桌面为三栏。手机主导航、视角 select 均实测 **44px 高**，刷新按钮 **48px 高**。

截图：[进行中手机](../../frontend/test-artifacts/phase4-watch-mobile.png)、[中屏当前视口](../../frontend/test-artifacts/phase4-watch-tablet.png)、[赛后桌面](../../frontend/test-artifacts/phase4-watch-desktop.png)。

## 观战 antislop Delivery Gate

范围限本轮新增/修改的观战与大厅入口；不替代未执行的全产品、跨浏览器或 200% 缩放验收。ENERGY 2 / RHYTHM 1 / MOTION 1，保留像素村庄、木框与营火桌，事件记录为信息焦点。

- R-01 PASS：未新增渐变；沿用纸色面板和村庄场景。
- R-02 PASS：新增用户文案无破折号。
- R-03 PASS：手机、中屏、桌面宽度与 scrollWidth 相等，导航及视角选择达到 44px。
- R-04 PASS：复用眼睛/文档/角色图标，分别对应观战、事件和座位。
- R-05 PASS：营火桌、名单、记录按产品内容布局，无模板区块。
- R-06 PASS：沿用已批准的像素字体，正文与记录使用既有可读字形。
- R-07 PASS：没有新增网格或点阵背景。
- R-08 PASS：观战入口使用眼睛图标，未添加装饰性箭头。
- R-09 PASS：公开/赛后标签说明实际披露范围，无营销徽章。
- R-10 PASS：未新增玻璃效果。
- R-11 PASS：按钮、选择框复用现有方形控件。
- R-12 PASS：木框用于信息区域边界，未新增悬浮大阴影。
- R-13 PASS：未新增发光。
- R-14 PASS：座位卡同尺寸便于比较；信息、发言、事件按内容分层。
- R-15 PASS：使用“观战”“刷新记录”“复制事件 ID”等明确动作。
- R-16 PASS：无新增营销术语。
- R-17 PASS：AI 人数、天数、角色、存活与版本来自真实服务端视图。
- R-18 PASS：无推荐语或虚构真人身份。
- R-19 PASS：未新增动画；实时事件不抢焦点。
- R-20 PASS：像素村庄、营火桌与九人座位保留产品识别。
- R-21 PASS：沿用原有昼夜场景，未另造主题模式。
- R-22 PASS：没有新增通用插画。
- R-23 PASS：没有新增资产；既有头像仍按座位派生。
- R-24 PASS：观战、大厅、回放路由存在；在线 mock 分析入口隐藏。
- R-25 PASS：实际 CSS 纸色 #f7f0df；正文 #2b3226 对比 11.65:1，静态说明 #57634f 为 5.60:1，公开标签 #4e5945 为 6.51:1，事件标签 #58614c 为 5.72:1；contrast-check.py 实算均通过。
- R-26 PASS：改动控件的实际行为见上方浏览器记录；直播不显示无效的本地播放器。
- R-27 PASS：加载/空事件/失败/重试/登录提示/参与者返回状态有页面或单元测试。
- R-28 PASS：未新增 FAQ。
- R-29 PASS：复用木色、纸色和原有红棕强调色。
- R-30 PASS：没有引入其他产品的布局模板。
- R-31 PASS：三栏用于并列比较桌、名单、事件；手机按阅读顺序堆叠。
- R-32 PASS：方向键、Enter、Escape 与焦点返回实际验证，已有 focus-visible 保留。
- R-33 PASS：源码通过 apply_patch 编辑；未用运行时 CSS/DOM 注入修改页面。
- R-34 PASS：既有昼夜共用纸色信息面板，未增加主题切换。
- R-35 PASS：构建、301 单测、22 真实网络页面及浏览器交互均实跑；截图留存。
- R-36 PASS：未将规则模式标为真实 LLM 推理或流式能力。
- R-37 PASS：保留已批准方向及 ENERGY 2 / RHYTHM 1 / MOTION 1。
- R-38 PASS：无编造角色、摘要、总天数、房间名或房间 ID。
- C-1 PASS：新增说明对应公开政策，布局对应已有游戏桌与事件。
- C-2 PASS：观战链接及筛选/详情/刷新均有实际行为。
- C-3 PASS：没有额外填充区域或虚假分析板。
- C-4 PASS：本轮手机、中屏、桌面与所列状态有验收证据，未将未执行项称为通过。
- C-5 PASS：以服务端授权事实为准，观战不会计算规则或胜负。
- Liveliness / Dials PASS：保持 2 / 1 / 1，没有新增动效。
- Liveliness / Focal point PASS：营火桌显示当前对局，右侧事件提供查看路径。
- Liveliness / Whitespace PASS：桌、名单、记录之间的区域间距清晰。
- Liveliness / Accent PASS：当前动作沿用红棕强调色。
- Liveliness / Identity PASS：木框、纸色、像素头像连续复用。
- Liveliness / Design Read PASS：接续时明确保留已有视觉方向，未重做设计。

剩余：多账号跨浏览器联合操控整局、逐角色完整浏览器键盘流程与 200% 缩放未逐项验收。当前观战结论仅覆盖本报告列出的实际证据。

## Redis 模式与多账号浏览器联合整局

日期：2026-10-12。后端以 `-Dwolfmind.storage=redis` 运行（Redis 8.10.1 容器，账号库为 SQLite），
运维假设见 [redis-schema.md §14](../backend/redis-schema.md)。上方各节的数字保留为内存模式的早期基线。

### 自动化证据

| 检查 | 结果 | 日志 |
| --- | --- | --- |
| 后端全套 `mvn -o -pl server -am test` | **584 通过，0 失败、0 跳过**：engine 97、server 487；含 Redis 契约等价、版本冲突、锁/幂等与 Spring 两次启动的重启恢复测试 | [后端](../../backend/phase4-redis-final-backend.log) |
| 前端单测 | **304 通过**，26 个文件 | [单测](../../frontend/phase4-redis-unit.log) |
| 类型检查与构建 | 成功 | [构建](../../frontend/phase4-redis-build.log) |
| 真实适配器（Redis 模式，干净库） | **15 通过，0 跳过** | [适配器](../../frontend/phase4-redis-adapters.log) |
| 真实网络页面（Redis 模式） | **22 通过，0 跳过** | [页面](../../frontend/phase4-redis-pages.log) |
| 真实 WebSocket（Redis 模式） | **4 通过，0 跳过** | [协议](../../frontend/phase4-redis-ws.log) |
| `e2e-smoke.mjs`（Redis 模式） | **44 通过，0 失败** | [冒烟](../../frontend/phase4-redis-smoke.log) |

内存模式下同一组真实接口测试也重跑通过，数量相同。冒烟第 7 节按首次订阅协议改为校验授权快照
（本人角色可见、狼人只额外看到队友、不重发历史 `ROLE_ASSIGNED`、`seq` 严格递增），不再期待历史事件重放。

### 浏览器联合整局

对局 `g_r_mv4vd3w9_1`：两个真人账号 alice（1 号，预言家）与 bob（2 号，猎人）在 Cursor 内置浏览器中分别登录，
其余 7 席为 AI。

| 场景 | 实际结果 |
| --- | --- |
| 开局与第 1 夜 | 两个账号的座位卡只标出本人身份（1 号预言家、2 号猎人），其余座位无角色标签；行动面板按本人角色显示 |
| 对局中重启后端 | 第 2 夜重启后端进程；同一令牌仍有效，对局版本、身份、计时与预言家私有查验记录保持；启动日志显示为 1 个进行中对局重建 AI Agent |
| 私有技能记录 | alice 的技能记录标注“仅自己可见”，内容为本人两次查验结果（4 号狼人、5 号好人） |
| 猎人回合 | 2 号被放逐后进入猎人开枪窗口，后端日志显示等待真人行动，而不是由 AI 代为决定 |
| 终局 | 第 4 天狼人阵营获胜；两个账号均显示结束状态、九个角色披露与相同结算 |

截图：[alice 第 1 夜](../../frontend/test-artifacts/phase4-redis-alice-night1.png)、
[bob 第 1 夜](../../frontend/test-artifacts/phase4-redis-bob-night1.png)、
[alice 重启后](../../frontend/test-artifacts/phase4-redis-alice-after-restart.png)、
[alice 私有技能记录](../../frontend/test-artifacts/phase4-redis-alice-private-skill-log.png)、
[alice 终局](../../frontend/test-artifacts/phase4-redis-alice-game-over.png)、
[bob 终局](../../frontend/test-artifacts/phase4-redis-bob-game-over.png)。

### 模型流式发言

真实模型模式下，LangChain4j 流式输出按句经泄密检查后发送 `AGENT_SPEECH_DELTA`，`speechId` 与随后提交的
`PLAYER_SPOKE` 相同；`PLAYER_SPOKE` 只提交一次，网关不再为已流式过的发言补发整段预览。
自动化测试使用模拟的流式模型；本轮没有用真实付费模型做端到端或浏览器验收。

### Playwright 三浏览器冒烟（2026-10-12）

`npm run e2e:browsers`（`e2e/smoke.spec.ts`）在 Chromium、Firefox、WebKit 各跑一遍：在村庄样式的登录页注册登录 → 大厅
（新账号无“继续对局”）→ 经 API 建房、准备、AI 补位开局 → 刷新大厅出现“你有一局正在进行。继续对局” → 点击进入
`/games/{gameId}`，阶段标题为第 1 夜，同步横幅消失（WebSocket 认证、订阅与快照基线完成）。后端为 Redis 模式。
三浏览器均通过。截图：
[Chromium 登录](../../frontend/test-artifacts/phase4-pw-chromium-login.png)、
[WebKit 大厅继续对局](../../frontend/test-artifacts/phase4-pw-webkit-lobby-resume.png)、
[Firefox 对局](../../frontend/test-artifacts/phase4-pw-firefox-game.png)，其余组合同名。

同一时段 5 局终局对局全部由 `TerminalGameArchiver` 归档进 SQLite，核对一致后 Redis 保留期才缩到 7 天（细节见 `TASKS.md` Phase 4 当前交接）。

### 仍未验收或已知问题

- 键盘与缩放验收（2026-10-12，Chromium，演示模式 `/games/demo`）：用 Playwright 只按 Tab / Enter / Ctrl+Enter 完成发言、投票、预言家、狼人、
  女巫（解药）、猎人 6 个场景，桌面 1280×800、200% 缩放（640×400 CSS 像素）、手机 390×844 三种视口 18 组全部完成提交；无横向溢出；
  每个 Tab 停靠点都有焦点环、在视口内且未被遮挡。验收中发现并修复两处：提交后对话框把焦点还给已消失或禁用的按钮，焦点落回 `body`
  （现改为落到行动面板）；矮屏与窄屏下底部固定的操作栏遮住 Tab 滚到的座位卡、目标与页脚链接（`RoleSkillBar` 把自身高度写入根元素
  `scroll-padding-bottom`）。截图 `frontend/test-artifacts/a11y-{desktop,zoom200,phone}-*.png`。
  仍未验收：Firefox / WebKit 的键盘流程、屏幕阅读器朗读、真实后端下的键盘整局；目标按钮组是逐个 Tab 而非方向键漫游，
  page-design 未要求，未改。
- 对局枚举对全部事件流键做 SCAN（所有者决定当前规模下保留，规模上限见 `redis-schema.md` §14.3）。全员挂机的对局会在连续 3 轮无人出局后以 `GAME_ABORTED(reason=STALLED)` 中止，前端显示「对局中止」面板。
- 真实付费模型的流式发言已在协议层验收（2026-10-12，DeepSeek `deepseek-flash`，整局 14 条发言：13 条多片预览、序号全部连续、
  13 条拼接与提交内容一致，详见 `TASKS.md`）；修复后已复跑一局并取得浏览器截图（见下）。
- 已解决（2026-10-12）：投票阶段 AI 串行调用模型、累计超过 45 s 时末位 AI 被记弃权；现为并发派发，真实模型复跑中所有 AI 均在 20 s 内投出。
- 修复后复跑的浏览器截图：[流式发言](../../frontend/test-artifacts/phase4-llm2-streaming.png)、
  [遗言](../../frontend/test-artifacts/phase4-llm2-vote.png)、[终局](../../frontend/test-artifacts/phase4-llm2-game-over.png)。
- 已解决（2026-10-12）：狼人与女巫发言在 AI 时限处退成兜底模板。AI 回合时限改为 30 s；发言回合低强度推理（`WOLFMIND_LLM_THINKING=speech-low`）；
  阻塞模型调用按回合剩余时间截断；新增复读检查。复跑对局 `g_r_mv56ste9_1`：21 条发言 0 条模板，平均 2.6 s。
- 已实现（2026-10-12）：等待房改为 WebSocket 房间订阅（`ROOM_SUBSCRIBE` / `ROOM_SNAPSHOT`），开局自动进入对局；
  双浏览器实机验收已完成（2026-10-12，对局 `g_r_mv59g8y2_3`，6/6 通过，详见 `TASKS.md` 剩余事项 4；截图 `frontend/test-artifacts/room-ws-*.png`）。
- 已解决（2026-10-12）：Redis AOF + 挂卷；终局自动归档；`GAME_BUSY` 统一最多 3 次；登录页样式、猎人面板标题、“当前发言”串天、大厅 `activeGameId`、D-2 缺号丢弃。
