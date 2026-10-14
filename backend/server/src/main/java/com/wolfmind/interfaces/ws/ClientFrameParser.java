package com.wolfmind.interfaces.ws;

import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.json.JsonException;

import java.util.Map;

/**
 * 客户端帧的 JSON 解析（websocket-protocol.md §4.2）。
 *
 * <p>解析失败**不抛异常给传输层**，而是返回 {@link Malformed}：帧格式错误是客户端问题，
 * 应该回一个 {@code ERROR} 帧让它自己修，而不是把连接炸掉。抛异常会让一个手写的
 * {@code {"type":"PING"}（漏了 id）} 直接断开整条连接。
 *
 * <p>字段类型必须严格：{@code lastSeq} 是数字而不是字符串。宽松转换（"5" → 5）会让
 * 客户端长期用错误类型而不自知，直到某次服务端升级才崩。
 */
final class ClientFrameParser {

    private ClientFrameParser() {}

    /** 解析结果：成功是具体帧，失败是带原因的可回帧错误。 */
    sealed interface Result {

        record Ok(ClientFrame frame) implements Result {}

        /** {@code code} 直接进 ERROR 帧，便于客户端定位是哪个字段错了。 */
        record Malformed(String code, String message) implements Result {}
    }

    static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return new Result.Malformed("VALIDATION_ERROR", "帧不能为空");
        }
        Map<String, Object> root;
        try {
            root = Json.readObject(text);
        } catch (JsonException error) {
            return new Result.Malformed("VALIDATION_ERROR", "帧不是合法 JSON：" + error.getMessage());
        }
        if (root == null) {
            return new Result.Malformed("VALIDATION_ERROR", "帧必须是 JSON 对象");
        }

        String id = text(root, "id");
        if (id == null) {
            return new Result.Malformed("VALIDATION_ERROR", "帧缺少字段 id");
        }
        String type = text(root, "type");
        if (type == null) {
            return new Result.Malformed("VALIDATION_ERROR", "帧缺少字段 type");
        }
        // payload 允许缺失（PING 没有参数），但一旦出现必须是对象。
        Map<String, Object> payload = Map.of();
        Object rawPayload = root.get("payload");
        if (rawPayload != null) {
            if (!(rawPayload instanceof Map<?, ?> map)) {
                return new Result.Malformed("VALIDATION_ERROR", "字段 payload 必须是 JSON 对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            payload = typed;
        }

        return switch (type) {
            case "AUTH" -> {
                String token = text(payload, "token");
                yield token == null
                        ? new Result.Malformed("VALIDATION_ERROR", "AUTH 缺少 payload.token")
                        : new Result.Ok(new ClientFrame.Auth(id, token));
            }
            case "SUBSCRIBE" -> subscribe(id, payload, false);
            case "RESUME" -> subscribe(id, payload, true);
            case "UNSUBSCRIBE" -> {
                String gameId = text(payload, "gameId");
                yield gameId == null
                        ? new Result.Malformed("VALIDATION_ERROR", "UNSUBSCRIBE 缺少 payload.gameId")
                        : new Result.Ok(new ClientFrame.Unsubscribe(id, gameId));
            }
            case "ROOM_SUBSCRIBE", "ROOM_UNSUBSCRIBE" -> {
                String roomId = text(payload, "roomId");
                if (roomId == null) {
                    yield new Result.Malformed("VALIDATION_ERROR", type + " 缺少 payload.roomId");
                }
                yield new Result.Ok("ROOM_SUBSCRIBE".equals(type)
                        ? new ClientFrame.RoomSubscribe(id, roomId)
                        : new ClientFrame.RoomUnsubscribe(id, roomId));
            }
            case "PING" -> new Result.Ok(new ClientFrame.Ping(id));
            case "ACK" -> {
                Long seq = number(payload, "seq");
                yield seq == null
                        ? new Result.Malformed("VALIDATION_ERROR", "ACK 缺少 payload.seq")
                        : new Result.Ok(new ClientFrame.Ack(id, seq));
            }
            // 目录外的帧类型（§5.1）不回显 type，避免客户端以为它被支持。
            default -> new Result.Malformed("VALIDATION_ERROR", "未知的帧类型：" + type);
        };
    }

    /** SUBSCRIBE 与 RESUME 的形状完全一致（§5.1），只是意图不同。 */
    private static Result subscribe(String id, Map<String, Object> payload, boolean resume) {
        String gameId = text(payload, "gameId");
        if (gameId == null) {
            return new Result.Malformed("VALIDATION_ERROR", "SUBSCRIBE/RESUME 缺少 payload.gameId");
        }
        // lastSeq 缺省为 0：表示"我没有本地状态，请给全量"（§8.2）。
        Long lastSeq = number(payload, "lastSeq");
        long from = lastSeq == null ? 0L : lastSeq;
        if (from < 0) {
            return new Result.Malformed("VALIDATION_ERROR", "lastSeq 不能为负");
        }
        return new Result.Ok(resume
                ? new ClientFrame.Resume(id, gameId, from)
                : new ClientFrame.Subscribe(id, gameId, from));
    }

    private static String text(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static Long number(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        // 字符串数字刻意不接受：宽松转换会让客户端长期用错类型而不自知。
        return null;
    }
}
