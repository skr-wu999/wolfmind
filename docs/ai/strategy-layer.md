# WolfMind 详细设计：策略层

本文细化 `DESIGN.md` §11（Strategy Layer）。核心命题是 `DESIGN.md` 给出的那句：
不要让 LLM 决定全部事情。

## 1. 职责分工

| 决策内容 | 由谁决定 | 理由 |
| --- | --- | --- |
| 这一回合能不能行动、能不能选这个目标 | 代码（硬约束 + 工具网关） | 规则，属于领域（`DESIGN.md` §2） |
| 谁是高威胁目标（狼队视角） | 代码（`ThreatModel` 打分） | 可计算、可复现、可单测 |
| 谁最像狼（村民阵营视角） | 代码（`SuspicionModel` 打分） | 同上 |
| 候选动作集与优先级 | 代码（`CandidateGenerator` + `DecisionPolicy`） | 保证 LLM 只在合法空间里挑 |
| 在候选集里选哪个 | LLM（可在打分与表达之间取舍） | 这是"取舍"，不是"规则" |
| 怎么表达、怎么欺骗、怎么解释 | LLM | `DESIGN.md` §11 明确划给 LLM |
| 发言的语气与人格 | 配置（`agentConfig.persona`）+ LLM 表达 | 只影响表达，不影响约束 |

`DESIGN.md` §11 举的狼人策略例子在本设计中逐条落地：

| `DESIGN.md` 的策略 | 落地方式 |
| --- | --- |
| 保护狼人队友 | 硬约束：`WOLF_KILL` 候选集排除狼人阵营（工具网关二次校验，INV-6） |
| 优先击杀高威胁玩家 | `ThreatModel` 打分排序，候选集取前 N，LLM 在其中取舍 |
| 降低暴露风险 | `DecisionPolicy` 的 `exposureRisk` 项 + 发言约束（不主动提狼队私有事实） |

## 2. 组件与数据流

```
GameView（角色可见）
   │
   ├──▶ FactExtractor ──▶ FactStore（本局事实：发言、投票、死亡、宣称）
   │
   ├──▶ ThreatModel ─────▶ Map<SeatNo, ThreatAssessment>      （狼队视角）
   ├──▶ SuspicionModel ──▶ Map<SeatNo, SuspicionAssessment>   （村民视角）
   │
   ├──▶ MemoryManager ───▶ 本局摘要 + 跨局策略摘录
   │
   ▼
CandidateGenerator（按角色与阶段产出合法候选集）
   │
   ▼
DecisionPolicy（打分：威胁/嫌疑/一致性/风险/收益）
   │
   ▼
PromptBuilder ──▶ LangGraph4j 工作流 ──▶ LangChain4j ──▶ LLM
   │
   ▼
OutputValidator（结构 + 候选集 + 阶段）──▶ LeakGuard（信息边界）
   │
   ▼
ActionProposal ──▶ ToolGateway（唯一写路径）
```

除 LLM 调用以外的全部组件都是纯函数或只读查询，可以直接单测。

## 3. 事实与推断

`FactExtractor` 把 `GameView` 里的事件折叠成结构化事实，供打分使用：

| 事实类型 | 来源事件 | 说明 |
| --- | --- | --- |
| `SPEECH` | `PLAYER_SPOKE` | 发言者、轮次、长度、是否点名他人 |
| `CLAIM` | 从 `SPEECH` 文本解析 | 角色宣称（自称预言家）、给出查验结论、给目标 |
| `VOTE` | `PLAYER_VOTED` | 投票者、目标、是否弃权 |
| `VOTE_RESULT` | `VOTE_TALLY_RESOLVED` | 出局者、票型分布 |
| `DEATH` | `DEATH_ANNOUNCED`；出局方式取自 `VOTE_TALLY_RESOLVED(result=EXILE)`、`HUNTER_SHOT` | 死亡座位、轮次、公开出局方式（`EXILE` / `HUNTER_SHOT` / `NIGHT` / `UNKNOWN`）。`NIGHT` 不区分狼刀与毒药；事件超出可读窗口时为 `UNKNOWN`，不得当作夜里死亡 |
| `SKILL_SIGNAL` | 来自公开发言 | 例如"我救了 5 号"这类公开宣称 |
| `PRIVATE_FACT` | `SEER_INSPECTED` / `WITCH_*` | 仅自己可见，只有本人视角存在 |

