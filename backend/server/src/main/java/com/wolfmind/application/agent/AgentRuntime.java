package com.wolfmind.application.agent;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.model.TurnResult;

/**
 * Agent Runtime：实例生命周期与回合执行入口（agent-runtime.md §3）。
 *
 * <p>对应 {@code DESIGN.md} §10 的 {@code createAgent()} / {@code destroyAgent()} /
 * {@code executeTurn()}。三条硬约束：
 * <ol>
 *   <li>{@code createAgent} 幂等：同一 {@code (gameId, playerId)} 重复创建返回既有实例，
 *       避免事件重放导致实例重复；</li>
 *   <li>回合只由 {@code TURN_GRANTED} 触发，Runtime 不轮询、不自行开始回合；</li>
 *   <li>Runtime 不直接写状态：写路径只有 {@code ToolGatewayPort}。</li>
 * </ol>
 */
public interface AgentRuntime {

    /** 开局或 AI 补位时为座位创建实例；幂等。 */
    AgentHandle createAgent(CreateAgentCommand command);

    /** 对局结束或座位被真人接管时释放实例；对未知句柄是安全操作。 */
    void destroyAgent(AgentHandle handle);

    /** 收到 {@code TURN_GRANTED} 时调用；同一实例禁止并发执行。 */
    TurnResult executeTurn(TurnRequest request);

    /** 对局结束时清理该局全部实例。 */
    void destroyGame(String gameId);

    /** 该对局当前的实例数，供运维与测试断言。 */
    int activeInstanceCount(String gameId);

    /** 座位实例创建时登记的配置；没有实例时为空。 */
    default java.util.Optional<AgentConfig> configOf(String gameId, String playerId) {
        return java.util.Optional.empty();
    }

    /**
     * 创建实例的命令。
     *
     * @param gameId   对局
     * @param playerId 座位玩家 id
     * @param seatNo   座位号
     * @param config   本座位配置（模型、温度、档位、人格）；同一局内不变
     */
    record CreateAgentCommand(String gameId, String playerId, int seatNo, AgentConfig config) {

        public CreateAgentCommand {
            if (gameId == null || gameId.isBlank()) {
                throw new IllegalArgumentException("gameId 不能为空");
            }
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不能为空");
            }
            if (seatNo < 1) {
                throw new IllegalArgumentException("seatNo 必须 ≥ 1");
            }
            if (config == null) {
                throw new IllegalArgumentException("config 不能为空");
            }
        }
    }

    /** 实例句柄：{@code (gameId, playerId)} 的稳定引用。 */
    record AgentHandle(String gameId, String playerId, int seatNo, String agentConfigId) {

        public AgentHandle {
            if (gameId == null || playerId == null) {
                throw new IllegalArgumentException("AgentHandle 需要 gameId 与 playerId");
            }
        }

        public String key() {
            return gameId + ":" + playerId;
        }
    }
}
