package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DecisionPolicy}：三档权重、确定性与平局规则（strategy-layer.md §4.3、§11 第四行）。 */
class DecisionPolicyTest {

    private final FactExtractor extractor = new FactExtractor();
    private final DecisionPolicy policy = new DecisionPolicy(new ThreatModel());

    private static Candidate killCandidate(int seatNo, double target, double infoGain, double pressure, double exposure) {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put(StrategyCodes.FACTOR_TARGET, target);
        breakdown.put(StrategyCodes.FACTOR_INFO_GAIN, infoGain);
        breakdown.put(StrategyCodes.FACTOR_PRESSURE, pressure);
        breakdown.put(StrategyCodes.FACTOR_EXPOSURE, exposure);
        return new Candidate(StrategyCodes.WOLF_KILL, seatNo, "p_" + seatNo,
                "刀 " + seatNo + " 号", 0.0, breakdown, List.of(), List.of());
    }

    private static AgentView wolfView() {
        return AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(2)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .votes(AgentViews.vote(1, 5, false))
                .build();
    }

    @Test
    @DisplayName("三档权重之和为 1，且三档对同一候选给出不同分数")
    void threeProfilesDiffer() {
        assertEquals(1.0, DecisionPolicy.AGGRESSIVE.sum(), 1e-9);
        assertEquals(1.0, DecisionPolicy.BALANCED.sum(), 1e-9);
        assertEquals(1.0, DecisionPolicy.CONSERVATIVE.sum(), 1e-9);

        AgentView view = wolfView();
        FactStore facts = extractor.extractStore(view);
        List<Candidate> candidates = List.of(killCandidate(8, 0.6, 0.2, 0.8, 0.9));

        double aggressive = policy.score(view, StrategyCodes.WEREWOLF,
                AgentViews.config(AgentConfig.StrategyProfile.AGGRESSIVE), candidates, facts).get(0).score();
        double balanced = policy.score(view, StrategyCodes.WEREWOLF,
                AgentViews.config(AgentConfig.StrategyProfile.BALANCED), candidates, facts).get(0).score();
        double conservative = policy.score(view, StrategyCodes.WEREWOLF,
                AgentViews.config(AgentConfig.StrategyProfile.CONSERVATIVE), candidates, facts).get(0).score();

        assertNotEquals(aggressive, balanced, 1e-9);
        assertNotEquals(balanced, conservative, 1e-9);
        assertTrue(aggressive > conservative, "进攻档在低暴露候选上应当比保守档更积极");
    }

    @Test
    @DisplayName("暴露项：狼队受 exposureAversion 缩放，村民阵营不受影响")
    void exposureAversionOnlyAffectsWolfCamp() {
        AgentView wolfView = wolfView();
        FactStore wolfFacts = extractor.extractStore(wolfView);
        List<Candidate> kill = List.of(killCandidate(8, 0.6, 0.2, 0.8, 0.9));

        double brave = policy.score(wolfView, StrategyCodes.WEREWOLF,
                AgentViews.config(AgentConfig.StrategyProfile.BALANCED, 0.0, 3, 60), kill, wolfFacts).get(0).score();
        double careful = policy.score(wolfView, StrategyCodes.WEREWOLF,
                AgentViews.config(AgentConfig.StrategyProfile.BALANCED, 1.0, 3, 60), kill, wolfFacts).get(0).score();

        assertTrue(brave > careful, "暴露规避强度越高，狼刀分数越低");

        AgentView villageView = AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();
        FactStore villageFacts = extractor.extractStore(villageView);
        List<Candidate> vote = List.of(new Candidate(StrategyCodes.VOTE, 8, "p_8", "投 8 号", 0.0,
                Map.of(StrategyCodes.FACTOR_TARGET, 0.6,
                        StrategyCodes.FACTOR_INFO_GAIN, 0.1,
                        StrategyCodes.FACTOR_PRESSURE, 0.9,
                        StrategyCodes.FACTOR_EXPOSURE, 0.9),
                List.of(), List.of()));

        double villageBrave = policy.score(villageView, StrategyCodes.VILLAGER,
                AgentViews.config(AgentConfig.StrategyProfile.BALANCED, 0.0, 3, 60), vote, villageFacts).get(0).score();
        double villageCareful = policy.score(villageView, StrategyCodes.VILLAGER,
                AgentViews.config(AgentConfig.StrategyProfile.BALANCED, 1.0, 3, 60), vote, villageFacts).get(0).score();

        assertEquals(villageBrave, villageCareful, 1e-9);
    }

    @Test
    @DisplayName("确定性：同一输入两次打分完全一致，且与输入顺序无关")
    void scoringIsDeterministic() {
        AgentView view = wolfView();
        FactStore facts = extractor.extractStore(view);
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);
        List<Candidate> candidates = List.of(
                killCandidate(4, 0.4, 0.2, 0.8, 0.5),
                killCandidate(8, 0.6, 0.2, 0.8, 0.5),
                killCandidate(6, 0.5, 0.2, 0.8, 0.5));

        List<Candidate> first = policy.score(view, StrategyCodes.WEREWOLF, config, candidates, facts);
        List<Candidate> reversed = new ArrayList<>(candidates);
        java.util.Collections.reverse(reversed);
        List<Candidate> second = policy.score(view, StrategyCodes.WEREWOLF, config, reversed, facts);

