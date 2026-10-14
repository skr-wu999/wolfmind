package com.wolfmind.interfaces.ws;

import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import com.wolfmind.domain.game.event.PlayerSpokeEvent;

import java.util.Objects;

/**
 * 把 Agent 的发言预览投递成 {@code AGENT_SPEECH_DELTA}。
 *
 * <p>{@code speechId} 按领域规则从回合期望版本派生：发言被接受时 {@code PLAYER_SPOKE} 的版本是期望版本加一，
 * 因此预览与最终发言共用同一个标识（websocket-protocol.md D-4）。回合版本过期时提交会被拒绝，
 * 这条预览不会被任何 {@code PLAYER_SPOKE} 取代，客户端按 D-2 丢弃。
 */
public final class WebSocketSpeechPreview implements SpeechPreviewPort {

    private final WebSocketGateway gateway;

    public WebSocketSpeechPreview(WebSocketGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public void publish(SpeechPreview preview) {
        gateway.onSpeechPreview(
                preview.gameId(),
                preview.playerId(),
                preview.seatNo(),
                PlayerSpokeEvent.speechIdFor(preview.gameId(), preview.expectedVersion() + 1),
                preview.delta(),
                preview.index(),
                preview.last());
    }
}
