package com.wolfmind.application.analysis;

import java.util.List;

/**
 * 单个座位的决策轨迹视图（api-spec.md §7 的
 * {@code GET /games/{gameId}/analysis/agents/{playerId}}）。
 *
 * <p><b>它不是模型思维过程。</b>agent-runtime.md §12 与 AGENTS.md §8 都明确：
 * 不得声称能读到模型的私有推理。这里暴露的全部内容都是**运行时可观察的事实**：
 * 候选集与打分（策略层代码算的）、工具调用与结果、token 与耗时、是否兜底。
 * 模型原文只以"最终提交的发言/动作"的形式出现在对局事件里。
 *
 * @param disclosure 同 {@link AnalysisSummary#disclosure()}：进行中不给轨迹（轨迹是最强的事实披露）
 */
public record AgentAnalysis(
        String gameId,
        String playerId,
        int seatNo,
        String role,
        String camp,
        String controllerType,
        String controllerRefId,
        String disclosure,
        List<DecisionTraceView> traces,
        String note) {

    public AgentAnalysis {
        traces = List.copyOf(traces);
    }

    /**
     * 一条决策轨迹。
     *
     * <p>字段按 {@code kind} 分组使用，不适用时一律为 {@code null}——
     * 用一个宽记录而不是九个子类，是因为前端要按时间轴统一渲染，而多态在 JSON 上会变成
     * 需要客户端 switch 的联合类型，多一次实现就多一次漂移。
     *
     * @param kind        {@code TURN_STARTED} / {@code CONTEXT} / {@code STRATEGY} / {@code LLM} /
     *                    {@code TOOL} / {@code REJECTED} / {@code TIMEOUT} / {@code FAILED} / {@code FINISHED}
     * @param candidates  仅 {@code STRATEGY}：策略层给出的候选集与打分
     * @param chosenAction 仅 {@code FINISHED}：最终动作类型
     * @param submitted   仅 {@code FINISHED}：该回合是否真的提交成功
     * @param reason      仅 {@code REJECTED}：拦截原因；复读拦截写成"与 N 号第 R 轮发言相似度 0.xx"
     * @param draft       仅复读拦截（{@code stage} 为 {@code ECHO_CHECK} / {@code ECHO_CHECK_REWRITE}）：
     *                    被拦下的发言稿。泄密拦截从不记录原稿
     */
    public record DecisionTraceView(
            String id,
            String kind,
            int version,
            String phase,
            int round,
            long occurredAt,
            List<CandidateView> candidates,
            String chosenAction,
            Integer chosenTargetSeatNo,
            Boolean submitted,
            Boolean fallbackUsed,
            Integer llmCalls,
            Integer repairRounds,
            String model,
            String provider,
            Integer promptTokens,
            Integer completionTokens,
            Long llmLatencyMs,
            String tool,
            String toolArgsDigest,
            String toolDecision,
            String denialCode,
            String stage,
            String reason,
            String draft) {

        public DecisionTraceView {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** 策略层的一个候选：动作、目标座位与打分（{@code strategy-layer.md} 的候选集形状）。 */
    public record CandidateView(String actionType, Integer targetSeatNo, double score) {}
}
