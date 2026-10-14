package com.wolfmind.application.agent.model;

/** 派发与工具预算共用的标识；version 是本次派发开始时的聚合版本。 */
public record TurnKey(String gameId, String playerId, int version) {
}
