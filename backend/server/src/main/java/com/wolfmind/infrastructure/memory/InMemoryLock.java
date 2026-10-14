package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.GameLockPort;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存单写者锁：对应 {@code game:{id}:lock} 的 {@code SET NX PX 3000}（redis-schema.md §8）。
 *
 * <p>语义保证：
 * <ol>
 *   <li><b>非阻塞</b>：同一对局同一时刻只有一个持有者，其余调用立即拿到空值（应用层转 {@code GAME_BUSY}）；</li>
 *   <li><b>TTL</b>：默认 3000 ms，与 redis-schema.md §8 的 {@code game.lock.ttlMs} 一致；
 *       到期后新写者可以接管，模拟"持有者崩溃后 TTL 自动释放"；</li>
 *   <li><b>释放非本次持有的锁是安全操作</b>：解锁是 compare-and-delete。锁已被接管、已被
 *       别的写者重新获取或令牌不匹配时，解锁都是静默 no-op，绝不误删他人锁。</li>
 * </ol>
 *
 * <p>锁值形如 {@code {instanceId}:{threadId}:{ownerToken}}，与 redis-schema.md §8 的锁值格式一致：
 * 续租与释放都按值比对，因此必须唯一标识持有者。
 */
public final class InMemoryLock implements GameLockPort {

    /** 与 redis-schema.md §8 的 {@code game.lock.ttlMs} 默认值一致。 */
    public static final long DEFAULT_TTL_MILLIS = 3000L;

    private final long ttlMillis;
    private final Clock clock;
    private final String instanceId;
    private final AtomicLong ownerSequence = new AtomicLong();
    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    public InMemoryLock() {
        this(Clock.systemUTC());
    }

    public InMemoryLock(Clock clock) {
        this(clock, DEFAULT_TTL_MILLIS);
    }

    public InMemoryLock(Clock clock, long ttlMillis) {
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为空");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("锁 TTL 必须为正数，实际为 " + ttlMillis);
        }
        this.clock = clock;
        this.ttlMillis = ttlMillis;
        this.instanceId = "mem-" + Long.toHexString(ProcessHandle.current().pid());
    }

    @Override
    public Optional<LockToken> tryLock(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        Holder candidate = null;
        synchronized (holders) {
            long now = clock.millis();
            Holder current = holders.get(gameId);
            if (current == null || current.expiredAt(now)) {
                // 锁空闲或已过期：接管。过期接管不需要显式清理，覆盖即替换。
                candidate = new Holder(owner(), now, now + ttlMillis);
                holders.put(gameId, candidate);
            }
        }
        return Optional.ofNullable(candidate).map(holder -> toToken(gameId, holder));
    }

    @Override
    public void unlock(LockToken token) {
        if (token == null || token.gameId() == null) {
            return;
        }
        synchronized (holders) {
            Holder current = holders.get(token.gameId());
            // 只删除"值相等且未过期"的那把锁。TTL 到期后原持有者再 unlock 时，
            // 锁可能已经被接管者换成新值，此时必须不删（否则会破坏接管者的互斥）。
            if (current == null || current.expiredAt(clock.millis())) {
                return;
            }
            if (current.owner().equals(token.ownerId())) {
                holders.remove(token.gameId(), current);
            }
        }
    }

    /** 该对局当前是否存在未过期的锁；供运维接口与测试观测。 */
    public boolean isLocked(String gameId) {
        Holder holder = holders.get(gameId);
        return holder != null && !holder.expiredAt(clock.millis());
    }

    /** 当前 TTL 毫秒数。 */
    public long ttlMillis() {
        return ttlMillis;
    }

    private String owner() {
        return instanceId + ":" + Thread.currentThread().threadId() + ":" + ownerSequence.incrementAndGet();
    }

    private LockToken toToken(String gameId, Holder holder) {
        return new LockToken(gameId, holder.owner(), holder.acquiredAt(), holder.expiresAt());
    }

    private record Holder(String owner, long acquiredAt, long expiresAt) {

        boolean expiredAt(long now) {
            return now >= expiresAt;
        }
    }
}
