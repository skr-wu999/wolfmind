package com.wolfmind.application.scheduling;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameSnapshot;

public final class TaskSubmissionGuard {
    private final TurnTaskStorePort tasks;
    private final TurnPlanner planner;
    public TaskSubmissionGuard(TurnTaskStorePort tasks, TurnPlanner planner) { this.tasks = tasks; this.planner = planner; }
    public void validate(ExecutionFence fence, GameSnapshot snapshot, long now) {
        if (!snapshot.gameId().value().equals(fence.gameId()) || !tasks.valid(fence, now)) {
            throw GameException.Factory.notAllowed("Task expired or superseded");
        }
        TurnTask task = tasks.tasks(fence.gameId()).stream().filter(t -> t.taskId().equals(fence.taskId())).findFirst().orElseThrow();
        if (!planner.current(task)) throw GameException.Factory.notAllowed("Task window closed");
    }
}
