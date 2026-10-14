package com.wolfmind.application.room;

import com.wolfmind.application.game.*;
import com.wolfmind.application.port.*;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomLifecycleServiceTest {
    final InfrastructureGameHarness h = new InfrastructureGameHarness(1730000000000L);
    final InMemoryRoomRepository repository = new InMemoryRoomRepository(h.eventStore());
    final InMemoryRoomIndex index = new InMemoryRoomIndex();
    final RoomApplicationService rooms = new RoomApplicationService(repository,index,h.lock(),h.publisher(),h.time());
    final AgentProvisioningPort agents = mock(AgentProvisioningPort.class);
    final Set<String> online = new HashSet<>();
    final RoomPresencePort presence = (room,game,user) -> online.contains(user);
    final RoomLifecycleService lifecycle = service();
    RoomLifecycleService service() { return new RoomLifecycleService(rooms,index,h.repository(),h.lock(),h.publisher(),
            agents,presence,h.time(),MetricsPort.NOOP,100L,h.eventStore()); }
    String room() {
        String id = rooms.create("owner","test",Map.of(),"create").roomId();
        rooms.join(id,"alice","join","join"); return id;
    }
    String start(String room) {
        rooms.ready(room,"owner","ready-owner"); rooms.ready(room,"alice","ready-alice");
        for (int seat=3;seat<=9;seat++) rooms.seatAi(room,"owner",seat,"ai-"+seat,"ai-"+seat);
        var start = new StartRoomGameApplicationService(repository,rooms,
                new StartGameApplicationService(h.lock(),h.eventStore(),h.repository(),h.publisher(),h.time()),agents);
        return start.start(room,"owner","start").gameId();
    }
    @Test void waitingOwnerHandoverSurvivesReplayAndLastHumanClosesRoom() {
        String id=room(); lifecycle.leaveRoom(id,"owner","leave-owner");
        assertEquals("alice",rooms.load(id).ownerId());
        assertEquals(1,rooms.load(id).humanCount());
        lifecycle.leaveRoom(id,"alice","leave-alice");
        assertEquals("CLOSED",RoomView.of(rooms.load(id)).status());
        var query=new GetRoomQueryService(repository,index);
        assertEquals(0,query.count(null)); assertEquals(1,query.count("CLOSED"));
    }
    @Test void runningExitKeepsRoleAndAliveStateThenLastHumanAborts() {
        String id=room(), game=start(id);
        var before=h.repository().load(game).game().snapshot().players().get(0);
        lifecycle.leaveGame(game,"owner","leave-owner");
        var after=h.repository().load(game).game().snapshot().players().get(0);
        assertEquals(before.role(),after.role()); assertEquals(before.alive(),after.alive());
        assertEquals(before.playerId(),after.playerId()); assertEquals(ControllerType.AI,after.controller().type());
        assertEquals("p_1",after.controller().refId());
        assertEquals("alice",rooms.load(id).ownerId());
        int version=h.eventStore().currentVersion(game);
        lifecycle.leaveGame(game,"owner","leave-owner"); assertEquals(version,h.eventStore().currentVersion(game));
        lifecycle.leaveGame(game,"alice","leave-alice");
        var state=h.repository().load(game).game().snapshot();
        assertEquals(GameStatus.ABORTED,state.status()); assertNull(state.winner());
        assertEquals("NO_HUMANS",state.finishReason());
        assertEquals("CLOSED",RoomView.of(rooms.load(id)).status());
        assertEquals(1,h.eventTypes(game).stream().filter("GAME_ABORTED"::equals).count());
    }
    @Test void strangerCannotLeaveAnotherPlayersGame() {
        String id=room(), game=start(id);
        int version=h.eventStore().currentVersion(game);
        assertThrows(GameException.class,()->lifecycle.leaveGame(game,"stranger","leave"));
        assertEquals(version,h.eventStore().currentVersion(game)); assertEquals(2,rooms.load(id).humanCount());
    }
    @Test void reconciliationRepairsCommittedDepartureAfterAnInterruptedRequest() {
        String id=room(), game=start(id);
        rooms.leave(id,"owner","partial");
        assertEquals(ControllerType.HUMAN,h.repository().load(game).game().snapshot().players().get(0).controller().type());
        online.add("alice"); service().sweep();
        assertEquals(ControllerType.AI,h.repository().load(game).game().snapshot().players().get(0).controller().type());
    }
    @Test void graceDoesNotExtendOnRepeatedChecksAndReconnectResetsIt() {
        String id=room(), game=start(id); online.add("alice");
        lifecycle.sweep(); h.advanceClock(99); lifecycle.sweep(); assertEquals(2,rooms.load(id).humanCount());
        online.add("owner"); lifecycle.sweep(); h.advanceClock(1000); lifecycle.sweep();
        assertEquals(2,rooms.load(id).humanCount());
        online.remove("owner"); lifecycle.sweep(); h.advanceClock(101); lifecycle.sweep();
        assertEquals(1,rooms.load(id).humanCount());
        assertEquals(GameStatus.RUNNING,h.repository().load(game).game().status());
        online.remove("alice"); lifecycle.sweep(); h.advanceClock(101); lifecycle.sweep();
        assertEquals(GameStatus.ABORTED,h.repository().load(game).game().status());
    }
    @Test void terminalNotificationClosesRoomAndIsIdempotent() {
        String id=room(), game=start(id); var loaded=h.repository().load(game);
        var events=loaded.game().abort("TEST",ActionContext.of("abort",h.now(),"SYSTEM","abort","abort",loaded.version()));
        var envelopes=EventEnvelopes.wrap(game,loaded.version()+1,events);
        h.repository().commit(game,loaded.version(),envelopes,GameRepositoryPort.SnapshotPolicy.FORCE);
        lifecycle.onCommitted(envelopes);
        int version=repository.load(id).version(); lifecycle.onCommitted(envelopes);
        assertEquals(version,repository.load(id).version()); assertEquals("CLOSED",RoomView.of(rooms.load(id)).status());
    }
    @Test void independentAiGameIsNotStoppedByRoomCleanup() {
        h.startNineAiGame("g_lab",RoleComposition.STANDARD_NINE,GameRules.DEFAULT,7L);
        lifecycle.sweep(); h.advanceClock(10000); lifecycle.sweep();
        assertEquals(GameStatus.RUNNING,h.repository().load("g_lab").game().status());
    }
    @Test void reconnectImmediatelyBeforeLockedDeparturePreventsRemoval() {
        String id = room();
        online.add("owner");
        rooms.leaveIfOffline(id, "owner", "offline-race", presence);
        assertEquals(2, rooms.load(id).humanCount());
        assertEquals("owner", rooms.load(id).ownerId());
    }
    @Test void aPrematureTerminalNotificationDoesNotCloseARunningRoom() {
        String id = room(), game = start(id);
        lifecycle.gameEnded(game);
        assertEquals("RUNNING", RoomView.of(rooms.load(id)).status());
    }
    @Test void restartRepairsLastDepartureAndKeepsAbortedStateIdempotent() {
        String id = room(), game = start(id);
        rooms.leave(id, "owner", "partial-owner");
        rooms.leave(id, "alice", "partial-alice");
        assertEquals(GameStatus.RUNNING, h.repository().load(game).game().status());
        service().sweep();
        assertEquals(GameStatus.ABORTED, h.repository().load(game).game().status());
        int version = h.eventStore().currentVersion(game);
        service().sweep();
        assertEquals(version, h.eventStore().currentVersion(game));
    }
    @Test void lobbyCountsHumansAndAiSeparatelyAfterDeparture() {
        String id=room(), game=start(id); lifecycle.leaveGame(game,"owner","leave");
        var summary=RoomView.of(rooms.load(id)).toSummary();
        assertEquals(9,summary.get("occupied")); assertEquals(1,summary.get("humanCount")); assertEquals(8,summary.get("aiCount"));
    }
    @Test void takeoverAfterVotingKeepsTheCommittedVoteAndRejectsTheOldHuman() {
        String id = room(), gameId = start(id);
        for (int step = 0; step < 30; step++) {
            var game = h.repository().load(gameId).game();
            if (game.phase() == GamePhase.DAY_VOTE) break;
            var state = game.snapshot();
            int seat = switch (game.phase()) {
                case NIGHT_WOLF -> game.wolvesAwaitingNomination().getFirst().value();
                case NIGHT_SEER -> state.players().stream().filter(p -> p.role() == Role.SEER)
                        .findFirst().orElseThrow().seatNo().value();
                case NIGHT_WITCH -> state.players().stream().filter(p -> p.role() == Role.WITCH)
                        .findFirst().orElseThrow().seatNo().value();
                default -> state.currentTurn().seatNo().value();
            };
            String actor = state.players().get(seat - 1).controller().refId();
            h.advanceClock(1);
            h.submitService().submit(new SubmitActionCommand(gameId, actor, "SKIP", Map.of(),
                    game.version(), "skip-" + step, "skip"));
        }
        assertEquals(GamePhase.DAY_VOTE, h.repository().load(gameId).game().phase());
        h.submitService().submit(new SubmitActionCommand(gameId, "owner", "VOTE", Map.of("targetSeatNo", 2),
                h.eventStore().currentVersion(gameId), "owner-vote", "vote"));
        var before = h.repository().load(gameId).game().snapshot();
        lifecycle.leaveGame(gameId, "owner", "exit-after-vote");
        var after = h.repository().load(gameId).game().snapshot();
        assertEquals(before.votes(), after.votes());
        assertEquals(1, h.eventTypes(gameId).stream().filter("PLAYER_VOTED"::equals).count());
        assertThrows(GameException.class, () -> h.submitService().submit(new SubmitActionCommand(gameId, "owner",
                "VOTE", Map.of("targetSeatNo", 3), after.version(), "old-human-vote", "vote")));
    }
}
