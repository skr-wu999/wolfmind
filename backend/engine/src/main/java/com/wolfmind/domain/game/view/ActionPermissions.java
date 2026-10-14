package com.wolfmind.domain.game.view;

import com.wolfmind.domain.game.model.ActionType;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.WolfChatAction;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前读取者"现在能做什么"的服务端投影（api-spec.md 5.2 的 {@code permissions[]}）。
 *
 * <p><b>为什么必须由服务端给，而不是前端按 phase + role 推导</b>：api-spec 5.2 明确写
 * "前端只允许消费这些字段，不得自行推导"。若前端推导，规则就在两端各实现一遍，
 * 必然漂移——而行动合法性属于领域职责（AGENTS.md 5）。
 *
 * <p><b>这个方法不得泄漏隐藏信息。</b>它只回答"读取者自己能做什么"，因此只读取：
 * <ul>
 *   <li>读取者自己的座位与角色（来自 {@code me}，本来就是本人可见）；</li>
 *   <li>公开的存活/出局状态；</li>
 *   <li>狼人视角下自己的队友（狼人本来就互见）。</li>
 * </ul>
 * 它**不**读取任何其他座位的角色来构造目标列表，也不透露"谁还没有行动"之外的私有事实。
 * 目标列表只在观战者（上帝视角）或读取者自己的行动中出现。
 */
public final class ActionPermissions {

    private ActionPermissions() {}

    /**
     * 计算读取者的可用动作。
     *
     * @param state    聚合快照（含公开状态）
     * @param me       读取者自己的视图（匿名/观战者为 {@code null}）
     * @param spectator 读取者是否为观战者
     * @return 可用动作列表；没有可做之事时为空列表（不是 null）
     */
    public static List<GameViewSnapshot.ActionPermission> of(
            GameSnapshot state, GameViewSnapshot.Participant me, boolean spectator) {

        // 观战者永远不能提交行动（api-spec.md 10 尾注），因此没有权限可言。
        if (spectator || me == null || me.role() == null) {
            return List.of();
        }
        // 终局与中止后没有行动。
        if (state.status() != GameStatus.RUNNING) {
            return List.of();
        }
        // 已出局的座位只剩"遗言"这一种可能，其余行动一律不可用。
        boolean alive = me.alive();
        GamePhase phase = state.phase();

        List<GameViewSnapshot.ActionPermission> permissions = new ArrayList<>();
        switch (phase) {
            case DAY_DISCUSS -> {
                if (alive && isMyTurn(state, me)) {
                    permissions.add(permission(ActionType.SPEAK, List.of(), null));
                }
            }
            case LAST_WORDS -> {
                // 遗言对"已出局的本人"开放，所以这里不看 alive。
                if (isMyTurn(state, me)) {
                    permissions.add(permission(ActionType.SPEAK, List.of(), null));
                }
            }
            case DAY_VOTE -> {
                if (alive && !hasVoted(state, me.seatNo())) {
                    permissions.add(permission(ActionType.VOTE, aliveSeatsExcept(state, me.seatNo()), state.rules().abstainAllowed()));
                }
            }
            case NIGHT_WOLF -> {
                if (alive && me.role() == Role.WEREWOLF && state.wolfNominations().stream()
                        .noneMatch(nomination -> nomination.wolfSeatNo() == me.seatNo())) {
                    permissions.add(permission(ActionType.WOLF_KILL, aliveSeatsExcept(state, me.seatNo()), null));
                }
                // 夜聊与提名互不影响：提名之后仍可继续讨论，直到阶段结束或达到条数上限。
                int chatsLeft = WolfChatAction.MAX_PER_NIGHT - state.wolfChatCount(me.seatNo());
                if (alive && me.role() == Role.WEREWOLF && chatsLeft > 0) {
                    permissions.add(new GameViewSnapshot.ActionPermission(
                            ActionType.WOLF_CHAT.name(), List.of(), null, chatsLeft));
                }
            }
            case NIGHT_SEER -> {
                if (alive && me.role() == Role.SEER) {
                    permissions.add(permission(ActionType.SEER_INSPECT, aliveSeatsExcept(state, me.seatNo()), null));
                }
            }
            case NIGHT_WITCH -> addWitchPermissions(state, me, permissions);
            case HUNTER_SHOOT -> {
                if (me.role() == Role.HUNTER && state.pendingHunter() != null
                        && state.pendingHunter() == me.seatNo()) {
                    permissions.add(permission(ActionType.HUNTER_SHOOT, aliveSeatsExcept(state, me.seatNo()), Boolean.TRUE));
                }
            }
            default -> {
                // 自动结算阶段（NIGHT_SETTLE / VOTE_SETTLE / DAY_ANNOUNCE）没有玩家行动。
            }
        }
        return List.copyOf(permissions);
    }

