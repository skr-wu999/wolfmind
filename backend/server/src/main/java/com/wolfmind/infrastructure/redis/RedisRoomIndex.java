package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.room.RoomIndexPort;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 房间索引：直接以 {@code room:{id}:events} 的存在为准，不另建索引键。
 *
 * <p>索引与事件流因此不会分叉：房间流过期（redis-schema.md §9.1）时房间同时从大厅消失。
 * {@link #add} 是空操作，因为它总在房间首批事件写入之后调用，此时流已存在。
 */
public final class RedisRoomIndex implements RoomIndexPort {

    private final StringRedisTemplate redis;

    public RedisRoomIndex(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void add(String roomId) {
    }

    @Override
    public List<String> all() {
        List<String> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisKeys.ROOM_PREFIX + "*" + RedisKeys.EVENTS_SUFFIX)
                .count(500)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                ids.add(key.substring(
                        RedisKeys.ROOM_PREFIX.length(), key.length() - RedisKeys.EVENTS_SUFFIX.length()));
            }
        }
        return List.copyOf(ids);
    }

    @Override
    public boolean contains(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.events(RedisKeys.ROOM_PREFIX + roomId)));
    }
}
