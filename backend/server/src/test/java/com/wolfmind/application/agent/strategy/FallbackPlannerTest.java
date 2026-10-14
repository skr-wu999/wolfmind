package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FallbackPlanner}：候选为空时产出 SKIP，否则取打分最高者（strategy-layer.md §9、§11 第七行）。 */
class FallbackPlannerTest {

    private final FallbackPlanner planner = new FallbackPlanner();

    private static AgentView view(String phase) {
        return AgentViews.builder()
                .phase(phase)
                .round(1)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();
    }

    private static Candidate candidate(String actionType, Integer seatNo, double score) {
        return new Candidate(actionType, seatNo, seatNo == null ? null : "p_" + seatNo,
                actionType, score, Map.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("候选集为空时产出 SKIP（含 null 候选集）")
    void emptyCandidatesProduceSkip() {
        ActionProposal empty = planner.plan(List.of(), view(StrategyCodes.DAY_VOTE),
                StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, null);
        assertEquals(StrategyCodes.SKIP, empty.actionType());
        assertEquals(StrategyCodes.REASON_FALLBACK_EMPTY_CANDIDATES, empty.reasonCode());

        ActionProposal nullCandidates = planner.plan(null, view(StrategyCodes.DAY_VOTE),
                StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, null);
        assertEquals(StrategyCodes.SKIP, nullCandidates.actionType());
    }

    @Test
    @DisplayName("非空候选集取打分最高者，平局按座位号升序")
    void topCandidateWins() {
        ActionProposal proposal = planner.plan(
                List.of(candidate(StrategyCodes.VOTE, 7, 0.2),
                        candidate(StrategyCodes.VOTE, 3, 0.2),
                        candidate(StrategyCodes.VOTE, 5, 0.1)),
                view(StrategyCodes.DAY_VOTE), StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, null);

        assertEquals(StrategyCodes.VOTE, proposal.actionType());
        assertEquals(3, proposal.targetSeatNo());
        assertEquals("p_3", proposal.targetId());
        assertEquals(StrategyCodes.REASON_FALLBACK_TOP1, proposal.reasonCode());
        assertNull(proposal.speech(), "投票阶段不需要发言");
    }

    @Test
    @DisplayName("发言阶段用结构化模板，句子来自 §9 第 3 条")
    void fallbackSpeechUsesTemplate() {
        ActionProposal proposal = planner.plan(
                List.of(candidate(StrategyCodes.SPEAK, null, 0.5)),
                view(StrategyCodes.DAY_DISCUSS), StrategyCodes.VILLAGER, StrategyCodes.DAY_DISCUSS, 5);

        assertEquals(StrategyCodes.SPEAK, proposal.actionType());
        assertNotNull(proposal.speech());
        assertFalse(proposal.speech().contains(StrategyCodes.DAY_DISCUSS));
        assertTrue(proposal.speech().contains("5 号"));
    }

    @Test
    @DisplayName("模板在没有怀疑目标时退化为通用表态，不留悬空座位号")
    void templateWithoutSuspectSeat() {
        String speech = FallbackPlanner.templateSpeech(2, StrategyCodes.DAY_DISCUSS, null);

        assertFalse(speech.contains("null"));
        assertFalse(speech.startsWith("第 "));
    }

    @Test
    @DisplayName("置信度被夹到 0..1，负分不会构造出非法提案")
    void confidenceIsClamped() {
        ActionProposal proposal = planner.plan(
                List.of(candidate(StrategyCodes.VOTE, 4, -0.4)),
                view(StrategyCodes.DAY_VOTE), StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, null);

        assertEquals(0.0, proposal.confidence(), 1e-9);
    }

    @Test
    @DisplayName("安全模板发言：非空且不含任何私有事实关键词")
    void safeSpeechIsClean() {
        String speech = planner.safeSpeech(view(StrategyCodes.DAY_DISCUSS));

        assertFalse(speech.isBlank());
        assertFalse(speech.contains("查验"));
        assertFalse(speech.contains("救"));
        assertFalse(speech.contains("毒"));
    }

    @Test
    void fallbackAndSafeSpeechDoNotAnnounceInternalStages() {
        for (String phase : List.of(StrategyCodes.DAY_DISCUSS, StrategyCodes.LAST_WORDS)) {
            String speech = FallbackPlanner.templateSpeech(1, phase, 8);
            assertFalse(speech.contains(phase), speech);
            assertFalse(speech.startsWith("第 "), speech);
            assertTrue(speech.contains("8 号"));
            assertFalse(planner.safeSpeech(view(phase)).contains(phase));
        }
    }
}
