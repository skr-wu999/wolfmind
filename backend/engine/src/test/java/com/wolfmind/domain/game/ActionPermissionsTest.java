package com.wolfmind.domain.game;

import com.wolfmind.domain.game.model.ActionType;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.DeathRecord;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkillState;
import com.wolfmind.domain.game.model.Turn;
import com.wolfmind.domain.game.model.Vote;
import com.wolfmind.domain.game.view.ActionPermissions;
import com.wolfmind.domain.game.view.GameViewSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code permissions[]} 服务端投影（api-spec.md 5.2）。
 *
 * <p>它驱动前端**整个行动 UI**（选目标、弃票、放弃、药量、二次确认），并且 api-spec 明确
 * 规定前端"不得自行推导"，所以这份计算是唯一真相，必须有测试锁住。
 *
 * <p>测试同时守一条安全性质：**观战者与匿名读取者没有任何权限**，
 * 且非本人回合不暴露任何动作——否则前端会显示本不该存在的按钮。
 */
class ActionPermissionsTest {

    private static final String GAME_ID = "g_perm_0001";

    @Test
    @DisplayName("观战者没有任何可执行动作（api-spec 10：观战者永远不能提交行动）")
    void spectatorHasNoPermissions() {
        GameSnapshot state = snapshot(GamePhase.DAY_VOTE, 1, Map.of(), null, null);
        GameViewSnapshot.Participant me = me(1, Role.VILLAGER, true);

        assertTrue(ActionPermissions.of(state, me, true).isEmpty());
        assertTrue(ActionPermissions.of(state, null, false).isEmpty(), "匿名读取者没有座位");
    }

    @Test
    @DisplayName("终局与中止后没有任何可执行动作")
    void finishedGameHasNoPermissions() {
        GameSnapshot state = snapshot(GamePhase.DAY_VOTE, 1, Map.of(), null, null,
                GameStatus.FINISHED);
        assertTrue(ActionPermissions.of(state, me(1, Role.VILLAGER, true), false).isEmpty(),
                "对局结束后不得再给出可执行动作");
    }

    @Test
    @DisplayName("白天讨论：只在本人回合给出 SPEAK，且不带目标")
    void discussionOffersSpeakOnlyOnOwnTurn() {
        GameSnapshot mine = snapshot(GamePhase.DAY_DISCUSS, 1, Map.of(), new Turn(
                PlayerId.of("p_2"), SeatNo.of(2), GamePhase.DAY_DISCUSS, 1, 0L), null);
        List<GameViewSnapshot.ActionPermission> onMyTurn =
                ActionPermissions.of(mine, me(2, Role.VILLAGER, true), false);
        assertEquals(1, onMyTurn.size());
        assertEquals(ActionType.SPEAK.name(), onMyTurn.get(0).type());
        assertTrue(onMyTurn.get(0).targets().isEmpty(), "发言没有目标");
        assertNull(onMyTurn.get(0).allowEmpty());

        GameSnapshot other = snapshot(GamePhase.DAY_DISCUSS, 1, Map.of(), new Turn(
                PlayerId.of("p_3"), SeatNo.of(3), GamePhase.DAY_DISCUSS, 1, 0L), null);
        assertTrue(ActionPermissions.of(other, me(2, Role.VILLAGER, true), false).isEmpty(),
                "不是本人回合时不得给出 SPEAK");
    }

    @Test
    @DisplayName("投票：给出存活他人作为目标、允许弃票；已投票后不再给出")
    void voteOffersAliveTargetsAndAbstain() {
        GameSnapshot state = snapshot(GamePhase.DAY_VOTE, 1, Map.of(), null, null);
        List<GameViewSnapshot.ActionPermission> permissions =
                ActionPermissions.of(state, me(1, Role.VILLAGER, true), false);

        assertEquals(1, permissions.size());
        GameViewSnapshot.ActionPermission vote = permissions.get(0);
        assertEquals(ActionType.VOTE.name(), vote.type());
        assertEquals(Boolean.TRUE, vote.allowEmpty(), "必须允许弃票");
        assertEquals(List.of(2, 3, 4), vote.targets(), "目标是存活他人，升序，不含自己");

        // 已投票：不再给出 VOTE，避免前端重复提交。
        Map<Integer, Vote> votes = new LinkedHashMap<>();
        votes.put(1, Vote.of(1, 2));
        GameSnapshot voted = snapshot(GamePhase.DAY_VOTE, 1, votes, null, null);
        assertTrue(ActionPermissions.of(voted, me(1, Role.VILLAGER, true), false).isEmpty());
    }

