# 女巫库存与规则说明修复验收

2026-10-14。本地实现完成，服务器当前仍为先前发布的 fix2，本次未重新发布镜像。

## 修改

- Game.applyWitchSaved 消耗解药，applyWitchPoisoned 消耗毒药；事件重放与在线处理一致。
- 跨夜重复用药返回 SKILL_UNAVAILABLE，已消耗的药不再出现在对应操作权限里；另一种药仍可正常使用。
- 旧快照可能保留错误库存，快照 JSON 加 skillStateSchemaVersion=2；仓储拒绝把旧格式作为恢复起点，改从事实事件重建。
- 完整 RoomView 增加有效 rules，非法旧配置返回 null，仍禁止开局；前端等待房与对局页按服务器数据展示胜利条件和自救限制。
- 屠边条件与 AI 目标选择不变。模型误毒的决策来源需要另查授权轨迹，此次没有把“毒到平民”当成非法行动。

## 验证

后端 `mvn -o -pl server -am -Dwolfmind.redis.required=true -Dwolfmind.redis.host=127.0.0.1 -Dwolfmind.redis.port=61239 test package`：
1010 项，0 失败、0 错误，性能基准 1 项跳过；真实 Redis 45 项实际执行。临时 Redis 仅用于 DB15，未访问业务 DB0。
专项覆盖跨夜重复解药/毒药、另一瓶药仍可用、已用药权限消失、事件重放、直接快照恢复和错误旧快照重建。
全量中的旧非法房间用例发现新增 rules 投影会阻碍其准备命令，已修为 rules=null；原开局拒绝测试保留并通过。
正常终局异步关闭测试出现过一次等待超时，专项与最终全量重跑均通过，未放宽其 5 秒断言。

前端 33 文件、406 项通过；vue-tsc 与真实同源 API 的生产构建通过。
Chromium 检查等待房/对局页弹窗、Escape 关闭、320/375/1280 宽度、弹窗不越过视口，pageErrors=[]。
Java/前端源码 UTF-8 BOM 0。未调用付费模型。

对服务器对局 g_r_mv861qkl_2 原始 76 条事件做本地重放：

```text
AFTER WITCH_SAVED v19 witch=SkillState[antidoteAvailable=false, poisonAvailable=true, shotAvailable=false]
AFTER WITCH_POISONED v68 witch=SkillState[antidoteAvailable=false, poisonAvailable=false, shotAvailable=false]
REPLAY status=FINISHED version=76 winner=WOLF reason=ALL_VILLAGERS_ELIMINATED
```

原死亡事实和胜负保持一致。证据：backend/witch-final.log、frontend/witch-tests.log、
frontend/test-artifacts/witch-rules-browser.json 与截图、backend/target/g_r_mv861qkl_2-fixed.log。
测试专用 Vite 与 Redis 容器已停止；用户 5180 开发页面未重启。

## 界面交付检查：PASS

本次仅添加规则文本及数据透传，沿用已批准的像素村庄弹窗；照片/图标/配色/导航结构不变。

- R-02 PASS：新增文本无长破折号。
- R-03 PASS：320/375/1280 宽度无横向溢出，弹窗范围在视口内，375 截图已查看。
- R-17 PASS：没有新增人数或经营指标。
- R-18 PASS：没有新增评价或虚构人物。
- R-23 PASS：无新视觉素材，复用原弹窗。
- R-24 PASS：未新增导航目的地。
- R-25 PASS：规则沿用原正文颜色与米白背景，已有正文对比检查通过，无新颜色。
- R-26 PASS：原查看/关闭规则操作在真实 Chromium 点击与 Escape 验证通过。
- R-27 PASS：规则缺失/未知时显示尚未同步，非法旧房间不假装规则正常。
- R-28 PASS：未增加 FAQ。
- R-32 PASS：原生 dialog 与原焦点样式保留，Escape 关闭通过。
- R-33 PASS：直接编辑源码，浏览器脚本仅读取与验证。
- R-34 PASS：未改主题行为。
- R-35 PASS：类型、构建、单元测试与浏览器均运行，页面 JS 错误为空。
- R-36 PASS：未新增安全/性能宣传。
- R-37 PASS：沿用原界面方向，page-design.md 记录 2/2/1 强度和本次增量。
- R-38 PASS：规则来自服务器，演示数据明确使用 demoRules。
- R-01 PASS：无新渐变/发光。
- R-04 PASS：无新图标。
- R-06 PASS：沿用原正文与标题字体。
- R-07 PASS：无新背景图案。
- R-08 PASS：无新装饰箭头。
- R-09 PASS：无新徽章。
- R-10 PASS：无新玻璃效果。
- R-12 PASS：无新阴影。
- R-13 PASS：无新发光。
- R-14 PASS：无新卡片。
- R-19 PASS：无新动画。
- R-22 PASS：无新插画。
- Liveliness dials PASS：ENERGY 2 / RHYTHM 2 / MOTION 1 沿用当前页面。
- Liveliness focus PASS：胜利条件加粗，解释与用药分段。
- Liveliness spacing PASS：沿用原正文段落间距。
- Liveliness accent PASS：保留原视觉重点，不增加强调色。
- Liveliness identity PASS：原像素木框保留。
- Design Read PASS：只扩充原规则弹窗，沿用原设计。
- C-1 PASS：新增内容对应实际发现的用药次数和胜利规则解释需求。
- C-2 PASS：原弹窗可打开和关闭。
- C-3 PASS：胜负、职业边界、用药次数、自救限制分段表达。
- C-4 PASS：两个页面共用同一组件与规则类型。
- C-5 PASS：未推测模型私有推理，未伪造服务器更新。
- R-05 PASS：原页面布局保留。
- R-11 PASS：沿用原字号。
- R-15 PASS：沿用原弹窗边框。
- R-16 PASS：未新增装饰布局。
- R-20 PASS：手机和桌面弹窗验证通过。
- R-21 PASS：原主题保留。
- R-29 PASS：无新颜色。
- R-30 PASS：沿用原按钮尺寸与状态。
- R-31 PASS：加粗文字表示真实胜利条件。

## 发布

新的可执行 JAR 与 frontend/dist 已生成。再次发布须更新前后端，建议新标签 20261014-fix3，保留 fix2 作为回退。
服务器仍运行 fix2；旧服务器快照由新仓储加载时从事件重建，历史动作与胜负不撤销。
