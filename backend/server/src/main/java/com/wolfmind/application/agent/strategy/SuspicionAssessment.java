package com.wolfmind.application.agent.strategy;

import java.util.List;
import java.util.Map;

/**
 * 村民阵营视角对某个座位的怀疑评估（strategy-layer.md §4.2）。
 *
 * <p>与 {@link ThreatAssessment} 对称：总分 0..1，五个分项可解释，标签供复盘。
 * 差别在于真值来源——对预言家来说，"宣称矛盾"这一项直接读自己的查验结果，
 * 而对其他角色只能从公开事实推断（§4.2 末段）。
 *
 * @param seatNo  被评估的座位
 * @param score   总分 0..1
 * @param factors 分项得分，键见本类的常量
 * @param tags    可解释标签（{@code CHECKED_WOLF}、{@code HOSTILE_VOTE}…）
 */
public record SuspicionAssessment(int seatNo, double score, Map<String, Double> factors, List<String> tags) {

    /** §4.2 的因子名。 */
    public static final String CONTRADICTION = "claimContradiction";
    public static final String VOTE_CONSISTENCY = "voteConsistency";
    public static final String EVASION = "speechEvasion";
    public static final String LINK = "exileLink";
    public static final String TIMELINE = "timelineShift";
    /** 预言家查验真值对总分的单调修正量；无真值的座位恒为 0。 */
    public static final String TRUTH_ADJUSTMENT = "truthAdjustment";

    public SuspicionAssessment {
        factors = Map.copyOf(factors);
        tags = List.copyOf(tags);
    }

    public double factor(String name) {
        return factors.getOrDefault(name, 0.0);
    }
}