    @Test
    @DisplayName("出局后没有行动，但遗言阶段仍可发言（LAST_WORDS 不看存活）")
    void deadPlayerHasNoActionsExceptLastWords() {
        GameSnapshot vote = snapshot(GamePhase.DAY_VOTE, 1, Map.of(), null, null);
        assertTrue(ActionPermissions.of(vote, me(1, Role.VILLAGER, false), false).isEmpty(),
                "出局的村民在投票阶段没有行动");

        GameSnapshot lastWords = snapshot(GamePhase.LAST_WORDS, 1, Map.of(), new Turn(
                PlayerId.of("p_1"), SeatNo.of(1), GamePhase.LAST_WORDS, 0, 0L), null);
        List<GameViewSnapshot.ActionPermission> permissions =
                ActionPermissions.of(lastWords, me(1, Role.VILLAGER, false), false);
        assertEquals(1, permissions.size(), "出局者的遗言必须可用，否则会被卡住");
        assertEquals(ActionType.SPEAK.name(), permissions.get(0).type());
    }

    @Test
    @DisplayName("只有狼人在夜间得到 WOLF_KILL，村民与预言家得不到")
    void onlyWerewolfGetsWolfKill() {
        GameSnapshot state = snapshot(GamePhase.NIGHT_WOLF, 1, Map.of(), null, null);

        List<GameViewSnapshot.ActionPermission> wolf =
                ActionPermissions.of(state, me(1, Role.WEREWOLF, true), false);
        assertEquals(2, wolf.size());
        assertEquals(ActionType.WOLF_KILL.name(), wolf.get(0).type());
        assertEquals(List.of(2, 3, 4), wolf.get(0).targets());
        assertEquals(ActionType.WOLF_CHAT.name(), wolf.get(1).type());
        assertEquals(List.of(), wolf.get(1).targets());
        assertEquals(20, wolf.get(1).remaining(), "remaining 是本夜剩余夜聊条数");

        assertTrue(ActionPermissions.of(state, me(1, Role.WEREWOLF, false), false).isEmpty(),
                "出局的狼人不能提名也不能夜聊");

        assertTrue(ActionPermissions.of(state, me(1, Role.VILLAGER, true), false).isEmpty(),
                "村民在狼人阶段不得得到任何动作");
    }

    @Test
    @DisplayName("女巫：按剩余药量给出 SAVE/POISON，并且 PASS 始终可用")
    void witchPermissionsFollowRemainingPotions() {
        GameSnapshot state = snapshot(GamePhase.NIGHT_WITCH, 1, Map.of(), null, 3);
        PlayerSnapshot witch = player(1, Role.WITCH, true,
                new SkillState(true, true, false));

        List<GameViewSnapshot.ActionPermission> permissions =
                ActionPermissions.of(state, meOf(witch), false);
        List<String> types = permissions.stream().map(GameViewSnapshot.ActionPermission::type).toList();

        assertTrue(types.contains(ActionType.WITCH_SAVE.name()));
        assertTrue(types.contains(ActionType.WITCH_POISON.name()));
        assertTrue(types.contains(ActionType.WITCH_PASS.name()),
                "本夜不用药必须始终可选，否则会被逼用药");

        GameViewSnapshot.ActionPermission save = permissions.stream()
                .filter(p -> p.type().equals(ActionType.WITCH_SAVE.name())).findFirst().orElseThrow();
        assertEquals(List.of(3), save.targets(), "解药只能指向当夜被刀的目标");
        assertEquals(1, save.remaining(), "剩余药量供前端显示");

        // 两瓶都用完：授药不再出现，只剩"本夜不用药"。
        GameSnapshot used = snapshot(GamePhase.NIGHT_WITCH, 1, Map.of(), null, null);
        GameViewSnapshot.Participant exhausted = meOf(player(
                1, Role.WITCH, true, new SkillState(false, false, false)));
        List<GameViewSnapshot.ActionPermission> none = ActionPermissions.of(used, exhausted, false);
        assertEquals(List.of(ActionType.WITCH_PASS.name()),
                none.stream().map(GameViewSnapshot.ActionPermission::type).toList(),
                "药已用完时不得再给出 SAVE/POISON，但 PASS 仍要保留");
    }

    @Test
    void witchCannotSelectSelfWhenSelfSaveIsForbidden() {
        // 默认规则只允许首夜自救：第二夜被刀时不把女巫本人列为可救助目标
        GameSnapshot state = snapshot(GamePhase.NIGHT_WITCH, 2, Map.of(), null, 1);
        var permissions = ActionPermissions.of(state, me(1, Role.WITCH, true), false);
        var save = permissions.stream().filter(p -> p.type().equals(ActionType.WITCH_SAVE.name()))
                .findFirst().orElseThrow();
        assertTrue(save.targets().isEmpty(), "禁用自救时不把女巫本人列为可救助目标");
        assertEquals(1, save.remaining(), "目标不可选不消耗库存");
        assertTrue(permissions.stream().anyMatch(p -> p.type().equals(ActionType.WITCH_PASS.name())));
    }

    @Test
    void witchCanSelectSelfOnFirstNight() {
        GameSnapshot state = snapshot(GamePhase.NIGHT_WITCH, 1, Map.of(), null, 1);
        var save = ActionPermissions.of(state, me(1, Role.WITCH, true), false).stream()
                .filter(p -> p.type().equals(ActionType.WITCH_SAVE.name()))
                .findFirst().orElseThrow();
        assertEquals(List.of(1), save.targets(), "首夜被刀的女巫可以选择救自己");
    }

