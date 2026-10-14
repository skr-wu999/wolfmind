package com.wolfmind.application.agent.speech;

/**
 * AI 发言的流式预览出口（websocket-protocol.md 的 {@code AGENT_SPEECH_DELTA}）。
 *
 * <p>预览是展示更新，不是领域事件：不写事件流、不推进版本，丢失或中断都不影响对局
 * （AGENTS.md §9）。权威内容只来自最终提交的 {@code PLAYER_SPOKE}。
 *
 * <p>Agent 侧只知道自己的回合版本；与 {@code PLAYER_SPOKE.speechId} 对齐的标识由实现按领域规则派生。
 */
public interface SpeechPreviewPort {

    SpeechPreviewPort NONE = preview -> { };

    void publish(SpeechPreview preview);

    /**
     * 一段预览。
     *
     * @param expectedVersion 本回合提交时的期望版本；发言被接受时，{@code PLAYER_SPOKE} 的版本是它加一
     * @param delta           本段文本
     * @param index           同一条发言内的段序号，从 0 开始
     * @param last            预览是否结束
     */
    record SpeechPreview(
            String gameId,
            String playerId,
            int seatNo,
            int expectedVersion,
            String delta,
            int index,
            boolean last) {}
}
