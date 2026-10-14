package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.infrastructure.json.Json;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 幂等记录（redis-schema.md §5.10）：{@code idem:{gameId}:{idempotencyKey}}，Hash，TTL 10 分钟。
 *
 * <p>首次结果不可覆盖：记录脚本在键已存在时原样返回已有字段，由调用方比对；
 * 结果不同说明同一幂等键对应了两次不同的提交，按缺陷抛出（与内存实现一致）。
 */
public final class RedisIdempotencyPort implements IdempotencyPort {

    static final long DEFAULT_TTL_MILLIS = 10L * 60L * 1000L;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RECORD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('HGETALL', KEYS[1])
            end
            redis.call('HSET', KEYS[1], 'version', ARGV[1], 'eventIds', ARGV[2],
                       'eventTypes', ARGV[3], 'resultDigest', ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return {}
            """, List.class);

    private final StringRedisTemplate redis;
    private final long ttlMillis;

    public RedisIdempotencyPort(StringRedisTemplate redis) {
        this(redis, DEFAULT_TTL_MILLIS);
    }

    public RedisIdempotencyPort(StringRedisTemplate redis, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("幂等键 TTL 必须为正数，实际为 " + ttlMillis);
        }
        this.redis = Objects.requireNonNull(redis, "redis");
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Optional<RecordedResult> find(String gameId, String idempotencyKey) {
        Map<Object, Object> fields = redis.opsForHash().entries(key(gameId, idempotencyKey));
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(decode(gameId, idempotencyKey, fields));
    }

    @Override
    public void record(String gameId, String idempotencyKey, RecordedResult result) {
        if (result == null) {
            throw new IllegalArgumentException("幂等结果不能为空");
        }
        String key = key(gameId, idempotencyKey);
        List<?> existing = redis.execute(RECORD_SCRIPT, List.of(key),
                Integer.toString(result.version()),
                Json.write(result.eventIds()),
                Json.write(result.eventTypes()),
                result.resultDigest() == null ? "" : result.resultDigest(),
                Long.toString(ttlMillis));
        if (existing == null || existing.isEmpty()) {
            return;
        }
        Map<Object, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < existing.size(); i += 2) {
            fields.put(existing.get(i), existing.get(i + 1));
        }
        if (!decode(gameId, idempotencyKey, fields).equals(result)) {
            throw new IllegalStateException(
                    "幂等键 " + key + " 已记录不同结果，重复提交必须返回首次结果");
        }
    }

    private static String key(String gameId, String idempotencyKey) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        return RedisKeys.idempotency(gameId, idempotencyKey);
    }

    private static RecordedResult decode(String gameId, String idempotencyKey, Map<Object, Object> fields) {
        String digest = String.valueOf(fields.get("resultDigest"));
        return new RecordedResult(
                gameId,
                idempotencyKey,
                Integer.parseInt(String.valueOf(fields.get("version"))),
                strings(String.valueOf(fields.get("eventIds"))),
                strings(String.valueOf(fields.get("eventTypes"))),
                digest.isEmpty() ? null : digest);
    }

    private static List<String> strings(String json) {
        Object parsed = Json.read(json);
        List<String> values = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }
}
