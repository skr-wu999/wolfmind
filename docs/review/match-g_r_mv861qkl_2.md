# 对局 g_r_mv861qkl_2 的胜负与用药核查

2026-10-14。读取服务器已结束对局的 GameView 与完整 76 条事件，未修改服务器状态、规则或业务源码，未调用模型。
记录：[回放](http://39.108.167.114:8080/games/g_r_mv861qkl_2/replay)。

## 结算结论

本局 winCondition=SIDE_KILL，3 名平民为 1、3、4 号，神职为 5 女巫、6 预言家、7 猎人。
屠边定义：狼全灭则好人胜；平民全灭或神职全灭则狼人胜。不是要求所有好人都死亡。

| 时间 | 已提交事实 | 版本 |
| --- | --- | --- |
| 第 1 夜 | 狼刀选择 5 号；5 号女巫解药自救，夜间死亡名单为空 | v15、v19、v21 |
| 第 1 天白天 | 4 号获得 3 票，是唯一最高票，被放逐；4 号为平民 | v54–v56 |
| 第 2 夜 | 狼刀选择 3 号；5 号女巫对 1 号用毒；1、3 号死亡 | v64、v68、v70–v72 |
| 第 2 天天亮 | 平民全部死亡，狼人胜；原因 ALL_VILLAGERS_ELIMINATED | v76 |

第 1 天平安夜只代表当夜没有死人；随后白天投票已经淘汰一名平民。第二夜是一刀加一毒，共两人死亡。
结算时剩下 3 狼人和 3 神职。用同一终态调用 SIDE_KILL 返回狼人胜；改用现有 WOLVES_OUTNUMBER 也会因 3≥3 返回狼人胜。
因此改为当前另一个胜利选项，不能让这局在该节点继续。

这局每种药各用一次，均在规则允许的阶段；毒到平民属于目标选择错误，不能由规则层依据隐藏身份替玩家改目标。
仅凭事件不能断定女巫的错误目标来自模型输出还是规则兜底，需另查已授权 Agent 决策轨迹。

## 确认的实现遗漏：用药未消耗库存

服务器终局视图中 5 号的 antidoteAvailable=true、poisonAvailable=true。
本地使用当前生产领域事件解码器重放服务器完整事件，也得到：

```text
AFTER WITCH_SAVED v19 witch=SkillState[antidoteAvailable=true, poisonAvailable=true, shotAvailable=false]
AFTER WITCH_POISONED v68 witch=SkillState[antidoteAvailable=true, poisonAvailable=true, shotAvailable=false]
REPLAY status=FINISHED version=76 winner=WOLF reason=ALL_VILLAGERS_ELIMINATED
```

这不是展示映射误差。Game.applyWitchSaved / applyWitchPoisoned 仅保存当夜目标，未调用 SkillState.consumeAntidote / consumePoison。
后续合法性校验与权限投影依据库存，跨夜可能再次允许已用过的药，与“全局一瓶解药、一瓶毒药”合同冲突。
本局每种药只使用一次，库存漏洞未改变这次的死亡或胜负结果；仍需修复未来长局的资源限制。

建议修复时把药的消耗放入对应事件 apply，保证在线处理和重放一致，测试跨夜二次用药拒绝、已用药权限消失、恢复后不重置。
还应让等待房/本局规则显示真实 winCondition：当前页面仅列角色构成，没有说明屠边条件。
AI 的毒药策略则按其可见证据评估，避免低信息时轻率用毒。

诊断输入与复现输出位于 backend/target/g_r_mv861qkl_2-view.json、g_r_mv861qkl_2-replay.json、g_r_mv861qkl_2-diagnosis.log。
诊断程序 InspectMatch.java 在 target 中独立运行，只做事件重放；未修复或发布上述用药问题。
