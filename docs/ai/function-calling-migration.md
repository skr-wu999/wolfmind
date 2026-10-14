# WolfMind Function Calling 改造计划

日期：2026-10-13。状态：**所有者批准并已实施**。

所有者已批准本计划并要求实施。以下保留改造计划作为决策记录；当前实现契约见
agent-runtime.md §8.1、strategy-layer.md §7.2、tool-gateway.md §2.0。
默认 FUNCTION_CALLING，4 次模型/6 次工具预算，行动元数据保留在工具参数内，
多写与查询/写混合批次整体拒绝；旧 JSON_PROPOSAL 可通过服务器配置回退。

验收：后端 engine 118 + server 644 项，0 失败、0 错误，1 项性能基准按默认跳过；
前端 30 文件/379 项通过，类型检查通过。当前 SDK 的同步/SSE 工具与推理续接已通过本机线路测试，
九 AI 工具调用替身对局完成终局。未调用付费模型、未重启运行中的服务、未做真实模型胜率实验。

## 1. 目标与范围

六个现有工具通过原生工具声明提供给模型。模型可以查询自己有权看到的状态与历史，
再选择一个行动工具；行动先形成 `ActionProposal`，完成候选、权限、表达校验后，
通过工具网关和应用服务提交。提交成功后本回合结束。

本次范围为后端 Agent 的模型适配、工作流、网关接入、流式预览及测试。
保留 Game 聚合、规则、Redis 状态与事件原子提交、SQLite 归档以及现有前端协议。
本次不增加新游戏工具、长期记忆工具、外部搜索、独立分析工具或基础设施。

依据：`agent-runtime.md` §8 与 `tool-gateway.md` §1 要求模型可见工具声明与网关执行；
`strategy-layer.md` §6/§7 同时规定正文 JSON 输出，因此迁移前需要明确更新这部分内部契约。

## 2. 当前缺口

| 当前实现 | 迁移需要 |
| --- | --- |
| `LlmRequest.ToolSpec` 只有名称、描述和空对象 schema | 从工具目录生成完整声明并实际发送 |
| `LlmResponse.Success` 只有正文 | 保留正文、工具调用 ID、名称、原始参数与供应商续接信息 |
| 消息只表示 role/content；适配器把非 system 全变成 user | 表示 assistant 工具调用和与其配对的 tool 结果 |
| `AgentWorkflow` 解析正文 JSON，再由动作类型映射工具 | 查询循环与行动调用归一化，保留后续校验和提交节点 |
| 网关写入口接收 `ActionProposal`，并重新构造参数 | 在归一化丢字段之前检查原始参数，避免未知字段或身份注入被丢弃 |
| 预览只从正文 JSON 提取 `speech` | 按工具调用 ID/index 收集参数片段，只提取 SpeakTool 的 `content` |
| 默认 2 次模型调用，6 次工具调用 | 查询、最终决策、修复/重写共享有界预算，预留提交与兜底额度 |

## 3. 建议内部契约

### 3.1 消息与响应

`LlmClient` 中的类型不引用 LangChain4j 类。建议增加：

- `ToolCall(callId, name, argumentsJson)`：保留未归一化的参数。
- 消息变体：System、User、Assistant（正文可空、可携带工具调用）、ToolResult。
- `ToolResult(callId, name, ok, code, payload)`：由服务端构造，工具结果与调用逐一配对。
- 成功响应携带 `text`、`toolCalls`、模型和用量；供应商续接字段由基础设施以不透明信息保存。
- 流式事件区分正文与工具参数片段，片段包含调用 index/ID；未完成参数不得执行。

业务动作从完整工具参数映射到现有 `ActionProposal`。普通响应正文不作为游戏动作提交。
缺失工具调用、未知工具、坏 JSON、重复字段、参数超长与不支持的批量调用进入结构化拒绝。

### 3.2 工具参数

工具仍为 `QueryGameStateTool`、`QueryHistoryTool`、`SpeakTool`、`SubmitVoteTool`、
`KillPlayerTool`、`UseSkillTool`。声明来源为 `ToolCatalog` 与 `RolePhaseMatrix`，
在基础设施中实现目录到模型声明的导出，通过内层端口提供给 PromptBuilder。

- 查询参数沿用 scope 与版本区间/limit。
- 发言参数使用 `content` 或 `skip/reason`。
- 投票使用目标或 `abstain`，狼刀使用目标或 `empty`。
- 技能使用 `skill` 和适用的目标/放弃参数，skill 枚举按角色与阶段裁剪。
- 模型不提供 gameId、行动者 playerId、seatNo、版本、deadline、commandId；这些均由 Runtime 绑定。
- 建议行动参数允许可选 `confidence`、`reasonCode`、`notes`，用于现有决策分析；
  在统一目录声明并校验，转换后不进入领域规则参数。不得把元数据当身份或授权证据。
