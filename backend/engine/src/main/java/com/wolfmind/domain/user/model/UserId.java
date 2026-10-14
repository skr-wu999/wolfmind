package com.wolfmind.domain.user.model;

import com.wolfmind.domain.game.model.GameException;

/**
 * 用户标识。
 *
 * <p>与 {@code gameId} / {@code roomId} 一样是**不透明字符串**（api-spec.md §1）。
 * 它同时充当事件的 {@code actorId}：真人的行动通过"userId == controller.refId"反查座位。
 */
public record UserId(String value) implements Comparable<UserId> {

    public UserId {
        if (value == null || value.isBlank()) {
            throw GameException.Factory.validation("UserId 不能为空");
        }
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public int compareTo(UserId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}