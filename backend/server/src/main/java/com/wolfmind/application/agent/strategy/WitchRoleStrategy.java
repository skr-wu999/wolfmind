package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * 女巫策略（strategy-layer.md §5 的 {@code WITCH} 行）。
 *
 * <p>三条硬约束在候选集上落地：
 * <ol>
 *   <li>解药候选**只能**是当夜被刀者（{@code NIGHT_KILL} 私有事实），并且该座位在视图里仍存活
 *       ——候选集里绝不出现死者；</li>
 *   <li>自救按规则：{@code witchSelfSave=false}，或 {@code witchSelfSaveFirstNightOnly=true}
 *       且已过首夜时，把自己从解药候选里去掉；</li>
 *   <li>同夜不可双药（INV-7）：解药与毒药同时出现在候选集里，但只能选一个；药已用完时
 *       对应候选直接消失，不给"必然被拒"的选项。</li>
 * </ol>
 *
 * <p>"不用药"永远是合法候选（§5 末段），因此女巫在任何夜晚都不会无候选可交。
 */
public final class WitchRoleStrategy extends AbstractRoleStrategy {

    @Override
    public String role() {
        return StrategyCodes.WITCH;
    }

    @Override
    public List<String> hardConstraints(AgentView view, String phase) {
        List<String> constraints = new ArrayList<>();
        constraints.add("解药只能救当夜被狼刀的目标，且目标必须仍然存活（witchSaveTargetMustBeKilled）");
        constraints.add("同夜不可同时使用解药与毒药（INV-7）：候选里两药互斥，只能选一个");
        if (view == null || !view.witchCanSelfSave()) {
            constraints.add(view != null && view.ruleBool("witchSelfSave", false)
                    ? "女巫只有首夜可以自救，本夜不可自救（witchSelfSaveFirstNightOnly=true）"
                    : "本局不可自救（witchSelfSave=false）");
        }
        constraints.add("毒药目标必须存活且不是自己（" + CONSTRAINT_TARGET_ALIVE + "）");
        return constraints;
    }

    @Override
    public List<Candidate> generate(RoleContext context) {
        if (context == null || context.view() == null || context.view().me() == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        switch (StrategyCodes.normalize(context.phase())) {
            case StrategyCodes.NIGHT_WITCH -> candidates.addAll(nightCandidates(context));
            case StrategyCodes.DAY_DISCUSS, StrategyCodes.LAST_WORDS ->
                    candidates.add(speakCandidate(context, VILLAGE_SPEAK_EXPOSURE,
                            "白天发言：暗示还是隐藏自己的用药由你决定"));
            case StrategyCodes.DAY_VOTE -> candidates.addAll(voteCandidates(
                    context, context.suspicionModel().rankAliveOthers(context.view(), context.facts()),
                    ruleBool(context.view(), "abstainAllowed", true),
                    "女巫投票的普通目标约束"));
            default -> {
                // 其余阶段没有女巫的动作
            }
        }
        return candidates;
    }

    private List<Candidate> nightCandidates(RoleContext context) {
        List<Candidate> candidates = new ArrayList<>();
        addSaveCandidate(context, candidates);
        addPoisonCandidates(context, candidates);
        candidates.add(noTarget(StrategyCodes.WITCH_PASS, "不用药（跳过本夜）",
                factors(0.0, 0.0, INACTION_PRESSURE, INACTION_EXPOSURE),
                List.of("不用药是合法候选（§5）")));
        return candidates;
    }

    private void addSaveCandidate(RoleContext context, List<Candidate> candidates) {
        if (!antidoteAvailable(context)) {
            return;
        }
        Integer victim = context.facts().nightKillVictim().orElse(null);
        if (victim == null) {
            // 拿不到当夜刀口就不给解药候选：解药只能救当夜被刀者，猜一个目标必然被领域拒绝
            return;
        }
        boolean self = context.view().me().seatNo() == victim;
        if (self && !context.view().witchCanSelfSave()) {
            return;
        }
        // other 列表不含自己：自救时存活状态要从 me 取
        boolean victimAlive = self
                ? context.view().me().alive()
                : context.view().seat(victim).map(AgentView.SeatView::alive).orElse(false);
        if (!victimAlive) {
            // 视图里刀口已经死亡说明本局救不回来（或投影已结算）；候选集不出现死者
            return;
        }
        candidates.add(candidate(
                StrategyCodes.WITCH_SAVE,
                context.view(),
                victim,
                "用解药救 " + victim + " 号（当夜被刀者）",
                factors(0.6, 0.2, 0.9, 0.1),
                List.of("NIGHT_VICTIM", "SAVE_TARGET"),
                List.of("解药只能救当夜被刀者", "同夜不可双药（INV-7）")));
    }

    private void addPoisonCandidates(RoleContext context, List<Candidate> candidates) {
        if (!poisonAvailable(context)) {
            return;
        }
        List<SuspicionAssessment> ranked = context.suspicionModel()
                .rankAliveOthers(context.view(), context.facts());
        for (SuspicionAssessment assessment : top(ranked, context.candidateLimit())) {
            candidates.add(candidate(
                    StrategyCodes.WITCH_POISON,
                    context.view(),
                    assessment.seatNo(),
                    "用毒药毒 " + assessment.seatNo() + " 号（怀疑度 "
                            + String.format(java.util.Locale.ROOT, "%.2f", assessment.score()) + "）",
                    factors(assessment.score(), 0.1, 0.8, 0.1),
                    mergeTags(assessment.tags(), "POISON_TARGET"),
                    List.of("毒药目标必须存活且不是自己", "同夜不可双药（INV-7）")));
        }
    }

    /**
     * 解药是否可用：技能视图与私有用药记录两处都看。
     *
     * <p>{@code skillState} 缺失（投影尚未填充）时按"可用"处理并交由领域最终判定——把药默认为
     * 已用会让女巫整局失去技能，比多一个会被拒绝的候选代价大得多。
     */
    private boolean antidoteAvailable(RoleContext context) {
        if (context.facts().antidoteUsed()) {
            return false;
        }
        AgentView.SkillView skills = context.view().me().skills();
        return skills == null || skills.antidoteAvailable();
    }

    private boolean poisonAvailable(RoleContext context) {
        if (context.facts().poisonUsed()) {
            return false;
        }
        AgentView.SkillView skills = context.view().me().skills();
        return skills == null || skills.poisonAvailable();
    }
}
