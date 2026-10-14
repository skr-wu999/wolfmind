package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 预言家策略（strategy-layer.md §5 的 {@code SEER} 行）。
 *
 * <p>硬约束：每夜必须查验存活他人，查验结果仅自己可见。这一点决定了候选集的形状——
 * 候选集是"全部存活他人"，按信息收益排序（未查验优先、发言强势次之，§5），
 * 并且**不按 {@code candidateLimit} 截断**：§5 对预言家写的是"全部存活他人"，
 * 截断会让"必须查验"这条硬约束在某些局面下变得没有可选目标。
 *
 * <p>白天报不报身份、怎么措辞降低被刀风险，留给 LLM（§5 的"交给 LLM 的部分"）。
 */
public final class SeerRoleStrategy extends AbstractRoleStrategy {

    @Override
    public String role() {
        return StrategyCodes.SEER;
    }

    @Override
    public List<String> hardConstraints(AgentView view, String phase) {
        return List.of(
                "每夜必须查验一名存活的其他玩家（没有\"跳过查验\"这个选项）",
                "查验结果仅自己可见，不得在发言里把它当作公共事实陈述",
                "不把票投给自己已经查验过的好人（真值优先于公开推断）");
    }

    @Override
    public List<Candidate> generate(RoleContext context) {
        if (context == null || context.view() == null || context.view().me() == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        switch (StrategyCodes.normalize(context.phase())) {
            case StrategyCodes.NIGHT_SEER -> candidates.addAll(inspectCandidates(context));
            case StrategyCodes.DAY_DISCUSS, StrategyCodes.LAST_WORDS ->
                    candidates.add(speakCandidate(context, VILLAGE_SPEAK_EXPOSURE,
                            "白天发言：是否报身份与查验结论由你决定"));
            case StrategyCodes.DAY_VOTE -> candidates.addAll(voteCandidatesFrom(
                    context, seerVoteRanking(context),
                    ruleBool(context.view(), "abstainAllowed", true),
                    "不投自己查验过的好人"));
            default -> {
                // 其余阶段没有预言家的动作
            }
        }
        return candidates;
    }

    /**
     * 全部存活他人，按"未查验优先、发言强势优先"排序（§5）。
     *
     * <p>排序键与打分因子分开：排序键是信息收益（未查验 1.0 / 已查验 0.15）与发言信息量的合成，
     * {@code targetScore} 用的是怀疑度——查验一个高嫌疑者比查验透明人更有价值。
     */
    private List<Candidate> inspectCandidates(RoleContext context) {
        record Inspect(int seatNo, double infoGain, double assertiveness, double suspicion, List<String> tags) {}

        List<Inspect> ranked = new ArrayList<>();
        for (int seatNo : aliveSeatNos(context.view())) {
            boolean uninspected = context.facts().inspectVerdict(seatNo).isEmpty();
            double infoGain = uninspected ? 1.0 : 0.15;
            double assertiveness = context.threatModel().information(context.view(), context.facts(), seatNo);
            double suspicion = context.suspicionModel().assess(context.view(), context.facts(), seatNo).score();
            List<String> tags = new ArrayList<>();
            tags.add(uninspected ? "UNINSPECTED" : "ALREADY_INSPECTED");
            if (assertiveness >= 0.5) {
                tags.add("ASSERTIVE");
            }
            ranked.add(new Inspect(seatNo, infoGain, assertiveness, suspicion, tags));
        }
        TieBreak tieBreak = TieBreak.of(context.view(), StrategyCodes.SEER_INSPECT);
        ranked.sort(Comparator.comparingDouble(Inspect::infoGain).reversed()
                .thenComparing(Comparator.comparingDouble(Inspect::assertiveness).reversed())
                .thenComparingLong(item -> tieBreak.key(item.seatNo()))
                .thenComparingInt(Inspect::seatNo));

        List<Candidate> candidates = new ArrayList<>();
        for (Inspect item : ranked) {
            candidates.add(candidate(
                    StrategyCodes.SEER_INSPECT,
                    context.view(),
                    item.seatNo(),
                    "查验 " + item.seatNo() + " 号（信息收益 "
                            + String.format(java.util.Locale.ROOT, "%.2f", item.infoGain())
                            + (item.assertiveness() >= 0.5 ? "，发言信息量高" : "") + "）",
                    factors(item.suspicion(), item.infoGain(), 0.9, 0.1),
                    mergeTags(item.tags(), "INSPECT_TARGET"),
                    List.of("每夜必须查验存活他人", CONSTRAINT_TARGET_ALIVE)));
        }
        return candidates;
    }

    /** 预言家的投票排序：真值优先，排除已确认的好人。 */
    private List<Ranked> seerVoteRanking(RoleContext context) {
        List<Ranked> ranking = new ArrayList<>();
        for (SuspicionAssessment assessment : context.suspicionModel()
                .rankAliveOthers(context.view(), context.facts())) {
            if (context.facts().isCheckedGood(assessment.seatNo())) {
                continue;
            }
            ranking.add(new Ranked(assessment.seatNo(), assessment.score(), assessment.tags()));
        }
        return ranking;
    }
}
