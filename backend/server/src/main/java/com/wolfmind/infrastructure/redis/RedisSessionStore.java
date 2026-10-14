package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.user.SessionPort;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话存储（redis-schema.md §5.9）：{@code user:{id}:session:{sessionId}}，Hash，存 secret 的 SHA-256。
 *
 * <p>过期交给键 TTL：Redis 删除过期键后校验自然失败，不需要惰性比较时间。
 */
public final class RedisSessionStore implements SessionPort {

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void issue(String userId, String sessionId, String secret, long issuedAt, long ttlMillis) {
        String key = RedisKeys.session(userId, sessionId);
        redis.opsForHash().putAll(key, Map.of(
                "userId", userId,
                "sessionId", sessionId,
                "secretHash", sha256(secret),
                "issuedAt", Long.toString(issuedAt)));
        redis.expire(key, Duration.ofMillis(ttlMillis));
    }

    @Override
    public Optional<Verified> verify(String token) {
        Parts parts = split(token);
        if (parts == null) {
            return Optional.empty();
        }
        Object stored = redis.opsForHash().get(RedisKeys.session(parts.userId(), parts.sessionId()), "secretHash");
        if (stored == null) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(
                sha256(parts.secret()).getBytes(StandardCharsets.UTF_8),
                stored.toString().getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(new Verified(parts.userId(), parts.sessionId(), token));
    }

    @Override
    public boolean revoke(String token) {
        Parts parts = split(token);
        if (parts == null) {
            return false;
        }
        return Boolean.TRUE.equals(redis.delete(RedisKeys.session(parts.userId(), parts.sessionId())));
    }

    @Override
    public int revokeAll(String userId) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisKeys.USER_PREFIX + RedisKeys.globLiteral(userId) + ":session:*")
                .count(200)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (keys.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(keys);
        return removed == null ? 0 : removed.intValue();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 必须提供 SHA-256", error);
        }
    }

    private record Parts(String userId, String sessionId, String secret) {}

    private static Parts split(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            return null;
        }
        for (String segment : segments) {
            if (segment.isBlank()) {
                return null;
            }
        }
        return new Parts(segments[0], segments[1], segments[2]);
    }
}
