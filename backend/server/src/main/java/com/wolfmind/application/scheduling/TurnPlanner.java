package com.wolfmind.application.scheduling;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.*;
import java.util.ArrayList;
import java.util.List;

public final class TurnPlanner {
    private final GameRepositoryPort repository;
    private final EventStorePort events;
    private final SpeechPacing pacing;

    public TurnPlanner(GameRepositoryPort repository, EventStorePort events, SpeechPacing pacing) {
        this.repository = repository; this.events = events; this.pacing = pacing;
    }

    public record Plan(GameSnapshot snapshot, List<TurnTask> tasks) { }

    public Plan plan(String gameId) {
        GameSnapshot state = repository.load(gameId).game().snapshot();
        if (state.status() != GameStatus.RUNNING) return new Plan(state, List.of());
        List<EventEnvelope> history = events.read(gameId, 1, state.version());
        EventEnvelope phase = last(history, "PHASE_CHANGED");
        EventEnvelope turn = state.currentTurn() == null ? null : last(history, "TURN_GRANTED");
        EventEnvelope anchor = turn == null ? phase : turn;
        if (anchor == null) throw new IllegalStateException("Missing committed window: " + gameId);
        int window = anchor.version();
        long deadline = deadline(state, phase);
        List<TurnTask> tasks = new ArrayList<>();
        if (deadline > 0) tasks.add(TurnTask.ready(gameId, TurnTask.Kind.TIMEOUT, "", 0,
                state.phase().name(), state.round(), window, 0, "", "", state.version(), deadline, deadline));
        List<SeatNo> seats = pendingActors(state);
        if (seats.isEmpty()) {
            tasks.add(TurnTask.ready(gameId, TurnTask.Kind.ADVANCE, "", 0, state.phase().name(),
                    state.round(), window, 0, "", "", state.version(), anchor.occurredAt(),
                    deadline > 0 ? deadline : anchor.occurredAt() + state.rules().turnDeadlineMs()));
        }
        for (SeatNo seat : seats) {
            PlayerSnapshot player = state.playerAt(seat);
            if (player.controller() == null || player.controller().type() == ControllerType.HUMAN) continue;
            long ready = anchor.occurredAt();
            if (state.currentTurn() != null && pacing.enabledFor(gameId)
                    && (state.phase() == GamePhase.DAY_DISCUSS || state.phase() == GamePhase.LAST_WORDS)) {
                EventEnvelope speech = last(history, "PLAYER_SPOKE");
                if (speech != null) ready = Math.max(ready, speech.occurredAt()
                        + pacing.pauseFor((String) speech.payload().get("content")));
            }
            int generation = history.stream().filter(e -> e.type().equals("PLAYER_CONTROLLER_CHANGED")
                    && e.payload().get("seatNo") instanceof Number number && number.intValue() == seat.value())
                    .mapToInt(EventEnvelope::version).max().orElse(0);
            tasks.add(TurnTask.ready(gameId, TurnTask.Kind.ACTION, PlayerId.forSeat(seat).value(), seat.value(),
                    state.phase().name(), state.round(), window, generation, player.controller().refId(),
                    player.controller().type().name(), state.version(), ready, deadline));
        }
        return new Plan(state, List.copyOf(tasks));
    }

    public boolean current(TurnTask task) {
        return plan(task.gameId()).tasks().stream().anyMatch(t -> t.taskId().equals(task.taskId()));
    }

    private static EventEnvelope last(List<EventEnvelope> events, String type) {
        for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).type().equals(type)) return events.get(i);
        return null;
    }

    private static long deadline(GameSnapshot state, EventEnvelope phase) {
        if (state.currentTurn() != null) return state.currentTurn().deadline();
        if (phase.payload().get("deadline") instanceof Number number) return number.longValue();
        return switch (state.phase()) {
            case DAY_VOTE -> phase.occurredAt() + state.rules().voteDeadlineMs();
            case NIGHT_WOLF, NIGHT_SEER, NIGHT_WITCH -> phase.occurredAt() + state.rules().turnDeadlineMs();
            default -> 0;
        };
    }

    private static List<SeatNo> pendingActors(GameSnapshot s) {
        return switch (s.phase()) {
            case DAY_DISCUSS, LAST_WORDS -> s.currentTurn() == null ? List.of() : List.of(s.currentTurn().seatNo());
            case DAY_VOTE -> s.players().stream().filter(p -> p.alive() && !s.votes().containsKey(p.seatNo().value()))
                    .map(PlayerSnapshot::seatNo).sorted().toList();
            case NIGHT_WOLF -> s.players().stream().filter(p -> p.alive() && p.role() == Role.WEREWOLF)
                    .map(PlayerSnapshot::seatNo).filter(seat -> s.wolfNominations().stream()
                            .noneMatch(n -> n.wolfSeatNo() == seat.value())).sorted().limit(1).toList();
            case NIGHT_SEER, NIGHT_WITCH -> s.players().stream().filter(p -> p.alive()
                    && p.role() == (s.phase() == GamePhase.NIGHT_SEER ? Role.SEER : Role.WITCH))
                    .map(PlayerSnapshot::seatNo).toList();
            case HUNTER_SHOOT -> s.pendingHunter() == null || s.currentTurn() == null
                    ? List.of() : List.of(SeatNo.of(s.pendingHunter()));
            default -> List.of();
        };
    }
}
