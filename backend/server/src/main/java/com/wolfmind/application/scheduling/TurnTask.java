package com.wolfmind.application.scheduling;

import java.util.List;

public record TurnTask(String taskId, String gameId, Kind kind, String playerId, int seatNo,
                       String phase, int round, int windowId, int controllerGeneration,
                       String controllerRef, String controllerType, int dispatchVersion,
                       long readyAt, long deadline, Status status, int attempts,
                       String leaseOwner, long leaseToken, long leaseUntil,
                       List<String> resultEventIds, String lastErrorCode) {
    public enum Kind { ACTION, TIMEOUT, ADVANCE }
    public enum Status { READY, RUNNING, SUCCEEDED, WAITING_DEADLINE, EXPIRED, CANCELLED }

    public TurnTask { resultEventIds = List.copyOf(resultEventIds); }

    public static TurnTask ready(String gameId, Kind kind, String playerId, int seatNo,
                                 String phase, int round, int windowId, int generation,
                                 String controllerRef, String controllerType, int version,
                                 long readyAt, long deadline) {
        String id = gameId + ":" + windowId + ":" + kind + ":" + playerId + ":" + generation;
        return new TurnTask(id, gameId, kind, playerId, seatNo, phase, round, windowId, generation,
                controllerRef, controllerType, version, readyAt, deadline, Status.READY, 0,
                "", 0, 0, List.of(), null);
    }

    public boolean active() { return status == Status.READY || status == Status.RUNNING || status == Status.WAITING_DEADLINE; }

    public TurnTask claimed(String owner, long now, long leaseMillis) {
        return copy(Status.RUNNING, attempts + 1, owner, leaseToken + 1,
                kind == Kind.ACTION ? Math.min(deadline, now + leaseMillis) : now + leaseMillis, List.of(), null);
    }

    public TurnTask finished(Status next, List<String> events, String error) {
        return copy(next, attempts, "", leaseToken, 0, events, error);
    }

    private TurnTask copy(Status next, int count, String owner, long token, long until,
                          List<String> events, String error) {
        return new TurnTask(taskId, gameId, kind, playerId, seatNo, phase, round, windowId,
                controllerGeneration, controllerRef, controllerType, dispatchVersion,
                readyAt, deadline, next, count, owner, token, until, events, error);
    }

    public ExecutionFence fence() {
        return new ExecutionFence(taskId, gameId, playerId, windowId, leaseToken, leaseOwner, deadline);
    }
}
