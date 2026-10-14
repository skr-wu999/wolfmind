package com.wolfmind.domain.game.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段状态机节点。
 *
 * <p>转换表来自 docs/architecture/ddd-design.md §3.2。{@link #allowsNext(GamePhase)} 是
 * 该表的唯一实现：任何阶段跳转都必须先通过它校验，表外转换一律拒绝。
 *
 * <p>注意 {@code HUNTER_SHOOT} 的后继是动态的（回到死亡发生时原本将要进入的阶段），
 * 因此它的后继集合是"可能取值的并集"，聚合另有 {@code resumePhase} 做精确校验。
 */
public enum GamePhase {

    /** 已创建聚合、尚未开局。 */
    GAME_INIT(0),
    /** 夜间：狼人提名。 */
    NIGHT_WOLF(1),
    /** 夜间：预言家查验。 */
    NIGHT_SEER(2),
    /** 夜间：女巫用药。 */
    NIGHT_WITCH(3),
    /** 夜间：结算（领域自动，无行动）。 */
    NIGHT_SETTLE(4),
    /** 白天：公布死讯（领域自动，无行动）。 */
    DAY_ANNOUNCE(5),
    /** 遗言窗口。 */
    LAST_WORDS(6),
    /** 白天：发言讨论，逐位发放回合。 */
    DAY_DISCUSS(7),
    /** 白天：同时投票，无回合窗口。 */
    DAY_VOTE(8),
    /** 白天：计票结算（领域自动，无行动）。 */
    VOTE_SETTLE(9),
    /** 插入阶段：猎人开枪。 */
    HUNTER_SHOOT(10),
    /** 终态。 */
    GAME_OVER(11);

    private static final Map<GamePhase, Set<GamePhase>> TRANSITIONS = Map.ofEntries(
            Map.entry(GAME_INIT, Set.of(NIGHT_WOLF)),
            Map.entry(NIGHT_WOLF, Set.of(NIGHT_SEER)),
            Map.entry(NIGHT_SEER, Set.of(NIGHT_WITCH)),
            Map.entry(NIGHT_WITCH, Set.of(NIGHT_SETTLE)),
            Map.entry(NIGHT_SETTLE, Set.of(HUNTER_SHOOT, DAY_ANNOUNCE)),
            Map.entry(DAY_ANNOUNCE, Set.of(HUNTER_SHOOT, LAST_WORDS)),
            // LAST_WORDS → NIGHT_WOLF 来自 §3.2 的状态机图（"未分胜负，round+1"回到顶部），
            // 而不是同一节的转换表；被放逐者遗言结束后进入下一轮夜晚是必需的边。
            // LAST_WORDS → GAME_OVER：遗言阶段做最后判定后进入终态——进入终态本身是一次阶段跳转，
            // 必须有 PHASE_CHANGED（domain-model.md §4 的 GAME_OVER 行、event-design.md §14.4）。
            Map.entry(LAST_WORDS, Set.of(DAY_DISCUSS, DAY_VOTE, HUNTER_SHOOT, NIGHT_WOLF, GAME_OVER)),
            Map.entry(DAY_DISCUSS, Set.of(DAY_DISCUSS, DAY_VOTE)),
            Map.entry(DAY_VOTE, Set.of(VOTE_SETTLE)),
            Map.entry(VOTE_SETTLE, Set.of(HUNTER_SHOOT, LAST_WORDS, NIGHT_WOLF, GAME_OVER)),
            Map.entry(HUNTER_SHOOT, Set.of(
                    NIGHT_WOLF, NIGHT_SEER, NIGHT_WITCH, NIGHT_SETTLE, DAY_ANNOUNCE,
                    LAST_WORDS, DAY_DISCUSS, DAY_VOTE, VOTE_SETTLE, GAME_OVER)),
            Map.entry(GAME_OVER, Set.<GamePhase>of()));

    private final int order;

    GamePhase(int order) {
        this.order = order;
    }

    /** 在 §3.2 主流程中的先后次序，用于时间线与断言，不参与规则判定。 */
    public int order() {
        return order;
    }

    /** 转换表校验：{@code to} 是否是本阶段允许的后继。 */
    public boolean allowsNext(GamePhase to) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(to);
    }

    public Set<GamePhase> successors() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }

    /** 夜间阶段。 */
    public boolean isNight() {
        return this == NIGHT_WOLF || this == NIGHT_SEER || this == NIGHT_WITCH || this == NIGHT_SETTLE;
    }

    /** 白天阶段。 */
    public boolean isDay() {
        return this == DAY_ANNOUNCE
                || this == LAST_WORDS
                || this == DAY_DISCUSS
                || this == DAY_VOTE
                || this == VOTE_SETTLE;
    }

    /** 终态。 */
    public boolean isTerminal() {
        return this == GAME_OVER;
    }

    /**
     * 是否发放回合窗口。
     *
     * <p>{@code DAY_VOTE} 与 {@code NIGHT_WOLF} 是同时提交、无顺序的阶段，不发放 `TURN_GRANTED`
     * （domain-model.md §4 说明）。
     */
    public boolean grantsTurn() {
        return switch (this) {
            case LAST_WORDS, DAY_DISCUSS, HUNTER_SHOOT -> true;
            default -> false;
        };
    }

    /** 是否有朝一日会"本阶段再也不能产生新事件"，即应用层可对其调用 {@code advance}。 */
    public boolean autoSettled() {
        return this == NIGHT_SETTLE || this == DAY_ANNOUNCE || this == VOTE_SETTLE;
    }

    /** 是否接受玩家行动（任一 Action 类型）。 */
    public boolean acceptsActions() {
        return switch (this) {
            case GAME_INIT, NIGHT_SETTLE, DAY_ANNOUNCE, VOTE_SETTLE, GAME_OVER -> false;
            default -> true;
        };
    }

    /** 主流程顺序列表，供测试遍历转换表。 */
    public static List<GamePhase> ordered() {
        return List.of(values());
    }
}
