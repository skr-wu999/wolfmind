package com.wolfmind.application.room;

import com.wolfmind.domain.room.model.Room;

/**
 * 房间聚合的持久化端口（ddd-design.md 5.2 的 {@code RoomRepository}）。
 *
 * <p>与 {@code GameRepositoryPort} 的分工一致：用例服务只依赖这个端口，
 * 具体存储（内存 / Redis）由基础设施层决定。
 *
 * <p><b>房间不需要快照。</b>房间生命周期短（创建到开局或关闭），事件量有个位数到几十条，
 * 全量重放的成本远低于维护第二套编解码器与校验和的风险。对局不同：它有几百条事件、
 * 且要在崩溃恢复时快速加载，所以那边做了快照。这里是有意不做，不是遗漏。
 */
public interface RoomRepositoryPort {

    /**
     * 房间流派名前缀。
     *
     * <p>这个约定由端口拥有，而不是由某个实现类拥有：应用层需要它来构造事件信封，
     * 而应用层不得依赖基础设施（DEP-1 的方向要求）。放在端口上，内存实现与将来的 Redis 实现
     * 才会共用同一个流派名——两处各写一份必然漂移，而漂移的后果是"房间加载不到自己刚写的事件"。
     */
    String STREAM_PREFIX = "room:";

    /** 房间 id 对应的流派名。 */
    static String streamName(String roomId) {
        return STREAM_PREFIX + roomId;
    }

    /** 加载房间：从事件流全量重放。 */
    LoadedRoom load(String roomId);

    /**
     * 追加事件。
     *
     * @param expectedVersion 期望的当前版本，防止"基于过期状态写入"
     * @return 提交后的版本与事件 id
     */
    CommitResult commit(String roomId, int expectedVersion, java.util.List<com.wolfmind.domain.game.event.EventEnvelope> events);

    /** 房间是否已存在（创建前的重名/重复创建检查）。 */
    boolean exists(String roomId);

    record LoadedRoom(Room room, int version) {

        public LoadedRoom {
            if (room == null) {
                throw new IllegalArgumentException("room 不能为空");
            }
        }
    }

    record CommitResult(int version, java.util.List<String> eventIds) {

        public CommitResult {
            eventIds = java.util.List.copyOf(eventIds);
        }
    }
}