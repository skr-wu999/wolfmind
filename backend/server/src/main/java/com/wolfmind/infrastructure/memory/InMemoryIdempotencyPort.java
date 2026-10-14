package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.IdempotencyPort;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存幂等键存储：对应 {@code idem:{gameId}:{idempotencyKey}}（redis-schema.md §5.10）。
 *
 * <p>语义保证：
 * <ol>
 *   <li>同一个 {@code (gameId, idempotencyKey)} 只保存首次结果；</li>
 *   <li>记录带 TTL（默认 10 分钟，{@code idempotency.ttlMinutes}）。TTL 的语义对齐 Redis 的
 *       懒过期：读取时判定，而不是后台线程扫描删除；</li>
 *   <li>过期后同一键可以重新记录（这正是"幂等键保留 10 分钟"的边界）。</li>
 * </ol>
 *
 * <p>并发提示：Redis 版把幂等记录放在事件写入的同一个 Lua 脚本里（system-design.md §5），
 * 因此内存版的使用者必须把 {@link #record} 与事件追加放在同一个临界区内，
 * 否则会留下"事件已写入但幂等记录丢失"的窗口。
 */
public final class InMemoryIdempotencyPort implements IdempotencyPort {

    /** 与 redis-schema.md §5.10 的默认 TTL 一致。 */
    public static final long DEFAULT_TTL_MILLIS = 10L * 60L * 1000L;

    private final long ttlMillis;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryIdempotencyPort() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyPort(Clock clock) {
        this(clock, DEFAULT_TTL_MILLIS);
    }

    public InMemoryIdempotencyPort(Clock clock, long ttlMillis) {
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为空");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("幂等键 TTL 必须为正数，实际为 " + ttlMillis);
        }
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Optional<RecordedResult> find(String gameId, String idempotencyKey) {
        String key = key(gameId, idempotencyKey);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiredAt(clock.millis())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    @Override
    public void record(String gameId, String idempotencyKey, RecordedResult result) {
        if (result == null) {
            throw new IllegalArgumentException("幂等结果不能为空");
        }
        long now = clock.millis();
        String key = key(gameId, idempotencyKey);
        entries.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.expiredAt(now)) {
                // 同一幂等键的首次结果不可被后续提交覆盖：覆盖会让"重复提交返回首次结果"失效。
                if (!existing.result().equals(result)) {
                    throw new IllegalStateException(
                            "幂等键 " + key + " 已记录不同结果，重复提交必须返回首次结果");
                }
                return existing;
            }
            return new Entry(result, now + ttlMillis);
        });
    }

    private String key(String gameId, String idempotencyKey) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        // 用换行分隔而不是 ":"：gameId 与 key 都可能含冒号，拼出歧义键会让两个不同请求
        // 互相命中对方的幂等记录。换行不会出现在 id/key 里（两者在入口已拒绝空白内容）。
        return gameId + '\n' + idempotencyKey;
    }

    /** 当前未过期的幂等记录条数。 */
    public int size() {
        long now = clock.millis();
        return (int) entries.values().stream().filter(entry -> !entry.expiredAt(now)).count();
    }

    private record Entry(RecordedResult result, long expiresAt) {

        boolean expiredAt(long now) {
            return now >= expiresAt;
        }
    }
}
