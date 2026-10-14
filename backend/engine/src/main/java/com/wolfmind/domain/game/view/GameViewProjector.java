package com.wolfmind.domain.game.view;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.DeathRecord;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Vote;
import com.wolfmind.domain.game.model.ViewerContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读视图投影：把聚合快照裁剪为"读取者有权看到的那一部分"（api-spec.md §5.2）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>不下发聚合内部中间状态</b>：{@code wolfNominations}、未公布的死亡、{@code deathQueue}
 *       一律不出现在视图里；视图只给"已经公开的事实"。</li>
 *   <li><b>{@code role} 按 §5.2 脱敏</b>：自己的角色可见；狼人可见队友；赛后全体可见；
 *       其余为 {@code null}。</li>
 *   <li><b>观战者的 {@code me} 为 {@code null}</b>：观战者没有座位，因此没有技能与身份面板。</li>
 * </ol>
 *
 * <p>投影是纯函数，不产生事件、不修改状态；读模型不进入领域事件流（ddd-design.md §6.2）。
 */
public final class GameViewProjector {

    private GameViewProjector() {}

    /**
     * 投影。
     *
     * @param state  聚合只读快照（含完整角色信息）
     * @param viewer 读取者身份
     */
    public static GameViewSnapshot project(GameSnapshot state, ViewerContext viewer) {
        boolean godView = viewer.revealed();

        GameViewSnapshot.Participant me = null;
        if (!viewer.spectator()) {
            for (PlayerId candidate : viewer.participantPlayerIds()) {
                PlayerSnapshot own = findById(state, candidate);
                if (own != null) {
                    // 本人视角一定保留自己的完整信息（含技能可用性）
                    me = toParticipant(own, own.role());
                    break;
                }
            }
        }

        List<GameViewSnapshot.Participant> players = new ArrayList<>();
        for (PlayerSnapshot player : state.players()) {
            boolean alive = player.alive() || (!viewer.revealed() && state.deaths().stream()
                    .anyMatch(death -> death.playerId().equals(player.playerId()) && !death.announced()));
            players.add(toParticipant(player, visibleRole(player, viewer, godView), alive));
        }

        List<GameViewSnapshot.VoteView> votes = new ArrayList<>();
        for (Map.Entry<Integer, Vote> entry : state.votes().entrySet()) {
            Vote vote = entry.getValue();
            votes.add(new GameViewSnapshot.VoteView(
                    entry.getKey(), vote.targetSeat(), vote.abstain()));
        }
        votes.sort(java.util.Comparator.comparingInt(GameViewSnapshot.VoteView::voterSeat));

        List<GameViewSnapshot.DeathView> deaths = new ArrayList<>();
        for (DeathRecord death : state.announcedDeaths()) {
            // 只给已公布的死亡，且不给死因：死因是隐藏信息（event-design.md §4）
            deaths.add(new GameViewSnapshot.DeathView(death.seatNo().value(), death.round()));
        }

        GameViewSnapshot.CurrentTurn currentTurn = state.currentTurn() == null
                ? null
                : new GameViewSnapshot.CurrentTurn(
                        state.currentTurn().playerId(),
                        state.currentTurn().seatNo().value(),
                        state.currentTurn().phase(),
                        state.currentTurn().turnIndex(),
                        state.currentTurn().deadline());

        return new GameViewSnapshot(
                state.gameId().value(),
                state.version(),
                state.status(),
                state.phase(),
                state.round(),
                state.turnIndex(),
                state.turnOrder(),
                currentTurn,
                rulesOf(state.rules()),
                me,
                players,
                votes,
                deaths,
                viewer.spectator(),
                state.winner(),
                state.finishReason(),
                // "我能做什么"必须在同一次投影里算出来：它依赖 me 的角色与技能状态，
                // 换一个地方算就会与 me 的可见性规则渐行渐远。
                ActionPermissions.of(state, me, viewer.spectator()));
    }

    /**
     * 某位玩家的角色是否对读取者可见（api-spec.md §5.2 的 role 字段规则）。
     *
     * <p>规则顺序：赛后 → 全部可见；本人 → 可见；狼人看队友 → 可见；
     * 其余 → {@code null}。
     */
    private static com.wolfmind.domain.game.model.Role visibleRole(
            PlayerSnapshot player, ViewerContext viewer, boolean godView) {
        if (godView) {
            return player.role();
        }
        if (viewer.participantPlayerIds().contains(player.playerId())) {
            return player.role();
        }
        // 狼人可见队友：读取者必须本身是狼人阵营，否则狼队身份会被村民读到
        boolean viewerIsWolf = viewer.participantPlayerIds().stream()
                .anyMatch(viewer.wolfPlayerIds()::contains);
        if (viewerIsWolf && player.isWolf()) {
            return player.role();
        }
        return null;
    }

    private static GameViewSnapshot.Participant toParticipant(PlayerSnapshot player, com.wolfmind.domain.game.model.Role role) {
        return toParticipant(player, role, player.alive());
    }

    private static GameViewSnapshot.Participant toParticipant(
            PlayerSnapshot player, com.wolfmind.domain.game.model.Role role, boolean alive) {
        return new GameViewSnapshot.Participant(
                player.playerId(),
                player.seatNo().value(),
                alive,
                player.ready(),
                player.controller().type(),
                player.controller().refId(),
                role,
                role == null ? null : role.camp(),
                role == null ? null : player.skillState());
    }

    private static PlayerSnapshot findById(GameSnapshot state, PlayerId playerId) {
        return state.players().stream()
                .filter(player -> player.playerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    /** 规则参数按 api-spec.md §5.2 下发；这是"配置类"信息，不含任何隐藏状态。 */
    private static Map<String, Object> rulesOf(GameRules rules) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("speechMaxLength", rules.speechMaxLength());
        map.put("turnDeadlineMs", rules.turnDeadlineMs());
        map.put("agentTurnDeadlineMs", rules.agentTurnDeadlineMs());
        map.put("voteDeadlineMs", rules.voteDeadlineMs());
        map.put("firstNightLastWords", rules.firstNightLastWords());
        map.put("laterNightLastWords", rules.laterNightLastWords());
        map.put("exileLastWords", rules.exileLastWords());
        map.put("dayShotLastWords", rules.dayShotLastWords());
        map.put("stalledRoundsLimit", rules.stalledRoundsLimit());
        map.put("witchSelfSave", rules.witchSelfSave());
        map.put("witchSelfSaveFirstNightOnly", rules.witchSelfSaveFirstNightOnly());
        map.put("witchSaveTargetMustBeKilled", rules.witchSaveTargetMustBeKilled());
        map.put("allowWolfEmptyKill", rules.allowWolfEmptyKill());
        map.put("wolfKillTieBreak", rules.wolfKillTieBreak().name());
        map.put("voteTieRule", rules.voteTieRule().name());
        map.put("hunterCanShootWhenPoisoned", rules.hunterCanShootWhenPoisoned());
        map.put("winCondition", rules.winCondition().name());
        map.put("abstainAllowed", rules.abstainAllowed());
        return Map.copyOf(map);
    }

    /** 便于测试与诊断：某座位上是否"对读取者可见角色"。 */
    public static boolean roleVisibleTo(
            GameSnapshot state, SeatNo seatNo, ViewerContext viewer) {
        boolean godView = viewer.revealed();
        PlayerSnapshot player = state.playerAt(seatNo);
        return visibleRole(player, viewer, godView) != null;
    }

    /** 便于测试：胜利方阵营（终局后才有值）。 */
    public static Camp winnerOf(GameSnapshot state) {
        return state.winner();
    }
}
