package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.user.SessionPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储的内存实现。
 *
 * <p>令牌形态 {@code {userId}.{sessionId}.{secret}} 是**不透明串**（redis-schema.md §5.9）。
 * 存储里只放 {@code secret} 的 SHA-256，不放明文：这样即使存储被读走，攻击者也无法
 * 直接构造出可用令牌——与"口令只存散列"是同一个理由。
 *
 * <p>过期是**惰性**判断（校验时比时间），不靠后台清理线程：会话表很小，
 * 而且惰性过期不会因为清理线程没跑而放行一个过期令牌。
 */
public final class InMemorySessionStore implements SessionPort {

    private record Record(String secretHash, long issuedAt, long expiresAt) {}

    private final Map<String, Record> sessions = new ConcurrentHashMap<>();

    @Override
    public void issue(String userId, String sessionId, String secret, long issuedAt, long ttlMillis) {
        sessions.put(key(userId, sessionId),
                new Record(sha256(secret), issuedAt, issuedAt + ttlMillis));
    }

    @Override
    public Optional<Verified> verify(String token) {
        Parts parts = split(token);
        if (parts == null) {
            return Optional.empty();
        }
        Record record = sessions.get(key(parts.userId(), parts.sessionId()));
        if (record == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() >= record.expiresAt()) {
            // 惰性清理：顺手移除，避免过期记录长期占用内存。
            sessions.remove(key(parts.userId(), parts.sessionId()));
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(
                sha256(parts.secret()).getBytes(StandardCharsets.UTF_8),
                record.secretHash().getBytes(StandardCharsets.UTF_8))) {
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
        return sessions.remove(key(parts.userId(), parts.sessionId())) != null;
    }

    @Override
    public int revokeAll(String userId) {
        int removed = 0;
        for (String key : sessions.keySet()) {
            if (key.startsWith(userId + ".")) {
                if (sessions.remove(key) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 在岗会话数；供测试与诊断。 */
    public int size() {
        return sessions.size();
    }

    private static String key(String userId, String sessionId) {
        return userId + "." + sessionId;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 必须提供 SHA-256", error);
        }
    }

    private record Parts(String userId, String sessionId, String secret) {}

    /** 拆分令牌；段数不对或任一段为空时返回 null（形态错误不抛异常）。 */
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