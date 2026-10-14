package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.events.EventGroupWorker;
import com.wolfmind.application.events.GameInsightService;
import com.wolfmind.application.game.GameArchivalService;
import com.wolfmind.application.port.*;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.interfaces.ws.WebSocketGateway;
import com.wolfmind.application.room.GetRoomQueryService;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class ReliableEventConsumers implements AutoCloseable {
    private final EventGroupWorker projection;
    private final EventGroupWorker broadcast;
    private final EventGroupWorker insight;
    public ReliableEventConsumers(StringRedisTemplate redis, RedisEventStore store, ReplayArchivePort archive,
                                   GameArchivalService archival, GameInsightService insights, WebSocketGateway gateway,
                                   GameViewQueryPort views, GetRoomQueryService rooms, MetricsPort metrics, long claimIdleMs) {
        this(redis,store,archive,archival,insights,gateway,views,rooms,metrics,claimIdleMs,null);
    }
    public ReliableEventConsumers(StringRedisTemplate redis, RedisEventStore store, ReplayArchivePort archive,
                                   GameArchivalService archival, GameInsightService insights, WebSocketGateway gateway,
                                   GameViewQueryPort views, GetRoomQueryService rooms, MetricsPort metrics, long claimIdleMs,
                                   com.wolfmind.application.room.RoomLifecycleService lifecycle) {
        projection = new EventGroupWorker("cg-projection", new RedisEventConsumption(redis,store,"cg-projection",archive,claimIdleMs), delivery -> {
            var state=archival.archiveThrough(delivery.gameId(),delivery.events().getLast().version()).state();
            if(lifecycle != null && (state.status()==GameStatus.FINISHED || state.status()==GameStatus.ABORTED))
                lifecycle.gameEnded(delivery.gameId());
            return state.status()==GameStatus.FINISHED || state.status()==GameStatus.ABORTED;
        },metrics);
        insight = new EventGroupWorker("cg-insight", new RedisEventConsumption(redis,store,"cg-insight",archive,claimIdleMs),insights::process,metrics);
        broadcast = new EventGroupWorker("cg-broadcast", new RedisEventConsumption(redis,store,"cg-broadcast",null,claimIdleMs), delivery -> {
            boolean settled;
            if(delivery.gameId().startsWith("room:")) {
                var room=rooms.versioned(delivery.gameId().substring(5));
                settled="CLOSED".equals(room.view().status());
            } else {
                var view=views.view(delivery.gameId(),com.wolfmind.domain.game.model.ViewerContext.anonymous());
                settled="FINISHED".equals(view.status().name()) || "ABORTED".equals(view.status().name());
            }
            gateway.onCommittedEvents(delivery.events()); return settled;
        },metrics);
    }
    public EventGroupWorker projection() { return projection; }
    public EventGroupWorker broadcast() { return broadcast; }
    public EventGroupWorker insight() { return insight; }
    public void start() { projection.start();broadcast.start();insight.start(); }
    @Override public void close() { projection.close();broadcast.close();insight.close(); }
}
