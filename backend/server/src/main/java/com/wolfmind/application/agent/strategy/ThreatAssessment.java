package com.wolfmind.application.agent.strategy;

import java.util.List;
import java.util.Map;

/**
 * 狼队视角对某个座位的威胁评估（strategy-layer.md §4.1 的
 * {@code ThreatAssessment{seatNo, score, tags[]}}）。
 *
 * <p>{@code factors} 保留五个分项，是为了让复盘能回答"为什么把他排前面"，
 * 而不是只看到一个黑箱总分。总分与分项都是 {@code 0..1}，方便实验室跨对局比较。
 *
 * @param seatNo  被评估的座位
 * @param score   总分 0..1
 * @param factors 分项得分，键见 {@link StrategyCodes} 的因子名与 §4.1 的因子
 * @param tags    可解释标签（{@code SEER_CLAIM}、{@code HIGH_THREAT}…）
 */
public record ThreatAssessment(int seatNo, double score, Map<String, Double> factors, List<String> tags) {

    /** §4.1 的因子名。 */
    public static final String DIVINE_SUSPECT = "divineSuspect";
    public static final String INFORMATION = "information";
    public static final String INFLUENCE = "influence";
    public static final String SURVIVAL = "survival";
    public static final String KILL_SAFETY = "killSafety";

    public ThreatAssessment {
        factors = Map.copyOf(factors);
        tags = List.copyOf(tags);
    }

    public double factor(String name) {
        return factors.getOrDefault(name, 0.0);
    }

    /** 击杀该座位的暴露风险，{@code killSafety} 的补数；{@code DecisionPolicy} 的暴露项用它。 */
    public double killRisk() {
        return 1.0 - factor(KILL_SAFETY);
    }
}
