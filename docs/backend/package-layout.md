# 后端包结构与依赖装配

2026-10-14 整理：保留 engine/server 模块与现有四层依赖方向；不改变游戏规则、公开 API、事件格式或数据库。

## 业务代码放在哪里

| 位置 | 职责 |
| --- | --- |
| engine 的 `domain.game/room/user` | 聚合、规则、状态转移与可重放事件 |
| server 的 `application.game/room/user` | 对局、房间、认证用例 |
| `application.agent` | Agent 生命周期、上下文、策略、工作流执行 |
| `application.scheduling` | 已提交回合事件的调度、预算围栏与超时协调 |
| `application.analysis/lab/events` | 分析查询、实验室批次与可靠消费用例 |
| `application.port` | 跨业务协作和外部设施的接口 |
| `interfaces.rest/ws` | 请求解析、认证身份绑定、响应与实时协议 |
| `infrastructure` | 存储、模型、工具适配与依赖装配 |

`StartRoomGameApplicationService` 和 `RunningGameAgentRecovery` 现在位于 `application.room`，
接口包不再放应用用例服务。控制器继续负责 HTTP，应用类不依赖控制器或传输异常类型。

## 配置类

原 `WolfMindConfiguration` 的 50 个 Bean/辅助方法按职责迁入 `infrastructure.config`：

| 类 | 装配内容 |
| --- | --- |
| ObservabilityConfiguration | 时间、指标、健康检查与轨迹维护 |
| MemoryStorageConfiguration | 进程内端口实现；各 Bean 保留原存储模式条件 |
| GameApplicationConfiguration | 对局存取、查询与提交/开局用例 |
| RoomApplicationConfiguration | 房间用例、开局编排与启动时恢复 |
| AgentRuntimeConfiguration | Agent Runtime、模型、工具网关与工作流选择 |
| SchedulingConfiguration | 事件调度、超时、显式轮询回退与发言间隔 |
| IdentityConfiguration | 账号认证与会话用例 |
| InsightConfiguration | 分析查询、实验配置与批次执行 |
| ApiSerializationConfiguration | REST/WS 共用的参与者 JSON 形状 |

Redis 专用装配仍位于 `infrastructure.redis.RedisStorageConfiguration`。
Spring Boot 从 `com.wolfmind` 扫描配置，无需业务类引用配置实现。
原 Bean 名称、存储条件、启动顺序、调度线程名称和 shutdown/close 方法保留。
新 AgentProvisioningPort 的装配也放在 AgentRuntimeConfiguration。

## 房间与 Agent 的协作

房间用例把合法座位转换为 `AgentProvisioningPort.Seat(playerId, seatNo, aiControlled)`，
通过 `prepare` / `restore` 准备实例。playerId 由领域的座位标识方法生成，不是从客户端或模型读取。

`application.agent.runtime.AgentProvisioningService` 实现该端口，负责规则/模型配置、
创建计数、同局已有实例的恢复幂等，以及模型模式对应的默认预算。
Agent 侧只接收端口值对象，不引用领域的 SeatAssignment、Player 或 Game。
因此 DEP-3（Room/Game 与 Agent 互不依赖）和 DEP-7（Agent 不依赖领域模型）继续生效。
创建部分失败仍按既有规则降级和超时处理，不改变开局、实例准备与房间冻结的顺序。

## 整理范围与验证

接口地址、错误码、WS 消息、事件与数据表没有变化。旧审计保留历史路径，不倒改原审计结论。
同步了启动类与相关配置/加载器的过时注释。

迁移采用 clean 编译，避免 target 中留下旧包类而重复注册 Bean。
验证包含 DEP-1..DEP-7、接口包不得放应用服务、新配置的引擎/调度选择、房间开局与预算、
完整服务器 HTTP/WS 和真实 Redis 回归；测试证据见 TASKS 的本次记录。

本轮没有机械移动所有端口、改名全部 AI 适配包，或重写 Game/AgentWorkflow。
这些操作需要按职责逐项拆分并单独验收，不能只靠改目录降低类行数。
