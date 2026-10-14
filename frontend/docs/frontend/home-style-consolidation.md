# 内页样式与首页统一

2026-10-15。所有者选定范围：统一旧页面风格、减少内部套框、修正拉伸与错位。
首页的雾隐村场景、米白纸面、方角像素描边与橙色主按钮为视觉基准。
此文记录展示层调整，不变更游戏规则、授权边界或 REST / WebSocket 契约。

## 实现

- 观战与回放、AI 分析、AI 实验室改用 `FogVillageScene`，复用首页的 `landing` 颜色令牌与 `px-panel`。
- `VillageHeader` 的 Logo、玩法/关于入口、音效控件使用首页的导航样式，事件绑定保留。
- 侧栏中的相关内容共用一个外层纸面面板；内部使用分隔线与留白。游戏桌内座位、聊天气泡、等待页子区域减少多层描边和阴影，保留选中、发言、淘汰与准备状态。
- 大厅、等待页侧栏采用自然高度，不再按列表或座位区高度拉伸。创建房间阵容区取消内套框。
- 分析页取消右栏负外边距；页面导航进入正常文档流。实验室游戏桌桌面高度为 440px，手机端使用自然高度网格；在线实验室明确配置、说明、对局与批次侧栏的网格位置。
- 内页共享规则集中在 `src/assets/inner-pages.css`，首页的主视觉保持原有样式。340px 以下导航换行，避免音效按钮被裁切。

## 验收

在 `frontend/` 中运行：

```powershell
npm run build
$env:VITE_USE_REAL_API='false'
$env:VITE_API_BASE_URL='http://127.0.0.1:8080'
$env:VITE_WS_URL='ws://127.0.0.1:8080/ws'
npm test -- --reporter=dot
```

类型检查与生产构建通过；34 个测试文件、409 项测试通过。
直接运行测试首次受本地 API 环境配置影响，78 项失败；显式固定上述测试配置后通过，未修改测试运行配置。
三个页面单测把场景桩从 `VillageScene` 改为 `FogVillageScene`；浏览器检查实际 Canvas 渲染。

浏览器使用隔离的本地 mock 适配器与内存登录夹具，无业务账号、付费模型或服务端数据修改。
检查大厅、等待页、对局、观战、回放、分析、实验室在 320 / 390 / 768 / 1024 / 1440px 下的排版。
发现并修复 320px 导航音效按钮裁切和实验室游戏桌百分比高度拉伸。
整页无横向溢出；实验室比较表通过专用容器横向滚动。
验证分析座位切换、决策详情弹窗、Escape 关闭与焦点恢复。
分析页前三列桌面顶部坐标相同；实验室桌面无旧木框，游戏桌高度为 440px。

新增输入框边界色 `#8a765c` 对纸面 `#fffaf0` 为 4.18:1，超过非文字边界的 3:1 门槛；
纸面内焦点色 `#973b21` 对 `#f5ecd6` 为 6.02:1。未据此声称整个产品完成无障碍认证。

证据位于 `test-artifacts/`：

- `home-style-tests.log`、`home-style-build.log`
- `home-style-responsive.json`
- `home-style-analysis-desktop.jpg`、`home-style-lab-desktop.jpg`、`home-style-game-desktop.jpg`
- `home-style-waiting-mobile.jpg`、`home-style-waiting-320.jpg`

此次未做真实多人/模型联调与部署；页面状态和操作回归来自既有单测与本地浏览器检查。
