package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FactExtractor}：固定 {@code AgentView} 输入断言事实集合（strategy-layer.md §11 第一行）。 */
class FactExtractorTest {

    private final FactExtractor extractor = new FactExtractor();

    private static AgentView dayTwoView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .nineSeats(4)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我是预言家，昨晚查验了5号是狼")
                .speech(6, 2, StrategyCodes.DAY_DISCUSS, 0, "我救了5号，好人别投我")
                .speech(3, 2, StrategyCodes.DAY_DISCUSS, 1, "5号是好人")
                .votes(AgentViews.vote(2, 5, false), AgentViews.vote(3, null, true))
                .deaths(AgentViews.death(7, 2))
                .privateFact(AgentViews.inspect(5, "WOLF", 1))
                .build();
    }

    @Test
    @DisplayName("发言事实：座位、轮次、阶段、长度与点名座位都来自固定输入")
    void extractsSpeechFacts() {
        AgentContext.Facts facts = extractor.extract(dayTwoView());

        assertEquals(3, facts.speeches().size());
        AgentContext.SpeechFact first = facts.speeches().get(0);
        assertEquals(2, first.seatNo());
        assertEquals(1, first.round());
        assertEquals(StrategyCodes.DAY_DISCUSS, first.phase());
        assertEquals("我是预言家，昨晚查验了5号是狼".length(), first.length());
        assertEquals(List.of(5), first.mentions());
    }

    @Test
    @DisplayName("点名只认视图里存在的座位，且按升序去重")
    void mentionsOnlyKnownSeats() {
        AgentView view = AgentViews.builder()
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .others(2, 3)
                .speech(1, 1, StrategyCodes.DAY_DISCUSS, 0, "3号、2号、3号都发言了，另外77号不在场上")
                .build();

        AgentContext.Facts facts = extractor.extract(view);

        assertEquals(List.of(2, 3), facts.speeches().get(0).mentions());
    }

    @Test
    @DisplayName("宣称解析：自称预言家、查验结论、用药宣告三类都落到 ClaimFact")
    void extractsClaims() {
        AgentContext.Facts facts = extractor.extract(dayTwoView());

        assertTrue(facts.claims().stream().anyMatch(claim ->
                claim.seatNo() == 2 && StrategyCodes.SEER.equals(claim.claimedRole())
                        && claim.targetSeat() == null),
                "应抽出\"我是预言家\"这类自称");
        assertTrue(facts.claims().stream().anyMatch(claim ->
                claim.seatNo() == 2 && "WOLF".equals(claim.result())
                        && Integer.valueOf(5).equals(claim.targetSeat())),
                "应抽出查验结论：5 号是狼");
        assertTrue(facts.claims().stream().anyMatch(claim ->
                claim.seatNo() == 6 && StrategyCodes.WITCH.equals(claim.claimedRole())
                        && "SAVE".equals(claim.result())
                        && Integer.valueOf(5).equals(claim.targetSeat())),
                "应抽出用药宣告（SKILL_SIGNAL）：我救了 5 号");
        assertTrue(facts.claims().stream().anyMatch(claim ->
                claim.seatNo() == 3 && "GOOD".equals(claim.result())
                        && Integer.valueOf(5).equals(claim.targetSeat())),
                "应抽出直接结论：5 号是好人");
    }

    @Test
    @DisplayName("投票与死亡事实保留原始形状：投票含弃权，死亡不含死因")
    void extractsVotesAndDeaths() {
        AgentContext.Facts facts = extractor.extract(dayTwoView());

        assertEquals(2, facts.votes().size());
        assertEquals(2, facts.votes().get(0).voterSeat());
        assertEquals(Integer.valueOf(5), facts.votes().get(0).targetSeat());
        assertFalse(facts.votes().get(0).abstain());
        assertTrue(facts.votes().get(1).abstain());
        assertNull(facts.votes().get(1).targetSeat());
        assertEquals(2, facts.votes().get(0).round(), "公开票属于当前投票轮");

        assertEquals(1, facts.deaths().size());
        assertEquals(7, facts.deaths().get(0).seatNo());
        assertEquals(2, facts.deaths().get(0).round());
    }

    @Test
    @DisplayName("JUDGE 事实进不来：没有私有事件时私有事实集合为空，死亡事实只有座位与轮次")
    void judgeFactsNeverEnterTheStore() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_ANNOUNCE)
                .round(2)
                .me(4, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(4)
                .deaths(AgentViews.death(7, 1))
                .build();

        AgentContext.Facts facts = extractor.extract(view);

        assertTrue(facts.privateFacts().isEmpty());
        assertEquals(1, facts.deaths().size());
        assertTrue(facts.claims().isEmpty());
        assertTrue(facts.speeches().isEmpty());
    }

    @Test
    @DisplayName("FactStore：查验真值、当夜刀口、队友名单与票型分布都从固定事实派生")
    void factStoreDerivesTruths() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(3)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true)
                .nineSeats(7)
                .privateFact(AgentViews.nightKill(5, 3))
                .privateFact(AgentViews.saved(5, 1))
                .votes(AgentViews.vote(2, 5, false), AgentViews.vote(3, 5, false), AgentViews.vote(4, 6, false))
                .build();

        FactStore store = extractor.extractStore(view);

        assertEquals(Integer.valueOf(5), store.nightKillVictim().orElse(null));
        assertTrue(store.antidoteUsed(), "WITCH_SAVED 出现即解药已用");
        assertFalse(store.poisonUsed());
        assertTrue(store.ownSaveRecord(5));
        assertEquals(2, store.voteTally().get(5));
        assertEquals(1, store.voteTally().get(6));
    }

    @Test
    @DisplayName("预言家的查验真值只来自自己的 SEER_INSPECT，不认识就当作没有")
    void seerTruthComesFromPrivateFactsOnly() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .nineSeats(4)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我查验了5号是狼")
                .privateFact(AgentViews.inspect(6, "VILLAGE", 1))
                .build();

        FactStore store = extractor.extractStore(view);

        assertTrue(store.isCheckedGood(6));
        assertFalse(store.isCheckedWolf(5), "发言里的宣称不是真值");
        assertEquals("", FactStore.normalizeVerdict("UNKNOWN"));
    }

    @Test
    @DisplayName("未知私有事实 kind 按\"没有该事实\"处理，不抛异常")
    void unknownPrivateFactKindIsIgnored() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(2)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true)
                .nineSeats(7)
                .privateFact(new AgentView.PrivateFact("SOMETHING_NEW", 5, "X", 2))
                .build();

        FactStore store = extractor.extractStore(view);

        assertNull(FactStore.kindOf(store.privateFacts().get(0)));
        assertTrue(store.nightKillVictim().isEmpty());
        assertFalse(store.antidoteUsed());
        assertTrue(store.knownTeammates().isEmpty());
    }
}
