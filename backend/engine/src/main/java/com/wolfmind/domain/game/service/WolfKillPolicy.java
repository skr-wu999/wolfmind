package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.WolfKillTieBreak;
import com.wolfmind.domain.game.event.WolfKillSelectedEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 狼刀定案（domain-model.md §6.2）。
 *
 * <p>全部存活狼人提名完毕或超时后按票数定案；平票取最小座位号（{@code wolfKillTieBreak}）
 * 以保证确定性；全员弃刀则空刀。纯函数：输入是提名列表，输出是定案目标。
 */
public final class WolfKillPolicy {

    private WolfKillPolicy() {}

    /**
     * 定案结果。
     *
     * @param targetSeat 定案目标座位；空刀时为 {@code null}
     * @param tally      每个被提名座位的票数，按座位号升序
     */
    public record Decision(Integer targetSeat, List<WolfKillSelectedEvent.Nomination> nominations,
                           List<SeatVotes> tally, boolean emptyKill) {}

    /** 单个座位的提名票数。 */
    public record SeatVotes(int seatNo, int votes) {}

    /**
     * 按提名定案。
     *
     * @param nominations 每条提名：狼人座位 + 目标座位（{@code null} 表示该狼人弃刀）
     * @param tieBreak    平票裁决方式
     */
    public static Decision decide(
            List<WolfKillSelectedEvent.Nomination> nominations, WolfKillTieBreak tieBreak) {

        Map<Integer, Integer> votes = new LinkedHashMap<>();
        for (WolfKillSelectedEvent.Nomination nomination : nominations) {
            Integer target = nomination.targetSeatNo();
            if (target != null) {
                votes.merge(target, 1, Integer::sum);
            }
        }

        List<SeatVotes> tally = votes.entrySet().stream()
                .map(entry -> new SeatVotes(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(SeatVotes::seatNo))
                .toList();

        if (votes.isEmpty()) {
            // 全员弃刀：空刀仍然产生事件，因为"当夜无人被刀"是可回放的对局事实。
            return new Decision(null, List.copyOf(nominations), List.of(), true);
        }

        int maxVotes = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> topSeats = votes.entrySet().stream()
                .filter(entry -> entry.getValue() == maxVotes)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        int chosen = tieBreak == WolfKillTieBreak.MAX_SEAT
                ? topSeats.get(topSeats.size() - 1)
                : topSeats.get(0);

        return new Decision(chosen, List.copyOf(nominations), tally, false);
    }

    /** 便于测试：只要目标座位。 */
    public static Optional<Integer> decideTarget(
            List<WolfKillSelectedEvent.Nomination> nominations, WolfKillTieBreak tieBreak) {
        return Optional.ofNullable(decide(nominations, tieBreak).targetSeat());
    }

    /** 便于测试构造提名列表。 */
    public static List<WolfKillSelectedEvent.Nomination> nominationsOf(int... seatPairs) {
        if (seatPairs.length % 2 != 0) {
            throw new IllegalArgumentException("提名必须是 (wolfSeat, targetSeat) 成对出现");
        }
        List<WolfKillSelectedEvent.Nomination> list = new ArrayList<>();
        for (int i = 0; i < seatPairs.length; i += 2) {
            int target = seatPairs[i + 1];
            list.add(new WolfKillSelectedEvent.Nomination(seatPairs[i], target <= 0 ? null : target));
        }
        return List.copyOf(list);
    }
}
