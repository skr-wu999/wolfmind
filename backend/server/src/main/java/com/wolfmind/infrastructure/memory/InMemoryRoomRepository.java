package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.room.RoomRepositoryPort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.room.event.RoomEventCodec;
import com.wolfmind.domain.room.model.Room;
import com.wolfmind.domain.room.model.RoomId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 房间仓储的内存实现。
 *
 * <p>复用 {@link EventStorePort} 承载房间事件流：它的第一个参数实际是**通用流派名**
 * （内部就是 {@code Map<String, List<EventEnvelope>>}），因此房间流用 {@code room:{roomId}}、
 * 对局流用 {@code game:{gameId}} 互不干扰。这样安排的好处是"事件存储的原子追加与版本前置校验"
 * 只有一份实现——给房间再造一套存储，就多一处可能不满足版本连续性的地方。
 *
 * <p>加载是**全量重放**：房间没有快照（原因见 {@link RoomRepositoryPort} 的说明）。
 */
public final class InMemoryRoomRepository implements RoomRepositoryPort {

    private final EventStorePort eventStore;

    public InMemoryRoomRepository(EventStorePort eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    }

    /** 房间 id 对应的流派名；直接复用端口上的约定，避免两处各写一份。 */
    public static String streamKey(String roomId) {
        return RoomRepositoryPort.streamName(roomId);
    }

    @Override
    public LoadedRoom load(String roomId) {
        String stream = streamKey(roomId);
        Room room = Room.create(RoomId.of(roomId));
        int version = 0;
        for (EventEnvelope envelope : eventStore.read(stream, 1, Integer.MAX_VALUE)) {
            DomainEvent event = RoomEventCodec.decode(envelope);
            room.replay(event);
            version = envelope.version();
        }
        return new LoadedRoom(room, version);
    }

    @Override
    public CommitResult commit(String roomId, int expectedVersion, List<EventEnvelope> events) {
        if (events.isEmpty()) {
            // 空提交不写事件、不推进版本：否则会出现"没有状态变化却多了一个版本"的空洞，
            // 与 INV-11（没有状态变化就不允许写事件）直接冲突。
            return new CommitResult(expectedVersion, List.of());
        }
        String stream = streamKey(roomId);
        eventStore.append(stream, expectedVersion, events);
        List<String> eventIds = new ArrayList<>(events.size());
        for (EventEnvelope envelope : events) {
            eventIds.add(envelope.eventId());
        }
        return new CommitResult(events.get(events.size() - 1).version(), eventIds);
    }

    @Override
    public boolean exists(String roomId) {
        return eventStore.exists(streamKey(roomId));
    }

    /** 当前版本；不存在时返回 0。 */
    public int currentVersion(String roomId) {
        return eventStore.currentVersion(streamKey(roomId));
    }
}