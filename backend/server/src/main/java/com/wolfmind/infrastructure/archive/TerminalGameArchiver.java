package com.wolfmind.infrastructure.archive;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 终局对局的 SQLite 归档（database-schema.md §10）。
 *
 * <p>顺序不可交换：事件 {@code INSERT OR IGNORE} 入库 → 核对条数与空洞 → 写 {@code games} 行作为完成标记
 * → 才调用 {@code afterArchived} 缩短 Redis 保留期。任何一步失败都停在原地，下一轮从头重做；
 * 已写入的事件靠唯一索引去重，所以重做是安全的。
 *
 * <p>没有用消费组：归档只针对已终局的对局整流读取，扫描本身就是重试与启动补扫。
 */
public final class TerminalGameArchiver {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalGameArchiver.class);
    private static final int PAGE_SIZE = 100;

    private final GameViewQueryPort games;
    private final EventStorePort eventStore;
    private final Set<String> settled = ConcurrentHashMap.newKeySet();
    private final com.wolfmind.application.port.MetricsPort metrics;
    private final com.wolfmind.application.game.GameArchivalService service;

    public TerminalGameArchiver(GameViewQueryPort games, EventStorePort eventStore,
                                SqliteArchiveRepository archive, Consumer<String> afterArchived, TimePort time) {
        this(games, eventStore, archive, afterArchived, time, com.wolfmind.application.port.MetricsPort.NOOP);
    }

    public TerminalGameArchiver(GameViewQueryPort games, EventStorePort eventStore,
                                SqliteArchiveRepository archive, Consumer<String> afterArchived, TimePort time,
                                com.wolfmind.application.port.MetricsPort metrics) {
        this(games, eventStore, archive, afterArchived, time, metrics,
                new com.wolfmind.application.game.GameArchivalService(eventStore, new SqliteMatchArchive(archive),
                        new com.wolfmind.infrastructure.memory.InMemoryGameStateCache(), afterArchived, time));
    }

    public TerminalGameArchiver(GameViewQueryPort games, EventStorePort eventStore,
                                SqliteArchiveRepository archive, Consumer<String> afterArchived, TimePort time,
                                com.wolfmind.application.port.MetricsPort metrics,
                                com.wolfmind.application.game.GameArchivalService service) {
        this.games = Objects.requireNonNull(games, "games");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.service = Objects.requireNonNull(service, "service");
    }

    /** 扫描一轮所有终局对局；返回本轮新完成归档的对局数。单局失败只记日志，不影响其他对局。 */
    public synchronized int sweep() {
        int archived = 0;
        int pending = 0;
        int failed = 0;
        for (String status : List.of("FINISHED", "ABORTED")) {
            for (int page = 1; ; page++) {
                List<Map<String, Object>> batch = games.listGames(status, null, page, PAGE_SIZE);
                for (Map<String, Object> item : batch) {
                    String gameId = String.valueOf(item.get("gameId"));
                    if (settled.contains(gameId)) {
                        continue;
                    }
                    try {
                        if (archiveOne(gameId)) {
                            archived++;
                        }
                        if (!settled.contains(gameId)) pending++;
                    } catch (RuntimeException error) {
                        pending++;
                        failed++;
                        metrics.counter(com.wolfmind.application.port.MetricsPort.Names.ARCHIVE_FAILURES, 1L);
                        // 核对失败会每轮重现，只在 debug 级别带堆栈，避免日志被同一条堆栈刷满。
                        LOG.warn("对局 {} 归档失败，Redis 保留期不变，下一轮重试：{}", gameId, error.toString());
                        LOG.debug("对局 {} 归档失败详情", gameId, error);
                    }
                }
                if (batch.size() < PAGE_SIZE) {
                    break;
                }
            }
        }
        metrics.gauge(com.wolfmind.application.port.MetricsPort.Names.ARCHIVE_PENDING_GAMES, pending);
        metrics.gauge(com.wolfmind.application.port.MetricsPort.Names.ARCHIVE_FAILED_GAMES, failed);
        return archived;
    }

    /**
     * 归档一局；已完成的只补做保留期缩短。
     *
     * @return 本次是否新写入了归档
     */
    boolean archiveOne(String gameId) {
        int version = eventStore.currentVersion(gameId);
        if (version == 0) return false;
        var result = service.archiveThrough(gameId, version);
        settled.add(gameId);
        return result.newCompletion();
    }
}
