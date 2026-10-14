package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentViewAdapter}：把领域投影翻译成 Agent 形状，且**私有事实按角色隔离**。
 *
 * <p>这是 AGENTS.md §11 Phase 3 验收项"秘密角色保持隔离"的端到端证据：
 * 预言家能看到自己的查验结果、女巫能看到当夜刀口、狼人能看到队友，
 * 而村民一条都拿不到（event-design.md §4 硬规则 1：AI 的可见性与它扮演的玩家完全一致）。
 */
class AgentViewAdapterTest {

    private GatewayHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GatewayHarness();
    }

    @Test
    @DisplayName("预言家：SELF 视图含自己的 SEER_INSPECT，且不含 NIGHT_KILL")
    void seerSeesOwnInspectionOnly() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int seer = harness.seatOfRole(Role.SEER);
        AgentView view = harness.view(seer);

        assertEquals("SEER", view.me().role(), "自己的角色必须可见");
        AgentView.PrivateFact inspect = view.privateEvents().stream()
                .filter(fact -> fact.is(AgentView.PrivateFact.FactKind.SEER_INSPECT))
                .findFirst()
                .orElseThrow(() -> new AssertionError("预言家必须看到自己的查验结果"));
        assertTrue(List.of("WOLF", "VILLAGE", "VILLAGER").contains(inspect.value()),
                "查验结果取值来自事件的 result：" + inspect.value());
        assertTrue(inspect.targetSeat() >= 1);

        assertTrue(view.privateEvents().stream()
                        .noneMatch(fact -> fact.is(AgentView.PrivateFact.FactKind.NIGHT_KILL)),
                "预言家不得知道狼刀刀口");
    }

    @Test
    @DisplayName("女巫：含 NIGHT_KILL（规则要求的定向披露），且不含 SEER_INSPECT")
    void witchSeesNightKillOnly() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int witch = harness.seatOfRole(Role.WITCH);
        AgentView view = harness.view(witch);

        AgentView.PrivateFact kill = view.privateEvents().stream()
                .filter(fact -> fact.is(AgentView.PrivateFact.FactKind.NIGHT_KILL))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "女巫必须拿到当夜刀口，否则解药永远用不出去（rules.witchSaveTargetMustBeKilled）"));
        assertEquals("KILLED", kill.value());
        assertEquals(harness.state().round(), kill.round(), "只披露当前轮");

        int killedSeat = harness.events().stream()
                .filter(event -> "WOLF_KILL_SELECTED".equals(event.type()))
                .filter(event -> event.round() == harness.state().round())
                .map(event -> event.payload().get("targetSeatNo"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).intValue())
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(killedSeat, kill.targetSeat(), "披露的必须正是狼刀定案的目标");

        assertTrue(view.privateEvents().stream()
                        .noneMatch(fact -> fact.is(AgentView.PrivateFact.FactKind.SEER_INSPECT)),
                "女巫不得看到预言家的查验结果");
    }

    @Test
    @DisplayName("村民：SEER_INSPECT 与 NIGHT_KILL 都拿不到")
    void villagerSeesNoSecrets() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int villager = harness.seatOfRole(Role.VILLAGER);
        AgentView view = harness.view(villager);

        assertTrue(view.privateEvents().stream().noneMatch(fact ->
                        fact.is(AgentView.PrivateFact.FactKind.SEER_INSPECT)
                                || fact.is(AgentView.PrivateFact.FactKind.NIGHT_KILL)
                                || fact.is(AgentView.PrivateFact.FactKind.WITCH_SAVED)
                                || fact.is(AgentView.PrivateFact.FactKind.WITCH_POISONED)),
                "村民不得拿到任何隐藏信息，实际 " + view.privateEvents());
        assertTrue(view.other().stream().allMatch(seat -> seat.role() == null),
                "对局进行中，村民看不到任何其他座位的角色");
    }

    @Test
    @DisplayName("狼人：能看到队友；村民看不到任何人")
    void wolfSeesTeammates() {
        harness.driveTo(GamePhase.NIGHT_SEER);
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int villager = harness.seatOfRole(Role.VILLAGER);
        List<Integer> wolves = harness.state().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF)
                .map(player -> player.seatNo().value())
                .toList();

        AgentView wolfView = harness.view(wolf);
        List<Integer> teammates = wolfView.privateEvents().stream()
                .filter(fact -> fact.is(AgentView.PrivateFact.FactKind.WOLF_TEAMMATE))
                .map(AgentView.PrivateFact::targetSeat)
                .toList();
        assertEquals(wolves.stream().filter(seat -> seat != wolf).toList(),
                teammates.stream().sorted().toList(),
                "狼人必须看到全部队友且只看到队友");
        assertTrue(wolfView.other().stream()
                        .filter(seat -> wolves.contains(seat.seatNo()))
                        .allMatch(seat -> "WEREWOLF".equals(seat.role())),
                "队友角色在投影里可见");
        assertTrue(wolfView.other().stream()
                        .filter(seat -> !wolves.contains(seat.seatNo()))
                        .allMatch(seat -> seat.role() == null),
                "非狼人角色对狼人不可见（api-spec.md §5.2）");

        assertTrue(harness.view(villager).privateEvents().stream()
                        .noneMatch(fact -> fact.is(AgentView.PrivateFact.FactKind.WOLF_TEAMMATE)),
                "村民不得看到狼队友");
    }

    @Test
    @DisplayName("夜聊：狼人视图带本晚夜聊，村民视图与 PUBLIC 裁剪一条都没有")
    void wolfChatReachesWolvesOnly() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int villager = harness.seatOfRole(Role.VILLAGER);
        assertTrue(harness.submitService.submit(com.wolfmind.application.game.SubmitActionCommand.of(
                GatewayHarness.GAME_ID, harness.playerIdOf(wolf), "WOLF_CHAT",
                java.util.Map.of("content", "今晚刀预言家"), harness.version(), "wolf_chat_1")).accepted());

        int teammate = harness.state().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF && player.seatNo().value() != wolf)
                .findFirst().orElseThrow().seatNo().value();
        AgentView teammateView = harness.view(teammate);
        assertEquals(List.of(new AgentView.WolfChatView(wolf, 1, "今晚刀预言家")), teammateView.wolfChat());
        assertEquals(teammateView.wolfChat(), QueryGameStateTool.reduce(teammateView, "MY_CAMP").wolfChat());
        assertTrue(QueryGameStateTool.reduce(teammateView, "PUBLIC").wolfChat().isEmpty(),
                "PUBLIC 裁剪不得带狼队夜聊");

        AgentView villagerView = harness.view(villager);
        assertTrue(villagerView.wolfChat().isEmpty(), "村民不得看到狼队夜聊");
        assertFalse(harness.views.visibleEventTypes(GatewayHarness.GAME_ID, harness.playerIdOf(villager))
                .contains("WOLF_CHAT_SENT"), "村民的事件读取不得包含 WOLF_CHAT_SENT");
    }

    @Test
    @DisplayName("每个座位都有自己的视图：me 非空、role 非空、座位号正确")
    void everySeatHasItsOwnView() {
        for (int seat = 1; seat <= 9; seat++) {
            AgentView view = harness.view(seat);
            assertNotNull(view, "座位 " + seat + " 必须有视图");
            assertNotNull(view.me(), "座位 " + seat + " 的 me 不能为空");
            assertEquals(seat, view.me().seatNo());
            assertEquals(harness.playerIdOf(seat), view.me().playerId());
            assertNotNull(view.me().role(), "自己的角色永远可见");
            assertNotNull(view.me().camp());
            assertNotNull(view.me().skills());
            assertEquals(GatewayHarness.GAME_ID, view.gameId());
            assertEquals("RUNNING", view.status());
            assertEquals(GamePhase.NIGHT_WOLF.name(), view.phase());
            assertEquals(8, view.other().size(), "others 是除自己以外的八个座位");
        }
    }

    @Test
    @DisplayName("未知座位与未知对局：返回 null 而不是伪造一个视图")
    void unknownSeatOrGameReturnsNull() {
        assertNull(harness.views.viewFor(GatewayHarness.GAME_ID, "p_99"));
        assertNull(harness.views.viewFor("g_不存在", "p_1"));
        assertFalse(harness.views.isSeatInGame(GatewayHarness.GAME_ID, "p_99"));
        assertFalse(harness.views.isSeatInGame("g_不存在", "p_1"));
        assertTrue(harness.views.isSeatInGame(GatewayHarness.GAME_ID, "p_1"));
    }

    @Test
    @DisplayName("公开部分：发言、票、死亡、回合、规则都从投影映射而来")
    void publicFieldsAreMapped() {
        harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.seatOfRole(Role.VILLAGER);

        // 先投一票（弃权），让公开票非空；投票本身不属于本测试的关注点
        int voter = harness.game().seatsAwaitingVote().get(0).value();
        harness.submitService.submit(com.wolfmind.application.game.SubmitActionCommand.of(
                GatewayHarness.GAME_ID, harness.playerIdOf(voter), "VOTE",
                java.util.Map.of("abstain", true), harness.version(), "test_vote_1"));

        AgentView view = harness.view(seat);
        assertFalse(view.speeches().isEmpty(), "白天讨论的发言必须进入视图");
        assertTrue(view.speeches().stream().anyMatch(speech -> "驱动用发言".equals(speech.content())));
        assertTrue(view.speeches().stream().allMatch(speech -> speech.seatNo() >= 1 && speech.round() == 1));
        assertFalse(view.votes().isEmpty(), "已提交的公开票必须进入视图");
        assertTrue(view.votes().stream().allMatch(vote -> vote.voterSeat() >= 1));
        assertTrue(view.deaths().stream().allMatch(death -> death.seatNo() >= 1),
                "已公布死亡只带座位与轮次（api-spec.md §5.2）");
        // turnOrder 是"当前轮存活玩家的发言顺序"（domain-model.md §4 的 DAY_DISCUSS 行 +
        // §6.2 的 TurnOrderPolicy），不是全部座位：本轮已有一人死亡，因此断言按语义写，
        // 而不是按固定数字——换角色配置或换死亡场景时它仍然成立。
        int aliveCount = (int) view.other().stream().filter(AgentView.SeatView::alive).count()
                + (view.me() != null && view.me().alive() ? 1 : 0);
        assertEquals(aliveCount, view.turnOrder().size(),
                "domain-model.md §4：发言顺序只含存活座位，实际 " + view.turnOrder());
        assertEquals(view.turnOrder().size(), new java.util.HashSet<>(view.turnOrder()).size(),
                "同一个座位不能在一个轮次里出现两次");
        assertTrue(view.turnOrder().stream().allMatch(position -> position >= 1 && position <= 9),
                "发言顺序是座位号列表，实际 " + view.turnOrder());
        assertTrue(view.rules().containsKey("speechMaxLength"), "规则参数随视图下发");
        assertNull(view.winner(), "对局未结束没有胜者");
    }

    @Test
    @DisplayName("出局方式只来自公开事件：首日放逐标 EXILE，夜里死亡标 NIGHT 且不带死因")
    void deathWaysComeFromPublicEvents() {
        harness.driveTo(GamePhase.DAY_VOTE);
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        while (harness.state().phase() == GamePhase.DAY_VOTE) {
            int voter = harness.game().seatsAwaitingVote().get(0).value();
            java.util.Map<String, Object> params = voter == wolf
                    ? java.util.Map.of("abstain", true)
                    : java.util.Map.of("targetSeatNo", wolf);
            assertTrue(harness.submitService.submit(com.wolfmind.application.game.SubmitActionCommand.of(
                    GatewayHarness.GAME_ID, harness.playerIdOf(voter), "VOTE",
                    params, harness.version(), "exile_vote_" + voter)).accepted());
        }
        harness.driveTo(GamePhase.NIGHT_WOLF);
        assertEquals(2, harness.state().round());

        AgentView view = harness.view(harness.seatOfRole(Role.VILLAGER));
        java.util.Map<Integer, String> ways = new java.util.TreeMap<>();
        view.deaths().forEach(death -> ways.put(death.seatNo(), death.way()));
        assertEquals(AgentView.DeathView.EXILE, ways.get(wolf), "被放逐的狼人：" + view.deaths());
        assertEquals(2, ways.size(), "首夜刀口 + 首日放逐：" + view.deaths());
        assertTrue(ways.entrySet().stream()
                        .filter(entry -> entry.getKey() != wolf)
                        .allMatch(entry -> AgentView.DeathView.NIGHT.equals(entry.getValue())),
                "夜里死亡只标 NIGHT，不区分狼刀与毒药：" + view.deaths());
    }

    @Test
    @DisplayName("事件窗口从最新往前取：长局也不会因为截断而丢掉最近的私有事实")
    void eventWindowTakesTheNewestEvents() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int seer = harness.seatOfRole(Role.SEER);
        AgentView view = harness.view(seer);

        int newestInspect = view.privateEvents().stream()
                .filter(fact -> fact.is(AgentView.PrivateFact.FactKind.SEER_INSPECT))
                .mapToInt(AgentView.PrivateFact::round)
                .max()
                .orElseThrow();
        assertEquals(harness.state().round(), newestInspect,
                "窗口覆盖到当前轮，最近的事实不会丢");
        assertTrue(harness.views.visibleEventTypes(GatewayHarness.GAME_ID, harness.playerIdOf(seer))
                        .contains("SEER_INSPECTED"),
                "预言家的事件读取里应包含自己的查验事件");
    }
}
