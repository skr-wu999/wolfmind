package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.SeatNo;

import java.util.Optional;

/**
 * 按角色定位座位：夜间阶段需要知道"谁是这个角色、是否还活着"。
 *
 * <p>角色在开局固化（INV-2），因此这里只是查询，不做任何分配决策。
 */
public final class RoleLocator {

    private RoleLocator() {}

    /** 该角色的持有着；角色不存在时返回空。 */
    public static Optional<PlayerSnapshot> holderOf(java.util.List<PlayerSnapshot> players, Role role) {
        return players.stream().filter(player -> player.role() == role).findFirst();
    }

    /** 该角色且仍然存活；已死亡返回空——死亡的角色不再拥有夜间阶段（应直接跳过）。 */
    public static Optional<PlayerSnapshot> aliveHolderOf(
            java.util.List<PlayerSnapshot> players, Role role) {
        return players.stream()
                .filter(player -> player.role() == role && player.alive())
                .findFirst();
    }

    /** 存活狼人，按座位号升序（事件顺序必须确定，INV-16）。 */
    public static java.util.List<PlayerSnapshot> aliveWolves(java.util.List<PlayerSnapshot> players) {
        return players.stream()
                .filter(player -> player.alive() && player.role() == Role.WEREWOLF)
                .sorted(java.util.Comparator.comparing(PlayerSnapshot::seatNo))
                .toList();
    }

    /** 全部玩家按座位号升序。 */
    public static java.util.List<PlayerSnapshot> bySeat(java.util.List<PlayerSnapshot> players) {
        return players.stream().sorted(java.util.Comparator.comparing(PlayerSnapshot::seatNo)).toList();
    }

    /** 座位号 → 玩家。 */
    public static Optional<PlayerSnapshot> at(java.util.List<PlayerSnapshot> players, SeatNo seatNo) {
        return players.stream().filter(player -> player.seatNo().equals(seatNo)).findFirst();
    }
}
