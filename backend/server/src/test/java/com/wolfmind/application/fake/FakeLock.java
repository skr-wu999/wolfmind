package com.wolfmind.application.fake;

import com.wolfmind.application.port.GameLockPort;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 假单写者锁：非阻塞语义与真实实现一致，并可强制"被他人持有"。
 *
 * <p>它记录 {@code tryLock} / {@code unlock} 次数，用于断言"任何出口都释放了锁"——
 * 这是 GAME_BUSY 与锁泄漏最容易出错的地方。
 */
public final class FakeLock implements GameLockPort {

    private final Set<String> held = new HashSet<>();
    private boolean forcedBusy;
    private int lockCalls;
    private int unlockCalls;

    @Override
    public Optional<LockToken> tryLock(String gameId) {
        lockCalls++;
        if (forcedBusy || held.contains(gameId)) {
            return Optional.empty();
        }
        held.add(gameId);
        return Optional.of(new LockToken(gameId, "test-owner", 0L, Long.MAX_VALUE));
    }

    @Override
    public void unlock(LockToken token) {
        unlockCalls++;
        if (token != null) {
            held.remove(token.gameId());
        }
    }

    public void forceBusy(boolean busy) {
        this.forcedBusy = busy;
    }

    public boolean isHeld(String gameId) {
        return held.contains(gameId);
    }

    public int lockCalls() {
        return lockCalls;
    }

    public int unlockCalls() {
        return unlockCalls;
    }
}
