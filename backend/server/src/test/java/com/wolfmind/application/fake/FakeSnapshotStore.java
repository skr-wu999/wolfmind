package com.wolfmind.application.fake;

import com.wolfmind.application.port.SnapshotStorePort;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** 假快照存储：同一版本幂等保存，{@code latest} 取最大版本。 */
public final class FakeSnapshotStore implements SnapshotStorePort {

    private final Map<String, NavigableMap<Integer, Snapshot>> snapshots = new HashMap<>();

    @Override
    public void save(Snapshot snapshot) {
        snapshots.computeIfAbsent(snapshot.gameId(), key -> new TreeMap<>())
                .put(snapshot.version(), snapshot);
    }

    @Override
    public Optional<Snapshot> latest(String gameId) {
        NavigableMap<Integer, Snapshot> byVersion = snapshots.get(gameId);
        if (byVersion == null || byVersion.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(byVersion.lastEntry().getValue());
    }

    @Override
    public Optional<Snapshot> atVersion(String gameId, int version) {
        NavigableMap<Integer, Snapshot> byVersion = snapshots.get(gameId);
        return byVersion == null ? Optional.empty() : Optional.ofNullable(byVersion.get(version));
    }

    /** 测试专用：伪造一个"已存在某版本快照"的事实，不需要真的能反序列化状态。 */
    public void put(String gameId, int version) {
        save(new Snapshot(gameId, version, "state@" + version, "checksum@" + version));
    }
}