打分只用角色可见的事实。`JUDGE` 事件（`PLAYER_KILLED` 原始记录）不进入 `FactStore`，
这是 AI 不作弊的实现级保证。

## 4. 打分模型

### 4.1 ThreatModel（狼队视角：杀谁收益最高）

| 因子 | 权重区间 | 计算 |
| --- | --- | --- |
| 神职嫌疑 | 0.35 | 预言家宣称、给查验结论、强势带节奏 |
| 信息量 | 0.20 | 发言提供的有效信息条数、是否给出可验证结论 |
| 影响力 | 0.20 | 被他人跟随的程度（他人投票是否与其一致） |
| 存活轮次 | 0.10 | 存活越久威胁越高 |
| 击杀风险 | 0.15 | 刀口是否暴露狼队（与狼人自身票型是否冲突） |

输出 `ThreatAssessment{seatNo, score, tags[]}`，tags 便于解释与复盘。

### 4.2 SuspicionModel（村民阵营视角：谁是狼）

| 因子 | 权重区间 | 计算 |
| --- | --- | --- |
| 宣称矛盾 | 0.30 | 与已知私有事实冲突（预言家视角）、多个预言家宣称 |
| 投票一致性 | 0.25 | 与他人投票的对立关系、是否总投好人 |
| 发言规避 | 0.20 | 回避表态、含糊、只跟票不输出结论 |
| 出局关联 | 0.15 | 与已出局狼人（若已知）的关系链 |
| 时间线 | 0.10 | 关键轮次的行为突变 |

预言家视角的 `宣称矛盾` 直接使用自己的 `PRIVATE_FACT`（查验结果），
这是它唯一的真值来源；其他角色只能用公开事实推断。

### 4.3 DecisionPolicy

为每个候选算总分：

```
score = w1 · targetScore        // 威胁或嫌疑
      + w2 · consistencyScore   // 与本阵营历史行为的一致性
      - w3 · exposureRisk       // 暴露风险（狼队更敏感）
      + w4 · infoGainScore      // 该选择带来的信息收益
      + w5 · pressureScore      // 对局势的推进（避免平票、避免无效票）
```

权重来自 `agentConfig.strategyProfile`，默认档位三档：`AGGRESSIVE`、`BALANCED`、
`CONSERVATIVE`。同一档位下打分完全确定，这是实验可比性的前提。

## 5. 角色策略

每个角色实现一个 `RoleStrategy`，提供"候选集生成 + 约束 + 表达目标"。

| 角色 | 硬约束（代码强制） | 候选生成 | 交给 LLM 的部分 |
| --- | --- | --- | --- |
| `WEREWOLF` | 目标不得是狼人阵营；每夜一票；不得使用任何村民阵营工具 | 存活非狼玩家按 `ThreatModel` 取前 3；含"空刀"选项 | 在候选里取舍；白天伪装村民的发言内容与欺骗方式 |
| `SEER` | 每夜必须查验存活他人；查验结果仅自己可见 | 候选为全部存活他人，按"信息收益"排序（未查验优先、发言强势优先） | 是否在白天报出身份与查验结论、如何措辞降低被刀风险 |
| `WITCH` | 同夜不可双药；自救按规则（默认仅首夜）；解药只救当夜被刀者 | 解药候选为当夜被刀者；毒药候选为 `SuspicionModel` 前 3；含"不用药" | 何时用药、如何暗示或隐藏自己的用药 |
| `HUNTER` | 仅在自己死亡且未被毒时可开枪；目标存活 | 候选为存活玩家按 `SuspicionModel` 排序；含"放弃开枪" | 是否开枪、是否先表态施压 |
| `VILLAGER` | 仅可发言与投票；投票目标存活 | 投票候选按 `SuspicionModel` 前 3；含弃权 | 站边、指认、如何解释自己的判断 |

猎人的"放弃开枪"与女巫的"不用药"都是合法候选，不需要特殊路径。

## 6. LangGraph4j 工作流

