package com.wolfmind.domain.game.model;

/**
 * 角色。camp 是派生属性，不单独存储（domain-model.md §5.1）。
 *
 * <p>数量的唯一来源是 {@link RoleComposition}；新增角色只需加枚举值与配置项
 * （ddd-design.md §9 演进规则）。
 */
public enum Role {

    WEREWOLF(Camp.WOLF, 1),
    SEER(Camp.VILLAGE, 2),
    WITCH(Camp.VILLAGE, 3),
    HUNTER(Camp.VILLAGE, null),
    VILLAGER(Camp.VILLAGE, null);

    private final Camp camp;
    private final Integer nightOrder;

    Role(Camp camp, Integer nightOrder) {
        this.camp = camp;
        this.nightOrder = nightOrder;
    }

    public Camp camp() {
        return camp;
    }

    /** 夜间行动顺序；{@code null} 表示该角色没有夜间行动阶段。 */
    public Integer nightOrder() {
        return nightOrder;
    }

    public boolean isWolfTeam() {
        return camp == Camp.WOLF;
    }

    public boolean isVillageTeam() {
        return camp == Camp.VILLAGE;
    }

    /** 是否有夜间行动阶段（domain-model.md §4 的阶段表）。 */
    public boolean hasNightAction() {
        return nightOrder != null;
    }
}