- 建议模型端省去冗余 actionType，由工具名、skill 与放弃标记导出动作类型；
  服务端已有动作类型仍沿用，不改变客户端命令格式。

模型声明与服务端校验共享逻辑定义。互斥、候选、资源等无法由供应商 schema 完整表达的条件，
继续服务端检查。strict 模式作为供应商可选能力，第一版不依赖 Beta strict 保证业务合法。

**已确认**：决策元数据放在行动工具参数内，以保留分析能力。
同时核对既有提示词中的“预言家没有跳过选项”等策略描述与已批准规则、工具目录的差异，
区分策略偏好与规则许可，不在迁移中静默改变规则。

### 3.3 执行与提交

建议增加原始调用校验/归一化入口，接收服务端 ToolSession 和 ToolCall：

1. 校验工具名、完整原始参数、身份绑定、当前阶段及预算。
2. 查询调用经过现有网关读取；结果按座位可见性序列化并限制返回大小。
3. 行动调用先映射为提案；通过结构、候选集、LeakGuard、EchoGuard 后才提交。
4. 提交继续进入现有网关的完整校验流水线和 SubmitActionApplicationService。
5. 每回合最多一次成功行动提交；成功后终止循环，不再请求模型确认，也不再进入兜底写入。

一次响应可携带多个只读调用，第一版顺序执行。多个写调用、查询与写混合的调用批次，
在执行任何调用前整体拒绝，再按剩余预算修复。每个调用都获得配对结果；
禁止只执行批次中的第一条写调用、忽略其他调用。

工具调用 ID 用于模型消息配对，不作为命令幂等键。提交重试、刷新与重放保持同一服务端提交身份；
区分 phaseVersion 与 aggregateVersion。阶段改变、资格失效或过期时拒绝旧决策，
不能仅替换 expectedVersion 强行提交。已成功提交后即使模型流、Trace 或消息处理失败也不得再写。

## 4. 执行阶段与验收

| 阶段 | 主要文件/组件 | 交付与验收 |
| --- | --- | --- |
| 1. 契约与协议适配 | LlmClient、LlmInvoker、LangChain4jLlmClient、LlmConnectivityCheck | 完整工具 schema 发出；空正文的工具调用可解析；assistant/tool 消息配对正确；同步与流式协议测试通过 |
| 2. 工具声明与接入 | ToolCatalog、RolePhaseMatrix、ToolGatewayPort、ToolGatewayAdapter、ToolArgsValidator、PermissionChecker | 原始参数先校验；六工具映射一致；未知字段/身份注入/错角色/错目标全部拒绝；只读结果仅包含授权信息 |
| 3. 有界工具循环 | AgentWorkflow、PromptBuilder、BudgetGuard、两种 WorkflowEngine | 查询→反馈→决策完整运行；写动作先校验再提交；多个写/混合批次无副作用拒绝；修复、重写、兜底不重复提交 |
| 4. 流式发言与 Trace | SpeechFieldExtractor、SpeechPreviewStream、SpeechPreviewPort、AgentTracePort | 正文与参数流分开；只有确认属于 SpeakTool 的 content 可预览；迟到片段丢弃；最终发言只提交一次 |
| 5. 切换与回归 | 配置接线、ScriptedLlmClient、端到端测试、AI 文档与 TASKS | 六工具覆盖；九 AI 替身整局终局；两种图引擎一致；可切回旧模式；完成现有后端测试与前端协议回归 |

阶段 1/2 完成不代表迁移完成。最终必须由 function-calling 模式完成查询与行动，
覆盖坏输出、资源耗尽与失败路径；不能留下仅“声明了工具”却仍只解析正文的半成品。

工作流建议在现有 `decide` 节点中实现有界查询循环；拿到行动提案后继续走现有
validate→repair→leakCheck/rewrite→submit→fallback→finalize。
SequentialWorkflowEngine 与 LangGraph4j 复用同一节点逻辑，避免两套执行语义。
repair/rewrite 也使用工具调用，重写只允许修改表达，动作、目标与写工具由程序锁定。

## 5. 预算与失败策略

**已批准并实现的默认配置**：function-calling 模式每回合最多 4 次模型调用，
可分配为 2 轮查询、1 轮行动、1 轮修复或重写；未查询时额度可用于现有失败处理，
总上限始终有效。工具总预算保留 6，建议最多 4 次只读尝试，
保留 1 次行动提交与 1 次兜底提交。查询、拒绝、修复、重写均计入各自总预算。
现有 2 次模型调用配置仍有效，只是查询之后可能没有修复额度。

