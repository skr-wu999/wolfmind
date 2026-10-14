package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.scheduling.*;
import java.util.*;

public final class InMemoryTurnTaskStore implements TurnTaskStorePort {
    private final Map<String, TurnTask> tasks = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, Integer> limits = new HashMap<>();
    @Override public synchronized void reconcile(String gameId, List<TurnTask> proposed) {
        Set<String> ids = new HashSet<>();
        proposed.forEach(task -> ids.add(task.taskId()));
        tasks.replaceAll((id, t) -> t.gameId().equals(gameId) && t.active() && !ids.contains(id)
                ? t.finished(TurnTask.Status.CANCELLED, List.of(), "WINDOW_CLOSED") : t);
        proposed.forEach(t -> tasks.putIfAbsent(t.taskId(), t));
    }
    @Override public synchronized List<TurnTask> tasks(String gameId) {
        return tasks.values().stream().filter(t -> t.gameId().equals(gameId)).toList();
    }
    @Override public synchronized List<TurnTask> due(long now, boolean timeouts, int limit) {
        return tasks.values().stream().filter(t -> t.status() == TurnTask.Status.READY && t.readyAt() <= now
                && (t.kind() == TurnTask.Kind.TIMEOUT) == timeouts).sorted(Comparator.comparingLong(TurnTask::readyAt))
                .limit(limit).toList();
    }
    @Override public synchronized Optional<TurnTask> claim(String id, String owner, long now, long leaseMs) {
        TurnTask task = tasks.get(id);
        if (task == null || task.status() != TurnTask.Status.READY || task.readyAt() > now) return Optional.empty();
        TurnTask claimed = task.claimed(owner, now, leaseMs); tasks.put(id, claimed); return Optional.of(claimed);
    }
    @Override public synchronized boolean finish(TurnTask task, TurnTask.Status status, List<String> events, String error) {
        TurnTask current = tasks.get(task.taskId());
        if (current == null || current.status() != TurnTask.Status.RUNNING
                || current.leaseToken() != task.leaseToken() || !current.leaseOwner().equals(task.leaseOwner())) return false;
        tasks.put(task.taskId(), current.finished(status, events, error)); return true;
    }
    @Override public synchronized boolean valid(ExecutionFence fence, long now) {
        TurnTask t = tasks.get(fence.taskId());
        return t != null && t.kind() == TurnTask.Kind.ACTION && t.status() == TurnTask.Status.RUNNING
                && t.leaseToken() == fence.leaseToken() && t.leaseOwner().equals(fence.leaseOwner())
                && t.gameId().equals(fence.gameId()) && t.playerId().equals(fence.playerId())
                && t.windowId() == fence.windowId()
                && now < t.deadline() && now < t.leaseUntil();
    }
    @Override public synchronized int recover(long now) {
        int count = 0;
        for (TurnTask task : List.copyOf(tasks.values())) if (task.status() == TurnTask.Status.RUNNING && task.leaseUntil() <= now) {
            TurnTask.Status next = task.kind() == TurnTask.Kind.ACTION ? TurnTask.Status.WAITING_DEADLINE : TurnTask.Status.READY;
            tasks.put(task.taskId(), task.finished(next, List.of(), "INTERRUPTED")); count++;
        }
        return count;
    }
    @Override public synchronized boolean reserve(ExecutionFence fence, String counter, int limit, long now) {
        if (!valid(fence, now)) return false;
        String key = fence.taskId() + ":" + counter;
        limits.putIfAbsent(key, limit);
        int count = counts.getOrDefault(key, 0);
        if (count >= limits.get(key)) return false;
        counts.put(key, count + 1); return true;
    }
    @Override public synchronized void purge(String gameId) {
        var ids = tasks.values().stream().filter(t -> t.gameId().equals(gameId)).map(TurnTask::taskId).toList();
        ids.forEach(tasks::remove);
        ids.forEach(id -> { counts.keySet().removeIf(key -> key.startsWith(id+":")); limits.keySet().removeIf(key -> key.startsWith(id+":")); });
    }
}
