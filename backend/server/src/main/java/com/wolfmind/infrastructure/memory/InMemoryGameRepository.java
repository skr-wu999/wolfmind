package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.GameStateCachePort;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventCodec;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 内存对局仓储：{@code GameRepositoryPort} 的同形实现。
 *
 * <p>加载语义固定为 event-design.md §11 的"恢复重放"：
 * <ol>
 *   <li>取最新快照（缺失或校验失败则退化为全量重放）；</li>
 *   <li>{@code Game.restore(snapshot)} 或 {@code Game.create(gameId)}；</li>
 *   <li>只读 {@code (snapshotVersion, 流尾]} 的增量事件，逐条 {@code replay}；</li>
 *   <li>校验版本连续：事件流不允许空洞（INV-11）。</li>
 * </ol>
 *
 * <p>提交语义（system-design.md §5）：追加事件（原子）→ 更新状态投影 → 按策略生成快照。
 * 发布与幂等记录不在本方法内：由应用服务显式编排，保证"先持久化后发布"。
 *
 * <p>快照策略（event-design.md §8）：{@code INTERVAL} 时在"提交后版本是快照间隔的整数倍"
 * 或"本批含终态事件"时生成；{@code FORCE} 无条件生成；{@code NONE} 不生成。
 * 间隔是配置项（默认 50），不硬编码进领域。
 */
public final class InMemoryGameRepository implements GameRepositoryPort {

    /** event-design.md §8：每 50 个事件生成一次快照。 */
    public static final int DEFAULT_SNAPSHOT_INTERVAL = 50;

    private static final List<EventType> TERMINAL_TYPES = List.of(EventType.GAME_FINISHED, EventType.GAME_ABORTED);
    private static final List<GameStatus> TERMINAL_STATUSES = List.of(GameStatus.FINISHED, GameStatus.ABORTED);

    private final EventStorePort eventStore;
    private final SnapshotStorePort snapshotStore;
    private final GameStateCachePort stateCache;
    private final int snapshotInterval;
    private final com.wolfmind.application.port.MetricsPort metrics;
    private final com.wolfmind.application.port.ArchivedGameRecoveryPort archiveRecovery;

    private final java.util.concurrent.atomic.AtomicInteger incrementalLoads =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger fullReplayLoads =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger checksumDegradations =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 按版本记住的对局状态。事件流只追加且有版本校验，同一版本的状态不会变；
     * 对局驱动每 200ms 列一次对局，Redis 模式下逐局完整加载会随已结束对局数线性变慢。
     */
    private final java.util.Map<String, StatusAt> statusByVersion =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 某一版本上的对局状态。 */
    public record StatusAt(int version, GameStatus status) {}

    public InMemoryGameRepository(
            EventStorePort eventStore, SnapshotStorePort snapshotStore, GameStateCachePort stateCache) {
        this(eventStore, snapshotStore, stateCache, DEFAULT_SNAPSHOT_INTERVAL);
    }

    public InMemoryGameRepository(
            EventStorePort eventStore,
            SnapshotStorePort snapshotStore,
            GameStateCachePort stateCache,
            int snapshotInterval) {
        this(eventStore, snapshotStore, stateCache, snapshotInterval,
                com.wolfmind.application.port.MetricsPort.NOOP);
    }

    /**
     * @param metrics 指标出口（Phase 5 可观测性）。装配在**仓储的提交路径**上而不是各个用例里：
     *                "事件写入了多少、有几局走到了终态"是存储事实，而仓储是它唯一的汇聚点——
     *                在三个用例里各记一次，迟早会漏掉第四个
     */
    public InMemoryGameRepository(
            EventStorePort eventStore,
            SnapshotStorePort snapshotStore,
            GameStateCachePort stateCache,
            int snapshotInterval,
            com.wolfmind.application.port.MetricsPort metrics) {
        this(eventStore, snapshotStore, stateCache, snapshotInterval, metrics, null);
    }

