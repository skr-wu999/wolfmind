package com.wolfmind.application.port;

import java.util.Optional;

/**
 * 快照存储端口（event-design.md §8）。
 *
 * <p>快照是缓存而不是真相（INV-15）：任何"只改快照不改事件"的实现都是缺陷，必须能通过
 * {@code EventStorePort.read(1..version)} 推导出与快照逐字段一致的状态。
 */
public interface SnapshotStorePort {

    /** 保存快照，按版本归档；实现对同一版本必须幂等。 */
    void save(Snapshot snapshot);

    /** 读取最新快照。 */
    Optional<Snapshot> latest(String gameId);

    /** 读取指定版本的快照。 */
    Optional<Snapshot> atVersion(String gameId, int version);

    /**
     * 快照：完整重放相关的聚合状态 + {@code checksum}。
     *
     * @param gameId   对局标识
     * @param version  快照对应的聚合版本
     * @param state    聚合状态的确定性序列化形式（由领域快照派生）
     * @param checksum 状态序列化的校验和；恢复时校验失败则退化为全量重放并告警
     */
    record Snapshot(String gameId, int version, String state, String checksum) {

        public Snapshot {
            if (gameId == null || gameId.isBlank()) {
                throw new IllegalArgumentException("gameId 不能为空");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version 必须 ≥ 1");
            }
            if (state == null || checksum == null) {
                throw new IllegalArgumentException("快照的 state 与 checksum 不能为空");
            }
        }
    }
}
