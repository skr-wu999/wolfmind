package com.wolfmind.application.room;

import com.wolfmind.domain.room.model.Room;
import com.wolfmind.domain.room.model.RoomSeat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间的对外视图（api-spec.md 4）。
 *
 * <p>它是**应用层的只读投影**，不是聚合本身：接口层把它直接序列化成响应体，
 * 因此字段名与顺序就是线上契约。把聚合直接暴露出去会让"房间内部有哪些状态"
 * 与"客户端能看到什么"绑死，将来加一个内部字段就可能泄漏出去。
 *
 * @param seats        9 个座位，按座位号升序；空座也在列表里（客户端据此渲染空位）
 * @param readyCount   已就绪座位数
 * @param ownerSeatNo  房主所在的座位号；房主已离开时为 {@code null}
 */
public record RoomView(
        String roomId,
        String name,
        String status,
        String ownerId,
        Integer ownerSeatNo,
        int occupied,
        int capacity,
        int readyCount,
        List<SeatView> seats,
        Map<String, Object> config,
        String gameId,
        String closedReason,
        com.wolfmind.domain.game.model.GameRules rules) {

    public RoomView {
        seats = List.copyOf(seats);
        config = Map.copyOf(config);
    }

    /**
     * 座位视图。
     *
     * <p>{@code controller} 是**嵌套对象** {@code {type, refId}}，与 GameView 的
     * {@code players[].controller} 保持同一形状：同一个概念在两条通道上不该有两种写法。
     */
    public record SeatView(
            int seatNo,
            String playerId,
            ControllerView controller,
            boolean ready) {}

    /** 控制者视图。 */
    public record ControllerView(String type, String refId) {}

    /** 由聚合构造视图。 */
    public static RoomView of(Room room) {
        List<SeatView> seats = new ArrayList<>();
        Integer ownerSeatNo = null;
        for (RoomSeat seat : room.seats()) {
            ControllerView controller = seat.controller() == null
                    ? null
                    : new ControllerView(seat.controller().type().name(), seat.controller().refId());
            seats.add(new SeatView(
                    seat.seatNo().value(),
                    seat.playerId() == null ? null : seat.playerId().value(),
                    controller,
                    seat.ready()));
            if (room.ownerId() != null && seat.occupied()
                    && room.ownerId().equals(seat.playerId().value())) {
                ownerSeatNo = seat.seatNo().value();
            }
        }
        return new RoomView(
                room.roomId().value(),
                room.name(),
                room.status() == null ? null : room.status().name(),
                room.ownerId(),
                ownerSeatNo,
                room.occupiedCount(),
                Room.SEAT_COUNT,
                room.readyCount(),
                seats,
                room.config(),
                room.gameId(),
                room.closedReason(),
                effectiveRules(room));
    }

    private static com.wolfmind.domain.game.model.GameRules effectiveRules(Room room) {
        try {
            return com.wolfmind.application.game.GameRulesCodec.fromRoomConfig(room.config());
        } catch (com.wolfmind.domain.game.model.GameException invalid) {
            if (invalid.code() == com.wolfmind.domain.game.model.DomainErrorCode.RULES_INVALID) return null;
            throw invalid;
        }
    }

    /** 列表项：不含 9 个座位明细，避免大厅列表把每个房间的座位都拖下来。 */
    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roomId", roomId);
        summary.put("name", name);
        summary.put("status", status);
        summary.put("ownerId", ownerId);
        summary.put("occupied", occupied);
        int humans = (int) seats.stream().filter(seat -> seat.playerId() != null && seat.controller() != null
                && "HUMAN".equals(seat.controller().type())).count();
        summary.put("humanCount", humans);
        summary.put("aiCount", occupied - humans);
        summary.put("capacity", capacity);
        summary.put("readyCount", readyCount);
        summary.put("gameId", gameId);
        return summary;
    }
}
