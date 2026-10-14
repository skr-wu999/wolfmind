package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成 {@link AgentView} 的测试构造器。
 *
 * <p>为什么需要它：权限矩阵与各道门的判定只依赖 {@code AgentView} 的字符串字段，不依赖真实对局。
 * 用合成视图可以把"G5 阶段门"、"G6 回合门"、"G9 队友约束"、"G10 资源门"、"G11 预算"
 * 一格一格地测，而不必把领域驱动到猎人开枪这类罕见状态；真实对局只用来验证写路径与可见性。
 */
final class AgentViews {

    private final String gameId = "g_view_1";
    private String status = "RUNNING";
    private String phase = "DAY_DISCUSS";
    private String role = "VILLAGER";
    private int mySeat = 1;
    private boolean alive = true;
    private int round = 1;
    private int version = 20;
    private Integer currentTurnSeat;
    private boolean antidote;
    private boolean poison;
    private boolean shot;
    private final List<Integer> deadSeats = new ArrayList<>();
    private final List<Integer> teammates = new ArrayList<>();
    private final List<AgentView.PrivateFact> facts = new ArrayList<>();
    private Map<String, Object> rules = new LinkedHashMap<>(Map.of(
            "speechMaxLength", 500, "witchSelfSave", false, "abstainAllowed", true));

    static AgentViews of(String role, String phase, int mySeat) {
        AgentViews views = new AgentViews();
        views.role = role;
        views.phase = phase;
        views.mySeat = mySeat;
        return views;
    }

    AgentViews status(String value) {
        this.status = value;
        return this;
    }

    AgentViews round(int value) {
        this.round = value;
        return this;
    }

    AgentViews version(int value) {
        this.version = value;
        return this;
    }

    AgentViews dead(int... seats) {
        for (int seat : seats) {
            deadSeats.add(seat);
        }
        return this;
    }

    AgentViews teammates(int... seats) {
        for (int seat : seats) {
            teammates.add(seat);
        }
        return this;
    }

    AgentViews currentTurn(Integer seat) {
        this.currentTurnSeat = seat;
        return this;
    }

    AgentViews skills(boolean antidoteAvailable, boolean poisonAvailable, boolean shotAvailable) {
        this.antidote = antidoteAvailable;
        this.poison = poisonAvailable;
        this.shot = shotAvailable;
        return this;
    }

    AgentViews facts(AgentView.PrivateFact... facts) {
        this.facts.addAll(List.of(facts));
        return this;
    }

    AgentViews rules(Map<String, Object> rules) {
        this.rules = new LinkedHashMap<>(rules);
        return this;
    }

    AgentView build() {
        List<AgentView.SeatView> others = new ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            if (seat == mySeat) {
                continue;
            }
            String seatRole = teammates.contains(seat) ? "WEREWOLF" : null;
            others.add(new AgentView.SeatView(
                    "p_" + seat, seat, !deadSeats.contains(seat), seatRole,
                    seatRole == null ? null : "WOLF", "AI"));
        }
        AgentView.CurrentTurn turn = currentTurnSeat == null ? null : new AgentView.CurrentTurn(
                "p_" + currentTurnSeat, currentTurnSeat, phase, 0, 0L);
        return new AgentView(
                gameId,
                version,
                status,
                phase,
                round,
                0,
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9),
                turn,
                Map.copyOf(rules),
                new AgentView.Self("p_" + mySeat, mySeat, role, campOf(role), alive, "AI",
                        new AgentView.SkillView(antidote, poison, shot)),
                List.copyOf(others),
                List.of(),
                List.of(),
                List.of(),
                List.copyOf(facts),
                null);
    }

    private static String campOf(String role) {
        return "WEREWOLF".equals(role) ? "WOLF" : "VILLAGE";
    }
}
