package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.SeatNo;

import java.util.ArrayList;
import java.util.List;

/**
 * 发言顺序裁定（domain-model.md §6.2）。
 *
 * <p>首轮从最小存活座位升序；之后从上一轮首位之后的第一个存活座位顺延，形成环形顺序。
 * 纯函数，不访问时钟与随机数，保证 INV-16。
 */
public final class TurnOrderPolicy {

    private TurnOrderPolicy() {}

    /**
     * 计算本轮发言顺序。
     *
     * @param alivePlayers  全部玩家（内部按存活过滤）
     * @param previousFirst 上一轮的首位座位；首轮传 {@code null}
     * @return 发言顺序（座位号），全部存活玩家恰好出现一次且环形连续
     */
    public static List<SeatNo> orderFor(List<PlayerSnapshot> alivePlayers, SeatNo previousFirst) {
        List<SeatNo> alive = alivePlayers.stream()
                .filter(PlayerSnapshot::alive)
                .map(PlayerSnapshot::seatNo)
                .sorted()
                .toList();
        if (alive.isEmpty()) {
            return List.of();
        }
        if (previousFirst == null) {
            return alive;
        }
        // 环形顺延：从"大于上一轮首位"的第一个存活座位开始，找不到则回到最小存活座位。
        int startIndex = 0;
        for (int i = 0; i < alive.size(); i++) {
            if (alive.get(i).compareTo(previousFirst) > 0) {
                startIndex = i;
                break;
            }
        }
        List<SeatNo> ordered = new ArrayList<>(alive.size());
        for (int offset = 0; offset < alive.size(); offset++) {
            ordered.add(alive.get((startIndex + offset) % alive.size()));
        }
        return List.copyOf(ordered);
    }
}
