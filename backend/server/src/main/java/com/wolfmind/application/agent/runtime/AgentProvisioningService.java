package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.application.port.AgentProvisioningPort;
import java.util.List;
import java.util.Objects;

/** 创建与重建座位 Agent；输入身份由房间应用用例生成。 */
public final class AgentProvisioningService implements AgentProvisioningPort {
    private final AgentRuntime runtime;
    private final LlmSettings settings;

    public AgentProvisioningService(AgentRuntime runtime, LlmSettings settings) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public int restore(String gameId, List<Seat> seats) {
        return runtime.activeInstanceCount(gameId) > 0 ? 0 : prepare(gameId, seats);
    }

    @Override
    public int prepare(String gameId, List<Seat> seats) {
        boolean useLlm = settings.configured() && !Boolean.getBoolean("wolfmind.agent.force-rule");
        int created = 0;
        for (Seat seat : seats) {
            if (!seat.aiControlled()) continue;
            AgentConfig config = AgentConfig.ruleBaseline(useLlm ? "room-" + seat.playerId() : "rule-baseline");
            try {
                runtime.createAgent(new AgentRuntime.CreateAgentCommand(gameId, seat.playerId(), seat.seatNo(),
                        useLlm ? withModel(config) : config));
                created++;
            } catch (RuntimeException error) {
                // 缺席座位由既有回合降级/超时处理；结果只计入成功创建的实例。
                continue;
            }
        }
        return created;
    }

    private AgentConfig withModel(AgentConfig base) {
        return new AgentConfig(base.agentConfigId(), base.name(), settings.defaultModel(),
                base.temperature(), base.repairTemperature(), base.strategyProfile(), base.persona(), base.mode(),
                base.summarizeThreshold(), base.exposureAversion(), base.memoryRecallLimit(), base.candidateLimit(),
                base.promptTemplateId(), TurnBudget.defaultFor(settings.interactionMode()));
    }
}
