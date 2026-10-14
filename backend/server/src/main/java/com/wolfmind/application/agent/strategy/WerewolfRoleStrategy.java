package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * 狼人策略（strategy-layer.md §5 的 {@code WEREWOLF} 行）。
 *
 * <p>硬约束由代码强制：刀口目标不得是狼人阵营（INV-6），每夜一个刀口，不使用任何村民阵营的工具。
 * 候选集里排除狼人队友这件事有**三重证据**兜底（阵营字段、角色字段、私有队友名单，见
 * {@link CampRules#isWolfSeat}）——视图的脱敏由领域负责，但 INV-6 是硬约束，不能寄希望于
 * 某一个字段恰好被填充。
 *
 * <p>白天伪装与欺骗方式留给 LLM（§1、§5 的"交给 LLM 的部分"）：本类只保证它拿到的
 * 投票候选里没有队友，"要不要演、怎么演"不在代码里决定。
 */
public final class WerewolfRoleStrategy extends AbstractRoleStrategy {

    @Override
    public String role() {
        return StrategyCodes.WEREWOLF;
    }

    @Override
    public List<String> hardConstraints(AgentView view, String phase) {
        return List.of(
                "刀口目标不得为狼人阵营（INV-6），队友永远不出现在候选集里",
                "每夜只能提名一个刀口（INV-5）；本局允许空刀时才有\"空刀\"候选",
                "不得使用任何村民阵营的工具（查验、用药、开枪）",
                "白天可以伪装村民，但不得说出狼队内部事实之外的私有信息");
    }

    @Override
    public List<Candidate> generate(RoleContext context) {
        if (context == null || context.view() == null || context.view().me() == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        switch (StrategyCodes.normalize(context.phase())) {
            case StrategyCodes.NIGHT_WOLF -> candidates.addAll(killCandidates(context));
            case StrategyCodes.DAY_DISCUSS, StrategyCodes.LAST_WORDS ->
                    candidates.add(speakCandidate(context, WOLF_SPEAK_EXPOSURE,
                            "白天发言：伪装成村民，控制暴露风险"));
            case StrategyCodes.DAY_VOTE -> candidates.addAll(voteCandidatesFrom(
                    context, wolfVoteRanking(context),
                    ruleBool(context.view(), "abstainAllowed", true),
                    "狼人投票同样不得指向队友（§5 的目标硬约束）"));
            default -> {
                // 其余阶段狼人没有动作：返回空候选集是合法结果，由兜底产出 SKIP
            }
        }
        return candidates;
    }

    /** 刀口候选：存活非狼玩家按 {@code ThreatModel} 取前 N，附"空刀"（§5）。 */
    private List<Candidate> killCandidates(RoleContext context) {
        List<Candidate> candidates = new ArrayList<>();
        for (ThreatAssessment assessment : top(
                context.threatModel().rankAliveOpponents(context.view(), context.facts()),
                context.candidateLimit())) {
            candidates.add(candidate(
                    StrategyCodes.WOLF_KILL,
                    context.view(),
                    assessment.seatNo(),
                    "刀 " + assessment.seatNo() + " 号（威胁 "
                            + String.format(java.util.Locale.ROOT, "%.2f", assessment.score())
                            + "，暴露风险 "
                            + String.format(java.util.Locale.ROOT, "%.2f", assessment.killRisk()) + "）",
                    factors(assessment.score(), 0.1, 0.8, assessment.killRisk()),
                    mergeTags(assessment.tags(), "WOLF_KILL_TARGET"),
                    List.of("目标不得为狼人阵营（INV-6）", "每夜只能提名一个刀口（INV-5）")));
        }
        if (ruleBool(context.view(), "allowWolfEmptyKill", true)) {
            candidates.add(noTarget(StrategyCodes.WOLF_KILL, "空刀（今夜不杀人）",
                    factors(0.0, 0.0, INACTION_PRESSURE, INACTION_EXPOSURE),
                    List.of("本局允许空刀（allowWolfEmptyKill）")));
        }
        return candidates;
    }

    /** 狼人的投票排序用威胁模型：白天把威胁最大的人推出去，与夜里的刀口判断一致。 */
    private List<Ranked> wolfVoteRanking(RoleContext context) {
        List<Ranked> ranking = new ArrayList<>();
        for (ThreatAssessment assessment : context.threatModel()
                .rankAliveOpponents(context.view(), context.facts())) {
            ranking.add(new Ranked(assessment.seatNo(), assessment.score(), assessment.tags()));
        }
        return ranking;
    }
}
