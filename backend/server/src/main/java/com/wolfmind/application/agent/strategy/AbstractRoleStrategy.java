package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 角色策略的公共部分：候选构造、目标解析与规则读取。
 *
 * <p>把"怎么造一个 Candidate"集中在这里，是为了让所有角色策略用同一种方式填写
 * {@code breakdown}（{@link DecisionPolicy} 的输入）与 {@code constraints}（进 prompt 的解释），
 * 避免某个角色漏填因子导致打分静默变成 0。
 */
public abstract class AbstractRoleStrategy implements RoleStrategy {

    /** 公共硬约束：目标存活且非自身（INV-9，tool-gateway.md G9）。 */
    protected static final String CONSTRAINT_TARGET_ALIVE = "目标必须存活且不是自己（INV-9）";

    /** 狼队候选的暴露规避：发言越少越安全，因此 SPEAK 候选自带较高的暴露项。 */
    protected static final double WOLF_SPEAK_EXPOSURE = 0.45;

    /** 村民阵营的发言暴露风险很低：说错话最多被怀疑，不会被"刀口"反噬。 */
    protected static final double VILLAGE_SPEAK_EXPOSURE = 0.15;

    /** 弃权/空刀/不用药/放弃开枪这类"不作为"候选的中性因子取值。 */
    protected static final double INACTION_PRESSURE = 0.2;
    protected static final double INACTION_EXPOSURE = 0.1;

    protected Candidate candidate(
            String actionType,
            AgentView view,
            Integer targetSeatNo,
            String label,
            Map<String, Double> breakdown,
            List<String> tags,
            List<String> constraints) {
        return new Candidate(
                actionType,
                targetSeatNo,
                targetSeatNo == null ? null : playerIdOf(view, targetSeatNo),
                label,
                0.0,
                breakdown,
                tags,
                constraints);
    }

    /** 无目标候选（弃权、空刀、不用药、放弃开枪）。 */
    protected Candidate noTarget(
            String actionType, String label, Map<String, Double> breakdown, List<String> constraints) {
        return new Candidate(actionType, null, null, label, 0.0, breakdown, List.of(), constraints);
    }

    /** 发言候选：候选集里只放一条，具体文本由 LLM 生成（§1：怎么表达属于 LLM）。 */
    protected Candidate speakCandidate(RoleContext context, double exposureRisk, String label) {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put(StrategyCodes.FACTOR_TARGET, 0.0);
        breakdown.put(StrategyCodes.FACTOR_INFO_GAIN, 0.2);
        breakdown.put(StrategyCodes.FACTOR_PRESSURE, 0.3);
        breakdown.put(StrategyCodes.FACTOR_EXPOSURE, exposureRisk);
        return candidate(StrategyCodes.SPEAK, context.view(), null, label, breakdown,
                List.of("EXPRESSION"), List.of("发言文本由你生成；不得提及视图之外的私有事实"));
    }

    /** 因子表的可变副本，按固定顺序写入，便于测试与 Trace 断言。 */
    protected Map<String, Double> factors(
            double target, double infoGain, double pressure, double exposureRisk) {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put(StrategyCodes.FACTOR_TARGET, target);
        breakdown.put(StrategyCodes.FACTOR_INFO_GAIN, infoGain);
        breakdown.put(StrategyCodes.FACTOR_PRESSURE, pressure);
        breakdown.put(StrategyCodes.FACTOR_EXPOSURE, exposureRisk);
        return breakdown;
    }

    /** 座位到玩家 id 的解析；视图里没有该座位时返回 {@code null}（工具网关随后按身份拒绝）。 */
    protected static String playerIdOf(AgentView view, int seatNo) {
        if (view == null) {
            return null;
        }
        if (view.me() != null && view.me().seatNo() == seatNo) {
            return view.me().playerId();
        }
        return view.seat(seatNo).map(AgentView.SeatView::playerId).orElse(null);
    }

    /** 取前 N 个；N 来自 {@code strategy.candidateLimit}（§12）。 */
    protected static <T> List<T> top(List<T> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int size = Math.min(Math.max(1, limit), items.size());
        return new ArrayList<>(items.subList(0, size));
    }

    /** 存活的他人的座位号，升序（{@code AgentView.aliveOthers} 已保证顺序）。 */
    protected static List<Integer> aliveSeatNos(AgentView view) {
        List<Integer> seats = new ArrayList<>();
        if (view != null) {
            for (AgentView.SeatView seat : view.aliveOthers()) {
                seats.add(seat.seatNo());
            }
        }
        return seats;
    }

    /** 投票类候选：按怀疑度降序取前 N，附加弃权候选（本局允许弃权时）。 */
    protected List<Candidate> voteCandidates(
            RoleContext context, List<SuspicionAssessment> ranked, boolean abstainAllowed, String roleNote) {
        List<Ranked> ranking = new ArrayList<>();
        for (SuspicionAssessment assessment : ranked) {
            ranking.add(new Ranked(assessment.seatNo(), assessment.score(), assessment.tags()));
        }
        return voteCandidatesFrom(context, ranking, abstainAllowed, roleNote);
    }

    /**
     * 投票类候选的通用构造：狼人用威胁排序、村民用怀疑排序，但"取前 N + 弃权"的边界完全一致。
     *
     * @param ranking 已排序的候选目标（调用方保证顺序确定）
     */
    protected List<Candidate> voteCandidatesFrom(
            RoleContext context, List<Ranked> ranking, boolean abstainAllowed, String roleNote) {
        List<Candidate> candidates = new ArrayList<>();
        for (Ranked item : top(ranking, context.candidateLimit())) {
            candidates.add(candidate(
                    StrategyCodes.VOTE,
                    context.view(),
                    item.seatNo(),
                    "投票给 " + item.seatNo() + " 号（评分 "
                            + String.format(java.util.Locale.ROOT, "%.2f", item.score()) + "）",
                    factors(item.score(), 0.1, 0.9, 0.1),
                    mergeTags(item.tags(), "VOTE_TARGET"),
                    List.of(CONSTRAINT_TARGET_ALIVE, roleNote)));
        }
        if (abstainAllowed) {
            candidates.add(noTarget(StrategyCodes.VOTE, "弃权（不投票）",
                    factors(0.0, 0.0, 0.1, INACTION_EXPOSURE),
                    List.of("本局允许弃权（abstainAllowed）")));
        }
        return candidates;
    }

    /** 排序结果的最小形状：投票/毒药/开枪候选都只关心"目标 + 分数 + 标签"。 */
    protected record Ranked(int seatNo, double score, List<String> tags) {

        public Ranked {
            tags = List.copyOf(tags);
        }
    }

    protected static List<String> mergeTags(List<String> tags, String extra) {
        List<String> merged = new ArrayList<>(tags);
        if (!merged.contains(extra)) {
            merged.add(extra);
        }
        return merged;
    }

    /** 规则开关：键名与 {@code GameRules} 的字段名一致，缺省值取 domain-model.md §3 的默认值。 */
    protected static boolean ruleBool(AgentView view, String key, boolean fallback) {
        return view != null && view.ruleBool(key, fallback);
    }

    protected static Optional<Integer> seatOf(AgentView.SeatView seat) {
        return seat == null ? Optional.empty() : Optional.of(seat.seatNo());
    }
}