```
        ┌──────────┐
        │ perceive │  读 GameView，抽取事实、阶段与回合
        └────┬─────┘
             ▼
        ┌──────────┐
        │  recall  │  读短期记忆与跨局策略摘录
        └────┬─────┘
             ▼
     ┌───────────────┐   事件很多时
     │   summarize   │◀── 条件边：公开事件数 > agentConfig.summarizeThreshold
     └───────┬───────┘
             ▼
        ┌──────────┐
        │ analyze  │  ThreatModel / SuspicionModel 打分
        └────┬─────┘
             ▼
        ┌──────────┐
        │strategize│  CandidateGenerator + DecisionPolicy 打分
        └────┬─────┘
             ▼
        ┌──────────┐
         │  decide  │  A：行动提案；B 发言：内部计划
        └────┬─────┘
             │ B 发言进入 express；A 直接往下
             ▼
        ┌──────────┐
        │ express  │  仅 B 发言：计划 → 公开表达
        └────┬─────┘
             ▼
        ┌──────────┐  失败  ┌────────┐
        │ validate ├───────▶│ repair │──┐ 最多 1 次
        └────┬─────┘        └────────┘  │
             │ 通过                 ▲───┘
             ▼
        ┌──────────┐  失败  ┌─────────┐
        │leakCheck ├───────▶│ rewrite │──┐ 最多 1 次
        └────┬─────┘        └─────────┘  │
             │ 通过                 ▲───┘
             ▼
        ┌──────────┐  被拒  ┌──────────┐
        │  submit  ├───────▶│ fallback │
        └────┬─────┘        └────┬─────┘
             │ 成功               │
             ▼                    ▼
        ┌──────────────────────────────┐
        │          finalize            │  记录 Trace，返回 TurnResult
        └──────────────────────────────┘
```

| 节点 | 类型 | 说明 |
| --- | --- | --- |
| `perceive` | 代码 | 从 `GameView` 抽取事实，不调用 LLM |
| `recall` | 代码 | `MemoryPort` 召回，按角色与阶段过滤 |
| `summarize` | 代码（可选 LLM） | 事件超阈值时压缩时间线；默认结构化模板 |
| `analyze` | 代码 | `ThreatModel` / `SuspicionModel` |
| `strategize` | 代码 | 候选集与打分；同时产出硬约束清单 |
| `decide` | LLM | 原生模式处理有界查询循环；取得行动工具调用后映射 ActionProposal，尚不执行行动 |
| `express` | LLM（仅 B 发言） | 将经过校验的内部发言计划转成 SpeakTool；使用新建的公开上下文 |
| `validate` | 代码 | 结构、候选集、阶段、长度校验 |
| `repair` | LLM | 携带拒绝原因重试一次 |
| `leakCheck` | 代码 | 信息边界校验 |
| `rewrite` | LLM | 携带违规原因重写，一次 |
| `submit` | 代码 | 经 `ToolGatewayClient` 提交 |
| `fallback` | 代码 | 规则 Top-1 或 `SKIP` |
| `finalize` | 代码 | 收敛 `TurnResult`，写 `AGENT_` 事件 |

只有 `decide`、`express`、`repair`、`rewrite` 节点调用 LLM，且总调用次数受
`agent.maxLlmCallsPerTurn` 约束：原生模式默认 4 次，旧 JSON 模式默认 2 次。
不需要查询时，默认路径仍可在 `decide` 一次取得合法提案。

### 6.1 单次调用与两阶段模式

| 模式 | LLM 调用 | 说明 |
| --- | --- | --- |
| A（默认） | 无查询时 1 次；查询与修复受回合总上限约束 | 原生模式通过行动工具参数给出动作或发言，温度 0.6；旧模式输出正文 JSON |
| B（可选） | 发言正常 2 次，查询/修复共用原有总预算 | 先制定内部发言计划（请求温度 0.3），再生成公开表达（请求温度 0.85）；非发言回合沿用 A |

两种模式的约束、校验、提交路径完全相同，因此可以对比"表达与决策分离"对胜率的影响
（`DESIGN.md` §14 AI 实验室的模型比较场景）。

#### B 的实际执行契约（2026-10-13 所有者批准）

B 仅作用于 `DAY_DISCUSS` / `LAST_WORDS`。`decide` 生成内部 SpeechPlan，校验成功才进入
新增 `express` 节点；两种引擎使用同一组节点处理器。无计划时进入既有兜底，不重新开始决策。
投票、查验、狼刀、用药、开枪沿用 A，不为非发言行动增加第二次调用。

内部计划字段：

