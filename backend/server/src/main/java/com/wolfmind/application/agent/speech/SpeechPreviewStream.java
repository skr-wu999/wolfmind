package com.wolfmind.application.agent.speech;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 一次模型调用的发言预览：把流式 token 变成按小句推送的 {@link SpeechPreviewPort.SpeechPreview}。
 *
 * <p>预览先于 {@code leakCheck} 节点到达观众，因此信息边界在这里逐段守：
 * 只在小句边界推送，且已累积的全文与待推送的前缀都必须通过泄密检查；任何一次不通过，
 * 本条预览立即停止且不再发 {@code final}，客户端等最终的 {@code PLAYER_SPOKE} 取代它
 * （websocket-protocol.md D-2 / D-4）。
 *
 * <p>token 回调在模型客户端的线程上，{@link #complete} 在 Agent 线程上（超时时两者可能交错），
 * 因此入口方法同步；{@code complete} 之后迟到的 token 被忽略。
 */
public final class SpeechPreviewStream implements Consumer<String> {

    private static final String CLAUSE_BOUNDARIES = "。！？!?\n；;，,";

    private final SpeechPreviewPort port;
    private final String gameId;
    private final String playerId;
    private final int seatNo;
    private final int expectedVersion;
    private final Predicate<String> leakFree;

    private final SpeechFieldExtractor extractor = new SpeechFieldExtractor();
    private final StringBuilder speech = new StringBuilder();
    private int emittedLength;
    private String emittedText = "";
    private int nextIndex;
    private boolean stopped;

    public SpeechPreviewStream(
            SpeechPreviewPort port,
            String gameId,
            String playerId,
            int seatNo,
            int expectedVersion,
            Predicate<String> leakFree) {
        this.port = Objects.requireNonNull(port, "port");
        this.gameId = gameId;
        this.playerId = playerId;
        this.seatNo = seatNo;
        this.expectedVersion = expectedVersion;
        this.leakFree = Objects.requireNonNull(leakFree, "leakFree");
    }

    @Override
    public synchronized void accept(String token) {
        if (stopped) {
            return;
        }
        String decoded = extractor.feed(token);
        if (decoded.isEmpty()) {
            return;
        }
        speech.append(decoded);
        int boundary = lastBoundary();
        if (boundary > emittedLength) {
            emitUpTo(boundary, false);
        }
    }

    /**
     * 模型调用结束。
     *
     * @param succeeded 调用是否成功拿到完整输出；失败时不补发 {@code final}
     */
    public synchronized void complete(boolean succeeded) {
        if (stopped || !succeeded || !extractor.speechClosed() || speech.isEmpty()) {
            stopped = true;
            return;
        }
        emitUpTo(speech.length(), true);
        stopped = true;
    }

    /** 已推送的段数；测试与 Trace 用。 */
    public synchronized int emittedCount() {
        return nextIndex;
    }

    private int lastBoundary() {
        for (int i = speech.length() - 1; i >= emittedLength; i--) {
            if (CLAUSE_BOUNDARIES.indexOf(speech.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    private void emitUpTo(int end, boolean last) {
        String prefix = speech.substring(0, end);
        if (!leakFree.test(speech.toString()) || !leakFree.test(prefix)) {
            stopped = true;
            return;
        }
        if (!last && !PublicSpeechText.previewReady(prefix)) return;
        String display = PublicSpeechText.clean(prefix);
        if (!display.startsWith(emittedText)) {
            stopped = true;
            return;
        }
        String delta = display.substring(emittedText.length());
        emittedLength = end;
        emittedText = display;
        if (delta.isEmpty() && (!last || display.isEmpty())) return;
        try {
            port.publish(new SpeechPreviewPort.SpeechPreview(
                    gameId, playerId, seatNo, expectedVersion, delta, nextIndex++, last));
        } catch (RuntimeException deliveryFailed) {
            // 预览投递失败不能影响回合：停掉本条预览，最终发言仍走 PLAYER_SPOKE。
            stopped = true;
        }
    }
}
