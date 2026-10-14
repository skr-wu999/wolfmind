# WolfMind 项目架构设计方案

# 1. 项目概述

## 项目名称

WolfMind

## 项目定位

一个基于 Multi-Agent AI 技术的在线狼人杀游戏平台。

目标：

构建一个支持：

- AI 自主狼人杀
- 真人 + AI 混合对战
- 真人在线多人游戏
- AI 决策分析
- 游戏回放
- Agent 实验平台

的 AI Native 游戏系统。

------

# 2. 核心设计理念

WolfMind 的核心思想：

> 游戏规则由传统后端确定性控制，AI Agent 负责非确定性的推理和策略决策。

也就是说：

```
游戏规则
    ↓
Game Domain

AI决策
    ↓
Agent Runtime

状态变化
    ↓
Event Driven Architecture
```

------

# 3. 总体架构

采用：

## DDD + Event Driven + Multi-Agent Architecture

整体：

```
                 Frontend(Vue3)

                      |
                      |

              WebSocket Gateway

                      |

              Application Layer

                      |

        ----------------------------

        |                          |

   Game Domain              Agent System

        |                          |

 Game Engine              Agent Runtime

        |                          |

 Domain Event             Strategy Layer

        |                          |

 Event Store              LangGraph4j

        |                          |

 Redis Stream             LangChain4j


                      |

              Persistence Layer


              Redis + SQLite
```

------

# 4. 后端整体分层

采用领域驱动设计。

## Interface Layer

负责：

- REST API
- WebSocket
- 请求参数转换

例如：

```
GameController

RoomController

UserController
```

------

## Application Layer

负责：

业务流程编排。

例如：

```
StartGameApplicationService

SubmitActionApplicationService
```

但是：

不包含游戏规则。

------

## Domain Layer

核心。

负责：

- 游戏规则
- 玩家
- 角色
- 行动
- 状态机
- 事件

例如：

```
Game

Player

Role

Action

GamePhase

DomainEvent
```

------

## Infrastructure Layer

负责：

外部技术。

包括：

- Redis
- SQLite
- LangChain4j
- WebSocket
- 文件存储

------

# 5. 游戏领域设计（DDD）

核心聚合：

## Game Aggregate

Game 是狼人杀的核心。

负责：

- 当前阶段
- 玩家状态
- 行动校验
- 状态推进

例如：

```
Game

    |
    |
    + players

    |
    |
    + phase

    |
    |
    + events
```

------

## Player Entity

玩家不绑定 AI。

错误：

```
Player
 |
Agent
```

正确：

```
Player

 |

PlayerController

 |

-----------------

HumanController

AIController

BotController
```

原因：

未来支持：

- 真人
- AI
- NPC

统一抽象。

------

# 6. 游戏事件驱动架构

所有游戏状态变化必须产生事件。

流程：

```
Player Action

      |

Game Aggregate

      |

Validate

      |

State Change

      |

Domain Event

      |

Event Bus

      |

Consumers
```

例如：

玩家投票：

```
VoteAction

↓

Game.handle()

↓

PlayerVotedEvent

↓

Redis Stream

↓

WebSocket广播

↓

AI Memory更新
```

------

# 7. Event Store设计

采用：

Redis Stream 作为第一阶段 Event Store。

存储：

游戏事件。

例如：

```
PLAYER_VOTED


PLAYER_KILLED


GAME_STARTED


PHASE_CHANGED
```

事件结构：

```
{
 eventId:"",
 gameId:"",
 version:10,
 type:"PLAYER_VOTED",
 payload:{
    playerId:1,
    targetId:5
 }
}
```

------

# 8. Snapshot机制

为了避免大量事件重放。

设计：

```
Event Stream

        +

Snapshot
```

例如：

每50个事件生成一次：

```
Snapshot(version=50)


phase:
DAY_VOTE

alivePlayers:
[1,3,5]
```

恢复：

```
Load Snapshot

+

Replay Later Events

=

Current State
```

------

# 9. AI Agent架构

AI 不直接绑定玩家。

结构：

```
Player

 |

AIController

 |

Agent Runtime

 |

Agent Instance

 |

LangGraph4j

 |

LangChain4j

 |

LLM
```

------

# 10. Agent Runtime

负责：

- Agent生命周期
- Context管理
- Memory管理
- Workflow执行

例如：

```
createAgent()

destroyAgent()

executeTurn()
```

------

# 11. Strategy Layer

增加策略层。

原因：

不要让 LLM 决定全部事情。

例如狼人策略：

代码控制：

```
保护狼人队友

优先击杀高威胁玩家

降低暴露风险
```

LLM负责：

```
如何表达

如何欺骗

如何解释
```

结构：

```
Game Context

↓

Strategy Layer

↓

LangGraph Workflow

↓

LLM
```

------

# 12. AI Tool Gateway

AI 不允许直接调用业务。

错误：

```
LLM

↓

GameService

↓

Database
```

正确：

```
LLM

↓

Tool Gateway

↓

Permission Check

↓

Game Action

↓

Game Engine
```

Tool：

包括：

```
QueryGameStateTool

QueryHistoryTool

SubmitVoteTool

KillPlayerTool

UseSkillTool

SpeakTool
```

------

# 13. 数据设计

## Redis

用于：

运行中的游戏。

保存：

```
game:{id}:state

game:{id}:events

game:{id}:players

game:{id}:votes

game:{id}:agent:{id}
```

------

## SQLite

用于：

长期数据。

保存：

```
users

games

game_events

game_history

agent_memory

statistics
```

------

# 14. 前端设计

核心页面：

## 首页

展示：

- 项目介绍
- 开始游戏

------

## 游戏大厅

功能：

- 房间列表
- 创建房间
- 加入游戏

------

## 房间等待页

展示：

- 玩家列表
- AI座位
- 准备状态

------

## 游戏页面（核心）

包含：

- 游戏桌
- 玩家卡牌
- AI发言
- 投票区域
- 技能按钮

------

## AI观战页面

观看：

- AI vs AI

------

## 游戏回放

展示：

- Event时间线
- 游戏过程

------

## AI分析页面

展示：

- Agent策略
- 决策过程
- 胜率分析

------

## AI实验室

用于：

- 配置Agent
- 批量测试
- 模型比较

------

# 15. 开发路线

## Phase 1

游戏核心

完成：

- DDD模型
- 状态机
- Action
- Domain Event

目标：

纯Java模拟狼人杀。

------

## Phase 2

Event Store

完成：

- Redis Stream
- Snapshot
- Replay

目标：

游戏可恢复。

------

## Phase 3

AI Agent

完成：

- Agent Runtime
- Strategy Layer
- LangGraph4j
- LangChain4j
- Tool Gateway

目标：

9个AI自动完成游戏。

------

## Phase 4

在线游戏

完成：

- Vue3
- WebSocket
- 房间系统
- AI补位

------

## Phase 5

高级能力

完成：

- AI分析
- 实验室
- Docker部署
- 监控

------

