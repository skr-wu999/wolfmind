package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 决策打分（strategy-layer.md §4.3）。
 *
 * <pre>
 * score = w1 · targetScore        // 威胁或嫌疑
 *       + w2 · consistencyScore   // 与本阵营历史行为的一致性
 *       - w3 · exposureRisk       // 暴露风险（狼队更敏感）
 *       + w4 · infoGainScore      // 该选择带来的信息收益
 *       + w5 · pressureScore      // 对局势的推进（避免平票、避免无效票）
 * </pre>
 *
 * <p>两个"文档给了结构、没给数值"的地方在这里定案，并写进注释以便复核：
 * <ul>
 *   <li><b>三档权重</b>（§4.3 只说"权重来自 {@code agentConfig.strategyProfile}"）：
 *       每一档五个权重之和都是 1，档位差异体现在"多看重目标还是多规避暴露"上——进攻档
 *       提高目标权重、降低暴露权重，保守档反过来；</li>
 *   <li><b>暴露项的角色差异</b>：{@code strategy.exposureAversion}（默认 0.5）是狼队的
 *       暴露规避强度，因此只对狼人阵营生效，且默认值恰好还原档位权重
 *       （倍数 {@code 2 × exposureAversion}），设为 0 表示完全不规避、设为 1 表示加倍规避。</li>
 * </ul>
 *
 * <p>确定性：全部因子来自候选自身的 {@code breakdown} 与只读事实；排序为
 * "总分降序 → 无目标排后 → {@link TieBreak} → 座位号 → 动作类型"的全序（§13）。
 * 平局键按动作类型区分用途，预言家查验与狼刀的平局因此不会落在同一个座位上。
 * 无目标候选（空刀、弃权、不用药、放弃开枪）没有座位号，在平局时排在等价的有目标候选之后，
 * 这是为了让"平局"这一个词在所有候选上都有定义，而不是给不作为偷偷加权。
 */
public final class DecisionPolicy {

    /** 一档策略的五个权重（§4.3）。 */
    public record Weights(double target, double consistency, double exposure, double infoGain, double pressure) {

        public Weights {
            for (double value : new double[] {target, consistency, exposure, infoGain, pressure}) {
                if (value < 0 || value > 1 || Double.isNaN(value)) {
                    throw new IllegalArgumentException("打分权重必须在 0..1 之间");
                }
            }
        }

        public double sum() {
            return target + consistency + exposure + infoGain + pressure;
        }
    }

    /** 进攻档：更看重目标收益，暴露顾虑最小。 */
    public static final Weights AGGRESSIVE = new Weights(0.45, 0.15, 0.10, 0.15, 0.15);

    /** 默认档：五因子平衡。 */
    public static final Weights BALANCED = new Weights(0.40, 0.20, 0.15, 0.15, 0.10);

    /** 保守档：宁可少收益，也要降低暴露与被针对的风险。 */
    public static final Weights CONSERVATIVE = new Weights(0.30, 0.25, 0.25, 0.10, 0.10);

