package com.wolfmind.application.analysis;

import java.util.List;

/**
 * 单个座位在对局里的可核对统计（api-spec.md §7 的"每人统计"）。
 *
 * <p><b>每个字段都回答"它来自哪条事件"</b>，因为分析页的价值完全建立在"可核对"上：
 * 一个算不出来源的数字比没有数字更糟——它会被当成事实引用。
 *
 * <table>
 *   <caption>字段来源</caption>
 *   <tr><th>字段</th><th>来源</th></tr>
 *   <tr><td>{@code speechCount}</td><td>{@code PLAYER_SPOKE} 的 actorId 命中次数</td></tr>
 *   <tr><td>{@code voteCount} / {@code abstainCount}</td><td>{@code PLAYER_VOTED} 的 {@code abstain}</td></tr>
 *   <tr><td>{@code voteHitCount} / {@code voteAccuracy}</td><td>票的目标是否为狼人阵营；仅在全员角色已披露时有值</td></tr>
 *   <tr><td>{@code deathRound} / {@code survivedRounds}</td><td>{@code DEATH_ANNOUNCED} 的 round 与 {@code GAME_FINISHED.rounds}</td></tr>
 *   <tr><td>{@code decisionTraces} / {@code llmCalls} / token / 耗时 / 工具 / 兜底</td>
 *       <td>{@code AGENT_} 决策轨迹（不进入对局事件流）</td></tr>
 * </table>
 *
 * @param voteHitCount  投出的票中命中狼人阵营的票数；{@code null} 表示角色未披露，算不出来
 * @param voteAccuracy  {@code voteHitCount / voteCount}；没有投票或角色未披露时为 {@code null}
 * @param survivedRounds 完整存活的轮数：在 {@code deathRound} 出局则记 {@code deathRound - 1}，
 *                       存活到终局则记总轮数。用"轮"而不是"毫秒"是因为规则的推进单位是轮
 * @param fallbackTurns 由规则兜底完成的回合数（{@code AGENT_TURN_FINISHED.fallbackUsed = true}）
 */
public record AgentStat(
        String playerId,
        int seatNo,
        String controllerType,
        String controllerRefId,
        boolean alive,
        String role,
        String camp,
        int speechCount,
        int voteCount,
        int abstainCount,
        Integer voteHitCount,
        Double voteAccuracy,
        int survivedRounds,
        Integer deathRound,
        int decisionTraces,
        int llmCalls,
        int promptTokens,
        int completionTokens,
        int toolCalls,
        int fallbackTurns,
        long totalLlmLatencyMs,
        long maxLlmLatencyMs,
        List<Integer> voteTargetSeats) {

    public AgentStat {
        voteTargetSeats = List.copyOf(voteTargetSeats);
    }
}
