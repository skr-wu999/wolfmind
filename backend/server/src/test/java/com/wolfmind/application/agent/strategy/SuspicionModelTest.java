package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SuspicionModel}：村民阵营视角的排序、真值与分数区间（strategy-layer.md §4.2、§11）。 */
class SuspicionModelTest {

    private final SuspicionModel model = new SuspicionModel();
    private final FactExtractor extractor = new FactExtractor();

    /** 预言家视角：已查验 5 号为狼、6 号为好人；2 号自称预言家并替 5 号说话。 */
    private static AgentView seerView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .nineSeats(4)
                .privateFact(AgentViews.inspect(5, "WOLF", 1))
                .privateFact(AgentViews.inspect(6, "VILLAGE", 1))
                .speech(2, 2, StrategyCodes.DAY_DISCUSS, 0, "我是预言家，查验了5号是好人")
                .votes(AgentViews.vote(2, 6, false))
                .build();
    }

    @Test
    @DisplayName("预言家的查验真值决定排序：查杀排第一，查好被压到最低")
    void seerTruthDrivesRanking() {
        AgentView view = seerView();
        FactStore facts = extractor.extractStore(view);

        List<SuspicionAssessment> ranked = model.rankAliveOthers(view, facts);

        assertEquals(5, ranked.get(0).seatNo(), "被查验为狼的座位必须排第一");
        assertTrue(ranked.get(0).tags().contains("CHECKED_WOLF"));
        SuspicionAssessment good = model.assess(view, facts, 6);
        assertTrue(good.score() <= 0.1, "被查验为好人的座位必须被压到最低，实际 " + good.score());
        assertTrue(good.tags().contains("CHECKED_GOOD"));
        assertTrue(model.assess(view, facts, 2).score() > good.score(),
                "公开推断出来的嫌疑仍然要高于已被真值排除的座位");
    }

    @Test
    @DisplayName("总分落在 0..1，且同一输入两次调用完全一致")
    void scoresAreDeterministicAndInRange() {
        AgentView view = seerView();
        FactStore facts = extractor.extractStore(view);

        List<SuspicionAssessment> first = model.rankAliveOthers(view, facts);
        List<SuspicionAssessment> second = model.rankAliveOthers(view, facts);

        assertEquals(first, second);
        for (SuspicionAssessment assessment : first) {
            assertTrue(assessment.score() >= 0.0 && assessment.score() <= 1.0);
        }
    }

    @Test
    @DisplayName("投票一致性：投给我已确认的好人时嫌疑显著上升")
    void hostileVoteRaisesSuspicion() {
        AgentView view = seerView();
        FactStore facts = extractor.extractStore(view);

        // 2 号投了被查验为好人的 6 号
        double consistency = model.assess(view, facts, 2).factor(SuspicionAssessment.VOTE_CONSISTENCY);
        double neutral = model.assess(view, facts, 8).factor(SuspicionAssessment.VOTE_CONSISTENCY);

        assertTrue(consistency > neutral, "投好人应比没有投票记录更可疑");
        assertTrue(model.assess(view, facts, 2).tags().contains("HOSTILE_VOTE"));
    }

    @Test
    @DisplayName("宣称矛盾：两个预言家宣称时两个宣称者都拿到较高矛盾分")
    void duplicateClaimsRaiseContradiction() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我是预言家，查验了5号是狼")
                .speech(3, 1, StrategyCodes.DAY_DISCUSS, 1, "我才是预言家，2号是狼")
                .build();
        FactStore facts = extractor.extractStore(view);

        assertEquals(0.5, model.assess(view, facts, 2).factor(SuspicionAssessment.CONTRADICTION), 1e-9);
        assertEquals(0.5, model.assess(view, facts, 3).factor(SuspicionAssessment.CONTRADICTION), 1e-9);
        assertTrue(model.assess(view, facts, 2).tags().contains("CLAIM_CONFLICT"));
    }

    @Test
    @DisplayName("发言规避：从不发言的座位比给出结论的座位更可疑")
    void silenceRaisesEvasion() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我觉得7号有问题，他的表态很飘")
                .speech(2, 2, StrategyCodes.DAY_DISCUSS, 0, "我坚持7号有问题，请大家看票型")
                .build();
        FactStore facts = extractor.extractStore(view);

        assertTrue(model.evasion(view, facts, 3) > model.evasion(view, facts, 2));
        assertTrue(model.assess(view, facts, 3).tags().contains("EVASIVE"));
    }

    @Test
    @DisplayName("出局关联：与已知狼人同票的座位拿到关系链分")
    void exileLinkUsesKnownWolves() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .nineSeats(4)
                .privateFact(AgentViews.inspect(5, "WOLF", 1))
                .votes(AgentViews.vote(5, 8, false), AgentViews.vote(6, 8, false))
                .build();
        FactStore facts = extractor.extractStore(view);

        assertTrue(model.assess(view, facts, 5).factor(SuspicionAssessment.LINK) >= 1.0,
                "自己就是已知狼人时关系链拉满");
        assertTrue(model.assess(view, facts, 6).factor(SuspicionAssessment.LINK) > 0.0,
                "与已知狼人同票的座位有关系链分");
        assertEquals(0.0, model.assess(view, facts, 8).factor(SuspicionAssessment.LINK), 1e-9);
    }

    @Test
    @DisplayName("非预言家视角：多重预言家宣称只影响宣称者，不影响沉默玩家")
    void duplicateClaimsOnlyAffectClaimants() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(1)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我是预言家")
                .speech(3, 1, StrategyCodes.DAY_DISCUSS, 1, "我是预言家")
                .build();
        FactStore facts = extractor.extractStore(view);

        assertEquals(0.0, model.assess(view, facts, 4).factor(SuspicionAssessment.CONTRADICTION), 1e-9);
        assertTrue(model.assess(view, facts, 2).factor(SuspicionAssessment.CONTRADICTION)
                > model.assess(view, facts, 4).factor(SuspicionAssessment.CONTRADICTION));
    }

    @Test
    @DisplayName("没有查验真值的座位，总分就是五个因子的加权和")
    void scoreIsWeightedSumWithoutTruth() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(3)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .others(2, 3, 4, 5, 6, 7, 8)
                .seat(AgentViews.seat(9, false))
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "4号很可疑")
                .speech(3, 1, StrategyCodes.DAY_DISCUSS, 0, "我同意2号")
                .votes(AgentViews.vote(2, 4, false), AgentViews.vote(3, 4, false), AgentViews.vote(5, 2, false))
                .deaths(AgentViews.death(9, 2))
                .build();
        FactStore facts = extractor.extractStore(view);

        for (SuspicionAssessment assessment : model.rankAliveOthers(view, facts)) {
            double expected = 0.30 * assessment.factor(SuspicionAssessment.CONTRADICTION)
                    + 0.25 * assessment.factor(SuspicionAssessment.VOTE_CONSISTENCY)
                    + 0.20 * assessment.factor(SuspicionAssessment.EVASION)
                    + 0.15 * assessment.factor(SuspicionAssessment.LINK)
                    + 0.10 * assessment.factor(SuspicionAssessment.TIMELINE);
            assertEquals(0.0, assessment.factor(SuspicionAssessment.TRUTH_ADJUSTMENT), 1e-9);
            assertEquals(expected, assessment.score(), 1e-9, "§4.2 的权重必须逐项生效");
        }
    }
}
