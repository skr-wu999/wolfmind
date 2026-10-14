package com.wolfmind.application.analysis;

import java.util.List;

/**
 * 对局分析概览（api-spec.md §7 的 {@code GET /games/{gameId}/analysis/summary}）。
 *
 * <p><b>{@code disclosure} 是这份响应的核心字段</b>，取 {@code FULL} 或 {@code PUBLIC}：
 * 进行中的对局只给公开事实（角色为 {@code null}、命中率为 {@code null}），
 * 终局后按 2026-10-12 批准的披露政策给完整记录。判断只由服务端做，
 * 客户端不得根据"自己是不是参与者"重新推导（AP-6）。
 *
 * @param disclosure   {@code FULL} 表示角色与由其派生的指标可用；{@code PUBLIC} 表示仅公开事实
 * @param winner       胜方阵营；未结束为 {@code null}
 * @param finishReason 终局原因码（{@code GAME_FINISHED.reason} 或 {@code GAME_ABORTED.reason}）
 * @param truncated    {@code true} 表示可见事件数超过读取上限，统计基于前 {@code MAX_EVENTS} 条
 * @param provenance   数据来源说明，直接展示在页脚（"数字从哪来"）
 */
public record AnalysisSummary(
        String gameId,
        String status,
        String disclosure,
        String winner,
        String finishReason,
        int rounds,
        int version,
        long startedAt,
        Long finishedAt,
        List<AgentStat> players,
        List<KeyEventView> keyEvents,
        boolean truncated,
        String provenance) {

    public AnalysisSummary {
        players = List.copyOf(players);
        keyEvents = List.copyOf(keyEvents);
    }
}