    public InMemoryGameRepository(EventStorePort eventStore, SnapshotStorePort snapshotStore,
                                  GameStateCachePort stateCache, int snapshotInterval,
                                  com.wolfmind.application.port.MetricsPort metrics,
                                  com.wolfmind.application.port.ArchivedGameRecoveryPort archiveRecovery) {
        if (eventStore == null || snapshotStore == null || stateCache == null) {
            throw new IllegalArgumentException("eventStore / snapshotStore / stateCache 都不能为空");
        }
        if (snapshotInterval < 1) {
            throw new IllegalArgumentException("快照间隔必须 ≥ 1，实际为 " + snapshotInterval);
        }
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.stateCache = stateCache;
        this.snapshotInterval = snapshotInterval;
        this.metrics = metrics == null ? com.wolfmind.application.port.MetricsPort.NOOP : metrics;
        this.archiveRecovery = archiveRecovery;
    }

    @Override
    public LoadedGame load(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw GameException.Factory.validation("gameId 不能为空");
        }
        requireStream(gameId);

        Optional<SnapshotStorePort.Snapshot> latest = snapshotStore.latest(gameId);
        SnapshotStorePort.Snapshot candidate = latest.orElse(null);
        if (candidate != null && (!isTrustworthy(gameId, candidate) || !currentSkillState(candidate))) {
            // 校验失败必须退化为全量重建，不允许"修一下快照继续用"（INV-15）。
            checksumDegradations.incrementAndGet();
            candidate = null;
        }

        Game restoredGame = candidate == null ? null : tryRestore(candidate);
        Game game;
        int fromVersion;
        if (restoredGame != null) {
            game = restoredGame;
            fromVersion = candidate.version() + 1;
            incrementalLoads.incrementAndGet();
        } else {
            if (candidate != null) {
                // checksum 通过但内容无法还原（解码失败或聚合拒绝）：同样是坏快照。
                // 事件流才是真相，因此这里的正确处理是重建，而不是让整个对局加载失败。
                checksumDegradations.incrementAndGet();
            }
            game = Game.create(GameId.of(gameId));
            fromVersion = 1;
            fullReplayLoads.incrementAndGet();
        }

