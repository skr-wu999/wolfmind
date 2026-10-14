package com.wolfmind.interfaces.rest;

import com.wolfmind.application.room.StartRoomGameApplicationService;
import com.wolfmind.application.agent.runtime.AgentProvisioningService;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.game.StartGameApplicationService;
import com.wolfmind.application.room.RoomApplicationService;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoomAgentBudgetTest {
    @ParameterizedTest
    @EnumSource(LlmClient.InteractionMode.class)
    void roomStartAndRecoveryRegisterTheModeBudget(LlmClient.InteractionMode mode) {
        var harness = new InfrastructureGameHarness(1_730_000_000_000L);
        var views = new AgentViewAdapter(harness.viewQuery());
        var trace = new InMemoryAgentTrace();
        var gateway = new ToolGatewayAdapter(views, harness.viewQuery(), harness.submitService(),
                harness.idempotency(), trace, harness.time());
        var runtime = new AgentRuntimeImpl(views, new WolfMindStrategy(gateway), gateway,
                new InMemoryMemoryPort(), trace, null, harness.time());
        var repository = new InMemoryRoomRepository(harness.eventStore());
        var rooms = new RoomApplicationService(repository, new InMemoryRoomIndex(), harness.lock(),
                harness.publisher(), harness.time());
        var settings = new LlmSettings("mock", "", "", 30000L, 0, "fixed-model",
                Map.of(LlmSettings.INTERACTION_KEY, mode.name()));
        var start = new StartRoomGameApplicationService(repository, rooms,
                new StartGameApplicationService(harness.lock(), harness.eventStore(), harness.repository(),
                        harness.publisher(), harness.time()), new AgentProvisioningService(runtime, settings));
        String roomId = rooms.create("owner", "budget-test", Map.of(), "create").roomId();
        rooms.ready(roomId, "owner", "ready");
        for (int seat = 2; seat <= 9; seat++) rooms.seatAi(roomId, "owner", seat, "config-" + seat, "seat-" + seat);

        var result = start.start(roomId, "owner", "start");
        assertEquals(8, result.agentCount());
        for (int seat = 2; seat <= 9; seat++) assertBudget(runtime.configOf(result.gameId(), "p_" + seat).orElseThrow(), mode);

        var recoveredRuntime = new AgentRuntimeImpl(views, new WolfMindStrategy(gateway), gateway,
                new InMemoryMemoryPort(), trace, null, harness.time());
        var restarted = new StartRoomGameApplicationService(repository, rooms,
                new StartGameApplicationService(harness.lock(), harness.eventStore(), harness.repository(),
                        harness.publisher(), harness.time()), new AgentProvisioningService(recoveredRuntime, settings));
        assertEquals(2, restarted.restoreAgents(result.gameId(), List.of(
                SeatAssignment.ai(2, "p_2"), SeatAssignment.ai(3, "p_3"))));
        assertBudget(recoveredRuntime.configOf(result.gameId(), "p_2").orElseThrow(), mode);
        assertBudget(recoveredRuntime.configOf(result.gameId(), "p_3").orElseThrow(), mode);
    }

    private static void assertBudget(AgentConfig config, LlmClient.InteractionMode mode) {
        assertEquals("fixed-model", config.model());
        assertEquals(mode == LlmClient.InteractionMode.FUNCTION_CALLING ? 4 : 2, config.budget().maxLlmCalls());
        assertEquals(6, config.budget().maxToolCalls());
        assertEquals(8000, config.budget().maxTokens());
        assertEquals(1500L, config.budget().softDeadlineMs());
        assertEquals(500L, config.budget().submitReserveMs());
    }
}
