package com.wolfmind.application.game;

import com.wolfmind.application.port.IdempotencyPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 提交行动的用例输出（api-spec.md §5.3 的响应体）。
 *
 * <p>{@code accepted = true} 只表示"命令被领域接受并且事件已写入"；客户端必须等
 * {@code PLAYER_VOTED} 等事件到达后再更新界面（api-spec.md §5.3 的关键语义）。
 *
 * @param replayed 本次返回的是首次执行的结果（幂等命中），事件是历史事实而非本次新写入
 */
public record ActionResult(
        boolean accepted,
        String gameId,
        int version,
        List<String> eventIds,
        List<String> eventTypes,
        boolean replayed) {

    public ActionResult {
        eventIds = List.copyOf(eventIds);
        eventTypes = List.copyOf(eventTypes);
    }

    /** 命令被接受并已持久化事件。 */
    public static ActionResult accepted(
            String gameId, int version, List<String> eventIds, List<String> eventTypes) {
        return new ActionResult(true, gameId, version, eventIds, eventTypes, false);
    }

    /** 幂等命中：原样返回首次结果。 */
    public static ActionResult replayed(IdempotencyPort.RecordedResult recorded) {
        return new ActionResult(
                true,
                recorded.gameId(),
                recorded.version(),
                recorded.eventIds(),
                recorded.eventTypes(),
                true);
    }

    /**
     * 领域没有产生状态变化（例如 {@code advance} 判定无需推进）。
     *
     * <p>不写事件、不记幂等，客户端可安全重试；版本保持原值，避免出现"空提交推进版本"。
     */
    public static ActionResult unchanged(String gameId, int version) {
        return new ActionResult(false, gameId, version, List.of(), List.of(), false);
    }

    public boolean producedEvents() {
        return !eventIds.isEmpty();
    }

    /** 供 {@code IdempotencyPort} 记录首次结果；{@code resultDigest} 是结果的稳定指纹。 */
    public IdempotencyPort.RecordedResult toRecordedResult(String idempotencyKey) {
        return new IdempotencyPort.RecordedResult(
                gameId, idempotencyKey, version, eventIds, eventTypes, digest());
    }

    private String digest() {
        String canonical = version + "|" + String.join(",", eventIds) + "|" + String.join(",", eventTypes);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 必须提供 SHA-256", ex);
        }
    }
}