        assertEquals(first, second, "打分结果与候选输入顺序无关");
        assertEquals(List.of(8, 6, 4), first.stream().map(Candidate::targetSeatNo).toList(),
                "分数降序");
    }

    @Test
    @DisplayName("平局：同一局同一轮顺序固定；无目标候选排在等价的有目标候选之后")
    void tiesAreStableWithinGameAndNoTargetGoesLast() {
        AgentView view = wolfView();
        FactStore facts = extractor.extractStore(view);
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);

        List<Candidate> tied = List.of(
                killCandidate(7, 0.5, 0.2, 0.8, 0.5), killCandidate(3, 0.5, 0.2, 0.8, 0.5));
        List<Integer> once = policy.score(view, StrategyCodes.WEREWOLF, config, tied, facts).stream()
                .map(Candidate::targetSeatNo).toList();
        List<Integer> twice = policy.score(view, StrategyCodes.WEREWOLF, config, tied.reversed(), facts).stream()
                .map(Candidate::targetSeatNo).toList();
        assertEquals(once, twice, "同一局同一轮的平局顺序可复现，与输入顺序无关");

        List<Candidate> equalScores = new ArrayList<>(List.of(
                new Candidate(StrategyCodes.WOLF_KILL, 7, "p_7", "刀 7 号", 0.5, Map.of(), List.of(), List.of()),
                new Candidate(StrategyCodes.WOLF_KILL, 3, "p_3", "刀 3 号", 0.5, Map.of(), List.of(), List.of()),
                new Candidate(StrategyCodes.WOLF_KILL, null, null, "空刀", 0.5, Map.of(), List.of(), List.of())));
        equalScores.sort(DecisionPolicy.order(view));
        assertEquals(null, equalScores.get(2).targetSeatNo(), "空刀排在等价的有目标候选之后");
    }

    @Test
    @DisplayName("平局不偏向小座位号：完全对称的候选在不同对局里第一名分散到各个座位")
    void tiesAreNotAnchoredToLowestSeat() {
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);
        List<Candidate> tied = new ArrayList<>();
        for (int seat = 2; seat <= 9; seat++) {
            tied.add(killCandidate(seat, 0.5, 0.2, 0.8, 0.5));
        }
        Map<Integer, Integer> firstPicks = new java.util.TreeMap<>();
        for (int game = 0; game < 400; game++) {
            AgentView view = AgentViews.builder()
                    .gameId("g_tie_" + game)
                    .phase(StrategyCodes.NIGHT_WOLF)
                    .round(1)
                    .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                    .nineSeats(1)
                    .build();
            int first = policy.score(view, StrategyCodes.WEREWOLF, config, tied,
                    extractor.extractStore(view)).get(0).targetSeatNo();
            firstPicks.merge(first, 1, Integer::sum);
        }
        assertEquals(8, firstPicks.size(), "每个座位都应当在某些对局里排第一：" + firstPicks);
        assertTrue(firstPicks.values().stream().allMatch(count -> count < 400 / 8 * 2),
                "没有哪个座位占据超过两倍的平均份额：" + firstPicks);
    }

    @Test
    @DisplayName("一致性因子：延续自己上一轮的目标比改换目标更一致")
    void consistencyFollowsPreviousVote() {
        AgentView view = wolfView();
        FactStore facts = extractor.extractStore(view);
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);
        List<Candidate> candidates = List.of(
                killCandidate(5, 0.5, 0.2, 0.8, 0.5),
                killCandidate(6, 0.5, 0.2, 0.8, 0.5));

        List<Candidate> scored = policy.score(view, StrategyCodes.WEREWOLF, config, candidates, facts);

        Candidate continued = scored.stream().filter(candidate -> candidate.targets(5)).findFirst().orElseThrow();
        Candidate switched = scored.stream().filter(candidate -> candidate.targets(6)).findFirst().orElseThrow();
        assertTrue(continued.breakdown().get(StrategyCodes.FACTOR_CONSISTENCY)
                        > switched.breakdown().get(StrategyCodes.FACTOR_CONSISTENCY),
                "上一轮投过 5 号，继续选 5 号的一致性更高");
        assertEquals(5, scored.get(0).targetSeatNo());
    }

    @Test
    @DisplayName("打分结果保留五个因子，分数等于公式计算结果")
    void breakdownKeepsEveryFactor() {
        AgentView view = wolfView();
        FactStore facts = extractor.extractStore(view);
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);
        Candidate scored = policy.score(view, StrategyCodes.WEREWOLF, config,
                List.of(killCandidate(8, 0.6, 0.2, 0.8, 0.9)), facts).get(0);

        for (String factor : List.of(StrategyCodes.FACTOR_TARGET, StrategyCodes.FACTOR_CONSISTENCY,
                StrategyCodes.FACTOR_EXPOSURE, StrategyCodes.FACTOR_INFO_GAIN, StrategyCodes.FACTOR_PRESSURE)) {
            assertTrue(scored.breakdown().containsKey(factor), "缺少因子 " + factor);
        }
        double expected = DecisionPolicy.BALANCED.target() * scored.breakdown().get(StrategyCodes.FACTOR_TARGET)
                + DecisionPolicy.BALANCED.consistency() * scored.breakdown().get(StrategyCodes.FACTOR_CONSISTENCY)
                - DecisionPolicy.BALANCED.exposure() * scored.breakdown().get(StrategyCodes.FACTOR_EXPOSURE)
                + DecisionPolicy.BALANCED.infoGain() * scored.breakdown().get(StrategyCodes.FACTOR_INFO_GAIN)
                + DecisionPolicy.BALANCED.pressure() * scored.breakdown().get(StrategyCodes.FACTOR_PRESSURE);
        assertEquals(expected, scored.score(), 1e-9);
    }

    @Test
    @DisplayName("空候选集打分结果为空列表")
    void emptyCandidatesScoreToEmpty() {
        AgentView view = wolfView();
        assertTrue(policy.score(view, StrategyCodes.WEREWOLF,
                AgentViews.config(AgentConfig.StrategyProfile.BALANCED), List.of(),
                extractor.extractStore(view)).isEmpty());
    }
}
