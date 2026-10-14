package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.SnapshotStorePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存快照存储：对应 {@code game:{id}:snapshot} 与 {@code game:{id}:snapshots}（ZSet）。
 *
 * <p>保留策略与 redis-schema.md §7.4 一致：每局只留最近 3 个版本，更早的淘汰。
 * 淘汰在这里而不是在调用方，因为"Redis 侧保留最近 3 个"是键空间契约，
 * 调用方不该知道 ZSet 的排序细节。
 *
 * <p>快照是缓存而不是真相（INV-15）：淘汰历史快照不丢信息，事件流才是真相。
 */
public final class InMemorySnapshotStore implements SnapshotStorePort {

    /** 与 redis-schema.md §7.4 的 "ZREMRANGEBYRANK 0 -4" 一致。 */
    public static final int RETAINED_VERSIONS = 3;

    private final Map<String, NavigableMap<Integer, Snapshot>> byGame = new ConcurrentHashMap<>();

    private final Map<String, Integer> acceptedWrites = new ConcurrentHashMap<>();

    @Override
    public void save(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不能为空");
        }
        synchronized (byGame) {
            NavigableMap<Integer, Snapshot> versions =
                    byGame.computeIfAbsent(snapshot.gameId(), key -> new TreeMap<>());
            Snapshot previous = versions.get(snapshot.version());
            if (previous != null) {
                // 同一版本重复落盘是正常现象（重试、恢复后补做）。相同内容不重复计数，
                // 内容不同则说明同一版本被写了两次不同的状态，这是缺陷而不是覆盖。
                if (!previous.equals(snapshot)) {
                    throw new IllegalStateException("版本 " + snapshot.version()
                            + " 已存在不同内容的快照，快照不可覆盖历史");
                }
                return;
            }
            versions.put(snapshot.version(), snapshot);
            acceptedWrites.merge(snapshot.gameId(), 1, Integer::sum);
            while (versions.size() > RETAINED_VERSIONS) {
                versions.pollFirstEntry();
            }
        }
    }

    @Override
    public Optional<Snapshot> latest(String gameId) {
        NavigableMap<Integer, Snapshot> versions = byGame.get(gameId);
        if (versions == null) {
            return Optional.empty();
        }
        synchronized (byGame) {
            Map.Entry<Integer, Snapshot> last = versions.lastEntry();
            return last == null ? Optional.empty() : Optional.of(last.getValue());
        }
    }

    @Override
    public Optional<Snapshot> atVersion(String gameId, int version) {
        NavigableMap<Integer, Snapshot> versions = byGame.get(gameId);
        if (versions == null) {
            return Optional.empty();
        }
        synchronized (byGame) {
            return Optional.ofNullable(versions.get(version));
        }
    }

    /** 当前保留的版本号，升序；供测试与运维接口断言保留策略。 */
    public List<Integer> retainedVersions(String gameId) {
        NavigableMap<Integer, Snapshot> versions = byGame.get(gameId);
        if (versions == null) {
            return List.of();
        }
        synchronized (byGame) {
            return new ArrayList<>(versions.keySet());
        }
    }

    /** 实际落盘次数（不含幂等重复写）；用于断言"同版本重复保存不重复写入"。 */
    public int acceptedWriteCount(String gameId) {
        return acceptedWrites.getOrDefault(gameId, 0);
    }
}
