package com.wolfmind.domain.game;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoteSkipValidationTest {
    private int command;

    private ActionContext context(Game game, String actor) {
        return ActionContext.of("vote-test-" + command++, 1_000 + command, actor, null, null, game.version());
    }

    private Game votingGame(boolean abstainAllowed) {
        var defaults = GameRules.DEFAULT;
        var rules = new GameRules(defaults.speechMaxLength(), defaults.turnDeadlineMs(), defaults.agentTurnDeadlineMs(),
                defaults.voteDeadlineMs(), defaults.firstNightLastWords(), defaults.laterNightLastWords(),
                defaults.exileLastWords(), defaults.dayShotLastWords(), defaults.witchSelfSave(),
                defaults.witchSelfSaveFirstNightOnly(), defaults.witchSaveTargetMustBeKilled(), defaults.allowWolfEmptyKill(),
                defaults.wolfKillTieBreak(), defaults.voteTieRule(), defaults.hunterCanShootWhenPoisoned(),
                defaults.winCondition(), abstainAllowed, defaults.stalledRoundsLimit());
        var game = Game.create(GameId.of("g_vote_skip"));
        var seats = new ArrayList<SeatAssignment>();
        for (int seat = 1; seat <= 9; seat++) seats.add(SeatAssignment.human(seat, "u_" + seat));
        game.start(new StartGameCommand(game.gameId(), "room", seats, RoleComposition.STANDARD_NINE, rules),
                context(game, "SYSTEM").withRandomSeed(42));
        for (int step = 0; step < 30 && game.phase() != GamePhase.DAY_VOTE; step++) {
            var state = game.snapshot();
            int seat = switch (game.phase()) {
                case NIGHT_WOLF -> state.players().stream().filter(player -> player.isWolf()
                        && state.wolfNominations().stream().noneMatch(vote -> vote.wolfSeatNo() == player.seatNo().value()))
                        .findFirst().orElseThrow().seatNo().value();
                case NIGHT_SEER -> state.players().stream().filter(player -> player.role() == Role.SEER)
                        .findFirst().orElseThrow().seatNo().value();
                case NIGHT_WITCH -> state.players().stream().filter(player -> player.role() == Role.WITCH)
                        .findFirst().orElseThrow().seatNo().value();
                default -> state.currentTurn().seatNo().value();
            };
            game.handle(SkipAction.voluntary(seat), context(game, "u_" + seat));
        }
        assertEquals(GamePhase.DAY_VOTE, game.phase());
        return game;
    }

    private void rejectedWithoutChange(Game game, Action action, String actor, DomainErrorCode code) {
        var before = game.snapshot();
        assertEquals(code, assertThrows(GameException.class, () -> game.handle(action, context(game, actor))).code());
        assertEquals(before, game.snapshot());
        assertTrue(game.pendingEvents().isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void submittedVoteCannotBeOverwrittenByEitherSkipReason(boolean abstainAllowed) {
        var game = votingGame(abstainAllowed);
        game.handle(VoteAction.to(1, 2), context(game, "u_1"));
        for (SkipReason reason : SkipReason.values()) {
            rejectedWithoutChange(game, SkipAction.of(1, reason), "SYSTEM", DomainErrorCode.ACTION_ALREADY_SUBMITTED);
            assertEquals(Vote.of(1, 2), game.snapshot().votes().get(1));
        }
    }

    @Test void voluntarySkipIsRejectedWhenAbstentionIsForbidden() {
        var game = votingGame(false);
        rejectedWithoutChange(game, SkipAction.voluntary(1), "u_1", DomainErrorCode.ACTION_NOT_ALLOWED);
    }

    @Test void allowedVoluntaryAbstentionCanOnlyBeSubmittedOnce() {
        var game = votingGame(true);
        List<DomainEvent> events = game.handle(SkipAction.voluntary(1), context(game, "u_1"));
        assertEquals(1, events.size());
        assertEquals(EventType.PLAYER_VOTED, events.getFirst().type());
        assertTrue(game.snapshot().votes().get(1).abstain());
        rejectedWithoutChange(game, SkipAction.voluntary(1), "u_1", DomainErrorCode.ACTION_ALREADY_SUBMITTED);
        rejectedWithoutChange(game, VoteAction.to(1, 2), "u_1", DomainErrorCode.ACTION_ALREADY_SUBMITTED);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void playerCannotClaimToBeTheTimeoutService(boolean abstainAllowed) {
        var game = votingGame(abstainAllowed);
        rejectedWithoutChange(game, SkipAction.timeout(1), "u_1", DomainErrorCode.ACTION_NOT_ALLOWED);
    }

    @Test void systemCanFillOnlyMissingVotesEvenWhenVoluntaryAbstentionIsForbidden() {
        var game = votingGame(false);
        game.handle(VoteAction.to(1, 2), context(game, "u_1"));
        var missing = game.timeoutSkips(100_000, true).skips();
        assertEquals(8, missing.size());
        assertTrue(missing.stream().noneMatch(skip -> skip.actor().value() == 1));
        var events = game.handle(missing.getFirst(), context(game, "SYSTEM"));
        assertEquals(EventType.PLAYER_VOTED, events.getFirst().type());
        assertEquals(Vote.of(1, 2), game.snapshot().votes().get(1));
        assertTrue(game.snapshot().votes().get(missing.getFirst().actor().value()).abstain());
    }
}
