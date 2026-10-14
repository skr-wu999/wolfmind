package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.TurnBudget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用视图构造器：把 {@code AgentView} 那十来个组件从每个测试里赶出去。
 *
 * <p>默认值刻意选"九人局第一夜、人人存活、无人发言"的最干净局面，测试只声明它关心的字段。
 * 这里出现的所有座位号都与真实九人局一致（1..9），避免测试构造出领域里不存在的形状。
 */
final class AgentViews {

    private AgentViews() {}

    static Builder builder() {
        return new Builder();
    }

    /** 默认规则参数，键名与 {@code GameRules} 字段名一致（与领域默认值同值）。 */
    static Map<String, Object> defaultRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("speechMaxLength", 500);
        rules.put("turnDeadlineMs", 30_000L);
        rules.put("voteDeadlineMs", 45_000L);
        rules.put("abstainAllowed", true);
        rules.put("allowWolfEmptyKill", true);
        rules.put("witchSelfSave", false);
        rules.put("witchSaveTargetMustBeKilled", true);
        return rules;
    }

    /** 默认配置：三档与候选上限可调，其余取 {@code AgentConfig} 的默认值。 */
    static AgentConfig config(AgentConfig.StrategyProfile profile) {
        return config(profile, 0.5, 3, 60);
    }

    static AgentConfig config(
            AgentConfig.StrategyProfile profile, double exposureAversion, int candidateLimit, int summarizeThreshold) {
        return new AgentConfig(
                "cfg_test",
                "test",
                "fake-model",
                0.6,
                0.2,
                profile,
                "中性",
                AgentConfig.WorkflowMode.A,
                summarizeThreshold,
                exposureAversion,
                8,
                candidateLimit,
                "tpl",
                TurnBudget.DEFAULT);
    }

    static AgentView.SeatView seat(int seatNo, boolean alive) {
        return new AgentView.SeatView("p_" + seatNo, seatNo, alive, null, null, "AI");
    }

    static AgentView.SeatView wolfSeat(int seatNo, boolean alive) {
        return new AgentView.SeatView("p_" + seatNo, seatNo, alive, StrategyCodes.WEREWOLF, "WOLF", "AI");
    }

    static AgentView.SeatView revealedSeat(int seatNo, boolean alive, String role, String camp) {
        return new AgentView.SeatView("p_" + seatNo, seatNo, alive, role, camp, "AI");
    }

    static AgentView.SpeechView speech(
            int seatNo, int round, String phase, int turnIndex, String content) {
        return new AgentView.SpeechView(seatNo, round, phase, turnIndex, content);
    }

    static AgentView.VoteView vote(int voterSeat, Integer targetSeatNo, boolean abstain) {
        return new AgentView.VoteView(voterSeat, targetSeatNo, abstain);
    }

    static AgentView.DeathView death(int seatNo, int round) {
        return new AgentView.DeathView(seatNo, round);
    }

    static AgentView.PrivateFact inspect(int seatNo, String verdict, int round) {
        return AgentView.PrivateFact.of(
                AgentView.PrivateFact.FactKind.SEER_INSPECT, seatNo, verdict, round);
    }

    static AgentView.PrivateFact nightKill(int seatNo, int round) {
        return AgentView.PrivateFact.of(
                AgentView.PrivateFact.FactKind.NIGHT_KILL, seatNo, "KILLED", round);
    }

    static AgentView.PrivateFact saved(int seatNo, int round) {
        return AgentView.PrivateFact.of(
                AgentView.PrivateFact.FactKind.WITCH_SAVED, seatNo, "SAVED", round);
    }

    static AgentView.PrivateFact poisoned(int seatNo, int round) {
        return AgentView.PrivateFact.of(
                AgentView.PrivateFact.FactKind.WITCH_POISONED, seatNo, "POISONED", round);
    }

    static AgentView.PrivateFact teammate(int seatNo, int round) {
        return AgentView.PrivateFact.of(
                AgentView.PrivateFact.FactKind.WOLF_TEAMMATE, seatNo, "WEREWOLF", round);
    }

    /**
     * 给一批座位补上"每个发言轮都说过一句话"的公开时间线。
     *
     * <p>真实视图的公开事件是完整的：到了第二轮投票，除了死人，每个座位都至少发过言。
     * 构造只有一个人说话的"残缺时间线"会让"发言规避"因子的分母失真，测出来的排序
     * 不是策略层的问题而是夹具的问题。
     */
    static Builder speakAll(Builder builder, int[] seatNos, int rounds, int mentionSeat) {
        for (int round = 1; round <= rounds; round++) {
            for (int seatNo : seatNos) {
                builder.speech(seatNo, round, StrategyCodes.DAY_DISCUSS, seatNo,
                        "第" + round + "轮我的看法：先关注" + mentionSeat + "号");
            }
        }
        return builder;
    }

    /** 可变构造器：链式声明关心的字段，其余取默认值。 */
    static final class Builder {

        private String gameId = "g_test";
        private int version = 42;
        private String status = "RUNNING";
        private String phase = StrategyCodes.NIGHT_WOLF;
        private int round = 1;
        private int turnIndex = 0;
        private List<Integer> turnOrder = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        private AgentView.CurrentTurn currentTurn;
        private Map<String, Object> rules = defaultRules();
        private AgentView.Self me;
        private final List<AgentView.SeatView> other = new ArrayList<>();
        private final List<AgentView.VoteView> votes = new ArrayList<>();
        private final List<AgentView.DeathView> deaths = new ArrayList<>();
        private final List<AgentView.SpeechView> speeches = new ArrayList<>();
        private final List<AgentView.PrivateFact> privateEvents = new ArrayList<>();
        private final List<AgentView.WolfChatView> wolfChat = new ArrayList<>();
        private String winner;

        Builder gameId(String value) {
            this.gameId = value;
            return this;
        }

        Builder version(int value) {
            this.version = value;
            return this;
        }

        Builder status(String value) {
            this.status = value;
            return this;
        }

        Builder phase(String value) {
            this.phase = value;
            return this;
        }

        Builder round(int value) {
            this.round = value;
            return this;
        }

        Builder turnIndex(int value) {
            this.turnIndex = value;
            return this;
        }

        Builder turnOrder(List<Integer> value) {
            this.turnOrder = value;
            return this;
        }

        Builder currentTurn(String playerId, int seatNo, String phase, int turnIndex, long deadline) {
            this.currentTurn = new AgentView.CurrentTurn(playerId, seatNo, phase, turnIndex, deadline);
            return this;
        }

        Builder rule(String key, Object value) {
            Map<String, Object> copy = new LinkedHashMap<>(rules);
            copy.put(key, value);
            this.rules = copy;
            return this;
        }

        Builder me(int seatNo, String role, String camp, boolean alive) {
            this.me = new AgentView.Self("p_" + seatNo, seatNo, role, camp, alive, "AI",
                    new AgentView.SkillView(true, true, true));
            return this;
        }

        Builder me(int seatNo, String role, String camp, boolean alive,
                   boolean antidote, boolean poison, boolean shot) {
            this.me = new AgentView.Self("p_" + seatNo, seatNo, role, camp, alive, "AI",
                    new AgentView.SkillView(antidote, poison, shot));
            return this;
        }

        /** 其他座位全部存活、角色不可见。 */
        Builder others(int... seatNos) {
            for (int seatNo : seatNos) {
                this.other.add(AgentViews.seat(seatNo, true));
            }
            return this;
        }

        Builder seat(AgentView.SeatView value) {
            this.other.add(value);
            return this;
        }

        /** 九人局默认形状：除自己以外全部存活。 */
        Builder nineSeats(int mySeat) {
            for (int seatNo = 1; seatNo <= 9; seatNo++) {
                if (seatNo != mySeat) {
                    this.other.add(AgentViews.seat(seatNo, true));
                }
            }
            return this;
        }

        Builder speech(int seatNo, int round, String phase, int turnIndex, String content) {
            this.speeches.add(AgentViews.speech(seatNo, round, phase, turnIndex, content));
            return this;
        }

        Builder votes(AgentView.VoteView... values) {
            this.votes.addAll(List.of(values));
            return this;
        }

        Builder deaths(AgentView.DeathView... values) {
            this.deaths.addAll(List.of(values));
            return this;
        }

        Builder privateFact(AgentView.PrivateFact value) {
            this.privateEvents.add(value);
            return this;
        }

        Builder winner(String value) {
            this.winner = value;
            return this;
        }

        Builder wolfChat(int seatNo, String content) {
            this.wolfChat.add(new AgentView.WolfChatView(seatNo, round, content));
            return this;
        }

        AgentView build() {
            return new AgentView(
                    gameId,
                    version,
                    status,
                    phase,
                    round,
                    turnIndex,
                    turnOrder,
                    currentTurn,
                    rules,
                    me,
                    List.copyOf(other),
                    List.copyOf(votes),
                    List.copyOf(deaths),
                    List.copyOf(speeches),
                    List.copyOf(privateEvents),
                    winner,
                    List.copyOf(wolfChat));
        }
    }
}
