package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.TallyResult;
import com.wolfmind.domain.game.model.Vote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 计票（domain-model.md §6.2）。
 *
 * <p>统计有效票；最高票为 0 → {@code ALL_ABSTAIN}；最高票并列 → {@code TIE}（无人出局）；
 * 否则 {@code EXILE}。纯函数，可直接单测覆盖边界：全员弃权、平票、狼人自刀出局。
 */
public final class VoteTallyPolicy {

    private VoteTallyPolicy() {}

    /**
     * 计票。
     *
     * @param votes        当前投票轮的票，键为投票者座位；未提交者不出现在 map 中（超时由调用方先补弃权票）
     * @param alivePlayers 存活玩家（用于校验目标是否存活，防止指向死者的票被计入）
     */
    public static TallyResult tally(Map<Integer, Vote> votes, List<PlayerSnapshot> alivePlayers) {
        Map<Integer, Boolean> aliveBySeat = new java.util.HashMap<>();
        for (PlayerSnapshot player : alivePlayers) {
            aliveBySeat.put(player.seatNo().value(), player.alive());
        }

        Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        int abstainCount = 0;
        for (Vote vote : votes.values()) {
            if (vote.abstain()) {
                abstainCount++;
                continue;
            }
            int target = vote.targetSeat();
            // 目标已死亡的票视为无效，不计入任何座位。
            if (!Boolean.TRUE.equals(aliveBySeat.get(target))) {
                continue;
            }
            counts.merge(target, 1, Integer::sum);
        }

        List<TallyResult.SeatTally> tally = counts.entrySet().stream()
                .map(entry -> new TallyResult.SeatTally(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(TallyResult.SeatTally::seatNo))
                .toList();

        int maxVotes = tally.stream().mapToInt(TallyResult.SeatTally::votes).max().orElse(0);
        if (maxVotes == 0) {
            return TallyResult.allAbstain(abstainCount);
        }

        List<Integer> topSeats = tally.stream()
                .filter(entry -> entry.votes() == maxVotes)
                .map(TallyResult.SeatTally::seatNo)
                .sorted()
                .toList();

        if (topSeats.size() > 1) {
            return TallyResult.tie(new ArrayList<>(tally), maxVotes, topSeats, abstainCount);
        }
        return TallyResult.exile(new ArrayList<>(tally), maxVotes, topSeats.get(0), abstainCount);
    }
}
