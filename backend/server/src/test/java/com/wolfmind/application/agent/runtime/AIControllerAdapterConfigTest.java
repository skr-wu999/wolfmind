package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.ScriptedLlmClient;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.model.TurnResult;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 驱动派发回合时必须沿用开局为座位注册的配置；否则房间开局配好的模型永远不会被调用。 */
class AIControllerAdapterConfigTest {

    private static final String GAME_ID = "g_adapter_config";
    private static final String MODEL = "model-under-test";

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void driverDispatchUsesTheConfigRegisteredAtGameStart(int callLimit) {
        TurnBudget customBudget = new TurnBudget(callLimit, 3, 4096, 1900L, 700L);
        InfrastructureGameHarness harness = new InfrastructureGameHarness(1_730_000_000_000L);
        AgentViewAdapter views = new AgentViewAdapter(harness.viewQuery());
        InMemoryAgentTrace trace = new InMemoryAgentTrace();
        ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(), harness.idempotency(), trace, harness.time());
        AgentRuntimeImpl impl = new AgentRuntimeImpl(
                views, new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), trace,
                new ScriptedLlmClient().functionCalling(), harness.time());
        Recording runtime = new Recording(impl);
        AIControllerAdapter adapter = new AIControllerAdapter(runtime, views, gateway, harness.time(),
                AgentConfig.ruleBaseline("driver-default"));
        ControlledTurnDriver driver = new ControlledTurnDriver(harness.repository(), adapter, harness.time(),
                harness.publisher(), harness.eventStore(), harness.lock());

        harness.startNineAiGame(GAME_ID, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 20240501L);
        for (int seat = 1; seat <= 9; seat++) {
            String playerId = PlayerId.forSeat(SeatNo.of(seat)).value();
            runtime.createAgent(new AgentRuntime.CreateAgentCommand(
                    GAME_ID, playerId, seat, withModel(AgentConfig.ruleBaseline("room-" + playerId), customBudget)));
        }

        for (int step = 0; step < 40 && runtime.requests.size() < 5; step++) {
            harness.advanceClock(200L);
            driver.step(GAME_ID);
        }

        assertFalse(runtime.requests.isEmpty(), "驱动应当派发过 AI 回合");
        for (TurnRequest request : runtime.requests) {
            assertEquals(MODEL, request.agentConfig().model(),
                    request.playerId() + " 的回合用了 " + request.agentConfig().agentConfigId());
            assertEquals("room-" + request.playerId(), request.agentConfig().agentConfigId());
            assertTrue(request.agentConfig().usesLlm());
            assertEquals(customBudget, request.agentConfig().budget());
            assertEquals(customBudget, request.budget());
        }
    }

    private static AgentConfig withModel(AgentConfig base, TurnBudget budget) {
        return new AgentConfig(base.agentConfigId(), base.name(), MODEL, base.temperature(),
                base.repairTemperature(), base.strategyProfile(), base.persona(), base.mode(),
                base.summarizeThreshold(), base.exposureAversion(), base.memoryRecallLimit(),
                base.candidateLimit(), base.promptTemplateId(), budget);
    }

    private static final class Recording implements AgentRuntime {

        private final AgentRuntimeImpl delegate;
        private final List<TurnRequest> requests = new ArrayList<>();

        Recording(AgentRuntimeImpl delegate) {
            this.delegate = delegate;
        }

        @Override
        public AgentHandle createAgent(CreateAgentCommand command) {
            return delegate.createAgent(command);
        }

        @Override
        public void destroyAgent(AgentHandle handle) {
            delegate.destroyAgent(handle);
        }

        @Override
        public TurnResult executeTurn(TurnRequest request) {
            requests.add(request);
            return delegate.executeTurn(request);
        }

        @Override
        public void destroyGame(String gameId) {
            delegate.destroyGame(gameId);
        }

        @Override
        public int activeInstanceCount(String gameId) {
            return delegate.activeInstanceCount(gameId);
        }

        @Override
        public Optional<AgentConfig> configOf(String gameId, String playerId) {
            return delegate.configOf(gameId, playerId);
        }
    }
}
