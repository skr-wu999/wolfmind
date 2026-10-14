package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.infrastructure.json.Json;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 快照存储（redis-schema.md §7）：{@code game:{id}:snapshot:{version}}、{@code game:{id}:snapshots}（ZSet）
 * 与 {@code game:{id}:snapshot}（最新）。
 *
 * <p>只在持有单写者锁的提交路径里调用，因此三步写入不需要脚本。保留最近 3 个版本；淘汰的快照
 * 直接删除而不先归档 {@code game_history}——该表的写入者尚未接入，快照又可由事件流重建（INV-15）。
 * 快照键的 TTL 与事件流对齐，避免留下无源快照。
 */
public final class RedisSnapshotStore implements SnapshotStorePort {

    static final int RETAINED_VERSIONS = 3;

    private final StringRedisTemplate redis;
    private final RedisEventStore eventStore;

    public RedisSnapshotStore(StringRedisTemplate redis, RedisEventStore eventStore) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    }

    @Override
    public void save(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不能为空");
        }
        String gameId = snapshot.gameId();
        String versionKey = RedisKeys.snapshotAt(gameId, snapshot.version());
        String encoded = encode(snapshot);

        Boolean created = redis.opsForValue().setIfAbsent(versionKey, encoded);
        if (!Boolean.TRUE.equals(created)) {
            String existing = redis.opsForValue().get(versionKey);
            if (existing != null && !existing.equals(encoded)) {
                throw new IllegalStateException("版本 " + snapshot.version()
                        + " 已存在不同内容的快照，快照不可覆盖历史");
            }
            return;
        }
        String indexKey = RedisKeys.snapshotIndex(gameId);
        redis.opsForZSet().add(indexKey, versionKey, snapshot.version());

        String latestKey = RedisKeys.latestSnapshot(gameId);
        Optional<Snapshot> latest = latest(gameId);
        if (latest.isEmpty() || latest.get().version() <= snapshot.version()) {
            redis.opsForValue().set(latestKey, encoded);
        }

        Long size = redis.opsForZSet().zCard(indexKey);
        if (size != null && size > RETAINED_VERSIONS) {
            Set<String> evicted = redis.opsForZSet().range(indexKey, 0, size - RETAINED_VERSIONS - 1);
            if (evicted != null && !evicted.isEmpty()) {
                redis.delete(evicted);
                redis.opsForZSet().remove(indexKey, evicted.toArray());
            }
        }

        long ttl = eventStore.ttlSecondsOf(gameId);
        if (ttl > 0) {
            Duration expiry = Duration.ofSeconds(ttl);
            for (String key : List.of(versionKey, indexKey, latestKey)) {
                redis.expire(key, expiry);
            }
        } else if (ttl == -1) {
            for (String key : List.of(versionKey, indexKey, latestKey)) redis.persist(key);
        }
    }

    @Override
    public Optional<Snapshot> latest(String gameId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.latestSnapshot(gameId)))
                .map(RedisSnapshotStore::decode);
    }

    @Override
    public Optional<Snapshot> atVersion(String gameId, int version) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.snapshotAt(gameId, version)))
                .map(RedisSnapshotStore::decode);
    }

    /** 当前保留的版本号，升序；供测试断言保留策略。 */
    List<Integer> retainedVersions(String gameId) {
        Set<String> keys = redis.opsForZSet().range(RedisKeys.snapshotIndex(gameId), 0, -1);
        if (keys == null) {
            return List.of();
        }
        return keys.stream()
                .map(key -> Integer.parseInt(key.substring(key.lastIndexOf(':') + 1)))
                .toList();
    }

    private static String encode(Snapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gameId", snapshot.gameId());
        body.put("version", snapshot.version());
        body.put("checksum", snapshot.checksum());
        body.put("state", snapshot.state());
        return Json.write(body);
    }

    private static Snapshot decode(String text) {
        Map<String, Object> body = Json.readObject(text);
        return new Snapshot(
                (String) body.get("gameId"),
                ((Number) body.get("version")).intValue(),
                (String) body.get("state"),
                (String) body.get("checksum"));
    }
}
