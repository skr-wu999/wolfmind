package com.wolfmind.application.agent.fake;

import com.wolfmind.application.agent.model.AgentView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构造 {@link AgentView} 的测试夹具：让单测专注在行为而不是字段填充。 */
public final class FakeAgentView {

    private FakeAgentView() {}

    /** 默认的 9 人局视图：本人座位 1，其他座位 2..9 存活。 */
    public static AgentView of(String role) {
        return of(role, 1, List.of(2, 3, 4, 5, 6, 7, 8, 9), "DAY_DISCUSS");
    }

    public static AgentView of(String role, int seatNo, List<Integer> otherSeats, String phase) {
        String camp = "WEREWOLF".equals(role) ? "WOLF" : "VILLAGE";
        List<AgentView.SeatView> others = new ArrayList<>();
        for (int seat : otherSeats) {
            others.add(new AgentView.SeatView("p_" + seat, seat, true, null, null, "BOT"));
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("speechMaxLength", 500);
        rules.put("voteDeadlineMs", 45_000);
        rules.put("turnDeadlineMs", 30_000);
        rules.put("firstNightLastWords", true);
        rules.put("exileLastWords", true);
        rules.put("dayShotLastWords", true);
        rules.put("stalledRoundsLimit", 3);
        rules.put("witchSelfSave", false);
        rules.put("abstainAllowed", true);

        return new AgentView(
                "g_test",
                10,
                "RUNNING",
                phase,
                1,
                0,
                List.of(seatNo),
                currentTurn(seatNo, phase),
                rules,
                new AgentView.Self(
                        "p_" + seatNo, seatNo, role, camp, true, "BOT",
                        new AgentView.SkillView(
                                "WITCH".equals(role), "WITCH".equals(role), "HUNTER".equals(role))),
                others,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    /** 无回合窗口的阶段（DAY_VOTE / NIGHT_WOLF）返回 null。 */
    private static AgentView.CurrentTurn currentTurn(int seatNo, String phase) {
        if ("DAY_VOTE".equals(phase) || "NIGHT_WOLF".equals(phase)) {
            return null;
        }
        return new AgentView.CurrentTurn("p_" + seatNo, seatNo, phase, 0, 1_730_000_300_000L);
    }

    /** 带私有事实的视图：预言家的查验结果。 */
    public static AgentView seerWithInspection(int inspectedSeat, String result) {
        AgentView base = of("SEER");
        return new AgentView(
                base.gameId(), base.version(), base.status(), base.phase(), base.round(),
                base.turnIndex(), base.turnOrder(), base.currentTurn(), base.rules(), base.me(),
                base.other(), base.votes(), base.deaths(), base.speeches(),
                List.of(new AgentView.PrivateFact("INSPECT", inspectedSeat, result, 1)),
                base.winner());
    }

    /** 已死亡的本座位（用于猎人开枪场景）。 */
    public static AgentView deadHunter(int seatNo, List<Integer> aliveSeats) {
        AgentView base = of("HUNTER", seatNo, aliveSeats, "HUNTER_SHOOT");
        return new AgentView(
                base.gameId(), base.version(), base.status(), base.phase(), base.round(),
                base.turnIndex(), base.turnOrder(), base.currentTurn(), base.rules(),
                new AgentView.Self(
                        "p_" + seatNo, seatNo, "HUNTER", "VILLAGE", false, "BOT",
                        new AgentView.SkillView(false, false, true)),
                base.other(), base.votes(), base.deaths(), base.speeches(),
                base.privateEvents(), base.winner());
    }
}
