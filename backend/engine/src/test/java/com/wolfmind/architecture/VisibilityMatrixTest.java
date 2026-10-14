package com.wolfmind.architecture;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.ViewerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可见性矩阵逐格校验（docs/architecture/event-design.md §4）。
 *
 * <p>§4 的矩阵是信息不对称的唯一判定依据：
 * <pre>
 * 读取者                                  PUBLIC  WOLF            PRIVATE   JUDGE
 * 参与对局的玩家                          可见    仅狼人阵营       仅本人    不可见
 * 观战者（对局进行中，未参与本局）        可见    不可见          不可见    不可见
 * 赛后回放（任意登录用户）                可见    可见            可见      可见
 * AI Agent                                可见    仅当是狼人       仅当是本人 不可见
 * </pre>
 * AI Agent 与它扮演的玩家完全一致（§4 硬规则 1），因此"参与者"一行同时覆盖 AI 与真人。
 *
 * <p>匿名读取者不在 §4 矩阵的四类之列：它既非参与者、非观战者，也未赛后公开。按
 * {@link ViewerContext#anonymous()} 的契约只能看到 {@code PUBLIC}；接口层本身也应拒绝匿名读取
 * {@code GET /games/{id}}（api-spec.md §10 权限矩阵："GET /games/{id} 匿名 = 否"）。
 */
class VisibilityMatrixTest {

    /** 座位 6 的玩家：狼人。 */
    private static final PlayerId WOLF_ONE = EventFixtures.player(6);
    /** 座位 7 的玩家：狼人。 */
    private static final PlayerId WOLF_TWO = EventFixtures.player(7);
    /** 座位 1 的玩家：村民阵营。 */
    private static final PlayerId VILLAGER = EventFixtures.player(1);
    /** 座位 2 的玩家：本测试用它代表"另一名非狼人参与者"。 */
    private static final PlayerId OTHER = EventFixtures.player(2);
    /** 他人（非读取者本人）的座位号，用于断言 PRIVATE 的隔离。 */
    private static final int OTHER_SEAT = 2;
    /** 读取者（村民参与者）本人的座位号。 */
    private static final int MY_SEAT = 1;

    private static final Set<PlayerId> WOLF_SEATS = Set.of(WOLF_ONE, WOLF_TWO);

    /** PUBLIC 代表事件：PHASE_CHANGED。 */
    private static DomainEvent publicEvent() {
        return EventFixtures.phaseChanged();
    }

    /** WOLF 代表事件：WOLF_KILL_NOMINATED。 */
    private static DomainEvent wolfEvent() {
        return EventFixtures.wolfKillNominated();
    }

    /** PRIVATE 代表事件：ROLE_ASSIGNED，目标是指定座位的玩家。 */
    private static DomainEvent privateEventFor(int seatNo) {
        return EventFixtures.roleAssignedTo(EventFixtures.player(seatNo), EventFixtures.seat(seatNo));
    }

    /** JUDGE 代表事件：PLAYER_KILLED。 */
    private static DomainEvent judgeEvent() {
        return EventFixtures.playerKilled();
    }

    /** 参与对局的村民：控制座位 1，本局狼人座位是 6/7。 */
    private static ViewerContext villagerParticipant() {
        return ViewerContext.participant("u_1", Set.of(VILLAGER), WOLF_SEATS, false);
    }

    /** 参与对局的狼人：控制座位 6，因此可见 WOLF 事件。 */
    private static ViewerContext wolfParticipant() {
        return ViewerContext.participant("u_6", Set.of(WOLF_ONE), WOLF_SEATS, false);
    }

    /** 观战者（对局进行中，未参与本局）：公开记录。 */
    private static ViewerContext spectator() {
        return ViewerContext.spectator("u_spectator");
    }

    /** 赛后回放：任意登录用户，全部可见。 */
    private static ViewerContext afterGame() {
        return ViewerContext.participant("u_1", Set.of(VILLAGER), WOLF_SEATS, true);
    }

    private static void assertRow(String viewerName, ViewerContext viewer, boolean publicExpected,
            boolean wolfExpected, boolean privateExpected, boolean judgeExpected) {
        assertTrue(viewer.canSee(publicEvent()) == publicExpected,
                "event-design.md §4 矩阵：读取者=" + viewerName + " 对 PUBLIC 的可见性应为 " + publicExpected);
        assertTrue(viewer.canSee(wolfEvent()) == wolfExpected,
                "event-design.md §4 矩阵：读取者=" + viewerName + " 对 WOLF 的可见性应为 " + wolfExpected
                        + "（WOLF：仅狼人阵营可见）");
        assertTrue(viewer.canSee(privateEventFor(OTHER_SEAT)) == privateExpected,
                "event-design.md §4 矩阵：读取者=" + viewerName + " 对 PRIVATE（目标为他人）的可见性应为 "
                        + privateExpected + "（PRIVATE：仅 payload.playerId 指定的玩家可见）");
        assertTrue(viewer.canSee(judgeEvent()) == judgeExpected,
                "event-design.md §4 矩阵：读取者=" + viewerName + " 对 JUDGE 的可见性应为 " + judgeExpected);
    }

    @Test
    @DisplayName("矩阵第 1 行：参与对局的玩家（村民阵营）")
    void participantWhoIsNotWolf() {
        assertRow("参与者-村民", villagerParticipant(), true, false, false, false);
    }

    @Test
    @DisplayName("矩阵第 1 行：参与对局的玩家（狼人阵营）")
    void participantWhoIsWolf() {
        assertRow("参与者-狼人", wolfParticipant(), true, true, false, false);
    }

    @Test
    @DisplayName("矩阵第 2 行：观战者（对局进行中，未参与本局）")
    void spectatorSeesPublicOnly() {
        assertRow("观战者", spectator(), true, false, false, false);
    }

    @Test
    @DisplayName("矩阵第 3 行：赛后回放（任意登录用户）")
    void afterGameEverythingIsVisible() {
        assertRow("赛后参与者", afterGame(), true, true, true, true);
    }

    @Test
    @DisplayName("§4 硬规则 1：AI Agent 与它扮演的玩家可见性完全一致，没有上帝视角")
    void aiAgentHasNoGodView() {
        // AI 扮演座位 1（村民阵营）：拿不到狼人事件
        ViewerContext aiVillager = ViewerContext.participant("agent_1", Set.of(VILLAGER), WOLF_SEATS, false);
        assertFalse(aiVillager.canSee(wolfEvent()),
                "event-design.md §4 硬规则 1：AI 的可见性与它扮演的玩家完全一致，"
                        + "村民阵营的 AI 不得看到 WOLF_KILL_NOMINATED");
        // AI 扮演座位 6（狼人）：可以拿到狼人事件，但仍然拿不到 JUDGE
        ViewerContext aiWolf = ViewerContext.participant("agent_6", Set.of(WOLF_ONE), WOLF_SEATS, false);
        assertTrue(aiWolf.canSee(wolfEvent()),
                "event-design.md §4 矩阵：狼人阵营的 AI 可见 WOLF 事件");
        assertFalse(aiWolf.canSee(judgeEvent()),
                "event-design.md §4 矩阵：狼人阵营的 AI 对 JUDGE 不可见");
    }

    @Test
    @DisplayName("PRIVATE 只给本人：他人角色、他人查验结果、他人用药都不可见")
    void privateVisibilityIsPerPlayer() {
        ViewerContext villager = villagerParticipant();
        assertTrue(villager.canSee(privateEventFor(MY_SEAT)),
                "event-design.md §4：PRIVATE 事件对其目标玩家本人可见（ROLE_ASSIGNED 的 targetPlayerId 指向本人）");
        assertFalse(villager.canSee(privateEventFor(OTHER_SEAT)),
                "event-design.md §4：PRIVATE 事件对同一局的其他参与者不可见");
        assertFalse(villager.canSee(EventFixtures.seerInspected()),
                "event-design.md §4：SEER_INSPECTED 为 PRIVATE（预言家），村民不可见");
        assertFalse(villager.canSee(EventFixtures.seerSkipped()),
                "event-design.md §4：SEER_SKIPPED 为 PRIVATE（预言家），村民不可见");
        assertFalse(villager.canSee(EventFixtures.witchPassed()),
                "event-design.md §4：WITCH_PASSED 为 PRIVATE（女巫），村民不可见");

        ViewerContext seer = ViewerContext.participant("u_2", Set.of(OTHER), WOLF_SEATS, false);
        assertTrue(seer.canSee(EventFixtures.seerInspected()),
                "event-design.md §4：SEER_INSPECTED 对预言家本人可见");
        assertFalse(seer.canSee(EventFixtures.witchSaved()),
                "event-design.md §4：WITCH_SAVED 对女巫之外的人不可见");
    }

    @Test
    @DisplayName("WOLF 只给狼队：村民与匿名读取者拿不到")
    void wolfVisibilityIsFactionOnly() {
        assertFalse(villagerParticipant().canSee(EventFixtures.wolfKillNominated()),
                "event-design.md §4：WOLF 事件仅狼人阵营可见");
        assertFalse(villagerParticipant().canSee(EventFixtures.wolfKillSelected()),
                "event-design.md §4：WOLF_KILL_SELECTED 仅狼人阵营可见");
        assertFalse(ViewerContext.anonymous().canSee(wolfEvent()),
                "event-design.md §4：匿名读取者不在矩阵的授权行内，只能看到 PUBLIC");
        assertTrue(wolfParticipant().canSee(EventFixtures.wolfKillSelected()),
                "event-design.md §4：狼人阵营可见 WOLF_KILL_SELECTED（含空刀）");
    }

    @Test
    @DisplayName("JUDGE 对进行中参与者和观战者不可见、赛后可见")
    void judgeVisibilityDiffersDuringAndAfterGame() {
        assertFalse(villagerParticipant().canSee(judgeEvent()),
                "event-design.md §4：JUDGE（PLAYER_KILLED）对参与对局的玩家不可见——死因与死亡时刻是隐藏信息");
        assertFalse(wolfParticipant().canSee(judgeEvent()),
                "event-design.md §4：JUDGE 对狼人参与者同样不可见（狼人只知道刀口，不知道结算细节）");
        assertFalse(spectator().canSee(judgeEvent()), "进行中观战只允许 PUBLIC");
        assertTrue(afterGame().canSee(judgeEvent()),
                "event-design.md §4：赛后回放（任意登录用户）全部可见");
        assertFalse(spectator().canSee(EventFixtures.gameRecovered()), "进行中观战不披露 JUDGE");
        assertFalse(villagerParticipant().canSee(EventFixtures.gameRecovered()),
                "event-design.md §4：GAME_RECOVERED 对参与者不可见");
    }

    @Test
    @DisplayName("匿名读取者（不在 §4 矩阵四类之内）只能看到 PUBLIC")
    void anonymousViewerSeesPublicOnly() {
        ViewerContext anonymous = ViewerContext.anonymous();
        assertTrue(anonymous.canSee(publicEvent()),
                "event-design.md §4：PUBLIC 为所有订阅者可见；匿名读取者也只能看到 PUBLIC");
        assertFalse(anonymous.canSee(wolfEvent()),
                "event-design.md §4 矩阵：匿名读取者不满足 WOLF 的授权条件（仅狼人阵营）");
        assertFalse(anonymous.canSee(privateEventFor(MY_SEAT)),
                "event-design.md §4 矩阵：匿名读取者不满足 PRIVATE 的授权条件（仅本人）");
        assertFalse(anonymous.canSee(judgeEvent()),
                "event-design.md §4 矩阵：匿名读取者不满足 JUDGE 的授权条件（系统、观战者与赛后回放）");
        assertFalse(anonymous.isParticipant(),
                "ViewerContext.anonymous() 的契约：匿名读取者不是参与者");
    }

    @Test
    @DisplayName("revealed=true（赛后）时全部可见，与是否狼人无关")
    void revealedFlagOpensEverything() {
        ViewerContext revealedVillager = ViewerContext.participant("u_1", Set.of(VILLAGER), Set.of(), true);
        assertTrue(revealedVillager.canSee(judgeEvent()),
                "event-design.md §4：赛后回放（任意登录用户）可见 JUDGE；revealed 与阵营无关");
        assertTrue(revealedVillager.canSee(wolfEvent()),
                "event-design.md §4：赛后回放可见 WOLF 事件");
    }
}
