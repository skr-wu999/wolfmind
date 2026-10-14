package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存事件存储：Redis Stream 的同形替换点。
 *
 * <p>为什么它必须"严格"而不是"够用"：这层实现是并发冲突的仲裁者（event-design.md §3.1）。
 * Redis 版用显式条目 ID {@code {version}-0} 让 Redis 拒绝重复写入；内存版必须自己实现
 * 同样的拒绝语义，否则测试会在"内存下通过、Redis 下丢事件"的地方给出错误的安全感。
 *
 * <p>语义保证：
 * <ol>
 *   <li><b>显式条目 ID = version</b>：每个 gameId 的版本从 1 开始，{@code version == 已写入条数}；</li>
 *   <li><b>先校验后写入</b>：首条必须等于 {@code expectedVersion + 1}，批内严格 +1 连续，
 *       任一条不满足则整批拒绝，且不留下部分写入（INV-11 无版本空洞）；</li>
 *   <li><b>重复版本拒绝</b>：流尾之后的版本一律 {@code GAME_VERSION_CONFLICT}；</li>
 *   <li><b>只追加</b>：已写入事件不可修改、不可删除（INV-14），read 返回的列表亦不可变。</li>
 * </ol>
 *
 * <p>并发：同一 gameId 的 append 在 {@code synchronized} 块内串行执行。Redis 的 Lua 脚本
 * 有同样的原子性，所以这里的实现不是"更强的保证"，而是同一契约的另一种承载方式。
 */
public final class InMemoryEventStore implements EventStorePort, GameIdCatalog {

    private final Map<String, List<EventEnvelope>> streams = new ConcurrentHashMap<>();
    private final java.util.List<java.util.function.Consumer<String>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    @Override public AutoCloseable onAppend(java.util.function.Consumer<String> listener) {
        listeners.add(listener); return () -> listeners.remove(listener);
    }

    @Override
    public void append(String gameId, int expectedVersion, List<EventEnvelope> events) {
        if (gameId == null || gameId.isBlank()) {
            throw GameException.Factory.validation("gameId 不能为空");
        }
        if (events == null) {
            throw GameException.Factory.validation("append 的事件列表不能为 null");
        }
        if (events.isEmpty()) {
            // 空批是合法的 no-op：应用层在"本批没有领域事件、只需要落快照或刷新投影"时
            // 会走同一条提交路径（例如终态快照补做）。把它当错误会迫使每个调用方多写一次判空。
            return;
        }
        if (expectedVersion < 0) {
            throw GameException.Factory.validation("expectedVersion 不能为负：" + expectedVersion);
        }

        synchronized (streams) {
            List<EventEnvelope> stream = streams.computeIfAbsent(gameId, key -> new ArrayList<>());
            int current = stream.isEmpty() ? 0 : stream.get(stream.size() - 1).version();

            // 校验阶段：任何一条不合法都在写入之前失败，因此不存在"半批"。
            if (current != expectedVersion) {
                throw GameException.Factory.versionConflict(expectedVersion, current);
            }
            int expectedNext = expectedVersion + 1;
            List<EventEnvelope> staged = new ArrayList<>(events.size());
            for (int i = 0; i < events.size(); i++) {
                EventEnvelope event = events.get(i);
                if (event == null) {
                    throw GameException.Factory.validation("第 " + i + " 条事件为 null");
                }
                if (!gameId.equals(event.gameId())) {
                    throw GameException.Factory.validation(
                            "第 " + i + " 条事件的 gameId=" + event.gameId() + " 与流分区键不一致");
                }
                int expectedVersionForEntry = expectedNext + i;
                if (event.version() != expectedVersionForEntry) {
                    // 首条不匹配时报版本冲突（调用方要的是"最新版本"），
                    // 批内不连续报重放不连续（这是契约违反，不是并发冲突）。
                    if (i == 0) {
                        throw GameException.Factory.versionConflict(expectedVersion, event.version() - 1);
                    }
                    throw GameException.Factory.replayOutOfOrder(expectedVersionForEntry, event.version());
                }
                staged.add(event);
            }

            stream.addAll(staged);
        }
        for (var listener : listeners) { try { listener.accept(gameId); } catch (RuntimeException ignored) { } }
    }

    @Override
    public List<EventEnvelope> read(String gameId, int fromVersion, int toVersion) {
        if (fromVersion > toVersion) {
            return List.of();
        }
        List<EventEnvelope> stream = streams.get(gameId);
        if (stream == null) {
            return List.of();
        }
        List<EventEnvelope> result = new ArrayList<>();
        synchronized (streams) {
            for (EventEnvelope event : stream) {
                if (event.version() >= fromVersion && event.version() <= toVersion) {
                    result.add(event);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public int currentVersion(String gameId) {
        List<EventEnvelope> stream = streams.get(gameId);
        if (stream == null) {
            return 0;
        }
        synchronized (streams) {
            return stream.isEmpty() ? 0 : stream.get(stream.size() - 1).version();
        }
    }

    @Override
    public boolean exists(String gameId) {
        List<EventEnvelope> stream = streams.get(gameId);
        if (stream == null) {
            return false;
        }
        synchronized (streams) {
            return !stream.isEmpty();
        }
    }

    /** 测试与运维排障用：当前流内事件条数。 */
    public int size(String gameId) {
        List<EventEnvelope> stream = streams.get(gameId);
        return stream == null ? 0 : stream.size();
    }

    /**
     * 已出现过的**对局** id，升序。
     *
     * <p>Redis 版用 {@code SCAN game:*:events} 得到同一信息。这里保持"按键排序"，
     * 让列表接口的分页在两种实现下顺序一致。
     *
     * <p><b>必须排掉房间与用户流。</b>它们与本存储共用同一个
     * {@link com.wolfmind.application.port.EventStorePort}，但键是
     * {@code room:{id}} 与 {@code user:{id}:events}。
     * 而 {@code GameRepositoryPort.load} 会把拿到的 id 当作 **Game 事件流**去解码——
     * 于是 {@code USER_REGISTERED} 触发
     * {@code GameException: 信封 USER_REGISTERED 不属于 Game 聚合事件流}。
     *
     * <p>后果不止是对局列表：**任何账号注册之后，列表接口就会抛异常**，
     * 而它同时是观战页、分析页与对局驱动的入口。这个缺陷在实现里躺了很久，
     * 直到对局驱动第一次真的去枚举运行中的对局才暴露——
     * 之前的调用方都只查单个已知 gameId。
     */
    @Override
    public List<String> gameIds() {
        List<String> ids = new ArrayList<>();
        for (String key : streams.keySet()) {
            if (!key.startsWith(ROOM_STREAM_PREFIX) && !key.startsWith(USER_STREAM_PREFIX)) {
                ids.add(key);
            }
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    /** 与 {@code RoomRepositoryPort.STREAM_PREFIX} 一致；在此复写常量以免反向依赖应用层。 */
    private static final String ROOM_STREAM_PREFIX = "room:";

    /** 与 {@code InMemoryUserRepository.STREAM_PREFIX} 一致。 */
    private static final String USER_STREAM_PREFIX = "user:";
}
