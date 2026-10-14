package com.wolfmind.application.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 一个候选动作（strategy-layer.md §4.3、§5）。
 *
 * <p>候选集是"代码控制的合法空间"：LLM 只能在其中取舍（{@code DESIGN.md} §11）。
 * 每条候选携带可解释的标签与命中的硬约束，既能拼进 prompt，也能进 Trace 供复盘。
 *
 * @param actionType   动作类型（如 {@code VOTE}、{@code WOLF_KILL}、{@code SPEAK}）
 * @param targetSeatNo 目标座位；{@code null} 表示"无目标"（弃权、空刀、不用药、跳过）
 * @param targetId     目标玩家 id，与 {@code targetSeatNo} 同源
 * @param label        给模型看的一行说明（不含隐藏信息）
 * @param score        {@code DecisionPolicy} 的总分
 * @param breakdown    分项得分，键为因子名（{@code targetScore} / {@code exposureRisk} …）
 * @param tags         可解释标签（{@code SEER_SUSPECT}、{@code HIGH_THREAT} …）
 * @param constraints  该候选命中的硬约束说明，进 prompt 的 {@code CANDIDATES} 分区
 */
public record Candidate(
        String actionType,
        Integer targetSeatNo,
        String targetId,
        String label,
        double score,
        Map<String, Double> breakdown,
        List<String> tags,
        List<String> constraints) {

    public Candidate {
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("候选的 actionType 不能为空");
        }
        breakdown = Map.copyOf(breakdown);
        tags = List.copyOf(tags);
        constraints = List.copyOf(constraints);
    }

    public static Candidate of(String actionType, Integer targetSeatNo, String targetId, String label) {
        return new Candidate(actionType, targetSeatNo, targetId, label, 0.0, Map.of(), List.of(), List.of());
    }

    /** 无目标候选（弃权、空刀、放弃开枪、不用药、跳过）。 */
    public static Candidate noTarget(String actionType, String label) {
        return new Candidate(actionType, null, null, label, 0.0, Map.of(), List.of(), List.of());
    }

    public Candidate withScore(double newScore, Map<String, Double> newBreakdown) {
        return new Candidate(
                actionType, targetSeatNo, targetId, label, newScore, newBreakdown, tags, constraints);
    }

    public Candidate withTags(List<String> newTags) {
        return new Candidate(
                actionType, targetSeatNo, targetId, label, score, breakdown, newTags, constraints);
    }

    public Candidate withConstraints(List<String> newConstraints) {
        return new Candidate(
                actionType, targetSeatNo, targetId, label, score, breakdown, tags, newConstraints);
    }

    public boolean hasTarget() {
        return targetSeatNo != null;
    }

    /** 该候选是否指向给定座位。 */
    public boolean targets(int seatNo) {
        return targetSeatNo != null && targetSeatNo == seatNo;
    }
}
