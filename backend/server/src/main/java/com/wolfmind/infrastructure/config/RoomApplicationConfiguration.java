package com.wolfmind.infrastructure.config;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.TimePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 房间用例、开局编排与运行中 Agent 恢复装配。 */
@Configuration
public class RoomApplicationConfiguration {

    @Bean
    com.wolfmind.application.room.RoomLifecycleService roomLifecycleService(
            com.wolfmind.application.room.RoomApplicationService rooms,
            com.wolfmind.application.room.RoomIndexPort index, GameRepositoryPort games, GameLockPort locks,
            EventPublisherPort publisher, com.wolfmind.application.port.AgentProvisioningPort agents,
            org.springframework.beans.factory.ObjectProvider<com.wolfmind.application.room.RoomPresencePort> presence,
            TimePort time, com.wolfmind.application.port.MetricsPort metrics, EventStorePort events,
            org.springframework.core.env.Environment environment) {
        return new com.wolfmind.application.room.RoomLifecycleService(rooms,index,games,locks,publisher,agents,
                (room,game,user) -> { var port=presence.getIfAvailable(); return port != null && port.present(room,game,user); },
                time,metrics,environment.getProperty("wolfmind.room.disconnect-grace-ms",Long.class,120000L),events);
    }

    @Bean
    org.springframework.beans.factory.InitializingBean roomLifecycleEvents(
            com.wolfmind.application.room.RoomLifecycleService lifecycle,
            com.wolfmind.infrastructure.memory.InMemoryEventPublisher publisher) {
        return () -> publisher.addRealtimeListener(lifecycle::onCommitted);
    }

    @Bean(destroyMethod="shutdown")
    java.util.concurrent.ScheduledExecutorService roomLifecycleMaintenance(
            com.wolfmind.application.room.RoomLifecycleService lifecycle,
            org.springframework.core.env.Environment environment) {
        var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable,"wolfmind-room-lifecycle"); thread.setDaemon(true); return thread;
        });
        if (environment.getProperty("wolfmind.room.lifecycle.enabled",Boolean.class,true)) {
            long interval = Math.max(1000L, environment.getProperty("wolfmind.room.reconcile-ms",Long.class,30000L));
            executor.scheduleWithFixedDelay(() -> {
                try { lifecycle.sweep(); }
                catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(RoomApplicationConfiguration.class)
                        .warn("房间生命周期检查失败，下轮重试",error); }
            },interval,interval,java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return executor;
    }

    // ------------------------------------------------------------------
    // 房间（Phase 4）
    // ------------------------------------------------------------------

    @Bean
    com.wolfmind.application.room.RoomRepositoryPort roomRepositoryPort(EventStorePort eventStore) {
        return new com.wolfmind.infrastructure.memory.InMemoryRoomRepository(eventStore);
    }

    @Bean
    com.wolfmind.application.room.RoomApplicationService roomApplicationService(
            com.wolfmind.application.room.RoomRepositoryPort repository,
            com.wolfmind.application.room.RoomIndexPort index,
            GameLockPort lock,
            EventPublisherPort publisher,
            TimePort time) {
        return new com.wolfmind.application.room.RoomApplicationService(
                repository, index, lock, publisher, time);
    }

    @Bean
    com.wolfmind.application.room.GetRoomQueryService getRoomQueryService(
            com.wolfmind.application.room.RoomRepositoryPort repository,
            com.wolfmind.application.room.RoomIndexPort index) {
        return new com.wolfmind.application.room.GetRoomQueryService(repository, index);
    }

    /**
     * 开局编排：房间 → 真实对局 + AI Agent 实例化（api-spec.md 5.1）。
     *
     * <p>房间用例通过 AgentProvisioningPort 准备实例，不依赖 Agent 配置或 SDK。
     */
    @Bean
    com.wolfmind.application.room.StartRoomGameApplicationService startRoomGameApplicationService(
            com.wolfmind.application.room.RoomRepositoryPort roomRepository,
            com.wolfmind.application.room.RoomApplicationService roomCommands,
            com.wolfmind.application.game.StartGameApplicationService startGame,
            com.wolfmind.application.port.AgentProvisioningPort agents) {
        return new com.wolfmind.application.room.StartRoomGameApplicationService(
                roomRepository,
                roomCommands,
                startGame,
                agents);
    }

    /** 启动时为持久化存储中运行中的对局补建 AI Agent（内存模式下启动时没有对局，空跑）。 */
    @Bean
    @org.springframework.core.annotation.Order(0)
    org.springframework.boot.ApplicationRunner runningGameAgentRecovery(
            GameViewQueryPort gameViews,
            GameRepositoryPort repository,
            com.wolfmind.application.room.StartRoomGameApplicationService startRoomGame) {
        com.wolfmind.application.room.RunningGameAgentRecovery recovery =
                new com.wolfmind.application.room.RunningGameAgentRecovery(gameViews, repository, startRoomGame);
        return arguments -> recovery.recover();
    }
}
