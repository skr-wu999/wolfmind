package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ROLE_ASSIGNED}：角色分配，每人一条（event-design.md §7.2）。
 *
 * <p>{@code PRIVATE}（本人）：角色是对局内最核心的隐藏信息，只在事件层标注可见性，
 * 推送、AI 上下文、回放接口共用同一份判定，避免任何地方另写过滤逻辑（AP-6）。
 */
public record RoleAssignedEvent(
        EventMeta meta,
        PlayerId playerId,
        SeatNo seatNo,
        Role role,
        Camp camp) implements DomainEvent {

    public RoleAssignedEvent {
        if (playerId == null || seatNo == null || role == null) {
            throw GameException.Factory.validation("RoleAssignedEvent 缺少 playerId / seatNo / role");
        }
        if (camp == null) {
            camp = role.camp();
        }
    }

    @Override
    public EventType type() {
        return EventType.ROLE_ASSIGNED;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.PRIVATE;
    }

    @Override
    public PlayerId targetPlayerId() {
        return playerId;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("playerId", playerId.value());
        payload.put("seatNo", seatNo.value());
        payload.put("role", role.name());
        payload.put("camp", camp.name());
        return EventPayloads.seal(payload);
    }
}
