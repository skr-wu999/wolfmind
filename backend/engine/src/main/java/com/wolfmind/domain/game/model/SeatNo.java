package com.wolfmind.domain.game.model;

/** 座位号：1..9，对局内唯一，玩家身份的稳定引用（ddd-design.md §1.3）。 */
public record SeatNo(int value) implements Comparable<SeatNo> {

    /** 标准局座位数最大值。座位数由 RoleComposition 校验，这里只做上界保护。 */
    public static final int MAX = 9;

    public SeatNo {
        if (value < 1 || value > MAX) {
            throw new IllegalArgumentException("座位号必须在 1.." + MAX + " 之间，实际为 " + value);
        }
    }

    public static SeatNo of(int value) {
        return new SeatNo(value);
    }

    @Override
    public int compareTo(SeatNo other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