保留现有软截止与提交预留；剩余时间不足时停止查询并进入最终决策或确定性兜底。
每次续接根据同一受控时钟重新计算超时，token 用量累计到回合及对局成本守卫。
重复查询可以复用同版本结果，但仍消耗尝试预算，防止无意义循环。
若提交或兜底需要额外重试，应受剩余额度控制，不能复用“满额度会话”绕过上限。

| 故障 | 处理 |
| --- | --- |
| 空调用、未知工具、坏参数、批次不合法 | 配对结构化拒绝；预算允许则修复一次，否则兜底 |
| 工具查询被拒绝 | 只回传作用于该座位的拒绝码与安全说明；不通过其他视角补查 |
| 阶段改变、截止或资格失效 | 拒绝迟到查询/行动结果；由服务端当前状态决定后续处理 |
| 查询与模型调用耗尽预算 | 停止循环，确定性合法兜底 |
| 提交成功后后续步骤失败 | 保留成功事实，不重试另一动作 |
| 模型不支持工具调用 | 返回明确不支持状态，走规则兜底或显式配置的旧模式；不静默把正文当工具调用 |

## 6. 流式与供应商兼容

当前正文中的 `speech` 将改为 SpeakTool 参数中的 `content`。流式适配按调用 index/ID
隔离缓存，只在工具名确定且批次满足行动约束后提取发言。多调用批次不能提前公开未经确认的片段；
可缓冲到该工具调用完成，再发分句预览。部分 JSON 只供展示，完整解析、校验后才能提交。
保留逐段 LeakGuard、重写替换、超时停流与现有 speechId/最终事件对齐规则。

工具查询返回的合法历史须纳入本回合授权事实集合，再交给上下文与 LeakGuard，
避免把刚查询到的合法信息误判为幻觉；不能扩大到 JUDGE 或他人 PRIVATE 信息。

DeepSeek 推理模式带 tools 时，官方要求后续请求完整回传此前的 reasoning_content。
先用本项目固定的 LangChain4j 1.20.0 做线路级往返测试，确认同步与流式可无损续接。
供应商要求的续接信息仅内部传输，不进入公开 Trace、日志、回放或决策分析。
如果当前 SDK 不满足要求，列出适配层修复与最小版本升级选项，报告后再决定；
不能通过静默关闭推理改变现有模型配置。

## 7. 测试与切换

自动化全部使用模型替身或本机 HTTP/SSE 服务器，不默认调用付费模型。

- 协议：请求确有 schema；空 content + tool_calls；assistant/tool 配对；完整参数与分片重组；供应商续接字段。
- 六工具：状态 scope、历史可见性、发言/跳过、投票/弃权、狼刀/空刀、查验/跳过、两药/不用药、猎人开枪/放弃。
- 安全：角色与阶段矩阵、未知/重复参数、身份注入、候选外/死亡/队友目标、查询结果与审计的可见性。
- 控制流：零查询直接行动、一至两轮查询后行动、重复查询、多个写/混合批次、预算用尽、超时、断流、阶段失效。
- 提交：重复 callId、服务端重试、版本刷新、写后失败和恢复都不能重复成功提交；状态与事件一致。
- 表达：仅 SpeakTool content 进入预览；修复/泄密/复读重写保留动作目标；最终发言一次提交。
- 集成：查询确实参与下一轮模型输入；九 AI 替身整局；两种引擎一致；既有 AI、网关、领域、实时与架构测试回归。

建议增加服务器配置的 `JSON_PROPOSAL` / `FUNCTION_CALLING` 模式，迁移期显式选择。
全部验收通过后 FUNCTION_CALLING 作为默认，旧模式短期保留回退能力；
同一回合协议固定，不在中途混用。实际模型小规模验收另行记录，不承诺胜率提升。

同步更新 `docs/ai/agent-runtime.md`、`strategy-layer.md`、`tool-gateway.md` 与 TASKS。
如果 Trace 持久化字段或任何 REST/WebSocket 字段必须变化，先单列兼容方案和审批点；
本计划优先复用现有对外字段，不将工具协议直接暴露给前端。

## 8. 官方协议参考

- [LangChain4j Tools：底层工具声明、调用请求与结果续接](https://docs.langchain4j.dev/tutorials/tools/)
- [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)
- [DeepSeek Thinking Mode：tools 请求的续接字段要求](https://api-docs.deepseek.com/guides/thinking_mode/)

官方当前文档描述协议要求；固定依赖版本是否满足，以线路级测试为准。
