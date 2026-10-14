package com.wolfmind.domain.room.model;

import com.wolfmind.domain.game.model.GameException;

/**
 * 房间标识。
 *
 * <p>与 {@code gameId} 一样是**不透明字符串**（api-spec.md 1）：客户端不得从中解析任何结构。
 * 当前形态 {@code r_<序列>} 只是生成约定，不是契约。
 */
public record RoomId(String value) implements Comparable<RoomId> {

    public RoomId {
        if (value == null || value.isBlank()) {
            throw GameException.Factory.validation("RoomId 不能为空");
        }
    }

    public static RoomId of(String value) {
        return new RoomId(value);
    }

    @Override
    public int compareTo(RoomId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}