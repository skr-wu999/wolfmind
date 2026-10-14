package com.wolfmind.application.analysis;

/**
 * 概览里的"关键事件"一行。
 *
 * <p>{@code summary} 由服务端按**公开事实**生成，不是把 {@code payload} 原文透给前端：
 * 分析页要的是"发生了什么"，而不是让客户端再实现一遍事件语义（那必然与领域漂移）。
 * 原文仍在回放接口里（{@code GET /games/{id}/replay}），两处职责不同。
 *
 * @param seatNo 事件的主角座位；无单一主角（阶段跳转、计票结果）时为 {@code null}
 */
public record KeyEventView(
        int version,
        String type,
        String phase,
        int round,
        long occurredAt,
        Integer seatNo,
        String summary) {}
