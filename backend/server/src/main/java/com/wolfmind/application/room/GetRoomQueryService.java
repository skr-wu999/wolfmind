package com.wolfmind.application.room;

import com.wolfmind.domain.game.model.GameException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 房间查询用例（api-spec.md §4 的 {@code GET /rooms} 与 {@code GET /rooms/{roomId}}）。
 *
 * <p>只读：不产生事件、不加锁。
 *
 * <p><b>列表是"读索引 → 逐个重放聚合 → 过滤 → 分页"</b>，即 N+1 式加载。
 * 当前阶段刻意先用它换取正确性：房间状态必须来自事件流重放，否则索引就得保存状态，
 * 又多一处会与事件流不一致的来源。房间数量增长后应改为投影表（SQLite 的 rooms 表），
 * 时机与上界已记入 TASKS.md。
 */
public final class GetRoomQueryService {

    private final RoomRepositoryPort repository;
    private final RoomIndexPort index;

    public GetRoomQueryService(RoomRepositoryPort repository, RoomIndexPort index) {
        this.repository = repository;
        this.index = index;
    }

    /** 单个房间；不存在抛 {@code ROOM_NOT_FOUND}。 */
    public RoomView view(String roomId) {
        return versioned(roomId).view();
    }

    /** 房间视图与它对应的房间事件流版本；推送端据此让客户端丢弃过期快照。 */
    public record VersionedRoomView(int version, RoomView view) {}

    /** 同 {@link #view}，另带房间事件流版本。 */
    public VersionedRoomView versioned(String roomId) {
        if (roomId == null || roomId.isBlank() || !index.contains(roomId)) {
            throw GameException.Factory.roomNotFound(String.valueOf(roomId));
        }
        RoomRepositoryPort.LoadedRoom loaded = repository.load(roomId);
        return new VersionedRoomView(loaded.version(), RoomView.of(loaded.room()));
    }

    /**
     * 房间列表。
     *
     * @param status 可选状态过滤（{@code WAITING} / {@code RUNNING} / {@code CLOSED}）。
     *               非法值只是过滤掉全部，不抛错——它是查询条件而不是命令参数，
     *               把它当错误会让"前端传了个笔误的枚举"变成 500。
     */
    public List<RoomView> list(String status, int page, int pageSize) {
        List<RoomView> rooms = matching(status);
        int from = Math.min((page - 1) * pageSize, rooms.size());
        int to = Math.min(from + pageSize, rooms.size());
        return List.copyOf(rooms.subList(from, to));
    }

    /**
     * 过滤后的**全量**条数。
     *
     * <p>api-spec.md §1 要求分页响应含 {@code total}，且语义是总数而不是当前页条数。
     * 对局列表那边目前返回的是当前页条数（已知偏差，见 TASKS.md）；房间这里从一开始就做对。
     */
    public int count(String status) {
        return matching(status).size();
    }

    private List<RoomView> matching(String status) {
        List<RoomView> rooms = new ArrayList<>();
        for (String roomId : index.all()) {
            RoomView view = RoomView.of(repository.load(roomId).room());
            if (status == null || status.isBlank()
                    ? !"CLOSED".equals(view.status()) : status.equals(view.status())) {
                rooms.add(view);
            }
        }
        // 最新创建的排在前面。房间号前缀是时间戳的 36 进制表示，因此 id 降序即为时间降序。
        rooms.sort(Comparator.comparing(RoomView::roomId).reversed());
        return rooms;
    }
}