| 字段 | 类型与约束 |
| --- | --- |
| `stance` | 必填枚举 `SUSPECT` / `SUPPORT` / `OBSERVE` |
| `focusSeat` | 可选整数；必须是视图中的座位，非 OBSERVE 必填；只是发言关注对象，不是领域行动目标 |
| `purpose` | 必填枚举 `REQUEST_EXPLANATION` / `COMPARE_EVIDENCE` / `CAUTION` / `FAREWELL` |
| `evidenceIds` | 必填数组，最多 4 个、不重复，只能引用服务器给出的公开证据 ID |

公开证据目录包括最近 16 条发言、当前公开票、已公布死亡，ID 来自座位、轮次、阶段和发言序号。
证据内容由服务器解析；模型不能提供证据正文、自由备注、私有事实 ID、身份字段或游戏行动。
重复 JSON 字段、尾随数据、错误类型、未知字段与不存在的证据均拒绝。
本次 B 只表达基于公开证据的立场，不自动公开本人角色、查验或用药事实。

原生模式中 `PrepareSpeechPlan` 是 Runtime 内部的结构化结果工具，**不是 ToolGateway 的第七个游戏工具**，
不改变对局、不新增 REST/WebSocket 契约。第一阶段可调用已授权的 QueryGameStateTool / QueryHistoryTool，
不能混合查询与计划或提交行动。第二阶段新建 system/user 消息，只有 SpeakTool 声明；
不续接第一阶段 assistant/tool 消息、私人备注、狼队夜聊、角色技能、隐藏队友身份或召回记忆。
背景从清除私有信息的视图重新装配，只传公开背景、验证后的计划及人格。JSON 回退模式支持相同两阶段。

最终校验强制 SPEAK、无领域目标、有正文、包含关注座位，拦截常见的直接反向立场表达；
另外拦截明确的私人行动/狼队友/自曝角色断言，再执行既有合法性、泄密和复读检查。
这些文本检查不能证明任意自然语言的全部语义与计划完全一致；没有增加额外的付费审查模型。
预览仅在最终表达通过检查、准备提交时生成，内部计划和不合格表达不公开。

两个阶段、查询、计划修正、表达修复与重写共享现有 LLM/工具预算和原截止时间。
启动计划至少需要剩余 2 次 LLM 额度；第一阶段始终保留 1 次给表达，并限制每次调用使用至多
剩余推理时间的一半。查询最多两轮，并预留最终计划、表达的调用额度及提交/兜底工具额度。
计划格式错误最多修正一次；模型失败直接兜底。第二阶段失败只重试表达，不能重做计划，
不能查询、切换行动或另选目标；额度不足或仍不合格则使用确定性兜底。行动提交被拒时不重启两阶段。

`AgentConfig.mode=B` 可按座位启用。部署 `WOLFMIND_AGENT_SPEECH_MODE=CONFIG`（默认）沿用座位配置，
`A` / `B` 在创建 Agent 时覆盖模式；修改需重新加载进程配置。供应商推理模式可能忽略请求温度，
温度数值不构成推理质量承诺。实验室表单本次不增加模式字段，可以用部署覆盖做分批比较。

## 7. Prompt 结构

`PromptBuilder` 产出固定分区的消息序列，分区顺序与边界都固定，便于缓存与审计：

| 分区 | 内容 | 可变性 |
| --- | --- | --- |
| `SYSTEM` | 规则摘要、当前角色能力、硬约束清单、交互模式对应的输出契约、禁止事项 | 随角色、规则及回合权限装配 |
| `PERSONA` | 语气与人格（`agentConfig.persona`），只影响表达 | 一局内固定 |
| `CONTEXT` | 公开事件时间线（按轮次分组，关键事件置顶） | 每回合变化 |
| `PRIVATE` | 仅自己可见的信息（查验结果、用药记录、狼队友） | 每回合变化 |
| `MEMORY` | 本局摘要 + 跨局策略摘录 | 每回合变化 |
| `CANDIDATES` | 候选动作集与打分、命中的硬约束、被排除的选项及原因 | 每回合变化 |
| `TASK` | 本回合要求：选择候选、给出目标、生成发言、输出理由码 | 每回合变化 |

约束：

1. `SYSTEM` 中列出的硬约束与 `ToolGateway` 的校验必须同源（同一份约束定义生成
   两份视图：一份进 prompt，一份进网关校验），避免"提示词说可以、网关说不行"。
