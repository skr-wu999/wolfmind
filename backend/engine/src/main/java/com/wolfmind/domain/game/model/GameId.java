package com.wolfmind.domain.game.model;

/** 对局标识：聚合标识，也是事件流分区键（domain-model.md §2.1）。 */
public record GameId(String value) {

    public GameId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
    }

    public static GameId of(String value) {
        return new GameId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
