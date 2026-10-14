package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.port.GameLockPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单写者锁（redis-schema.md §8）：{@code SET {scope}:lock {owner} NX PX ttl}，释放用 compare-and-del。
 *
 * <p>锁值 {@code {instanceId}:{threadId}:{seq}} 唯一标识持有者；TTL 到期后原持有者再释放时，
 * 值已不匹配，脚本不会删掉接管者的锁。
 */
public final class RedisLock implements GameLockPort {

    static final long DEFAULT_TTL_MILLIS = 3000L;

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final long ttlMillis;
    private final String instanceId = "redis-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong sequence = new AtomicLong();

    public RedisLock(StringRedisTemplate redis) {
        this(redis, DEFAULT_TTL_MILLIS);
    }

    public RedisLock(StringRedisTemplate redis, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("锁 TTL 必须为正数，实际为 " + ttlMillis);
        }
        this.redis = Objects.requireNonNull(redis, "redis");
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Optional<LockToken> tryLock(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        String owner = instanceId + ":" + Thread.currentThread().threadId() + ":" + sequence.incrementAndGet();
        long now = System.currentTimeMillis();
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(RedisKeys.lock(gameId), owner, Duration.ofMillis(ttlMillis));
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(new LockToken(gameId, owner, now, now + ttlMillis));
    }

    @Override
    public void unlock(LockToken token) {
        if (token == null || token.gameId() == null || token.ownerId() == null) {
            return;
        }
        redis.execute(RELEASE_SCRIPT, List.of(RedisKeys.lock(token.gameId())), token.ownerId());
    }

    /** 该对局当前是否持有锁；供测试观测。 */
    boolean isLocked(String gameId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.lock(gameId)));
    }
}
