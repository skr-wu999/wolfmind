package com.wolfmind.domain.game.model;

/**
 * 玩家的只读投影（domain-model.md §5.1）。
 *
 * <p>{@code role} 在这里不做脱敏：脱敏是 {@code GameViewProjector} 与 {@code VisibilityFilter}
 * 的职责，领域快照必须保留完整状态才能用于回放与恢复（INV-15）。
 */
public record PlayerSnapshot(
        PlayerId playerId,
        SeatNo seatNo,
        Role role,
        Camp camp,
        boolean alive,
        PlayerController controller,
        boolean ready,
        SkillState skillState) {

    public boolean isWolf() {
        return role != null && role.isWolfTeam();
    }
}
