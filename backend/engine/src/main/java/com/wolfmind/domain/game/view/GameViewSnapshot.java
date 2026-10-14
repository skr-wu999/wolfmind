package com.wolfmind.domain.game.view;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.SkillState;
import com.wolfmind.domain.game.model.ViewerContext;

import java.util.List;

/**
 * 角色可见的只读投影（domain 侧的完整形状）。
 *
 * <p>它保留 {@code GameView} 的全部字段（见 docs/backend/api-spec.md §5.2），包括 {@code me} 与
 * 每位玩家的 {@code role}；**脱敏已经在本对象里完成**，应用层只是把它映射为传输 DTO。
 *
 * <p>之所以先有这一层：脱敏必须只有一份实现（event-design.md §4 硬规则 2、AP-6）。把它放在应用层
 * 会让 WebSocket 推送、REST 视图、AI 工具各自有机会"再写一遍"，而那是隐藏信息泄漏的典型成因。
 */
public record GameViewSnapshot(
        String gameId,
        int version,
        GameStatus status,
        GamePhase phase,
        int round,
        int turnIndex,
        List<Integer> turnOrder,
        CurrentTurn currentTurn,
        java.util.Map<String, Object> rules,
        Participant me,
        List<Participant> players,
        List<VoteView> votes,
        List<DeathView> deaths,
        boolean spectator,
        Camp winner,
        String finishReason,
        List<ActionPermission> permissions) {

    public GameViewSnapshot {
        turnOrder = List.copyOf(turnOrder);
        players = List.copyOf(players);
        votes = List.copyOf(votes);
        deaths = List.copyOf(deaths);
        rules = java.util.Map.copyOf(rules);
        permissions = List.copyOf(permissions);
    }

    /** 回合窗口；{@code DAY_VOTE}、{@code NIGHT_WOLF} 等无回合阶段为 {@code null}。 */
    public record CurrentTurn(PlayerId playerId, int seatNo, GamePhase phase, int turnIndex, long deadline) {}

    /**
     * 一位玩家在视图中的样子。
     *
     * <p>{@code role} 已按 §5.2 的规则脱敏：自己的角色可见、狼人可见队友、观战者与赛后全体可见，
     * 其余情况为 {@code null}。
     */
    public record Participant(
            PlayerId playerId,
            int seatNo,
            boolean alive,
            boolean ready,
            ControllerType controllerType,
            String controllerRefId,
            Role role,
            Camp camp,
            SkillState skillState) {}

    /** 当前投票轮的公开票；未投票者不出现。 */
    public record VoteView(int voterSeat, Integer targetSeatNo, boolean abstain) {}

    /**
     * 读取者现在可以执行的一个动作（api-spec.md 5.2 的 {@code permissions[]}）。
     *
     * <p>它由服务端计算而不是前端推导：行动合法性属于领域职责，两端各写一遍必然漂移。
     * 前端的行动 UI（选目标、弃票、放弃、药量显示、二次确认）完全由这个列表驱动。
     *
     * @param type       动作类型，取值与提交行动用的 {@code actionType} 白名单一致
     * @param targets    合法目标座位号（升序）；无目标型动作为空列表
     * @param allowEmpty 是否允许"空选择"（弃票 / 放弃开枪）；不适用时为 {@code null}
     * @param remaining  剩余可用次数（药量等）；不适用时为 {@code null}
     */
    public record ActionPermission(
            String type, List<Integer> targets, Boolean allowEmpty, Integer remaining) {

        public ActionPermission {
            targets = List.copyOf(targets);
        }
    }

    /** 已通过 {@code DEATH_ANNOUNCED} 公布的死亡，不含死因。 */
    public record DeathView(int seatNo, int round) {}

    /** 读取者视角是否属于"上帝视角"：观战者或赛后公开。 */
    public boolean godView(ViewerContext viewer) {
        return viewer.spectator() || viewer.revealed();
    }
}
