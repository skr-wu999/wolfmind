package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.room.RoomIndexPort;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 房间索引的内存实现。
 *
 * <p>用 {@link CopyOnWriteArraySet} 而不是 {@code HashSet}：读多写少（大厅频繁列表、
 * 建房很少），且要保证迭代时的顺序稳定与并发安全——大厅列表在迭代期间有新房创建是常态。
 */
public final class InMemoryRoomIndex implements RoomIndexPort {

    private final Set<String> roomIds = new CopyOnWriteArraySet<>();

    @Override
    public void add(String roomId) {
        if (roomId != null && !roomId.isBlank()) {
            roomIds.add(roomId);
        }
    }

    @Override
    public List<String> all() {
        return List.copyOf(roomIds);
    }

    @Override
    public boolean contains(String roomId) {
        return roomIds.contains(roomId);
    }
}