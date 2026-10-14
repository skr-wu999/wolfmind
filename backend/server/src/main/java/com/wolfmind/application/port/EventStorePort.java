package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.List;

/**
 * 事件存储端口（docs/architecture/event-design.md §3.3）。
 *
 * <p>它是状态真相的写入者：{@link #append} 必须以"显式条目 ID = version"写入，使存储自身成为
 * 并发冲突的仲裁者；同一命令产生的多条事件必须连续写入，不允许出现版本空洞（INV-11）。
 */
public interface EventStorePort {

    /**
     * 追加事件。
     *
     * <p>实现必须保证：以 {@code expectedVersion + 1} 作为第一条事件的起始版本，逐条递增写入；
     * 任一条冲突则整批失败且不留下部分写入。这条契约让"要么全成、要么回到 expectedVersion"
     * 成为存储层责任，而不是调用方需要重试补偿的事情。
     *
     * @param gameId          事件流分区键
     * @param expectedVersion 追加前的聚合版本（当前已知的最后版本）
     * @param events          待追加事件，顺序即版本顺序，不得为空
     * @throws com.wolfmind.domain.game.model.GameException
     *         {@code GAME_VERSION_CONFLICT} 当存储中的当前版本与 {@code expectedVersion} 不一致
     */
    void append(String gameId, int expectedVersion, List<EventEnvelope> events);

    /**
     * 读取版本区间内的事件，按 {@code version} 升序。
     *
     * <p>upcaster 在读取路径执行（event-design.md §6）：返回前把旧 {@code schemaVersion} 补齐为
     * 当前版本；写入路径永远写最新版本。
     *
     * @param fromVersion 起始版本（含）；{@code 1} 表示全量重放
     * @param toVersion   结束版本（含）；{@code Integer.MAX_VALUE} 表示读到流尾
     */
    List<EventEnvelope> read(String gameId, int fromVersion, int toVersion);

    /** 当前流内最后一条事件的版本；流为空时返回 0。 */
    int currentVersion(String gameId);

    /** 流是否存在（用于崩溃恢复时区分"从未存在"与"事件已全部过期"）。 */
    boolean exists(String gameId);

    /** Best-effort local wakeup only; consumers recover from the persisted stream. */
    default AutoCloseable onAppend(java.util.function.Consumer<String> listener) { return () -> {}; }
}
