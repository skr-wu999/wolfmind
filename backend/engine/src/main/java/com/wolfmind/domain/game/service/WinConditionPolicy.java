package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.WinCondition;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 胜负判定（domain-model.md §6.2、ddd-design.md §2.4）。
 *
 * <p>规则由 {@link WinCondition} 选择：
 * <ul>
 *   <li>{@code WOLVES_OUTNUMBER}：狼全灭 → 村民胜；狼数 ≥ 村民阵营存活数 → 狼人胜；</li>
 *   <li>{@code SIDE_KILL}（屠边）：狼全灭 → 村民胜；平民全灭或神职全灭 → 狼人胜。</li>
 * </ul>
 *
 * <p>两种规则的判定顺序都固定为"先判狼全灭"，因此最后一狼与最后一民同时死亡时判村民胜
 * （domain-model.md §6.2 明确要求这条边界行为）。
 */
public final class WinConditionPolicy {

    private WinConditionPolicy() {}

    /** 胜负判定结果。 */
    public record Verdict(Camp winner, String reason) {}

    /** 胜负原因字符串，与 {@code GAME_FINISHED.reason} 一致。 */
    public static final String REASON_ALL_WOLVES_ELIMINATED = "ALL_WOLVES_ELIMINATED";
    public static final String REASON_WOLVES_OUTNUMBER_VILLAGE = "WOLVES_OUTNUMBER_VILLAGE";
    public static final String REASON_ALL_VILLAGERS_ELIMINATED = "ALL_VILLAGERS_ELIMINATED";
    public static final String REASON_ALL_GODS_ELIMINATED = "ALL_GODS_ELIMINATED";

    /**
     * 判定胜负。
     *
     * @param players   全部玩家快照（含已死亡者：屠边需要知道开局时每一边有没有人）
     * @param condition 本局胜负条件
     * @return 已分出胜负时返回判定，否则返回空
     */
    public static Optional<Verdict> check(List<PlayerSnapshot> players, WinCondition condition) {
        long aliveWolves = players.stream().filter(p -> p.alive() && isWolf(p)).count();
        if (aliveWolves == 0) {
            return Optional.of(new Verdict(Camp.VILLAGE, REASON_ALL_WOLVES_ELIMINATED));
        }
        return switch (condition) {
            case WOLVES_OUTNUMBER -> checkOutnumber(players, aliveWolves);
            case SIDE_KILL -> checkSideKill(players);
        };
    }

    private static Optional<Verdict> checkOutnumber(List<PlayerSnapshot> players, long aliveWolves) {
        long aliveVillage = players.stream().filter(p -> p.alive() && !isWolf(p)).count();
        if (aliveWolves >= aliveVillage) {
            return Optional.of(new Verdict(Camp.WOLF, REASON_WOLVES_OUTNUMBER_VILLAGE));
        }
        return Optional.empty();
    }

    private static Optional<Verdict> checkSideKill(List<PlayerSnapshot> players) {
        if (sideEliminated(players, WinConditionPolicy::isVillager)) {
            return Optional.of(new Verdict(Camp.WOLF, REASON_ALL_VILLAGERS_ELIMINATED));
        }
        if (sideEliminated(players, WinConditionPolicy::isGod)) {
            return Optional.of(new Verdict(Camp.WOLF, REASON_ALL_GODS_ELIMINATED));
        }
        return Optional.empty();
    }

    /** 该边开局时有人、且现在全部出局。 */
    private static boolean sideEliminated(
            List<PlayerSnapshot> players, Predicate<PlayerSnapshot> side) {
        boolean present = players.stream().anyMatch(side);
        return present && players.stream().noneMatch(p -> p.alive() && side.test(p));
    }

    private static boolean isWolf(PlayerSnapshot player) {
        return player.camp() == Camp.WOLF;
    }

    private static boolean isVillager(PlayerSnapshot player) {
        return player.role() == Role.VILLAGER;
    }

    /** 神职：村民阵营里除平民以外的角色（预言家、女巫、猎人）。 */
    private static boolean isGod(PlayerSnapshot player) {
        return player.camp() == Camp.VILLAGE && player.role() != Role.VILLAGER;
    }
}
