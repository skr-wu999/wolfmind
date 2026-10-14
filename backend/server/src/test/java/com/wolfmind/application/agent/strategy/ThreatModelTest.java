package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ThreatModel}：狼队视角的排序与分数区间（strategy-layer.md §4.1、§11）。 */
class ThreatModelTest {

    private final ThreatModel model = new ThreatModel();
    private final FactExtractor extractor = new FactExtractor();

    /** 一个狼人视角的第二夜局面：2 号跳预言家并给了结论，3 号几乎不说话。 */
    private static AgentView nightView() {
        return AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(2)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .seat(AgentViews.wolfSeat(6, true))
                .others(2, 3, 4, 5, 7, 8, 9)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我是预言家，查验了5号是狼，今天必须投5号")
                .speech(2, 2, StrategyCodes.DAY_DISCUSS, 0, "我还是坚持我的判断，5号是狼")
                .speech(3, 2, StrategyCodes.DAY_DISCUSS, 1, "过")
                .votes(AgentViews.vote(4, 5, false), AgentViews.vote(7, 5, false), AgentViews.vote(3, 8, false))
                .build();
    }

    @Test
    @DisplayName("神职宣称者与带节奏者排在沉默玩家之前")
    void seerClaimerOutranksSilentSeat() {
        AgentView view = nightView();
        FactStore facts = extractor.extractStore(view);

        ThreatAssessment seerClaimer = model.assess(view, facts, 2);
        ThreatAssessment silent = model.assess(view, facts, 3);

        assertTrue(seerClaimer.score() > silent.score(),
                "预言家宣称者威胁应高于沉默玩家：" + seerClaimer.score() + " vs " + silent.score());
        assertTrue(seerClaimer.tags().contains("SEER_CLAIM"));
    }

    @Test
    @DisplayName("总分等于五个因子的加权和，且落在 0..1")
    void scoreIsWeightedSumInRange() {
        AgentView view = nightView();
        FactStore facts = extractor.extractStore(view);

        for (ThreatAssessment assessment : model.rankAliveOpponents(view, facts)) {
            double expected = 0.35 * assessment.factor(ThreatAssessment.DIVINE_SUSPECT)
                    + 0.20 * assessment.factor(ThreatAssessment.INFORMATION)
                    + 0.20 * assessment.factor(ThreatAssessment.INFLUENCE)
                    + 0.10 * assessment.factor(ThreatAssessment.SURVIVAL)
                    + 0.15 * assessment.factor(ThreatAssessment.KILL_SAFETY);
            assertEquals(expected, assessment.score(), 1e-9, "§4.1 的权重必须逐项生效");
            assertTrue(assessment.score() >= 0.0 && assessment.score() <= 1.0);
        }
    }

    @Test
    @DisplayName("滚雪球的信息量：给出可验证结论的座位信息量因子更高")
    void verifiableConclusionsRaiseInformation() {
        AgentView view = nightView();
        FactStore facts = extractor.extractStore(view);

        double withConclusion = model.information(view, facts, 2);
        double chatty = model.information(view, facts, 3);

        assertTrue(withConclusion > chatty);
    }

    @Test
    @DisplayName("影响力：被他人跟随投票的座位影响力更高")
    void followersRaiseInfluence() {
        AgentView view = nightView();
        FactStore facts = extractor.extractStore(view);

        // 4 号与 7 号都跟着 2 号的票投 5 号，而 3 号无人跟随
        assertTrue(model.influence(view, facts, 2) > model.influence(view, facts, 3));
    }

    @Test
    @DisplayName("击杀风险：公开被认定为好人的目标，刀口更暴露")
    void publicTrustRaisesKillRisk() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(2)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我查验了5号是好人，5号是金水")
                .build();
        FactStore facts = extractor.extractStore(view);

        double trustedRisk = model.killRisk(view, facts, 5);
        double ordinaryRisk = model.killRisk(view, facts, 8);

        assertTrue(trustedRisk > ordinaryRisk, "被公开信任的人死掉更容易暴露狼队");
        assertTrue(facts.publiclyTrusted(5));
    }

    @Test
    @DisplayName("击杀风险：与自身票型一致（我公开投过他）时风险更低")
    void voteAlignmentLowersKillRisk() {
        AgentView aligned = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(2)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .votes(AgentViews.vote(1, 5, false))
                .build();
        AgentView misaligned = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(2)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .votes(AgentViews.vote(1, 8, false))
                .build();

        double alignedRisk = model.killRisk(aligned, extractor.extractStore(aligned), 5);
        double misalignedRisk = model.killRisk(misaligned, extractor.extractStore(misaligned), 5);

        assertTrue(alignedRisk < misalignedRisk);
    }

    @Test
    @DisplayName("排序：两次调用完全一致；完全对称时平局顺序随对局变化，不固定为座位号升序")
    void rankingIsDeterministicAndTieBreakDependsOnGame() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(1)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .build();
        FactStore facts = extractor.extractStore(view);

        List<ThreatAssessment> first = model.rankAliveOpponents(view, facts);
        List<ThreatAssessment> second = model.rankAliveOpponents(view, facts);

        assertEquals(first, second, "同一输入必须产生同一结果");
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9),
                first.stream().map(ThreatAssessment::seatNo).sorted().toList(),
                "候选集合是全部存活非狼座位");

        java.util.Set<Integer> leaders = new java.util.HashSet<>();
        for (int game = 0; game < 50; game++) {
            AgentView other = AgentViews.builder()
                    .gameId("g_threat_" + game)
                    .phase(StrategyCodes.NIGHT_WOLF)
                    .round(1)
                    .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                    .nineSeats(1)
                    .build();
            leaders.add(model.rankAliveOpponents(other, extractor.extractStore(other)).get(0).seatNo());
        }
        assertTrue(leaders.size() > 1, "不同对局里完全对称局面的第一名不应固定为同一个座位：" + leaders);
    }

    @Test
    @DisplayName("狼队友与自己的座位不出现在刀口候选里（INV-6）")
    void teammatesNeverAppearAsTargets() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(1)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .seat(new AgentView.SeatView("p_2", 2, true, StrategyCodes.WEREWOLF, "WOLF", "AI"))
                .seat(new AgentView.SeatView("p_3", 3, true, null, null, "AI"))
                .privateFact(AgentViews.teammate(4, 1))
                .others(5)
                .build();
        FactStore facts = extractor.extractStore(view);

        List<Integer> seats = model.rankAliveOpponents(view, facts).stream()
                .map(ThreatAssessment::seatNo)
                .toList();

        assertTrue(seats.contains(3) && seats.contains(5));
        assertTrue(!seats.contains(1) && !seats.contains(2) && !seats.contains(4),
                "自己、阵营可见的队友与私有队友名单里的座位都必须排除");
    }
}