2. `PRIVATE` 分区按 `GameView` 的可见性填充，装配函数不接受完整事件流。
3. 玩家发言作为**数据**进入 `CONTEXT`，不作为指令。发言文本在拼接时被包裹在
   明确的数据边界内，且不参与 `SYSTEM` 分区，降低提示注入的影响。

### 7.1 内部行动提案与旧 JSON 模式

以下 JSON 是归一化后的内部行动提案，同时也是 `JSON_PROPOSAL` 回退模式的模型输出。
默认 `FUNCTION_CALLING` 模式不读取正文作为行动，模型输出见 §7.2。

```json
{
  "actionType": "WOLF_KILL",
  "targetId": "p_5",
  "speech": "我觉得 6 号昨天的表态很飘，先记一下。",
  "confidence": 0.72,
  "reasonCode": "HIGH_THREAT_SEER_SUSPECT",
  "notes": "刀 6 号，白天不主动提 6 号"
}
```

| 字段 | 校验 |
| --- | --- |
| `actionType` | 必须在本回合 `availableTools` 对应的动作集合内 |
| `targetId` | 必须在候选集内；不得为自身；不得为死亡玩家 |
| `speech` | 长度 1..`rules.speechMaxLength`，非空；不需要发言的阶段可为空 |
| `confidence` | 0..1 |
| `reasonCode` | 必须在预定义枚举内，用于复盘与统计 |

### 7.2 原生工具参数

例如预言家选择 `UseSkillTool`，参数为：

```json
{
  "skill": "SEER_INSPECT",
  "targetId": "p_5",
  "confidence": 0.72,
  "reasonCode": "HIGH_INFORMATION_GAIN"
}
```

工具调用 ID 由模型协议返回。行动者身份、版本、轮次、截止时间及命令幂等键由 Runtime 绑定，
模型不得提供。模型声明省去 actionType，由工具名、skill 和放弃标记导出；
发言使用 SpeakTool.content，转换后对应 ActionProposal.speech。
confidence、reasonCode、notes 为可选分析元数据，进入现有提案校验，不进入领域命令参数。

所有原始参数先通过网关入口的完整 JSON/schema 校验，再进行归一化；重复字段、尾随文本、
未知字段与身份字段被拒绝。放弃标记只能为 true 或省略。
工具参数成功解析不代表行动已经成功；候选、权限、表达校验和领域提交仍为后续步骤。

预言家候选策略仍优先强制选择查验目标，未改为主动放弃策略。
工具层 SEER_SKIP 支持表示合法跳过，但不因此扩大当前策略的候选集合。

原生模式从完整 SpeakTool 调用参数确认 content 后发送分句预览，未完成参数不执行、
正文与私人备注不作为发言。预览仍经过逐段 LeakGuard；PLAYER_SPOKE 仍只提交一次。
当前版本在完整调用响应确认后发送预览，未实现参数到达过程中的即时公开。

## 8. 校验与防泄漏

### 8.1 OutputValidator

| 校验 | 失败处理 |
| --- | --- |
| JSON 可解析 | `repair`（携带原始输出与解析错误） |
| `actionType` 在允许集合内 | `repair` |
| `targetId` 在候选集内 | `repair`，并把候选集重新列出 |
| 阶段与角色匹配 | `repair` |
| `speech` 长度与空值 | `repair` |
| `confidence` 与 `reasonCode` 合法 | 使用默认值，不触发修复 |

修复只允许一次；仍失败则 `fallback`。

### 8.2 LeakGuard

LeakGuard 拦截的是"这个 Agent 说出了它自己都不可能知道的事实"，
它同时也拦住了模型幻觉出上帝视角的情况。

| 判定 | 处理 |
| --- | --- |
| 文本中出现 `GameView` 之外的座位私有事实（例如村民说出"女巫昨晚救了 5 号"） | 违规，`rewrite` |
| 文本中出现 `JUDGE` 层事实（例如"3 号是被毒死的"） | 违规，`rewrite` |
| 狼人说出狼队内部事实（刀口、队友） | 允许，它知道这些；是否暴露由策略决定 |
| 自称身份（"我是预言家"） | 允许，这是宣称而非窃取事实 |
| 与候选集无关的额外承诺 | 允许，但记录到 Trace 供复盘 |

