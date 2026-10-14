package com.wolfmind.application.scheduling;

/** Server-bound task credential; never constructed from model arguments or client payloads. */
public record ExecutionFence(String taskId, String gameId, String playerId, int windowId,
                             long leaseToken, String leaseOwner, long deadline) { }
