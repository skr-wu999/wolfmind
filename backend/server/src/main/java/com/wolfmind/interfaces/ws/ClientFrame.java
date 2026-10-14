package com.wolfmind.interfaces.ws;

import java.util.Map;

/**
 * 客户端到服务端的帧（websocket-protocol.md §4.2）。
 *
 * <p>信封只有三个字段：{@code id, type, payload}。**没有 {@code v} / {@code ts} / {@code seq}**——
 * 客户端不声明协议版本（服务端按 §12 的兼容规则发送），也不自增 seq
 * （seq 是状态推进序号，只能由服务端从事件版本推导）。
 *
 * <p>用 record + sealed 表达而不是 {@code Map<String,Object>}：帧类型与它的 payload 形状是
 * **编译期**契约的一部分。用 Map 的话，"AUTH 帧忘了带 token"要到运行时才发现，
 * 而这里它连编译都过不了。
 */
sealed interface ClientFrame {

    /** 客户端生成的帧标识；服务端在 ERROR 的 details 中回引它。 */
    String id();

    /** 帧类型字符串（大写下划线，取值见 §5.1）。 */
    String type();

    /** 原始 payload；未解析成具体帧时保留原样。 */
    Map<String, Object> payload();

    /** {@code AUTH}：连接后 3 秒内必须发送，且只发一次。 */
    record Auth(String id, String token) implements ClientFrame {

        @Override
        public String type() {
            return "AUTH";
        }

        @Override
        public Map<String, Object> payload() {
            // 令牌**不进日志**：payload() 只用于诊断与序列化，不用于审计。
            return Map.of("token", token);
        }
    }

    /** {@code SUBSCRIBE}：订阅一个对局；{@code lastSeq} 缺省为 0（要求全量快照）。 */
    record Subscribe(String id, String gameId, long lastSeq) implements ClientFrame {

        @Override
        public String type() {
            return "SUBSCRIBE";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of("gameId", gameId, "lastSeq", lastSeq);
        }
    }

    /** {@code RESUME}：重连后补齐缺口；语义与 SUBSCRIBE 相同，只是意图更明确。 */
    record Resume(String id, String gameId, long lastSeq) implements ClientFrame {

        @Override
        public String type() {
            return "RESUME";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of("gameId", gameId, "lastSeq", lastSeq);
        }
    }

    /** {@code UNSUBSCRIBE}：取消订阅。 */
    record Unsubscribe(String id, String gameId) implements ClientFrame {

        @Override
        public String type() {
            return "UNSUBSCRIBE";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of("gameId", gameId);
        }
    }

    /** {@code ROOM_SUBSCRIBE}：订阅等待房；服务端立即回一帧 {@code ROOM_SNAPSHOT}，之后房间每次变化再推一帧。 */
    record RoomSubscribe(String id, String roomId) implements ClientFrame {

        @Override
        public String type() {
            return "ROOM_SUBSCRIBE";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of("roomId", roomId);
        }
    }

    /** {@code ROOM_UNSUBSCRIBE}：取消房间订阅；无应答帧。 */
    record RoomUnsubscribe(String id, String roomId) implements ClientFrame {

        @Override
        public String type() {
            return "ROOM_UNSUBSCRIBE";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of("roomId", roomId);
        }
    }

    /** {@code PING}：心跳；服务端必须回 PONG。 */
    record Ping(String id) implements ClientFrame {

        @Override
        public String type() {
            return "PING";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of();
        }
    }

    /** {@code ACK}：确认已处理到某个 seq，只影响服务端积压缓冲，不影响领域状态。 */
    record Ack(String id, long seq) implements ClientFrame {

        @Override
        public String type() {
            return "ACK";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of("seq", seq);
        }
    }
}