实现方式：把 `GameView` 派生的可见事实构建成指纹集合
（座位号 × 事实类型 × 关键值），对生成文本做结构化匹配；
命中不在可见集合内的指纹即违规。LeakGuard 是保守的，宁可漏判也不误杀：
它只匹配结构化事实指纹，不做语义审查。

重写一次仍违规时，使用安全模板发言（只包含阶段相关的通用表态），
保证对局不因表达问题卡住。

### 8.3 EchoGuard（复读检查）

2026-10-12 所有者批准。真实模型对局中，多名座位整段复述前一位的发言，只改座位号
（对局 `g_r_mv563ji9_1`、`g_r_mv56e2su_1`）。EchoGuard 在 LeakGuard 未命中后检查发言回合
（`DAY_DISCUSS`、`LAST_WORDS`）的发言：

| 项 | 规则 |
| --- | --- |
| 比较对象 | 视图内全部可见发言，含本人此前的发言 |
| 相似度 | 去掉数字、标点、空白与 ASCII 字母后，相邻双字集合的 Dice 系数 |
| 公共片段 | 与某条发言比较前，双方都去掉"除这条之外还有至少 2 条发言用过"的双字 |
| 阈值 | 0.7；双方规范化后都不少于 20 字才比较 |
| 命中 | `rewrite`，提示词指出最相似的座位与轮次，要求从自己的立场表态、不照搬原句与句式 |
| 重写后仍命中 | 提交首稿与改写稿中相似度较低的一版，不用安全模板 |
| 重写调用失败、输出不合法、改写稿泄密或预算耗尽 | 提交首稿（它已通过 LeakGuard） |

2026-10-13 所有者批准调整（对局 `g_r_mv5ggj6c_1`）：第一天 1 号夜里出局，后面六位都在开场复述
"1 号夜里出局，遗言自称女巫，原因不公开……"，旧算法下正常发言已接近阈值（最高 0.59），
4 号、7 号被连拦两次退成安全模板，看起来像在念模板。因此：

- **公共片段**：同一天大家复述同一条公开事实是正常的，不该算谁抄谁。比较对象本身不计入出现次数（留一法），
  否则一旦有人整段照抄成功，第三个照抄者会因为原文已"出现在两条里"而漏检；
- **退路**：安全模板只给泄密场景用。复读稿没有泄密，说得像别人总好过"我暂时不表态"；
- **阈值**：两局实测，整段照抄 0.89–1.00；`g_r_mv5ggj6c_1` 第一天各自表述的发言最高 0.51，
  套同一开场白再加自己判断的发言 0.55–0.63。阈值 0.7 两边都留有余量。

重写与泄密重写共用每回合 LLM 预算（默认 2 次）。
任务区同时要求发言从自己的立场出发、不复述他人原句，并写明当前轮次。

复读拦截写 `AGENT_OUTPUT_REJECTED` 轨迹，`stage` 为 `ECHO_CHECK`（首稿）或 `ECHO_CHECK_REWRITE`（改写稿），
payload 含被比对的 `seatNo` / `round` / `similarity` 与被拦的稿子 `draft`；`ECHO_CHECK_REWRITE`
另含 `kept`（`ORIGINAL` / `REWRITE`，最终提交了哪一版）。改写失败另记 `REWRITE_INVALID`（`code` / `reason`）
或 `LEAK_CHECK_REWRITE`（`kind`）。**泄密拦截从不记录原稿**：它可能含该座位不该说出的隐藏信息。
轨迹只在对局结束后经分析接口返回（api-spec.md §7.2）。

## 9. 兜底策略

`FallbackPlanner` 是不依赖 LLM 的规则决策器：

```
1. 取候选集中 DecisionPolicy 打分最高者
2. 若候选集为空（无合法动作）→ SKIP
3. 发言使用自然表态模板，不播报轮次或内部阶段代码：
   "我的判断是先关注 {topSuspectSeat} 号，想听听他的理由。"
   遗言用收尾表态；没有关注目标时用不包含座位断言的通用表态。
4. 标记 TurnResult.status = FALLBACK、fallbackUsed = true
```

兜底存在的意义是让"9 个 AI 自动完成一局"（`DESIGN.md` §15 Phase 3 的目标）
在 LLM 完全不可用时也能达成，同时让实验室可以做"纯规则基线"对照。

