package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * 猎人策略（strategy-layer.md §5 的 {@code HUNTER} 行）。
 *
 * <p>硬约束：仅在自己死亡且未被毒时可开枪，目标必须存活。前半句的可判定部分是阶段本身
 * ——领域只在"待开枪的猎人本人且未被毒"时才把对局推进到 {@code HUNTER_SHOOT}，因此策略层
 * 不需要（也无法）从视图判定死因；视图里能看到的只有 {@code skillState.shotAvailable}
 * 与存活玩家列表，本类只用这两项。
 *
 * <p>"放弃开枪"是合法候选（§5 末段），所以猎人永远不会无候选可交。
 */
public final class HunterRoleStrategy extends AbstractRoleStrategy {

    @Override
    public String role() {
        return StrategyCodes.HUNTER;
    }

    @Override
    public List<String> hardConstraints(AgentView view, String phase) {
        return List.of(
                "仅在自己死亡且未被毒时可开枪（阶段由领域判定，本层不推断死因）",
                "开枪目标必须存活且不是自己（" + CONSTRAINT_TARGET_ALIVE + "）",
                "全局只能开一枪（shotAvailable=false 时只能放弃开枪）");
    }

    @Override
    public List<Candidate> generate(RoleContext context) {
        if (context == null || context.view() == null || context.view().me() == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        switch (StrategyCodes.normalize(context.phase())) {
            case StrategyCodes.HUNTER_SHOOT_PHASE -> candidates.addAll(shootCandidates(context));
            case StrategyCodes.DAY_DISCUSS, StrategyCodes.LAST_WORDS ->
                    candidates.add(speakCandidate(context, VILLAGE_SPEAK_EXPOSURE,
                            "发言：是否先表态施压由你决定"));
            case StrategyCodes.DAY_VOTE -> candidates.addAll(voteCandidates(
                    context, context.suspicionModel().rankAliveOthers(context.view(), context.facts()),
                    ruleBool(context.view(), "abstainAllowed", true),
                    "猎人投票的普通目标约束"));
            default -> {
                // 其余阶段没有猎人的动作
            }
        }
        return candidates;
    }

    private List<Candidate> shootCandidates(RoleContext context) {
        List<Candidate> candidates = new ArrayList<>();
        if (shotAvailable(context)) {
            for (SuspicionAssessment assessment : top(
                    context.suspicionModel().rankAliveOthers(context.view(), context.facts()),
                    context.candidateLimit())) {
                candidates.add(candidate(
                        StrategyCodes.HUNTER_SHOOT,
                        context.view(),
                        assessment.seatNo(),
                        "开枪打 " + assessment.seatNo() + " 号（怀疑度 "
                                + String.format(java.util.Locale.ROOT, "%.2f", assessment.score()) + "）",
                        factors(assessment.score(), 0.1, 0.8, 0.15),
                        mergeTags(assessment.tags(), "SHOOT_TARGET"),
                        List.of(CONSTRAINT_TARGET_ALIVE, "全局只能开一枪（INV-8）")));
            }
        }
        candidates.add(noTarget(StrategyCodes.HUNTER_SHOOT, "放弃开枪",
                factors(0.0, 0.0, INACTION_PRESSURE, INACTION_EXPOSURE),
                List.of("放弃开枪是合法候选（§5）")));
        return candidates;
    }

    /** 开枪次数是否还在：技能视图缺失时按"可用"处理，由领域做最终判定。 */
    private boolean shotAvailable(RoleContext context) {
        AgentView.SkillView skills = context.view().me().skills();
        return skills == null || skills.shotAvailable();
    }
}
