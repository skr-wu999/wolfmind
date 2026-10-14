package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OutputValidator}：四类坏输入触发修复，confidence / reasonCode 非法只回落默认值（§8.1）。 */
class OutputValidatorTest {

    private final OutputValidator validator = new OutputValidator();
    private final AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);

    private static AgentView villagerView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(1)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();
    }

    private static AgentView voteView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .others(2, 3, 4, 5, 6, 7, 8)
                .seat(AgentViews.seat(9, false))
                .build();
    }

    private static Candidate speakCandidate() {
        return new Candidate(StrategyCodes.SPEAK, null, null, "发言", 0.0, Map.of(), List.of(), List.of());
    }

    private static Candidate voteCandidate(int seatNo) {
        return new Candidate(StrategyCodes.VOTE, seatNo, "p_" + seatNo, "投票", 0.0, Map.of(), List.of(), List.of());
    }

    @Test
    void submittedSpeechDropsMechanicalStageHeader() {
        var result = validator.validate("{\"actionType\":\"SPEAK\",\"speech\":\"第 1 天，DAY_DISCUSS。我的判断是先关注 8 号。\"}",
                List.of(speakCandidate()), villagerView(), config, List.of("SPEAK"));
        assertTrue(result.valid());
        assertEquals("我的判断是先关注 8 号。", result.proposal().speech());
    }

    @Test
    void stageHeaderAloneIsNotAcceptedAsSpeech() {
        var result = validator.validate("{\"actionType\":\"SPEAK\",\"speech\":\"第 1 天，DAY_DISCUSS。\"}",
                List.of(speakCandidate()), villagerView(), config, List.of("SPEAK"));
        assertFalse(result.valid());
        assertEquals(StrategyCodes.FAIL_SPEECH_LENGTH, result.failureCode());
    }

    private static Candidate abstainCandidate() {
        return new Candidate(StrategyCodes.VOTE, null, null, "弃权", 0.0, Map.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("坏 JSON：不可解析的输出触发修复，不产生提案")
    void badJsonIsRejected() {
        StrategyPort.ValidationResult result = validator.validate(
                "我想投 5 号，但是我不输出 JSON", List.of(speakCandidate()), villagerView(), config,
                List.of(StrategyCodes.SPEAK, StrategyCodes.SKIP));

        assertFalse(result.valid());
        assertEquals(StrategyCodes.FAIL_BAD_JSON, result.failureCode());
        assertNull(result.proposal());
    }

    @Test
    @DisplayName("actionType 不在允许集合内：触发修复")
    void actionNotAllowedIsRejected() {
        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"WOLF_KILL\",\"targetId\":\"p_5\"}",
                List.of(speakCandidate()), villagerView(), config,
                List.of(StrategyCodes.SPEAK, StrategyCodes.SKIP));

        assertFalse(result.valid());
        assertEquals(StrategyCodes.FAIL_ACTION_NOT_ALLOWED, result.failureCode());
    }

    @Test
    @DisplayName("阶段与角色不匹配：动作在允许集合里但不属于本阶段候选集")
    void phaseRoleMismatchIsRejected() {
        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"SKIP\"}", List.of(speakCandidate()), villagerView(), config,
                List.of(StrategyCodes.SPEAK, StrategyCodes.SKIP));

        assertFalse(result.valid());
        assertEquals(StrategyCodes.FAIL_PHASE_ROLE_MISMATCH, result.failureCode());
    }

    @Test
    @DisplayName("目标不在候选集内：越界目标触发修复")
    void targetOutsideCandidatesIsRejected() {
        List<Candidate> candidates = List.of(voteCandidate(5), abstainCandidate());
        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"VOTE\",\"targetId\":\"p_7\"}", candidates, voteView(), config,
                List.of(StrategyCodes.VOTE, StrategyCodes.SKIP));

        assertFalse(result.valid());
        assertEquals(StrategyCodes.FAIL_TARGET_NOT_IN_CANDIDATES, result.failureCode());
    }

    @Test
    @DisplayName("目标非法：自己与死者都被拒绝")
    void selfAndDeadTargetsAreRejected() {
        List<Candidate> candidates = List.of(voteCandidate(5), abstainCandidate());
        StrategyPort.ValidationResult self = validator.validate(
                "{\"actionType\":\"VOTE\",\"targetId\":\"p_1\"}", candidates, voteView(), config,
                List.of(StrategyCodes.VOTE, StrategyCodes.SKIP));
        assertEquals(StrategyCodes.FAIL_TARGET_INVALID, self.failureCode());

        List<Candidate> withDead = List.of(voteCandidate(5), voteCandidate(9), abstainCandidate());
        StrategyPort.ValidationResult dead = validator.validate(
                "{\"actionType\":\"VOTE\",\"targetId\":\"p_9\"}", withDead, voteView(), config,
                List.of(StrategyCodes.VOTE, StrategyCodes.SKIP));
        assertEquals(StrategyCodes.FAIL_TARGET_INVALID, dead.failureCode());
    }

    @Test
    @DisplayName("缺少 targetId：有目标候选却不给目标触发修复")
    void missingTargetIsRejected() {
        List<Candidate> candidates = List.of(voteCandidate(5));

        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"VOTE\"}", candidates, voteView(), config,
                List.of(StrategyCodes.VOTE, StrategyCodes.SKIP));

        assertFalse(result.valid());
        assertEquals(StrategyCodes.FAIL_TARGET_NOT_IN_CANDIDATES, result.failureCode());
    }

    @Test
    @DisplayName("发言长度：超长与需要发言却为空都触发修复")
    void speechLengthIsValidated() {
        String tooLong = "啊".repeat(501);
        StrategyPort.ValidationResult over = validator.validate(
                "{\"actionType\":\"SPEAK\",\"speech\":\"" + tooLong + "\"}",
                List.of(speakCandidate()), villagerView(), config, List.of(StrategyCodes.SPEAK));
        assertFalse(over.valid());
        assertEquals(StrategyCodes.FAIL_SPEECH_LENGTH, over.failureCode());

        StrategyPort.ValidationResult empty = validator.validate(
                "{\"actionType\":\"SPEAK\",\"speech\":\"\"}",
                List.of(speakCandidate()), villagerView(), config, List.of(StrategyCodes.SPEAK));
        assertFalse(empty.valid());
        assertEquals(StrategyCodes.FAIL_SPEECH_LENGTH, empty.failureCode());
    }

    @Test
    @DisplayName("不需要发言的阶段允许空 speech")
    void emptySpeechAllowedWhenPhaseDoesNotRequireIt() {
        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"VOTE\",\"targetId\":\"p_5\"}",
                List.of(voteCandidate(5)), voteView(), config, List.of(StrategyCodes.VOTE));

        assertTrue(result.valid());
        assertNull(result.proposal().speech());
    }

    @Test
    @DisplayName("confidence 非法：使用默认值 0.5，不触发修复")
    void invalidConfidenceFallsBackWithoutRepair() {
        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"SPEAK\",\"speech\":\"我先听听\",\"confidence\":5}",
                List.of(speakCandidate()), villagerView(), config, List.of(StrategyCodes.SPEAK));

        assertTrue(result.valid(), "非法 confidence 不应让整条输出作废");
        assertEquals(0.5, result.proposal().confidence(), 1e-9);
    }

    @Test
    @DisplayName("reasonCode 非法：回落到 UNSPECIFIED，不触发修复")
    void invalidReasonCodeFallsBackWithoutRepair() {
        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"SPEAK\",\"speech\":\"我先听听\",\"reasonCode\":\"banana\"}",
                List.of(speakCandidate()), villagerView(), config, List.of(StrategyCodes.SPEAK));

        assertTrue(result.valid());
        assertEquals(StrategyCodes.REASON_UNSPECIFIED, result.proposal().reasonCode());
    }

    @Test
    @DisplayName("合法输出：提案的目标取自候选集而不是模型给的字符串")
    void validOutputBindsTargetToCandidates() {
        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"VOTE\",\"targetId\":\"5号\",\"speech\":\"  我投5号  \","
                        + "\"confidence\":0.72,\"reasonCode\":\"max_suspicion\"}",
                List.of(voteCandidate(5), abstainCandidate()), voteView(), config,
                List.of(StrategyCodes.VOTE, StrategyCodes.SKIP));

        assertTrue(result.valid());
        ActionProposal proposal = result.proposal();
        assertEquals(StrategyCodes.VOTE, proposal.actionType());
        assertEquals(5, proposal.targetSeatNo());
        assertEquals("p_5", proposal.targetId(), "targetId 以候选集为准");
        assertEquals("我投5号", proposal.speech());
        assertEquals(0.72, proposal.confidence(), 1e-9);
        assertEquals("MAX_SUSPICION", proposal.reasonCode(), "合法理由码归一为大写");
    }

    @Test
    @DisplayName("弃权：无目标形式命中无目标候选，标记写法也容忍")
    void abstainFormsAreAccepted() {
        List<Candidate> candidates = List.of(voteCandidate(5), abstainCandidate());

        StrategyPort.ValidationResult noTarget = validator.validate(
                "{\"actionType\":\"VOTE\"}", candidates, voteView(), config, List.of(StrategyCodes.VOTE));
        assertTrue(noTarget.valid());
        assertNull(noTarget.proposal().targetSeatNo());

        StrategyPort.ValidationResult flag = validator.validate(
                "{\"actionType\":\"VOTE\",\"abstain\":true}", candidates, voteView(), config,
                List.of(StrategyCodes.VOTE));
        assertTrue(flag.valid());
        assertNull(flag.proposal().targetSeatNo());
    }

    @Test
    @DisplayName("被 markdown 代码块包裹的 JSON 仍然可解析，不浪费一次修复")
    void fencedJsonIsAccepted() {
        StrategyPort.ValidationResult result = validator.validate(
                "```json\n{\"actionType\":\"SPEAK\",\"speech\":\"我先听听\"}\n```",
                List.of(speakCandidate()), villagerView(), config, List.of(StrategyCodes.SPEAK));

        assertTrue(result.valid());
        assertEquals("我先听听", result.proposal().speech());
    }

    @Test
    @DisplayName("观战者视角（没有 me）时任何输出都非法")
    void missingSelfViewIsRejected() {
        AgentView spectator = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .build();

        StrategyPort.ValidationResult result = validator.validate(
                "{\"actionType\":\"SPEAK\",\"speech\":\"你好\"}",
                List.of(speakCandidate()), spectator, config, List.of(StrategyCodes.SPEAK));

        assertFalse(result.valid());
    }
}
