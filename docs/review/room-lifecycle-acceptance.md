# 房间退出验收

2026-10-14。本文件记录本地修改与验证；当时未更新阿里云。后续已按所有者授权完成镜像构建与服务器发布，见 [实际发布记录](../ops/releases/20261014-fix2.md)。

## 结果与范围

参与者主动退出先提交服务器命令，成功后返回大厅，运行中显示确认弹窗；失败原地重试。
等待房退出释放座位并移交房主，最后真人离开关闭房间。运行房退出保存接管意图，Game 控制者改为 AI，保留角色、存活状态及已提交票。
最后真人离开中止运行中的普通对局；正常结算也自动关闭房间，大厅只显示活动房间，回放保留。
NO_HUMANS/ROOM_CLOSED 不计胜负与胜率分母，仍保存成本及历史。

离线按账号汇总房间/对局订阅，默认宽限 120 秒、补漏每 30 秒；重连取消计时，多标签合并，观战不能为别的账号保活。
重启重新给予宽限；补漏按已保存的 Room 退出意图修复 Game，处理失败不会堵住其他房间。
AI 专用竞技场/实验室不属于普通房间扫描。大厅可见列表每 15 秒更新。

此次修改保留分层与现有事件格式，新增 POST /api/v1/games/{gameId}/leave、摘要 humanCount/aiCount。
同时修复 Room 控制者事件的解码与 Redis 消费组分流，并修复 ABORTED 保留旧 phase 时前端仍显示夜间行动的问题。
契约见 [room-lifecycle.md](../backend/room-lifecycle.md)，发布步骤见 [room-exit-release.md](../ops/room-exit-release.md)。

## 实际执行的检查

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 后端全量 `mvn -o -pl server -am ... test package` | 1006 项，0 失败、0 错误；性能基准 1 项跳过 | backend/room-lifecycle-final.log；engine 125、server 881，Surefire XML |
| 强制真实 Redis 验收 | 45 项实际执行，非离线跳过；临时容器与 DB15，不操作业务 DB0 | Redis* Surefire XML；wolfmind.redis.required=true、port=56498 |
| 真人已投票后退出 | 票保留，旧真人提交拒绝 | RoomLifecycleServiceTest.takeoverAfterVotingKeepsTheCommittedVoteAndRejectsTheOldHuman |
| 正常终局与重启 | Redis/SQLite 重启恢复后打完整局；cg-projection 自动关闭房间，大厅默认空 | RedisRestartAcceptanceTest |
| 退出、断线、补漏 | 陌生账号拒绝、重复退出、宽限重连、锁内再次检查在线、跨聚合中途失败补做 | RoomLifecycleServiceTest；RoomApiContractTest |
| 多标签/观战隔离 | 同账号一标签断开仍在线，全部断开离线，其他账号观战不代替在线 | RoomSubscriptionLiveTest |
| 前端单元测试 | 32 文件、400 项通过 | frontend/room-lifecycle-all.log |
| 类型与构建 | vue-tsc、同源真实 API 的 Vite 生产构建通过 | frontend/dist；构建终端输出 |
| 真实 Chromium，两账号 | 内存与 Redis 两种后端模式均通过；先接管后中止，真人 1/AI 8，默认列表移除，回放入口保留 | frontend/room-exit-browser.log、room-exit-browser-redis.log |
| 浏览器最终检查 | Escape 取消并恢复焦点、Enter 确认退出、320/375/768/1280 无横向溢出、页面 JS 错误为空、终局同步完成 | frontend/room-exit-final-browser.log；e2e/room-exit.spec.ts |
| 部署配置 | docker compose config --quiet 通过；宽限与周期透传 | deploy/docker-compose.yml、deploy/.env.example |
| 编码 | 源码 BOM 检查为 0，实际 javac 编译通过 | 本次扫描与 Maven 构建 |

测试均使用固定规则/模型替身，不调用付费模型。临时后端和 Redis 容器已停止。
已有历史游戏和旧发言不重写；上述本地验收阶段未进行服务器发布、真实付费模型对局或全浏览器上线验收。后续服务器发布与外网检查单独记录在发布记录中。

## 界面交付检查：PASS

仅检查此次退出按钮、确认弹窗、真人/AI 人数和终局显示的增量。沿用既有像素村庄、木框与配色；
ENERGY 2 / RHYTHM 2 / MOTION 1。截图位于 frontend/test-results-room-exit-final 与 test-results-room-exit-redis。

