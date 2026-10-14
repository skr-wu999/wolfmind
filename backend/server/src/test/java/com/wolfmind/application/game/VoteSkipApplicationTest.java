package com.wolfmind.application.game;

import com.wolfmind.application.scheduling.SpeechPacing;
import com.wolfmind.application.scheduling.TurnPlanner;
import com.wolfmind.application.scheduling.TurnTask;
import com.wolfmind.domain.game.event.EventCodec;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VoteSkipApplicationTest {
    static final String ID = "g_vote_application";
    final InfrastructureGameHarness harness = new InfrastructureGameHarness(1_730_000_000_000L);

    @BeforeEach void reachVotingThroughCommittedEvents() {
        var rules = GameRulesCodec.fromRoomConfig(Map.of("rules", Map.of("abstainAllowed", false, "voteDeadlineMs", 15_000)));
        harness.startNineAiGame(ID, RoleComposition.STANDARD_NINE, rules, 42);
        for (int step = 0; step < 30; step++) {
            var game = harness.repository().load(ID).game();
            if (game.phase() == GamePhase.DAY_VOTE) return;
            var state = game.snapshot();
            int seat = switch (game.phase()) {
                case NIGHT_WOLF -> game.wolvesAwaitingNomination().getFirst().value();
                case NIGHT_SEER -> state.players().stream().filter(player -> player.role() == Role.SEER)
                        .findFirst().orElseThrow().seatNo().value();
                case NIGHT_WITCH -> state.players().stream().filter(player -> player.role() == Role.WITCH)
                        .findFirst().orElseThrow().seatNo().value();
                default -> state.currentTurn().seatNo().value();
            };
            harness.advanceClock(1);
            harness.submitService().submit(new SubmitActionCommand(ID, "p_" + seat, "SKIP", Map.of(),
                    game.version(), "prepare-" + step, "prepare"));
        }
        fail("did not reach voting");
    }

    private ActionResult submit(String action, Map<String, Object> params, String key) {
        return harness.submitService().submit(new SubmitActionCommand(ID, "p_1", action, params,
                harness.eventStore().currentVersion(ID), key, key));
    }

    private void reject(String action, Map<String, Object> params, DomainErrorCode code) {
        var before = harness.repository().load(ID).game().snapshot();
        var events = harness.eventStream(ID);
        assertEquals(code, assertThrows(GameException.class, () -> submit(action, params, "reject-" + params)).code());
        assertEquals(before, harness.repository().load(ID).game().snapshot());
        assertEquals(events, harness.eventStream(ID));
    }

    @Test void skipCannotOverwriteACommittedVoteAndDuplicateCommandReplaysTheOriginalResult() {
        var result = submit("VOTE", Map.of("targetSeatNo", 2), "vote");
        int count = harness.eventStream(ID).size();
        var replayed = submit("VOTE", Map.of("targetSeatNo", 2), "vote");
        assertTrue(replayed.replayed());
        assertEquals(result.eventIds(), replayed.eventIds());
        assertEquals(count, harness.eventStream(ID).size());
        reject("SKIP", Map.of(), DomainErrorCode.ACTION_ALREADY_SUBMITTED);
        reject("SKIP", Map.of("reason", "TIMEOUT"), DomainErrorCode.ACTION_NOT_ALLOWED);
        assertEquals(Vote.of(1, 2), harness.repository().load(ID).game().snapshot().votes().get(1));
    }

    @Test void voluntaryAbstentionAndForgedTimeoutProduceNoCommittedEvents() {
        reject("SKIP", Map.of(), DomainErrorCode.ACTION_NOT_ALLOWED);
        reject("SKIP", Map.of("reason", "TIMEOUT"), DomainErrorCode.ACTION_NOT_ALLOWED);
        reject("VOTE", Map.of("abstain", true), DomainErrorCode.ACTION_NOT_ALLOWED);
        var view = harness.viewQuery().view(ID, ViewerContext.participant("p_1", Set.of(PlayerId.of("p_1")), Set.of(), false));
        assertEquals(false, view.permissions().stream().filter(permission -> permission.type().equals("VOTE"))
                .findFirst().orElseThrow().allowEmpty());
    }

    @Test void trustedTimeoutWaitsForTheConfiguredDeadlineAndFillsOnlyMissingVotes() {
        submit("VOTE", Map.of("targetSeatNo", 2), "vote");
        var planner = new TurnPlanner(harness.repository(), harness.eventStore(), SpeechPacing.NONE);
        var task = planner.plan(ID).tasks().stream().filter(item -> item.kind() == TurnTask.Kind.TIMEOUT)
                .findFirst().orElseThrow();
        var phase = harness.eventStream(ID).stream().filter(event -> event.type().equals("PHASE_CHANGED"))
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(phase.occurredAt() + 15_000, task.deadline());
        var timeout = new TimeoutApplicationService(harness.repository(), harness.lock(), harness.publisher(), harness.time(), planner);
        int version = harness.eventStore().currentVersion(ID);
        harness.setClockTo(task.deadline() - 1);
        timeout.execute(task);
        assertEquals(version, harness.eventStore().currentVersion(ID));
        harness.setClockTo(task.deadline());
        timeout.execute(task);
        var votes = harness.eventStream(ID).stream().filter(event -> event.type().equals("PLAYER_VOTED")).toList();
        assertEquals(9, votes.size());
        var mine = votes.stream().filter(event -> ((Number) event.payload().get("seatNo")).intValue() == 1).toList();
        assertEquals(1, mine.size());
        assertEquals(false, mine.getFirst().payload().get("abstain"));
        assertEquals(2, ((Number) mine.getFirst().payload().get("targetSeatNo")).intValue());
        assertTrue(votes.stream().filter(event -> ((Number) event.payload().get("seatNo")).intValue() != 1)
                .allMatch(event -> event.actorId().equals("SYSTEM") && Boolean.TRUE.equals(event.payload().get("abstain"))));
        var finished = harness.repository().load(ID).game();
        var restored = Game.create(GameId.of(ID));
        harness.eventStream(ID).forEach(event -> restored.replay(EventCodec.decode(event)));
        assertEquals(finished.snapshot(), restored.snapshot());
        var current = harness.eventStream(ID);
        timeout.execute(task);
        assertEquals(current, harness.eventStream(ID));
    }
}
