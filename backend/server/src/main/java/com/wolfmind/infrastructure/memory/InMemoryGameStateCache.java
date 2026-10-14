package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.GameStateCachePort;
import com.wolfmind.domain.game.model.GameSnapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存状态投影：对应 {@code game:{id}:state}（redis-schema.md §5.2）。
 *
 * <p>它是投影而不是真相，允许短暂滞后，任何业务判断都不得只读它（AP-8）。
 * 因此本实现刻意不提供"按投影做条件判断"的辅助方法。
 */
public final class InMemoryGameStateCache implements GameStateCachePort {

    private final Map<String, GameSnapshot> states = new ConcurrentHashMap<>();

    @Override
    public void put(GameSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不能为空");
        }
        states.put(snapshot.gameId().value(), snapshot);
    }

    @Override
    public GameSnapshot get(String gameId) {
        return gameId == null ? null : states.get(gameId);
    }

    @Override
    public void remove(String gameId) {
        if (gameId != null) {
            states.remove(gameId);
        }
    }

    /** 已投影的对局数；供运维接口与测试使用。 */
    public int size() {
        return states.size();
    }
}
