package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.ToolSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验流水线 G1..G11（tool-gateway.md §4、§12）的逐门测试。
 *
 * <p>用合成视图把每一道门单独逼到失败：真实的九人局很难稳定复现"猎人没子弹"、
 * "预言家本夜已查验"这类状态，而门本身只依赖 {@code AgentView} 的字段。
 */
class PermissionCheckerTest {

    private static final ToolGatewayConfig CONFIG = ToolGatewayConfig.DEFAULT;
    private static final long NOW = 1_700_000_000_000L;

    private final PermissionChecker checker = new PermissionChecker(CONFIG, new TurnBudgetLedger());

    // ---------------------------------------------------------------- G1 / G2

    @Test
    @DisplayName("G1：未注册的工具名 → TOOL_UNKNOWN，先于参数校验")
    void g1UnknownTool() {
        PermissionChecker.Outcome outcome = check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"),
                "DeleteGameTool", null, Map.of("scope", "SELF"), view("VILLAGER", "DAY_DISCUSS", 3));

        assertEquals(DenialCodes.TOOL_UNKNOWN, outcome.denial().code());
        assertTrue(outcome.denial().message().contains("DeleteGameTool"));
        assertTrue(DenialCodes.all().contains(outcome.denial().code()), "拒绝码必须在登记表内");
    }

    @Test
    @DisplayName("G2：非法 scope 与超长发言 → TOOL_ARGS_INVALID")
    void g2ArgsAreValidated() {
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "GOD"), view("VILLAGER", "DAY_DISCUSS", 3)).denial().code());

        AgentView tightView = AgentViews.of("VILLAGER", "DAY_DISCUSS", 3)
                .rules(Map.of("speechMaxLength", 5)).build();
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.SPEAK, "SPEAK",
                Map.of("actionType", "SPEAK", "content", "这句话明显超过五个字"), tightView).denial().code());
    }

    @Test
    @DisplayName("顺序固定：G1 先于 G2，G2 先于 G3")
    void gateOrderIsFixed() {
        // 未知工具 + 坏参数 + 伪造身份：只应报 G1 的码
        assertEquals(DenialCodes.TOOL_UNKNOWN, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), "GhostTool", null,
                Map.of("scope", "GOD"), null).denial().code());

        // 坏参数 + 身份无法确认（视图为空）：先报 G2 的参数码，说明 G2 在 G3 之前
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "GOD"), null).denial().code());

        // 参数合法但身份无法确认：才轮到 G3
        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "SELF"), null).denial().code());
    }

    // ---------------------------------------------------------------- G3

    @Test
    @DisplayName("G3：会话与视图身份不一致 → TOOL_IDENTITY_MISMATCH")
    void g3IdentityBinding() {
        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, check(
                session("p_7", 7, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "SELF"), view("VILLAGER", "DAY_DISCUSS", 3)).denial().code(),
                "会话声明的座位与视图不一致");

        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, check(
                session("p_3", 3, "SEER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "SELF"), view("VILLAGER", "DAY_DISCUSS", 3)).denial().code(),
                "会话角色与视图角色不一致（INV-2 角色不可变）");

        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "SELF"), null).denial().code(),
                "座位不属于本局：身份无法确认");
    }

    @Test
    @DisplayName("G3：参数里夹带身份字段 → TOOL_IDENTITY_MISMATCH")
    void g3IdentityFieldsInArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", "SELF");
        args.put("playerId", "p_9");

        PermissionChecker.Outcome outcome = check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                args, view("VILLAGER", "DAY_DISCUSS", 3));

        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, outcome.denial().code());
        assertTrue(outcome.denial().retryable() == false, "身份不匹配不可原样重试");
    }

    @Test
    @DisplayName("G3：notes 里声明第三方身份 → 拒绝；声明本人 → 忽略")
    void g3IdentityClaimsInNotes() {
        AgentView view = view("VILLAGER", "DAY_VOTE", 3);

        Map<String, Object> forged = Map.of(
                "actionType", "VOTE", "targetSeatNo", 5, "notes", "playerId=p_9, 我要投 5 号");
        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, check(
                session("p_3", 3, "VILLAGER", "DAY_VOTE"), ToolCatalog.VOTE, "VOTE", forged, view)
                .denial().code());

        Map<String, Object> honest = Map.of(
                "actionType", "VOTE", "targetSeatNo", 5, "notes", "playerId=p_3 投 5 号");
        assertNull(check(session("p_3", 3, "VILLAGER", "DAY_VOTE"), ToolCatalog.VOTE, "VOTE", honest, view)
                .denial(), "声明与本人一致时按忽略处理，不把正常表达判成攻击");

        Map<String, Object> targetMention = Map.of(
                "actionType", "VOTE", "targetId", "p_5", "targetSeatNo", 5, "notes", "目标 playerId=p_5");
        assertNull(check(session("p_3", 3, "VILLAGER", "DAY_VOTE"), ToolCatalog.VOTE, "VOTE",
                targetMention, view).denial(), "提到本回合目标不算伪造身份（targetId 与 targetSeatNo 同源）");
    }

    // ---------------------------------------------------------------- G4

    @Test
    @DisplayName("G4：非 RUNNING 或终态阶段 → GAME_ALREADY_FINISHED")
    void g4GameMustBeRunning() {
        assertEquals(DenialCodes.GAME_ALREADY_FINISHED, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "SELF"),
                AgentViews.of("VILLAGER", "DAY_DISCUSS", 3).status("FINISHED").build()).denial().code());

        assertEquals(DenialCodes.GAME_ALREADY_FINISHED, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "SELF"),
                AgentViews.of("VILLAGER", "GAME_OVER", 3).build()).denial().code());
    }

    // ---------------------------------------------------------------- G5 / G7

    @Test
    @DisplayName("G5：阶段不允许该工具 → ACTION_NOT_ALLOWED，并给出可用替代")
    void g5PhaseGate() {
        PermissionChecker.Outcome outcome = check(
                session("p_3", 3, "WEREWOLF", "DAY_DISCUSS"), ToolCatalog.KILL, "WOLF_KILL",
                Map.of("actionType", "WOLF_KILL", "targetSeatNo", 5), view("WEREWOLF", "DAY_DISCUSS", 3));

        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, outcome.denial().code());
        assertTrue(outcome.denial().message().contains("DAY_DISCUSS"));
        assertTrue(outcome.denial().alternatives().stream()
                        .anyMatch(candidate -> candidate.actionType().equals("SPEAK")),
                "拒绝必须给出「现在还能做什么」（§5）");
    }

    @Test
    @DisplayName("G7：角色不具备该能力 → ACTION_NOT_ALLOWED（村民不能查验/狼刀）")
    void g7RoleGate() {
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, check(
                session("p_3", 3, "VILLAGER", "NIGHT_SEER"), ToolCatalog.SKILL, "SEER_INSPECT",
                Map.of("actionType", "SEER_INSPECT", "skill", "SEER_INSPECT", "targetSeatNo", 5),
                view("VILLAGER", "NIGHT_SEER", 3)).denial().code());

        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, check(
                session("p_3", 3, "VILLAGER", "NIGHT_WOLF"), ToolCatalog.KILL, "WOLF_KILL",
                Map.of("actionType", "WOLF_KILL", "targetSeatNo", 5), view("VILLAGER", "NIGHT_WOLF", 3))
                .denial().code());
    }

    @Test
    @DisplayName("兜底 SKIP：夜里也能提交（Runtime 固定用 SpeakTool 承载它）")
    void fallbackSkipWorksInNightPhases() {
        PermissionChecker.Outcome outcome = check(
                session("p_3", 3, "WEREWOLF", "NIGHT_WOLF"), ToolCatalog.SPEAK, "SKIP",
                Map.of("actionType", "SKIP", "skip", true, "reason", "VOLUNTARY"),
                view("WEREWOLF", "NIGHT_WOLF", 3));

        assertNull(outcome.denial(), "兜底动作必须能通过，否则对局会卡住（agent-runtime.md §10）");
    }

    // ---------------------------------------------------------------- G6

    @Test
    @DisplayName("G6：需要回合窗口的阶段，不是本人回合 → TURN_NOT_YOURS")
    void g6TurnWindow() {
        AgentView notMyTurn = AgentViews.of("VILLAGER", "DAY_DISCUSS", 3)
                .currentTurn(5).build();
        assertEquals(DenialCodes.TURN_NOT_YOURS, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.SPEAK, "SPEAK",
                Map.of("actionType", "SPEAK", "content", "你好"), notMyTurn).denial().code());

        AgentView myTurn = AgentViews.of("VILLAGER", "DAY_DISCUSS", 3).currentTurn(3).build();
        assertNull(check(session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.SPEAK, "SPEAK",
                Map.of("actionType", "SPEAK", "content", "你好"), myTurn).denial());

        AgentView noTurn = AgentViews.of("VILLAGER", "DAY_DISCUSS", 3).build();
        assertEquals(DenialCodes.TURN_NOT_YOURS, check(
                session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.SPEAK, "SPEAK",
                Map.of("actionType", "SPEAK", "content", "你好"), noTurn).denial().code(),
                "尚未发放回合时不能发言");

        // 只读工具不受回合窗口限制：不产生事件，等待时看一眼不应非法
        assertNull(check(session("p_3", 3, "VILLAGER", "DAY_DISCUSS"), ToolCatalog.QUERY_GAME_STATE, null,
                Map.of("scope", "SELF"), notMyTurn).denial());
    }

    // ---------------------------------------------------------------- G8

    @Test
    @DisplayName("G8：预言家本夜已查验 → ACTION_NOT_ALLOWED；上一夜的查验不影响本夜")
    void g8SeerOncePerNight() {
        AgentView alreadyInspected = AgentViews.of("SEER", "NIGHT_SEER", 2)
                .round(1)
                .facts(AgentView.PrivateFact.of(
                        AgentView.PrivateFact.FactKind.SEER_INSPECT, 5, "WOLF", 1))
                .build();
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, check(
                session("p_2", 2, "SEER", "NIGHT_SEER"), ToolCatalog.SKILL, "SEER_INSPECT",
                Map.of("actionType", "SEER_INSPECT", "skill", "SEER_INSPECT", "targetSeatNo", 6),
                alreadyInspected).denial().code());

        AgentView previousRound = AgentViews.of("SEER", "NIGHT_SEER", 2)
                .round(2)
                .facts(AgentView.PrivateFact.of(
                        AgentView.PrivateFact.FactKind.SEER_INSPECT, 5, "WOLF", 1))
                .build();
        assertNull(check(session("p_2", 2, "SEER", "NIGHT_SEER"), ToolCatalog.SKILL, "SEER_INSPECT",
                Map.of("actionType", "SEER_INSPECT", "skill", "SEER_INSPECT", "targetSeatNo", 6),
                previousRound).denial(), "每夜 1 次，不是每局 1 次");
    }

    @Test
    @DisplayName("G8：女巫同夜双药第二次被拒（INV-7：同夜不得同用解药与毒药）")
    void g8WitchCannotUseBothPotionsInOneNight() {
        AgentView poisoned = AgentViews.of("WITCH", "NIGHT_WITCH", 4)
                .round(1)
                .skills(true, false, false)
                .facts(AgentView.PrivateFact.of(
                        AgentView.PrivateFact.FactKind.WITCH_POISONED, 6, "POISONED", 1))
                .build();

        PermissionChecker.Outcome save = check(
                session("p_4", 4, "WITCH", "NIGHT_WITCH"), ToolCatalog.SKILL, "WITCH_SAVE",
                Map.of("actionType", "WITCH_SAVE", "skill", "WITCH_SAVE", "targetSeatNo", 5), poisoned);
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, save.denial().code(), "同夜已用毒药就不能再用解药");
        assertTrue(save.denial().message().contains("INV-7"));

        PermissionChecker.Outcome poisonAgain = check(
                session("p_4", 4, "WITCH", "NIGHT_WITCH"), ToolCatalog.SKILL, "WITCH_POISON",
                Map.of("actionType", "WITCH_POISON", "skill", "WITCH_POISON", "targetSeatNo", 7), poisoned);
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, poisonAgain.denial().code(), "毒药每夜只能 1 次");
    }

    @Test
    @DisplayName("G8：解药全局只有 1 次，用过之后下一夜也不能再用")
    void g8AntidoteIsOncePerGame() {
        AgentView usedBefore = AgentViews.of("WITCH", "NIGHT_WITCH", 4)
                .round(2)
                .skills(false, true, false)
                .facts(AgentView.PrivateFact.of(
                        AgentView.PrivateFact.FactKind.WITCH_SAVED, 5, "SAVED", 1))
                .build();

        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, check(
                session("p_4", 4, "WITCH", "NIGHT_WITCH"), ToolCatalog.SKILL, "WITCH_SAVE",
                Map.of("actionType", "WITCH_SAVE", "skill", "WITCH_SAVE", "targetSeatNo", 6), usedBefore)
                .denial().code());
    }

    // ---------------------------------------------------------------- G9

    @Test
    @DisplayName("G9：死亡目标、自身目标、狼刀队友、越界座位全部拒绝")
    void g9TargetGate() {
        AgentView view = AgentViews.of("WEREWOLF", "NIGHT_WOLF", 1).dead(5).teammates(2, 3).build();

        assertEquals(DenialCodes.ACTION_INVALID_TARGET, check(
                session("p_1", 1, "WEREWOLF", "NIGHT_WOLF"), ToolCatalog.KILL, "WOLF_KILL",
                Map.of("actionType", "WOLF_KILL", "targetSeatNo", 5), view).denial().code(),
                "已死亡目标（INV-10）");

        assertEquals(DenialCodes.ACTION_INVALID_TARGET, check(
                session("p_1", 1, "WEREWOLF", "NIGHT_WOLF"), ToolCatalog.KILL, "WOLF_KILL",
                Map.of("actionType", "WOLF_KILL", "targetSeatNo", 1), view).denial().code(),
                "不能指向自己");

        PermissionChecker.Outcome teammate = check(
                session("p_1", 1, "WEREWOLF", "NIGHT_WOLF"), ToolCatalog.KILL, "WOLF_KILL",
                Map.of("actionType", "WOLF_KILL", "targetSeatNo", 2), view);
        assertEquals(DenialCodes.ACTION_INVALID_TARGET, teammate.denial().code(), "狼刀不得指向队友（INV-6）");
        assertTrue(teammate.denial().alternatives().stream()
                        .anyMatch(candidate -> candidate.targets(4)),
                "拒绝要带上仍然合法的候选目标");

        assertEquals(DenialCodes.ACTION_INVALID_TARGET, check(
                session("p_1", 1, "WEREWOLF", "NIGHT_WOLF"), ToolCatalog.KILL, "WOLF_KILL",
                Map.of("actionType", "WOLF_KILL", "targetId", "p_99"), view).denial().code(),
                "目标玩家不在本局");
    }

    @Test
    @DisplayName("G9：targetId 与 targetSeatNo 指向不同座位 → TOOL_ARGS_INVALID（参数注入）")
    void g9ConflictingTargetsAreRejected() {
        AgentView view = AgentViews.of("VILLAGER", "DAY_VOTE", 3).build();
        Map<String, Object> args = Map.of(
                "actionType", "VOTE", "targetId", "p_5", "targetSeatNo", 6);

        assertEquals(DenialCodes.TOOL_ARGS_INVALID, check(
                session("p_3", 3, "VILLAGER", "DAY_VOTE"), ToolCatalog.VOTE, "VOTE", args, view)
                .denial().code());
    }

    @Test
    @DisplayName("G9：合法目标被解析成座位号，交给写路径使用")
    void g9ResolvesLegalTarget() {
        AgentView view = AgentViews.of("VILLAGER", "DAY_VOTE", 3).build();
        PermissionChecker.Outcome bySeat = check(
                session("p_3", 3, "VILLAGER", "DAY_VOTE"), ToolCatalog.VOTE, "VOTE",
                Map.of("actionType", "VOTE", "targetSeatNo", 6), view);
        assertNull(bySeat.denial());
        assertEquals(6, bySeat.resolvedTargetSeat());

        PermissionChecker.Outcome byId = check(
                session("p_3", 3, "VILLAGER", "DAY_VOTE"), ToolCatalog.VOTE, "VOTE",
                Map.of("actionType", "VOTE", "targetId", "p_7"), view);
        assertNull(byId.denial());
        assertEquals(7, byId.resolvedTargetSeat(), "targetId 必须解析成座位号");

        PermissionChecker.Outcome abstain = check(
                session("p_3", 3, "VILLAGER", "DAY_VOTE"), ToolCatalog.VOTE, "VOTE",
                Map.of("actionType", "VOTE", "abstain", true), view);
        assertNull(abstain.denial());
        assertNull(abstain.resolvedTargetSeat(), "弃权没有目标");
    }

    // ---------------------------------------------------------------- G10

    @Test
    @DisplayName("G10：解药/毒药/子弹用尽 → ACTION_NOT_ALLOWED；放弃开枪仍合法")
    void g10ResourceGate() {
        AgentView emptyWitch = AgentViews.of("WITCH", "NIGHT_WITCH", 4).skills(false, false, false).build();
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, check(
                session("p_4", 4, "WITCH", "NIGHT_WITCH"), ToolCatalog.SKILL, "WITCH_SAVE",
                Map.of("actionType", "WITCH_SAVE", "skill", "WITCH_SAVE", "targetSeatNo", 5), emptyWitch)
                .denial().code());

        AgentView emptyHunter = AgentViews.of("HUNTER", "HUNTER_SHOOT", 8)
                .skills(false, false, false).currentTurn(8).build();
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, check(
                session("p_8", 8, "HUNTER", "HUNTER_SHOOT"), ToolCatalog.SKILL, "HUNTER_SHOOT",
                Map.of("actionType", "HUNTER_SHOOT", "skill", "HUNTER_SHOOT", "targetSeatNo", 5), emptyHunter)
                .denial().code());
        assertNull(check(
                session("p_8", 8, "HUNTER", "HUNTER_SHOOT"), ToolCatalog.SKILL, "HUNTER_GIVE_UP",
                Map.of("actionType", "HUNTER_GIVE_UP", "skill", "HUNTER_GIVE_UP"), emptyHunter).denial(),
                "放弃开枪不需要子弹（domain-model.md V8）");
    }

    @Test
    @DisplayName("G9/G10：女巫不可自救（witchSelfSave=false），规则允许时可以救自己")
    void g9WitchSelfSaveFollowsRules() {
        AgentView noSelfSave = AgentViews.of("WITCH", "NIGHT_WITCH", 4)
                .skills(true, true, false).build();
        assertEquals(DenialCodes.ACTION_INVALID_TARGET, check(
                session("p_4", 4, "WITCH", "NIGHT_WITCH"), ToolCatalog.SKILL, "WITCH_SAVE",
                Map.of("actionType", "WITCH_SAVE", "skill", "WITCH_SAVE", "targetSeatNo", 4), noSelfSave)
                .denial().code());
    }

    @Test
    @DisplayName("G9：仅首夜可自救——首夜救自己放行，第二夜救自己被拒")
    void g9WitchSelfSaveFirstNightOnly() {
        Map<String, Object> rules = Map.of("speechMaxLength", 500, "abstainAllowed", true,
                "witchSelfSave", true, "witchSelfSaveFirstNightOnly", true);
        Map<String, Object> args = Map.of(
                "actionType", "WITCH_SAVE", "skill", "WITCH_SAVE", "targetSeatNo", 4);

        AgentView firstNight = AgentViews.of("WITCH", "NIGHT_WITCH", 4)
                .rules(rules).round(1).skills(true, true, false).build();
        assertNull(check(session("p_4", 4, "WITCH", "NIGHT_WITCH"), ToolCatalog.SKILL, "WITCH_SAVE",
                args, firstNight).denial(), "首夜允许女巫救自己");

        AgentView secondNight = AgentViews.of("WITCH", "NIGHT_WITCH", 4)
                .rules(rules).round(2).skills(true, true, false).build();
        assertEquals(DenialCodes.ACTION_INVALID_TARGET, check(
                session("p_4", 4, "WITCH", "NIGHT_WITCH"), ToolCatalog.SKILL, "WITCH_SAVE",
                args, secondNight).denial().code(), "第二夜起不可自救");
    }

    // ---------------------------------------------------------------- G11

    @Test
    @DisplayName("G11：超过每回合调用上限 → BUDGET_EXCEEDED，且上限内不受影响")
    void g11ToolCallBudget() {
        PermissionChecker budgeted = new PermissionChecker(CONFIG, new TurnBudgetLedger());
        ToolSession session = session("p_3", 3, "VILLAGER", "DAY_VOTE");
        AgentView view = view("VILLAGER", "DAY_VOTE", 3);
        Map<String, Object> args = Map.of("actionType", "VOTE", "abstain", true);

        for (int call = 1; call <= CONFIG.maxToolCallsPerTurn(); call++) {
            assertNull(budgeted.check(session, ToolCatalog.VOTE, "VOTE", args, view, NOW).denial(),
                    "第 " + call + " 次调用应在上限内");
        }
        PermissionChecker.Outcome denied =
                budgeted.check(session, ToolCatalog.VOTE, "VOTE", args, view, NOW);
        assertNotNull(denied.denial());
        assertEquals(DenialCodes.BUDGET_EXCEEDED, denied.denial().code());
        assertTrue(denied.denial().retryable() == false, "预算耗尽不可原样重试");
    }

    @Test
    @DisplayName("G11：被更前面的门拒绝的调用不消耗预算（改对参数仍然可以提交）")
    void g11RejectedCallsDoNotConsumeBudget() {
        PermissionChecker budgeted = new PermissionChecker(CONFIG, new TurnBudgetLedger());
        ToolSession session = session("p_3", 3, "VILLAGER", "DAY_VOTE");
        AgentView view = view("VILLAGER", "DAY_VOTE", 3);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(DenialCodes.ACTION_INVALID_TARGET, budgeted.check(
                    session, ToolCatalog.VOTE, "VOTE",
                    Map.of("actionType", "VOTE", "targetSeatNo", 3), view, NOW).denial().code());
        }
        assertNull(budgeted.check(session, ToolCatalog.VOTE, "VOTE",
                Map.of("actionType", "VOTE", "abstain", true), view, NOW).denial(),
                "反复提交非法目标不应把预算烧光");
    }

    @Test
    @DisplayName("G11：会话余额为 0 或已过截止时刻 → BUDGET_EXCEEDED")
    void g11DeadlineAndSessionBudget() {
        AgentView view = view("VILLAGER", "DAY_VOTE", 3);
        Map<String, Object> args = Map.of("actionType", "VOTE", "abstain", true);

        ToolSession exhausted = new ToolSession(
                "g_view_1", "p_3", 3, "VILLAGER", "DAY_VOTE", view.version(), NOW + 10_000L, 0);
        assertEquals(DenialCodes.BUDGET_EXCEEDED, checker.check(
                exhausted, ToolCatalog.VOTE, "VOTE", args, view, NOW).denial().code());

        ToolSession pastDeadline = new ToolSession(
                "g_view_1", "p_3", 3, "VILLAGER", "DAY_VOTE", view.version(), NOW - 1, 6);
        PermissionChecker.Outcome late = checker.check(
                pastDeadline, ToolCatalog.VOTE, "VOTE", args, view, NOW);
        assertEquals(DenialCodes.BUDGET_EXCEEDED, late.denial().code());
        assertTrue(late.denial().message().contains("截止"));
    }

    // ---------------------------------------------------------------- 工具方法

    private PermissionChecker.Outcome check(
            ToolSession session, String tool, String action, Map<String, Object> args, AgentView view) {
        return checker.check(session, tool, action, args, view, NOW);
    }

    private static AgentView view(String role, String phase, int seat) {
        return AgentViews.of(role, phase, seat).build();
    }

    private static ToolSession session(String playerId, int seat, String role, String phase) {
        return new ToolSession("g_view_1", playerId, seat, role, phase, 20, NOW + 10_000L, 6);
    }
}
