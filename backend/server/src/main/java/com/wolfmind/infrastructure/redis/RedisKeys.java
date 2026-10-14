package com.wolfmind.infrastructure.redis;

/**
 * 键名映射（redis-schema.md §2）。
 *
 * <p>{@code EventStorePort} 与 {@code GameLockPort} 的第一个参数是"流派名"：对局直接用
 * {@code gameId}，房间与账号带前缀 {@code room:{id}}、{@code user:{id}}。这里把流派名映射到
 * 键空间：{@code game:{id}:events}、{@code room:{id}:events}、{@code user:{id}:events}。
 */
final class RedisKeys {

    static final String ROOM_PREFIX = "room:";
    static final String USER_PREFIX = "user:";
    static final String GAME_PREFIX = "game:";
    static final String EVENTS_SUFFIX = ":events";

    private RedisKeys() {}

    /** 流派名对应的键前缀，不含末尾冒号：{@code game:g_1}、{@code room:r_1}、{@code user:u_1}。 */
    static String scope(String partition) {
        if (partition == null || partition.isBlank()) {
            throw new IllegalArgumentException("流派名不能为空");
        }
        if (partition.startsWith(ROOM_PREFIX) || partition.startsWith(USER_PREFIX)) {
            return partition;
        }
        return GAME_PREFIX + partition;
    }

    static String events(String partition) {
        return scope(partition) + EVENTS_SUFFIX;
    }

    static String lock(String partition) {
        return scope(partition) + ":lock";
    }

    static String latestSnapshot(String gameId) {
        return scope(gameId) + ":snapshot";
    }

    static String snapshotIndex(String gameId) {
        return scope(gameId) + ":snapshots";
    }

    static String snapshotAt(String gameId, int version) {
        return scope(gameId) + ":snapshot:" + version;
    }

    static String idempotency(String gameId, String idempotencyKey) {
        return "idem:" + gameId + ":" + idempotencyKey;
    }

    static String session(String userId, String sessionId) {
        return USER_PREFIX + userId + ":session:" + sessionId;
    }

    /** SCAN 模式里的字面量转义：{@code * ? [ ] \} 都是 glob 元字符。 */
    static String globLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