公开 AI 发言的表达处理（2026-10-14）：`PublicSpeechText` 去掉机械的轮次/阶段开头，
把正文内已知阶段代码转成中文名称，保留判断、目标座位和正常的时间引用。
模型输出经过该处理后仍接受非空、长度、立场和泄密校验；原稿超长仍拒绝。
流式预览在小句边界做同样处理，轮次开头等到完整句子才判断，避免分段推送阶段代码；
原稿和原稿前缀仍先通过泄密检查。游戏状态、工具权限、事件中的 phase 和旧发言记录保持原契约。
该处理不增加模型调用；提示词同时要求直接表达判断，不复制系统标题或阶段标签。

## 10. 与工具网关的衔接

策略层只产出 `ActionProposal`，不执行任何工具：

| 步骤 | 责任方 |
| --- | --- |
| 生成 `ActionProposal` | 工作流 `finalize` 节点 |
| 映射到工具名与参数 | `ToolGatewayClient` |
| 权限与阶段校验 | `ToolGateway`（[tool-gateway.md §4](tool-gateway.md)） |
| 转成领域 Action | `ToolInvocationApplicationService` |
| 领域校验与事件产生 | `Game.handle` → `PLAYER_VOTED` 等 |

因此策略层即使产出了越权动作，也只会得到一次结构化拒绝，
不会改变对局状态（C-5）。

## 11. 可测试性与可复现性

| 组件 | 测试方式 |
| --- | --- |
| `FactExtractor` | 固定 `GameView` 输入，断言事实集合 |
| `ThreatModel` / `SuspicionModel` | 构造对局片段，断言排序与打分区间 |
| `CandidateGenerator` | 断言各角色各阶段的候选集边界（狼不出现队友、死者不出现） |
| `DecisionPolicy` | 断言三档策略的打分差异与确定性 |
| `OutputValidator` | 覆盖越界目标、非法动作、超长发言、坏 JSON |
| `LeakGuard` | 正例（狼人提刀口）与反例（村民提女巫用药） |
| `FallbackPlanner` | 断言候选为空时产出 `SKIP` |
| 工作流 | 用固定 LLM 输出驱动 LangGraph4j 图，断言节点路径与重试次数 |

LLM 通过接口注入，测试使用固定输出的实现，因此工作流可以完全离线验证。
同一 `GameView` + 同一 `agentConfig` + 同一模型版本必须产生同一结果，
这是实验室对比结论可信的前提。

## 12. 配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `strategy.profile` | `BALANCED` | 打分权重档位 |
| `strategy.candidateLimit` | 3 | 候选集大小 |
| `strategy.mode` | `A` | 单次调用 / 两阶段 |
| `strategy.temperature.decide` | 0.6 | `decide` 节点温度 |
| `strategy.temperature.repair` | 0.2 | 修复节点温度 |
| `strategy.summarizeThreshold` | 60 | 触发时间线压缩的事件数 |
| `strategy.exposureAversion` | 0.5 | 狼队暴露规避强度 |
| `strategy.persona` | 中性 | 语气与人格，只影响表达 |

配置按 `AgentConfig` 维度存储，实验室可以按对局指定配置；同一局内配置不变。

## 13. 禁止事项

| 禁止 | 原因 |
| --- | --- |
| 让 LLM 输出工具名与原始参数后直接执行 | 必须在候选集与白名单内，且经网关校验 |
| 把决定性判断（票数、死亡、胜负）交给 LLM | 属于领域规则（`DESIGN.md` §2） |
| 把 `JUDGE` 事件或他人 `PRIVATE` 事件写进 prompt | 作弊，且实验结论无效 |
| 让 LLM 自由选择候选集之外的目标 | 破坏硬约束，`DESIGN.md` §11 的"代码控制"落空 |
| 在 prompt 里拼接未过滤的玩家发言作为指令 | 提示注入 |
| 让策略层直接调用应用服务或写事件 | 绕过工具网关（C-5） |
| 用随机数弥补打分平局 | 破坏可复现性；平局按 `TieBreak`（`gameId`+轮次+用途+座位号的 SplitMix64 哈希）排序，同局同轮结果固定 |
| 平局按座位号升序取首个 | 2026-10-13 前的做法：开局无信息时所有座位同分，狼刀、怀疑、查验都落到最小座位（建房真人通常在 1 号）。`TieBreak` 不用 `randomSeed`，因为种子决定身份分配，会泄露身份 |
