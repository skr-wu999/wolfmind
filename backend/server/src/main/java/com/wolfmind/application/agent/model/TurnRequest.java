package com.wolfmind.application.agent.model;

import java.util.List;

/**
 * 一次 AI 回合的输入（agent-runtime.md §5.1）。
 *
 * <p>由 {@code AIControllerAdapter} 消费 {@code TURN_GRANTED} 后构造。所有定位信息
 * （阶段、轮次、版本、deadline）都来自领域事件，不在 Runtime 内推导——AI 不自行开始回合。
 *
 * @param gameId     对局
 * @param playerId   本回合属于哪个座位
 * @param seatNo     座位号
 * @param role       本座位角色（来自 {@code GameView.me}，不在 Runtime 内推导）
 * @param camp       本座位阵营
 * @param phase      当前阶段
 * @param round      第几轮
 * @param turnIndex  回合序号
 * @param version    视图版本，工具网关的 {@code expectedVersion} 来源
 * @param deadline   提交截止时刻（毫秒时间戳），来自 {@code TURN_GRANTED}
 * @param view       角色可见视图，已按可见性矩阵过滤
 * @param availableTools 由角色与阶段决定的工具白名单；同时也是 prompt 里的可用动作集合
 * @param budget     本回合预算
 * @param agentConfig 本座位的配置
 */
public record TurnRequest(
        String gameId,
        String playerId,
        int seatNo,
        String role,
        String camp,
        String phase,
        int round,
        int turnIndex,
        int version,
        long deadline,
        AgentView view,
        List<String> availableTools,
        TurnBudget budget,
        AgentConfig agentConfig,
        com.wolfmind.application.scheduling.ExecutionFence fence) {

    public TurnRequest(String gameId, String playerId, int seatNo, String role, String camp,
                       String phase, int round, int turnIndex, int version, long deadline,
                       AgentView view, List<String> availableTools, TurnBudget budget, AgentConfig agentConfig) {
        this(gameId, playerId, seatNo, role, camp, phase, round, turnIndex, version, deadline,
                view, availableTools, budget, agentConfig, null);
    }

    public TurnRequest {
        if (gameId == null || playerId == null) {
            throw new IllegalArgumentException("TurnRequest 需要 gameId 与 playerId");
        }
        if (view == null) {
            throw new IllegalArgumentException("TurnRequest 需要角色可见视图");
        }
        if (agentConfig == null) {
            throw new IllegalArgumentException("TurnRequest 需要 agentConfig");
        }
        if (budget == null) {
            budget = TurnBudget.DEFAULT;
        }
        availableTools = List.copyOf(availableTools);
    }

    /** 构造工具会话：身份与版本取自本请求，模型无法干预（G3）。 */
    public ToolSession toolSession(int remainingToolCalls) {
        return new ToolSession(
                gameId, playerId, seatNo, role, phase, version, deadline, remainingToolCalls, round, fence);
    }

    public boolean expectsSpeech() {
        return "DAY_DISCUSS".equals(phase) || "LAST_WORDS".equals(phase);
    }

    public TurnKey turnKey() {
        return new TurnKey(gameId, playerId, version);
    }
}