        List<EventEnvelope> events = eventStore.read(gameId, fromVersion, Integer.MAX_VALUE);
        int expected = fromVersion;
        for (EventEnvelope envelope : events) {
            if (envelope.version() != expected) {
                throw GameException.Factory.replayOutOfOrder(expected, envelope.version());
            }
            game.replay(EventCodec.decode(envelope));
            expected++;
        }
        return new LoadedGame(game, game.version());
    }

    /**
     * 快照是否可信。
     *
     * <p>两个条件都必须满足：文本 checksum 与记录一致（存储层没损坏），
     * 且快照版本不超过事件流当前水位（事件流被裁剪或快照来自另一个存储时不可信）。
     */
    private boolean isTrustworthy(String gameId, SnapshotStorePort.Snapshot snapshot) {
        String actualChecksum = GameSnapshotCodec.checksum(snapshot.state());
        if (!actualChecksum.equals(snapshot.checksum())) {
            return false;
        }
        return snapshot.version() <= eventStore.currentVersion(gameId);
    }

    private static boolean currentSkillState(SnapshotStorePort.Snapshot snapshot) {
        try {
            return GameSnapshotCodec.hasCurrentSkillState(snapshot.state());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Decode failures fall back to replay; a snapshot is a cache, not the source of truth. */
    private static Game tryRestore(SnapshotStorePort.Snapshot snapshot) {
        try {
            return Game.restore(GameSnapshotCodec.decode(snapshot.state()));
        } catch (RuntimeException error) {
            return null;
        }
    }

    /**
     * 加载**指定版本**的聚合（api-spec.md §6 的 {@code ?atVersion=}）。
     *
     * <p>它刻意不做"取最近快照再回退"：快照只保留最近几个版本，而回放页要的是任意一步；
     * 更关键的是**快照的版本只能小于等于目标版本**，用它再回退需要反向重放——
     * 而领域只提供正向 {@code replay}。从版本 1 正向重放到目标版本是唯一与
     * {@code load} 语义完全一致的实现，一局两百来条事件的代价可以接受。
     *
     * @param atVersion 目标版本（含）；大于流尾时等价于加载到流尾
     */
    public LoadedGame loadAt(String gameId, int atVersion) {
        if (gameId == null || gameId.isBlank()) {
            throw GameException.Factory.validation("gameId 不能为空");
        }
        if (atVersion < 1) {
            throw GameException.Factory.validation("atVersion 必须大于等于 1，实际为 " + atVersion);
        }
        requireStream(gameId);
        Game game = Game.create(GameId.of(gameId));
        List<EventEnvelope> events = eventStore.read(gameId, 1, atVersion);
        int expected = 1;
        for (EventEnvelope envelope : events) {
            if (envelope.version() != expected) {
                throw GameException.Factory.replayOutOfOrder(expected, envelope.version());
            }
            game.replay(EventCodec.decode(envelope));
            expected++;
        }
        return new LoadedGame(game, game.version());
    }

    private void requireStream(String gameId) {
        if (!eventStore.exists(gameId)
                && (archiveRecovery == null || !archiveRecovery.restoreMissing(gameId))) {
            throw GameException.Factory.notRunning("不存在或没有完整归档");
        }
    }

    @Override
    public CommitResult commit(
            String gameId, int expectedVersion, List<EventEnvelope> events, SnapshotPolicy snapshotPolicy) {
        if (snapshotPolicy == null) {
            throw GameException.Factory.validation("snapshotPolicy 不能为空");
        }
        // 第 1 步：原子追加。冲突时整批不写，调用方按 GAME_VERSION_CONFLICT 重新加载。
        eventStore.append(gameId, expectedVersion, events);
        metrics.counter(com.wolfmind.application.port.MetricsPort.Names.EVENTS_APPENDED, events.size());
        for (EventEnvelope event : events) {
            // 终态事件是"这一局结束"的唯一权威信号：用 game.status() 判断会把"已经结束"重复计一次。
            if (event.eventType() == EventType.GAME_FINISHED) {
                metrics.counter(com.wolfmind.application.port.MetricsPort.Names.GAMES_FINISHED, 1);
            } else if (event.eventType() == EventType.GAME_ABORTED) {
                metrics.counter(com.wolfmind.application.port.MetricsPort.Names.GAMES_ABORTED, 1);
            }
        }

        int committedVersion = eventStore.currentVersion(gameId);
        List<String> eventIds = events.stream().map(EventEnvelope::eventId).toList();
        List<String> eventTypes = events.stream().map(EventEnvelope::type).toList();

        boolean snapshotNeeded = switch (snapshotPolicy) {
            case NONE -> false;
            case FORCE -> true;
            case INTERVAL -> committedVersion % snapshotInterval == 0 || containsTerminalEvent(events);
        };

        if (snapshotNeeded) {
            // 快照与投影都从"提交后的权威状态"派生。load 只重放最后一个快照之后的增量，
            // 因此这里的代价受 snapshotInterval 上界约束，不会随对局长度线性增长。
            GameSnapshot snapshot = load(gameId).game().snapshot();
            stateCache.put(snapshot);
            String state = GameSnapshotCodec.encode(snapshot);
            snapshotStore.save(new SnapshotStorePort.Snapshot(
                    gameId, snapshot.version(), state, GameSnapshotCodec.checksum(state)));
        }

        return new CommitResult(committedVersion, eventIds, eventTypes);
    }

    /** 本批事件是否包含终态事件（{@code GAME_FINISHED} / {@code GAME_ABORTED}），决定是否强制快照。 */
    static boolean containsTerminalEvent(List<EventEnvelope> events) {
        for (EventEnvelope event : events) {
            if (TERMINAL_TYPES.contains(event.eventType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用当前权威状态刷新状态投影（{@code game:{id}:state}）。
     *
     * <p>{@code commit} 只在生成快照时顺带写投影；应用服务在需要"提交后立即可读"时显式调用本方法。
     */
    public GameSnapshot refreshProjection(String gameId) {
        GameSnapshot snapshot = load(gameId).game().snapshot();
        stateCache.put(snapshot);
        return snapshot;
    }

    /** 生成一次快照并落盘，返回快照对应的版本；供恢复流程与运维接口使用。 */
    public int snapshotNow(String gameId) {
        GameSnapshot snapshot = load(gameId).game().snapshot();
        stateCache.put(snapshot);
        String state = GameSnapshotCodec.encode(snapshot);
        snapshotStore.save(new SnapshotStorePort.Snapshot(
                gameId, snapshot.version(), state, GameSnapshotCodec.checksum(state)));
        return snapshot.version();
    }

    /** 快照间隔（配置项）。 */
    public int snapshotInterval() {
        return snapshotInterval;
    }

    /**
     * 当前对局的版本水位；不存在的对局返回 0。
     *
     * <p>只读版本不需要加载聚合：事件流尾部就是权威水位（event-design.md §3.1）。
     */
    public int currentVersion(String gameId) {
        return eventStore.currentVersion(gameId);
    }

    /** 当前对局状态；对局不存在时返回空。 */
    public java.util.Optional<GameStatus> statusOf(String gameId) {
        return statusAt(gameId).map(StatusAt::status);
    }

    /** 当前版本与状态；对局不存在时返回空。只问一次流尾，版本未变时不重新加载聚合。 */
    public java.util.Optional<StatusAt> statusAt(String gameId) {
        int version = eventStore.currentVersion(gameId);
        if (version == 0) {
            statusByVersion.remove(gameId);
            return java.util.Optional.empty();
        }
        StatusAt known = statusByVersion.get(gameId);
        if (known != null && known.version() == version) {
            return java.util.Optional.of(known);
        }
        Game game = load(gameId).game();
        StatusAt current = new StatusAt(game.version(), game.status());
        statusByVersion.put(gameId, current);
        return java.util.Optional.of(current);
    }

    /**
     * 是否已观察到该对局进入终态。终态之后不再追加事件，
     * 因此按"运行中"筛选时可以直接跳过，不必再访问事件流。
     */
    public boolean knownTerminal(String gameId) {
        StatusAt known = statusByVersion.get(gameId);
        return known != null && TERMINAL_STATUSES.contains(known.status());
    }

    /** 已知对局 id，升序；列表接口用。 */
    public List<String> knownGameIds() {
        if (eventStore instanceof GameIdCatalog catalog) {
            return catalog.gameIds();
        }
        return List.of();
    }

    /** 走"快照 + 增量"路径的加载次数；与全量重放次数一起用于验证恢复路径真的生效。 */
    public int incrementalLoadCount() {
        return incrementalLoads.get();
    }

    /** 走全量重放路径的加载次数。 */
    public int fullReplayLoadCount() {
        return fullReplayLoads.get();
    }

    /** 因快照校验失败而退化为全量重放的次数。持续大于 0 说明快照写入路径有缺陷。 */
    public int checksumDegradationCount() {
        return checksumDegradations.get();
    }

    /**
     * 清零加载路径计数。
     *
     * <p>只给测试与诊断用：装配夹具本身会调用 {@code load}，如果不清零，"这次恢复走了哪条路径"
     * 的断言就会被夹具的调用污染。生产代码没有任何理由调用它。
     */
    public void resetLoadCounters() {
        incrementalLoads.set(0);
        fullReplayLoads.set(0);
        checksumDegradations.set(0);
    }
}