    /**
     * 女巫的三种选择。
     *
     * <p>{@code remaining} 是**剩余药量**：前端据此决定按钮是否可点、确认框里显示什么。
     * 0 表示已用完，但动作仍然列出——因为"不能再救"与"现在不是你的回合"是两件事，
     * 让前端自己判断"0 次所以灰掉"比让服务端藏掉这个动作更可解释。
     */
    private static void addWitchPermissions(
            GameSnapshot state, GameViewSnapshot.Participant me,
            List<GameViewSnapshot.ActionPermission> permissions) {
        if (me.role() != Role.WITCH || !me.alive()) {
            return;
        }
        var skill = me.skillState();
        if (skill == null) {
            return;
        }
        if (skill.antidoteAvailable()) {
            // 解药必须指向"当夜被刀的目标"，服务端已经把这个事实定向披露给女巫。
            List<Integer> saveTargets = state.wolfKillTarget() == null
                    || (state.wolfKillTarget() == me.seatNo() && !state.rules().witchCanSelfSave(state.round()))
                    ? List.of()
                    : List.of(state.wolfKillTarget());
            permissions.add(new GameViewSnapshot.ActionPermission(
                    ActionType.WITCH_SAVE.name(), saveTargets, Boolean.FALSE, 1));
        }
        if (skill.poisonAvailable()) {
            permissions.add(new GameViewSnapshot.ActionPermission(
                    ActionType.WITCH_POISON.name(),
                    aliveSeatsExcept(state, me.seatNo()), Boolean.FALSE, 1));
        }
        // "本夜不用药"始终可用：它是一个合法且常见的选择，藏掉它会逼玩家用掉药。
        permissions.add(new GameViewSnapshot.ActionPermission(
                ActionType.WITCH_PASS.name(), List.of(), Boolean.TRUE, null));
    }

    private static boolean isMyTurn(GameSnapshot state, GameViewSnapshot.Participant me) {
        return state.currentTurn() != null && state.currentTurn().seatNo().value() == me.seatNo();
    }

    private static boolean hasVoted(GameSnapshot state, int seatNo) {
        return state.votes().containsKey(seatNo);
    }

    /**
     * 可行动者的座位号，按升序。
     *
     * <p>只看**公开的存活状态**：目标列表是"现在可以指向谁"，本身不泄漏任何角色信息。
     * 是否排除队友等规则由领域在 {@code handle} 时判断——前端不需要知道原因，
     * 也不允许据此推断身份。
     */
    private static List<Integer> aliveSeatsExcept(GameSnapshot state, int ownSeat) {
        List<Integer> seats = new ArrayList<>();
        for (PlayerSnapshot player : state.players()) {
            if (player.alive() && player.seatNo().value() != ownSeat) {
                seats.add(player.seatNo().value());
            }
        }
        seats.sort(Integer::compareTo);
        return List.copyOf(seats);
    }

    private static GameViewSnapshot.ActionPermission permission(
            ActionType type, List<Integer> targets, Boolean allowEmpty) {
        return new GameViewSnapshot.ActionPermission(type.name(), targets, allowEmpty, null);
    }

    /** 该座位是否由真人控制；用于统计"还需等待多少人"，不参与授权。 */
    static boolean isHuman(PlayerSnapshot player) {
        return player.controller() != null && player.controller().type() == ControllerType.HUMAN;
    }
}
