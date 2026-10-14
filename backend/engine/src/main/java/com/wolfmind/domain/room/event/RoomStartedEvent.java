package com.wolfmind.domain.room.event;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventPayloads;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.domain.room.model.RoomId;

import java.util.Map;

/**
 * {@code ROOM_STARTED}：房间开局，座位冻结、对局开始（{@code PUBLIC}）。
 *
 * <p><b>为什么必须有这个事件，而不是把状态直接改在命令方法里</b>：房间状态是事件流的投影
 * （INV-13/INV-16）。开局时同时变化两件事——{@code status→RUNNING} 与记录 {@code gameId}——
 * 若不产出事件，这两处变更就只存在于内存：进程重启后重放事件流会得到"房间还在等待"，
 * 于是座位重新可改，而那个对局已经在跑。<b>这是真实的数据不一致，不是形式问题。</b>
 *
 * <p><b>为什么不能复用 {@code ROOM_CLOSED}</b>：api-spec.md §4 的 {@code status} 取值是
 * {@code WAITING|PLAYING}（实现为 {@code WAITING|RUNNING|CLOSED}），客户端据此决定
 * "能否入座"与"跳到对局页"。把开局写成关闭会让等待页把进行中的房间显示成已关闭；
 * 而现成的 6 个房间事件码没有一个能同时表达"进入 RUNNING"与"绑定 gameId"。
 * 因此本事件是对 event-design.md §7.1 房间事件目录的**补充**，已同步登记到该目录与
 * {@code EventCatalogTest} 的 {@code ROOM_TYPES}。
 */
public record RoomStartedEvent(
        EventMeta meta,
        RoomId roomId,
        String gameId) implements DomainEvent {

    public RoomStartedEvent {
        if (roomId == null) {
            throw GameException.Factory.validation("RoomStartedEvent 缺少 roomId");
        }
        if (gameId == null || gameId.isBlank()) {
            throw GameException.Factory.validation("RoomStartedEvent 缺少 gameId");
        }
    }

    @Override
    public EventType type() {
        return EventType.ROOM_STARTED;
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
        payload.put("roomId", roomId.value());
        payload.put("gameId", gameId);
        return EventPayloads.seal(payload);
    }
}
