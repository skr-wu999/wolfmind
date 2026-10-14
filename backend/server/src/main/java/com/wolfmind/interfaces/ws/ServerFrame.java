package com.wolfmind.interfaces.ws;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端到客户端的帧信封（websocket-protocol.md §4.1）。
 *
 * <p>六个字段全部必填：{@code v, id, type, ts, seq, payload}。{@code payload} 固定用空对象
 * 而不是 {@code null}——客户端按 §4.3 只从 {@code type} 分派，一个 null 会让
 * "没有参数的帧"与"帧损坏"无法区分。
 *
 * <p>{@code seq} 的语义（§4.3）：{@code GAME_EVENT} 的 seq 等于该事件的 {@code version}；
 * {@code GAME_SNAPSHOT_SYNC} 的 seq 等于同步点版本 {@code replayedTo}；其余帧的 seq
 * 是连接内单调递增（不参与状态推进）。**客户端只按 seq 排序，不看 type 之外的语义。**
 */
record ServerFrame(
        int v,
        String id,
        String type,
        long ts,
        long seq,
        Map<String, Object> payload) {

    /** 当前协议版本（§4.1）。 */
    static final int VERSION = 1;

    ServerFrame {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("帧 id 不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("帧 type 不能为空");
        }
        // 固定空对象而不是 null：见类注释。
        payload = payload == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }

    /** 构造一个非状态推进帧（seq 由调用方给连接内递增号）。 */
    static ServerFrame of(String id, String type, long ts, long seq) {
        return new ServerFrame(VERSION, id, type, ts, seq, Map.of());
    }

    /** 序列化为可直接发送的 Map；字段顺序与 §4.1 的表述顺序一致，便于比对文档。 */
    Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("v", v);
        wire.put("id", id);
        wire.put("type", type);
        wire.put("ts", ts);
        wire.put("seq", seq);
        wire.put("payload", payload);
        return wire;
    }

    /** 服务端帧类型目录（§5.2）。封闭集合：目录外的类型不允许出现（§13 校验清单）。 */
    enum Type {
        AUTH_OK,
        SUBSCRIBED,
        UNSUBSCRIBED,
        GAME_SNAPSHOT_SYNC,
        GAME_EVENT,
        AGENT_SPEECH_DELTA,
        SYNC_REQUIRED,
        ROOM_SNAPSHOT,
        PONG,
        ERROR
    }

    /** 关闭码表（§3.6）。 */
    enum CloseCode {
        /** 未认证：不重连，清会话跳登录。 */
        UNAUTHENTICATED(4401),
        /** 无权限：不重连该订阅。 */
        FORBIDDEN(4403),
        /** 认证/订阅/心跳超时：重连即可恢复。 */
        TIMEOUT(4408),
        /** 频率超限：退避起点改为 15s。 */
        RATE_LIMITED(4429),
        /** 服务端异常：重连并按 RESUME 补齐。 */
        INTERNAL(1011);

        private final int code;

        CloseCode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
