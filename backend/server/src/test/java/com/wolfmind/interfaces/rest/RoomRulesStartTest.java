package com.wolfmind.interfaces.rest;

import com.wolfmind.application.room.StartRoomGameApplicationService;
import com.wolfmind.application.agent.runtime.AgentProvisioningService;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.application.game.GameRulesCodec;
import com.wolfmind.application.game.StartGameApplicationService;
import com.wolfmind.application.room.RoomApplicationService;
import com.wolfmind.domain.game.event.EventCodec;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.GameStartedEvent;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryRoomIndex;
import com.wolfmind.infrastructure.memory.InMemoryRoomRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomRulesStartTest {
    final InfrastructureGameHarness harness = new InfrastructureGameHarness(1_730_000_000_000L);
    final InMemoryRoomRepository repository = new InMemoryRoomRepository(harness.eventStore());
    final InMemoryRoomIndex index = new InMemoryRoomIndex();
    final RoomApplicationService rooms = new RoomApplicationService(repository, index,
            harness.lock(), harness.publisher(), harness.time());
    final AgentRuntime runtime = mock(AgentRuntime.class);
    final StartRoomGameApplicationService start = new StartRoomGameApplicationService(repository, rooms,
            new StartGameApplicationService(harness.lock(), harness.eventStore(), harness.repository(),
                    harness.publisher(), harness.time()), new AgentProvisioningService(runtime,
            new LlmSettings("mock", "", "", 30_000, 0, "", Map.of())));

    private String readyRoom(Map<String, Object> config) {
        String room = rooms.create("owner", "rules", config, "create").roomId();
        rooms.ready(room, "owner", "ready");
        for (int seat = 2; seat <= 9; seat++) rooms.seatAi(room, "owner", seat, "ai-" + seat, "seat-" + seat);
        return room;
    }

    @Test void roomOverridesReachTheAggregateEventPublicViewAndReplay() {
        var config = Map.<String, Object>of("rules", Map.of("voteDeadlineMs", 15_000L, "abstainAllowed", false,
                "speechMaxLength", 123, "witchSelfSave", false));
        String room = readyRoom(config);
        var result = start.start(room, "owner", "start");
        var loaded = harness.repository().load(result.gameId()).game();
        var expected = GameRulesCodec.fromRoomConfig(config);
        assertEquals(expected, loaded.snapshot().rules());
        var events = harness.eventStore().read(result.gameId(), 1, loaded.version());
        var started = (GameStartedEvent) EventCodec.decode(events.getFirst());
        assertEquals(expected, started.rules());
        var replay = Game.create(GameId.of(result.gameId()));
        for (var event : events) replay.replay(EventCodec.decode(event));
        assertEquals(loaded.snapshot(), replay.snapshot());
        var publicView = harness.viewQuery().view(result.gameId(), ViewerContext.spectator("observer"));
        assertEquals(15_000L, publicView.rules().get("voteDeadlineMs"));
        assertEquals(false, publicView.rules().get("abstainAllowed"));
        assertEquals(123, publicView.rules().get("speechMaxLength"));
        var again = start.start(room, "owner", "start-again");
        assertEquals(result.gameId(), again.gameId());
        assertEquals(loaded.version(), harness.eventStore().currentVersion(result.gameId()));
    }

    @Test void noRulesUseDefaults() {
        var result = start.start(readyRoom(Map.of()), "owner", "start");
        assertEquals(GameRules.DEFAULT, harness.repository().load(result.gameId()).game().snapshot().rules());
    }

    @Test void invalidRulesAreRejectedAtCreationBeforeAnyAgentIsCreated() {
        var error = assertThrows(GameException.class, () -> rooms.create("owner", "invalid",
                Map.of("rules", Map.of("voteDeadlineMs", -1)), "bad-create"));
        assertEquals(DomainErrorCode.RULES_INVALID, error.code());
        verifyNoInteractions(runtime);
    }

    @Test void invalidLegacyRoomCannotStartOrCreateAgents() {
        String room = "r_legacy";
        var legacy = com.wolfmind.domain.room.model.Room.create(com.wolfmind.domain.room.model.RoomId.of(room));
        var events = legacy.create("legacy", "owner", Map.of("rules", Map.of("abstainAllowed", "false")),
                ActionContext.of("legacy-create", harness.now(), "owner", "legacy", "legacy", 0));
        repository.commit(room, 0, events.stream().map(event -> EventEnvelope.of(event,
                com.wolfmind.application.room.RoomRepositoryPort.streamName(room), event.version())).toList());
        index.add(room);
        rooms.ready(room, "owner", "ready");
        for (int seat = 2; seat <= 9; seat++) rooms.seatAi(room, "owner", seat, "ai-" + seat, "seat-" + seat);
        var error = assertThrows(GameException.class, () -> start.start(room, "owner", "start"));
        assertEquals(DomainErrorCode.RULES_INVALID, error.code());
        verifyNoInteractions(runtime);
        assertEquals(0, harness.eventStore().currentVersion("g_" + room));
    }
}
