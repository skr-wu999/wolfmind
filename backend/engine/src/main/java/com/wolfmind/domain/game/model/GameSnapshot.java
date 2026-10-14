package com.wolfmind.domain.game.model;

import com.wolfmind.domain.game.event.WolfKillSelectedEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 聚合完整状态的只读投影（domain-model.md §7「snapshot() 是只读推导」）。
 *
 * <p>快照不是真相来源（INV-15）：任何快照都必须能由事件流重新推导。本记录保留完整角色信息，
 * 因为恢复与回放需要它；脱敏发生在 {@code GameViewProjector} / {@code VisibilityFilter}。
 *
 * <p>字段全部是"重放能还原的事实"，派生判断（如 {@link #witchActed()}）只做组合，不引入额外状态，
 * 避免出现"快照与事件流可能分叉"的第二个真相。
 *
 * <p>{@code wolfChatCounts} 是本夜每名狼人已发送的夜聊条数（键为座位号），进入 {@code NIGHT_WOLF}
 * 时清空；{@code stateVersion} 是最后一条非 {@code WOLF_CHAT_SENT} 事件的版本，应用层用它判断
 * 客户端的 {@code expectedVersion} 之后是否只追加了夜聊。
 */
public record GameSnapshot(
        GameId gameId,
        int version,
        GameStatus status,
        GamePhase phase,
        int round,
        RoleComposition roleComposition,
        GameRules rules,
        String roomId,
        List<PlayerSnapshot> players,
        List<Integer> turnOrder,
        int turnIndex,
        Integer previousDiscussionFirst,
        Turn currentTurn,
        Integer wolfKillTarget,
        List<WolfKillSelectedEvent.Nomination> wolfNominations,
        Integer witchSaved,
        Integer witchPoisoned,
        boolean witchPassed,
        Integer inspectedTarget,
        Camp inspectResult,
        Map<Integer, Vote> votes,
        List<DeathRecord> deaths,
        GamePhase resumePhase,
        GamePhase pendingAfterLastWords,
        List<Integer> pendingLastWords,
        int lastWordsIndex,
        Integer pendingHunter,
        Integer pendingShotVictim,
        Camp winner,
        String finishReason,
        Map<Integer, Integer> wolfChatCounts,
        int stateVersion) {

    public GameSnapshot {
        players = List.copyOf(players);
        turnOrder = List.copyOf(turnOrder);
        wolfNominations = List.copyOf(wolfNominations);
        votes = Map.copyOf(votes);
        deaths = List.copyOf(deaths);
        pendingLastWords = List.copyOf(pendingLastWords);
        wolfChatCounts = Map.copyOf(wolfChatCounts);
        if (stateVersion > version) {
            throw GameException.Factory.validation(
                    "stateVersion " + stateVersion + " 不能大于 version " + version);
        }
    }

    /** 没有夜聊的状态：计数为空，{@code stateVersion} 等于 {@code version}。 */
    public GameSnapshot(
            GameId gameId,
            int version,
            GameStatus status,
            GamePhase phase,
            int round,
            RoleComposition roleComposition,
            GameRules rules,
            String roomId,
            List<PlayerSnapshot> players,
            List<Integer> turnOrder,
            int turnIndex,
            Integer previousDiscussionFirst,
            Turn currentTurn,
            Integer wolfKillTarget,
            List<WolfKillSelectedEvent.Nomination> wolfNominations,
            Integer witchSaved,
            Integer witchPoisoned,
            boolean witchPassed,
            Integer inspectedTarget,
            Camp inspectResult,
            Map<Integer, Vote> votes,
            List<DeathRecord> deaths,
            GamePhase resumePhase,
            GamePhase pendingAfterLastWords,
            List<Integer> pendingLastWords,
            int lastWordsIndex,
            Integer pendingHunter,
            Integer pendingShotVictim,
            Camp winner,
            String finishReason) {
        this(gameId, version, status, phase, round, roleComposition, rules, roomId, players, turnOrder,
                turnIndex, previousDiscussionFirst, currentTurn, wolfKillTarget, wolfNominations,
                witchSaved, witchPoisoned, witchPassed, inspectedTarget, inspectResult, votes, deaths,
                resumePhase, pendingAfterLastWords, pendingLastWords, lastWordsIndex, pendingHunter,
                pendingShotVictim, winner, finishReason, Map.of(), version);
    }

    /** 该狼人本夜已发送的夜聊条数。 */
    public int wolfChatCount(int seatNo) {
        return wolfChatCounts.getOrDefault(seatNo, 0);
    }

    public PlayerSnapshot playerAt(SeatNo seatNo) {
        return players.stream()
                .filter(player -> player.seatNo().equals(seatNo))
                .findFirst()
                .orElseThrow(() -> GameException.Factory.invalidTarget("座位 " + seatNo + " 不存在"));
    }

    public PlayerSnapshot playerById(PlayerId playerId) {
        return players.stream()
                .filter(player -> player.playerId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> GameException.Factory.invalidTarget("玩家 " + playerId + " 不存在"));
    }

    public List<PlayerSnapshot> alivePlayers() {
        return players.stream().filter(PlayerSnapshot::alive).toList();
    }

    public long aliveWolfCount() {
        return players.stream().filter(player -> player.alive() && player.isWolf()).count();
    }

    public long aliveVillageCount() {
        return players.stream().filter(player -> player.alive() && !player.isWolf()).count();
    }

    /**
     * 女巫本夜是否已作出决定（用药或跳过）。
     *
     * <p>V6 用它判定"重复提交"，必须同时覆盖"用药"与"主动跳过/超时跳过"：否则跳过之后还能再用药，
     * INV-7 就会被绕过。
     */
    public boolean witchActed() {
        return witchSaved != null || witchPoisoned != null || witchPassed;
    }

    /** 预言家本夜是否已作出决定（查验或放弃）。 */
    public boolean seerActed() {
        return inspectedTarget != null;
    }

    /** 是否仍有存活狼人尚未提名（{@code NIGHT_WOLF} 的退出条件）。 */
    public boolean allAliveWolvesNominated() {
        List<Integer> aliveWolfSeats = players.stream()
                .filter(player -> player.alive() && player.isWolf())
                .map(player -> player.seatNo().value())
                .toList();
        return aliveWolfSeats.stream()
                .allMatch(seat -> wolfNominations.stream()
                        .anyMatch(nomination -> nomination.wolfSeatNo() == seat));
    }

    /** 是否全部存活玩家都已投票（{@code DAY_VOTE} 的退出条件）。 */
    public boolean allAlivePlayersVoted() {
        return players.stream()
                .filter(PlayerSnapshot::alive)
                .allMatch(player -> votes.containsKey(player.seatNo().value()));
    }

    /** 已公布的死亡，按座位号升序；视图只下发这些（不含死因）。 */
    public List<DeathRecord> announcedDeaths() {
        return deaths.stream()
                .filter(DeathRecord::announced)
                .sorted(Comparator.comparing(DeathRecord::seatNo))
                .toList();
    }

    /** 本轮死亡事实，含尚未公布的（供遗言资格与胜负复盘使用）。 */
    public List<DeathRecord> deathsInRound(int targetRound) {
        return deaths.stream()
                .filter(death -> death.round() == targetRound)
                .sorted(Comparator.comparing(DeathRecord::seatNo))
                .toList();
    }
}