    /**
     * 候选的全序：总分降序 → 无目标排后 → 按动作类型分用途的 {@link TieBreak} → 座位号 → 动作类型。
     *
     * <p>它是兜底与候选渲染的共用排序，任何"取最高分"的地方都用它，保证同一个候选集
     * 在任何组件里选出同一个第一名。
     */
    public static Comparator<Candidate> order(AgentView view) {
        Map<String, TieBreak> byAction = new HashMap<>();
        return Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.targetSeatNo() == null)
                .thenComparingLong(candidate -> candidate.targetSeatNo() == null
                        ? 0L
                        : byAction.computeIfAbsent(String.valueOf(candidate.actionType()),
                                action -> TieBreak.of(view, action)).key(candidate.targetSeatNo()))
                .thenComparingInt(candidate -> candidate.targetSeatNo() == null
                        ? Integer.MAX_VALUE : candidate.targetSeatNo())
                .thenComparing(Candidate::actionType);
    }

    private final ThreatModel threatModel;

    public DecisionPolicy(ThreatModel threatModel) {
        this.threatModel = threatModel == null ? new ThreatModel() : threatModel;
    }

    /** 档位到权重表；未知档位回落到默认档（{@code AgentConfig} 已保证非空）。 */
    public static Weights weightsFor(AgentConfig.StrategyProfile profile) {
        if (profile == null) {
            return BALANCED;
        }
        return switch (profile) {
            case AGGRESSIVE -> AGGRESSIVE;
            case CONSERVATIVE -> CONSERVATIVE;
            case BALANCED -> BALANCED;
        };
    }

    /** 给候选集打总分并按 {@link #order} 排序；输入顺序不影响输出。 */
    public List<Candidate> score(
            AgentView view, String role, AgentConfig config, List<Candidate> candidates, FactStore facts) {
        List<Candidate> scored = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return scored;
        }
        Weights weights = weightsFor(config == null ? null : config.strategyProfile());
        boolean wolfCamp = CampRules.isWolfCamp(CampRules.campOfRole(role));
        double exposureWeight = weights.exposure() * exposureAversionFactor(config, wolfCamp);

        for (Candidate candidate : candidates) {
            double target = clamp01(factor(candidate, StrategyCodes.FACTOR_TARGET, 0.0));
            double infoGain = clamp01(factor(candidate, StrategyCodes.FACTOR_INFO_GAIN, 0.2));
            double pressure = clamp01(factor(candidate, StrategyCodes.FACTOR_PRESSURE, 0.3));
            double exposure = clamp01(factor(candidate, StrategyCodes.FACTOR_EXPOSURE,
                    defaultExposure(candidate, view, facts, wolfCamp)));
            double consistency = clamp01(consistency(candidate, view, wolfCamp, facts));

            double score = weights.target() * target
                    + weights.consistency() * consistency
                    - exposureWeight * exposure
                    + weights.infoGain() * infoGain
                    + weights.pressure() * pressure;

            Map<String, Double> breakdown = new LinkedHashMap<>();
            breakdown.put(StrategyCodes.FACTOR_TARGET, target);
            breakdown.put(StrategyCodes.FACTOR_CONSISTENCY, consistency);
            breakdown.put(StrategyCodes.FACTOR_EXPOSURE, exposure);
            breakdown.put(StrategyCodes.FACTOR_INFO_GAIN, infoGain);
            breakdown.put(StrategyCodes.FACTOR_PRESSURE, pressure);
            scored.add(candidate.withScore(score, breakdown));
        }
        scored.sort(order(view));
        return scored;
    }

    /**
     * 与本阵营历史行为的一致性（§4.3 的 {@code consistencyScore}）。
     *
     * <p>两项证据：自己上一轮是否就指向这个目标（立场连续），以及本阵营的票是否也指向这里。
     * "本阵营"对狼队是我与可见队友，对村民阵营是当前轮的公开票方向——村民阵营无法在视图里
     * 枚举出所有队友，只能用公开共识作为替代口径（这一点已在交付报告中留痕）。
     */
    private double consistency(Candidate candidate, AgentView view, boolean wolfCamp, FactStore facts) {
        if (!candidate.hasTarget() || view == null || view.me() == null) {
            return 0.5;
        }
        int seatNo = candidate.targetSeatNo();
        Integer myLastVote = facts.lastVoteTargetOf(view.me().seatNo()).orElse(null);
        double continuation = myLastVote == null ? 0.5 : (myLastVote == seatNo ? 1.0 : 0.0);

        double campAlignment;
        if (wolfCamp) {
            Set<Integer> camp = new LinkedHashSet<>();
            camp.add(view.me().seatNo());
            camp.addAll(facts.knownTeammates());
            int total = 0;
            int aligned = 0;
            for (int voterSeat : camp) {
                for (com.wolfmind.application.agent.model.AgentContext.VoteFact vote : facts.votesBy(voterSeat)) {
                    if (vote.abstain() || vote.targetSeat() == null) {
                        continue;
                    }
                    total++;
                    if (vote.targetSeat() == seatNo) {
                        aligned++;
                    }
                }
            }
            campAlignment = total == 0 ? 0.5 : aligned / (double) total;
        } else {
            Map<Integer, Integer> tally = new TreeMap<>(facts.voteTally());
            int total = tally.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) {
                campAlignment = 0.5;
            } else {
                int max = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                List<Integer> leaders = tally.entrySet().stream()
                        .filter(entry -> entry.getValue() == max)
                        .map(Map.Entry::getKey)
                        .toList();
                campAlignment = leaders.size() == 1 && leaders.get(0) == seatNo ? 1.0 : 0.0;
            }
        }
        return 0.6 * continuation + 0.4 * campAlignment;
    }

    /** 候选没有自带暴露项时的兜底：狼队看刀口暴露，村民阵营看是否误伤公开可信者。 */
    private double defaultExposure(Candidate candidate, AgentView view, FactStore facts, boolean wolfCamp) {
        if (!candidate.hasTarget() || view == null) {
            return 0.3;
        }
        int seatNo = candidate.targetSeatNo();
        if (wolfCamp && StrategyCodes.WOLF_KILL.equals(StrategyCodes.normalize(candidate.actionType()))) {
            return threatModel.killRisk(view, facts, seatNo);
        }
        return facts.publiclyTrusted(seatNo) ? 0.5 : 0.15;
    }

    /** 暴露权重的角色缩放：只对狼队生效，默认 0.5 的 {@code exposureAversion} 还原档位权重。 */
    private double exposureAversionFactor(AgentConfig config, boolean wolfCamp) {
        if (!wolfCamp) {
            return 1.0;
        }
        double aversion = config == null ? 0.5 : config.exposureAversion();
        return Math.max(0.0, Math.min(2.0, 2.0 * aversion));
    }

    private static double factor(Candidate candidate, String name, double fallback) {
        Double value = candidate.breakdown().get(name);
        return value == null ? fallback : value;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
