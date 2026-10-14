package com.wolfmind.application.scheduling;

import java.util.List;
import java.util.Optional;

public interface TurnTaskStorePort {
    /** Persist new tasks idempotently, cancelling active tasks absent from this authoritative plan. */
    void reconcile(String gameId, List<TurnTask> tasks);
    List<TurnTask> tasks(String gameId);
    List<TurnTask> due(long now, boolean timeouts, int limit);
    Optional<TurnTask> claim(String taskId, String owner, long now, long leaseMillis);
    boolean finish(TurnTask claimed, TurnTask.Status status, List<String> events, String error);
    boolean valid(ExecutionFence fence, long now);
    boolean reserve(ExecutionFence fence, String counter, int limit, long now);
    /** An interrupted ACTION is not returned to READY: its paid model attempt may have started. */
    int recover(long now);
    void purge(String gameId);
}