    @Test
    @DisplayName("猎人：只有待开枪的猎人本人得到 HUNTER_SHOOT，且允许放弃")
    void hunterShootOnlyForPendingHunter() {
        GameSnapshot pending = snapshot(GamePhase.HUNTER_SHOOT, 1, Map.of(), null, null, 2);
        List<GameViewSnapshot.ActionPermission> mine =
                ActionPermissions.of(pending, me(2, Role.HUNTER, false), false);
        assertEquals(1, mine.size());
        assertEquals(ActionType.HUNTER_SHOOT.name(), mine.get(0).type());
        assertEquals(Boolean.TRUE, mine.get(0).allowEmpty(), "必须允许放弃开枪");

        // 待开枪的不是我：不得给出动作，否则前端会替别人显示按钮。
        assertTrue(ActionPermissions.of(pending, me(3, Role.VILLAGER, true), false).isEmpty());
    }

    @Test
    @DisplayName("任何权限都不能指向不存在的座位，且目标必须去重升序")
    void targetsAreAliveAndDistinct() {
        GameSnapshot state = snapshot(GamePhase.DAY_VOTE, 1, Map.of(), null, null);
        for (GameViewSnapshot.ActionPermission permission :
                ActionPermissions.of(state, me(1, Role.VILLAGER, true), false)) {
            List<Integer> targets = permission.targets();
            assertEquals(targets.stream().distinct().count(), targets.size(), "目标不得重复");
            List<Integer> sorted = new ArrayList<>(targets);
            sorted.sort(Integer::compareTo);
            assertEquals(sorted, targets, "目标必须升序，前端按顺序渲染");
            assertFalse(targets.contains(1), "目标不得包含自己");
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static GameViewSnapshot.Participant me(int seatNo, Role role, boolean alive) {
        return meOf(player(seatNo, role, alive, new SkillState(true, true, true)));
    }

    private static GameViewSnapshot.Participant meOf(PlayerSnapshot player) {
        return new GameViewSnapshot.Participant(
                player.playerId(),
                player.seatNo().value(),
                player.alive(),
                player.ready(),
                player.controller().type(),
                player.controller().refId(),
                player.role(),
                player.camp(),
                player.skillState());
    }

    private static PlayerSnapshot player(int seatNo, Role role, boolean alive, SkillState skill) {
        Camp camp = role == Role.WEREWOLF ? Camp.WOLF : Camp.VILLAGE;
        // 组件顺序：playerId, seatNo, role, camp, alive, controller, ready, skillState
        return new PlayerSnapshot(
                PlayerId.of("p_" + seatNo),
                SeatNo.of(seatNo),
                role,
                camp,
                alive,
                new PlayerController(ControllerType.HUMAN, "u_" + seatNo),
                true,
                skill);
    }

    private static GameSnapshot snapshot(
            GamePhase phase, int round, Map<Integer, Vote> votes, Turn currentTurn, Integer wolfKill) {
        return snapshot(phase, round, votes, currentTurn, wolfKill, GameStatus.RUNNING, null);
    }

    private static GameSnapshot snapshot(
            GamePhase phase, int round, Map<Integer, Vote> votes, Turn currentTurn, Integer wolfKill,
            GameStatus status) {
        return snapshot(phase, round, votes, currentTurn, wolfKill, status, null);
    }

    private static GameSnapshot snapshot(
            GamePhase phase, int round, Map<Integer, Vote> votes, Turn currentTurn, Integer wolfKill,
            Integer pendingHunter) {
        return snapshot(phase, round, votes, currentTurn, wolfKill, GameStatus.RUNNING, pendingHunter);
    }

    private static GameSnapshot snapshot(
            GamePhase phase, int round, Map<Integer, Vote> votes, Turn currentTurn, Integer wolfKill,
            GameStatus status, Integer pendingHunter) {
        List<PlayerSnapshot> players = new ArrayList<>();
        // 座位 1 是"本人"之外的通用局面：1 村民、2 村民、3 预言家、4 狼人。
        // 角色本身不影响 ActionPermissions（它只看 me 的角色），因此这里只需覆盖几种典型。
        players.add(player(1, Role.VILLAGER, true, new SkillState(true, true, true)));
        players.add(player(2, Role.VILLAGER, true, new SkillState(true, true, true)));
        players.add(player(3, Role.SEER, true, new SkillState(true, true, true)));
        players.add(player(4, Role.WEREWOLF, true, new SkillState(true, true, true)));

        return new GameSnapshot(
                GameId.of(GAME_ID),
                11,
                status,
                phase,
                round,
                RoleComposition.STANDARD_NINE,
                GameRules.DEFAULT,
                "",
                players,
                List.of(1, 2, 3, 4),
                0,
                null,
                currentTurn,
                wolfKill,
                List.of(),
                null,
                null,
                false,
                null,
                null,
                votes,
                List.<DeathRecord>of(),
                null,
                null,
                List.of(),
                0,
                pendingHunter,
                null,
                null,
                null);
    }
}