- R-02 PASS：新增文案不使用长破折号。
- R-03 PASS：320/375/768/1280 宽度无横向溢出；375 像素弹窗及结算截图已查看。
- R-17 PASS：人数直接来自服务端摘要，真实接口/浏览器验证真人 1、AI 8。
- R-18 PASS：未新增评价或虚构用户内容。
- R-23 PASS：复用既有素材、组件与导航位置，无新生产视觉素材。
- R-24 PASS：大厅、回放、分析均为现有有效路由。
- R-25 PASS：退出按钮文字对比 6.31:1，确认按钮 4.70:1，正文 9.91:1，人数小字 5.90:1。
- R-26 PASS：真实点击退出和继续游戏，键盘确认；失败/重试分支通过单元测试。
- R-27 PASS：已有大厅空态/加载/错误态保留；退出有禁用、等待文字和弹窗内错误反馈。
- R-28 PASS：未新增 FAQ。
- R-32 PASS：原生 dialog；Escape 取消、回焦与 Enter 确认通过浏览器验证，沿用 focus-visible 样式。
- R-33 PASS：功能直接修改源码，未使用运行时注入或外部脚本修改界面。
- R-34 PASS：未新增或改动主题切换；退出使用已有固定高对比配色。
- R-35 PASS：构建、单元测试、真实两账号点击流程均执行，页面 JS 错误数组为空。
- R-36 PASS：没有新增未经验证的安全、性能或客户宣传。
- R-37 PASS：沿用已批准像素村庄界面，页面设计文档记录本次位置、配色与强度。
- R-38 PASS：新增状态、接管/中止说明均对应实际服务器行为。
- R-01 PASS：未新增渐变或发光。
- R-04 PASS：未新增图标，人数沿用 people 图标表示座位占用。
- R-06 PASS：沿用既有字体与字号，确认说明使用原正文样式。
- R-07 PASS：未新增背景网格或纹理。
- R-08 PASS：新增退出按钮不附装饰箭头。
- R-09 PASS：未新增胶囊徽章，人数为实际文本。
- R-10 PASS：未新增玻璃效果。
- R-12 PASS：保留已有木框，不增加浮动阴影层级。
- R-13 PASS：未新增发光效果。
- R-14 PASS：未新增卡片，弹窗按两种真实操作排列。
- R-19 PASS：未新增动画，状态反馈使用文字/禁用状态。
- R-22 PASS：未新增插画。
- Liveliness dials PASS：2/2/1 与既有页面一致，未提高装饰或动效强度。
- Liveliness focus PASS：退出弹窗以标题、规则说明和确认操作为重点。
- Liveliness spacing PASS：沿用 dialog 的标题、正文、按钮间距分隔任务。
- Liveliness accent PASS：确认操作用原橙棕色，取消沿用原次要按钮。
- Liveliness identity PASS：按钮、弹窗与大厅沿用木框像素村庄风格。
- Design Read PASS：按用户要求复用现有界面，增量方向记录在 page-design.md。
- C-1 PASS：每项新增内容对应退出、人数、终局这三个已批准需求。
- C-2 PASS：新增交互均经过真实点击或错误分支测试。
- C-3 PASS：人数小字分行，退出说明与两种操作的层级清楚。
- C-4 PASS：沿用现有组件、样式和响应式断点，无另起视觉系统。
- C-5 PASS：未编造对局状态、指标或付费模型验收结果。
- R-05 PASS：原页面结构与间距保留，未插入无关区块。
- R-11 PASS：新增按钮和小字沿用原有字号与按钮样式。
- R-15 PASS：复用原 dialog 尺寸和样式，无新增圆角体系。
- R-16 PASS：布局使用现有 footer/dialog/人数单元格，不新增装饰层。
- R-20 PASS：桌面、手机截图均检查，无需改变原主布局。
- R-21 PASS：沿用当前主题选择，未新增强制暗色。
- R-29 PASS：使用现有棕、橙棕、米白、深绿，无额外颜色体系。
- R-30 PASS：确认/取消沿用原按钮尺寸与交互状态。
- R-31 PASS：真人/AI 文本表示实际人数，按钮颜色区分确认与取消。

## 发布边界

本地 Jar 与前端 dist 已重新生成。本地验收结束时 Docker 镜像尚需构建；后续已按发布文档构建并更新前后端，见实际发布记录。
首次观察离线前最多等待一个扫描周期，死连接还受 WS 心跳检测影响，自动关闭不是精确的 120 秒。
所有真人离开后不再排新模型回合；已发出的模型请求可能仍产生本次费用，返回结果由终态/版本校验拒绝。
