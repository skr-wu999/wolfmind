package com.wolfmind.application.fake;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 假聚合仓储。
 *
 * <p>它不做"快照 + 增量重放"（那需要把事件载荷反序列化回领域事件，属于基础设施的编解码职责），
 * 而是让测试用一个已构造好的聚合注册进来，并跟踪它的版本。这样应用层编排（版本前置校验、
 * 提交顺序、锁、发布）仍然被真实检验，而领域状态的构造交给领域自己的公开入口。
 *
 * <p>提交仍写入 {@link FakeEventStore}，因此"事件连续追加"的存储在测试里也是真的。
 */
public final class FakeGameRepository implements GameRepositoryPort {

    private final EventStorePort store;
    private final Map<String, Game> games = new HashMap<>();
    private final Map<String, Integer> versions = new HashMap<>();
    private final List<CommitCall> commits = new ArrayList<>();
    private int loadCalls;

    public FakeGameRepository(EventStorePort store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public LoadedGame load(String gameId) {
        loadCalls++;
        Game game = games.get(gameId);
        if (game == null) {
            throw GameException.Factory.notRunning("INIT");
        }
        return new LoadedGame(game, versions.getOrDefault(gameId, 0));
    }

    @Override
    public CommitResult commit(
            String gameId, int expectedVersion, List<EventEnvelope> events, SnapshotPolicy snapshotPolicy) {
        int current = versions.getOrDefault(gameId, 0);
        if (current != expectedVersion) {
            throw GameException.Factory.versionConflict(expectedVersion, current);
        }
        store.append(gameId, expectedVersion, events);
        int newVersion = expectedVersion + events.size();
        versions.put(gameId, newVersion);
        commits.add(new CommitCall(gameId, expectedVersion, List.copyOf(events), snapshotPolicy));
        return new CommitResult(
                newVersion,
                events.stream().map(EventEnvelope::eventId).toList(),
                events.stream().map(EventEnvelope::type).toList());
    }

    /** 测试夹具：注册一个已经处于某状态的聚合（版本取 {@code game.version()}）。 */
    public void register(Game game) {
        String gameId = game.gameId().value();
        games.put(gameId, game);
        versions.put(gameId, game.version());
    }

    public Game game(String gameId) {
        return games.get(gameId);
    }

    /** 测试专用：把加载到的版本与事件流拉开，用于验证恢复路径的一致性校验。 */
    public void forceVersion(String gameId, int version) {
        versions.put(gameId, version);
    }

    public CommitCall lastCommit() {
        if (commits.isEmpty()) {
            throw new AssertionError("没有任何提交");
        }
        return commits.get(commits.size() - 1);
    }

    public int commitCount() {
        return commits.size();
    }

    public int loadCalls() {
        return loadCalls;
    }

    public record CommitCall(
            String gameId, int expectedVersion, List<EventEnvelope> events, SnapshotPolicy snapshotPolicy) {}
}
