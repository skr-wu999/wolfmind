package com.wolfmind.application.agent.fake;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.strategy.StrategyPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 策略层的假实现：让 Runtime 与工作流可以在没有真实策略层时被单测。
 *
 * <p>它不是"空实现"：它按脚本产出候选集与提案，因此能覆盖工作流的全部分支
 * （LLM 成功、校验失败后修复、防泄漏重写、网关拒绝后兜底、候选集为空）。
 * 真实策略层由 {@code strategy-dev} 交付，二者用同一份 {@code StrategyPort} 契约。
 */
public final class FakeStrategy implements StrategyPort {

    private List<Candidate> candidates = new ArrayList<>();
    private ActionProposal fallbackProposal = ActionProposal.skip("NO_CANDIDATE");
    private String validationFailureCode;
    private String validationFailureReason;
    private boolean leakDetected;
    private String leakDetail = "村民提到了女巫用药";
    private int validateCalls;
    private int leakCheckCalls;
    private int echoHits;
    private final java.util.ArrayDeque<Double> echoSimilarities = new java.util.ArrayDeque<>();
    private int echoCheckCalls;

    /** 设置候选集。 */
    public FakeStrategy withCandidates(Candidate... values) {
        this.candidates = new ArrayList<>(List.of(values));
        return this;
    }

    /** 让校验固定失败（用于验证 repair 轮）。 */
    public FakeStrategy failingValidation(String code, String reason) {
        this.validationFailureCode = code;
        this.validationFailureReason = reason;
        return this;
    }

    /** 让校验恢复成功（用于验证 repair 之后通过）。 */
    public FakeStrategy passingValidation() {
        this.validationFailureCode = null;
        this.validationFailureReason = null;
        return this;
    }

    /** 让防泄漏固定命中（用于验证 rewrite 轮）。 */
    public FakeStrategy leaking() {
        this.leakDetected = true;
        return this;
    }

    public FakeStrategy notLeaking() {
        this.leakDetected = false;
        return this;
    }

    /** 让复读检查的前 {@code hits} 次命中（用于验证复读重写与转模板）。 */
    public FakeStrategy echoing(int hits) {
        this.echoHits = hits;
        return this;
    }

    /** 复读检查依次返回这些相似度，用完后不再命中（用于验证"提交相似度较低的一版"）。 */
    public FakeStrategy echoingWith(double... similarities) {
        for (double similarity : similarities) {
            echoSimilarities.add(similarity);
        }
        this.echoHits = similarities.length;
        return this;
    }

    public int echoCheckCalls() {
        return echoCheckCalls;
    }

    public FakeStrategy withFallback(ActionProposal proposal) {
        this.fallbackProposal = proposal;
        return this;
    }

    /** 兜底取传入候选集的第一个（与真实策略"取最高分"同形），候选为空时跳过。 */
    public FakeStrategy fallbackToFirstCandidate() {
        this.fallbackProposal = null;
        return this;
    }

    public int validateCalls() {
        return validateCalls;
    }

    public int leakCheckCalls() {
        return leakCheckCalls;
    }

    @Override
    public List<Candidate> candidates(AgentView view, String role, String phase, AgentConfig config) {
        return List.copyOf(candidates);
    }

    @Override
    public List<String> allowedActions(String role, String phase) {
        return List.of("SPEAK", "VOTE", "SKIP");
    }

    @Override
    public ValidationResult validate(
            String raw, List<Candidate> candidates, AgentView view, AgentConfig config) {
        validateCalls++;
        if (validationFailureCode != null) {
            return ValidationResult.invalid(validationFailureCode, validationFailureReason);
        }
        // 极简解析：脚本里的输出形如 ACTION:VOTE:3 或 ACTION:SPEAK:5:发言 ；production 的策略层解析真实 JSON
        String[] parts = raw == null ? new String[0] : raw.split(":", 4);
        if (parts.length < 2) {
            return ValidationResult.invalid("OUTPUT_UNPARSABLE", "输出不是约定格式");
        }
        Integer target = parts.length >= 3 ? Integer.valueOf(parts[2].trim()) : null;
        return ValidationResult.ok(new ActionProposal(
                parts[1].trim(),
                target == null ? null : "p_" + target,
                target,
                parts.length >= 4 ? parts[3] : "脚本发言",
                0.7,
                "SCRIPTED",
                null));
    }

    @Override
    public LeakCheckResult leakCheck(String text, AgentView view) {
        leakCheckCalls++;
        return leakDetected ? LeakCheckResult.violation("PRIVATE_FACT", leakDetail) : LeakCheckResult.clean();
    }

    @Override
    public EchoCheckResult echoCheck(String text, AgentView view) {
        echoCheckCalls++;
        if (echoHits > 0) {
            echoHits--;
            double similarity = echoSimilarities.isEmpty() ? 0.9 : echoSimilarities.poll();
            return EchoCheckResult.echo(3, 1, similarity, false);
        }
        return EchoCheckResult.clean();
    }

    @Override
    public ActionProposal fallback(List<Candidate> candidates, AgentView view, String role, String phase) {
        if (fallbackProposal != null) {
            return fallbackProposal;
        }
        if (candidates.isEmpty()) {
            return ActionProposal.skip("NO_CANDIDATE");
        }
        Candidate top = candidates.get(0);
        return ActionProposal.of(top.actionType(), top.targetId(), top.targetSeatNo());
    }

    @Override
    public AgentContext assembleContext(
            AgentView view, List<Candidate> candidates, AgentConfig config, String memory) {
        return new AgentContext(
                "规则摘要",
                config.persona(),
                "公开时间线",
                "私有信息",
                memory,
                candidates,
                "本回合任务",
                AgentContext.Facts.empty(),
                0);
    }

    /** 便于断言的候选构造。 */
    public static Candidate candidate(String actionType, Integer seat, double score) {
        return new Candidate(
                actionType, seat, seat == null ? null : "p_" + seat,
                actionType + (seat == null ? "" : " -> " + seat),
                score, Map.of("targetScore", score), List.of("FAKE"), List.of());
    }
}
