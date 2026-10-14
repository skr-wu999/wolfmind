package com.wolfmind.domain.game.model;

/**
 * 阶段跳转的结果载体：由领域服务计算、由聚合写入事件。
 *
 * <p>{@code resumePhase} 只在插入阶段（{@code HUNTER_SHOOT}）有值。
 */
public record PhaseChange(GamePhase from, GamePhase to, int round, GamePhase resumePhase) {

    public PhaseChange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("PhaseChange 的 from 与 to 不能为空");
        }
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 ≥ 1");
        }
    }

    public static PhaseChange of(GamePhase from, GamePhase to, int round) {
        return new PhaseChange(from, to, round, null);
    }

    public static PhaseChange into(GamePhase from, GamePhase to, int round, GamePhase resumePhase) {
        return new PhaseChange(from, to, round, resumePhase);
    }

    public boolean isInsertion() {
        return resumePhase != null;
    }
}
