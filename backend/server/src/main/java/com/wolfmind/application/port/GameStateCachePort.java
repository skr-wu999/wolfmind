package com.wolfmind.application.port;

import com.wolfmind.domain.game.model.GameSnapshot;

/**
 * 状态投影端口：{@code game:{id}:state}（redis-schema.md）。
 *
 * <p>它是投影而不是真相：允许短暂滞后，**任何业务判断都不得只读它**（AP-8）。写入由
 * {@code cg-projection} 消费者在事件写入成功后推进。
 */
public interface GameStateCachePort {

    void put(GameSnapshot snapshot);

    /** 读取最近投影；对局状态未知时返回 {@code null}。 */
    GameSnapshot get(String gameId);

    void remove(String gameId);
}
