package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.Map;

/**
 * {@code PLAYER_CONTROLLER_CHANGED}：座位控制者变更。
 *
 * <p>同一个 {@code type} 字符串出现在两条事件流里，这是有意的：房间流（
 * {@code room:{id}:events}）记录入座与补位，对局流（{@code game:{id}:events}）记录"对局进行中
 * 换人操作"。两条流的 payload 保持同名字段一致（{@code roomId, seatNo, fromType, toType, refId}），
 * 对局流额外带 {@code playerId} 以定位对局内的玩家实体。
 *
 * <p>用途是"真人掉线由 AI/Bot 接管"（AI 补位）：规则校验不需要分支，因为 {@code Player} 只保存
 * {@code {type, refId}}（ddd-design.md §3.3）。谁在操作某个座位不是秘密，角色才是，因此
 * 可见性为 {@code PUBLIC}。
 */
public record PlayerControllerChangedEvent(
        EventMeta meta,
        String roomId,
        PlayerId playerId,
        SeatNo seatNo,
        ControllerType fromType,
        String fromRefId,
        ControllerType toType,
        String toRefId) implements DomainEvent {

    public PlayerControllerChangedEvent {
        if (playerId == null || seatNo == null || toType == null || toRefId == null) {
            throw GameException.Factory.validation("PlayerControllerChangedEvent 缺少必要字段");
        }
    }

    /**
     * 房间来源未知时的便捷构造（{@code roomId} 为可选字段）。
     *
     * <p>对局流的事件始终由 {@code Game} 产生并带上 {@code roomId}；这个重载只是让调用方在
     * 房间来源确实未知时不必伪造一个空字符串。
     */
    public PlayerControllerChangedEvent(
            EventMeta meta,
            PlayerId playerId,
            SeatNo seatNo,
            ControllerType fromType,
            String fromRefId,
            ControllerType toType,
            String toRefId) {
        this(meta, null, playerId, seatNo, fromType, fromRefId, toType, toRefId);
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_CONTROLLER_CHANGED;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.PUBLIC;
    }

    @Override
    public PlayerId targetPlayerId() {
        return null;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("roomId", roomId);
        payload.put("playerId", playerId.value());
        payload.put("seatNo", seatNo.value());
        payload.put("fromType", fromType == null ? null : fromType.name());
        payload.put("fromRefId", fromRefId);
        payload.put("toType", toType.name());
        payload.put("refId", toRefId);
        return EventPayloads.seal(payload);
    }
}
