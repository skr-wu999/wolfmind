package com.wolfmind.infrastructure.config;

import com.wolfmind.application.game.ActionCodec;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.GameStateCachePort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.infrastructure.memory.InMemoryGameRepository;
import com.wolfmind.infrastructure.memory.InMemoryGameViewQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 对局存取、查询和命令用例装配。 */
@Configuration
public class GameApplicationConfiguration {

    /**
     * 返回具体类型而不是端口：视图查询要按"快照 + 增量"加载聚合，需要这个具体实现的额外能力。
     * 端口 GameRepositoryPort 仍然由容器按类型解析得到（实现类本身就是它的实例）。
     */
    @Bean
    InMemoryGameRepository gameRepository(
            EventStorePort eventStore,
            SnapshotStorePort snapshotStore,
            GameStateCachePort stateCache,
            com.wolfmind.application.port.MetricsPort metrics,
            org.springframework.beans.factory.ObjectProvider<com.wolfmind.application.port.ArchivedGameRecoveryPort>
                    archiveRecovery) {
        return new InMemoryGameRepository(eventStore, snapshotStore, stateCache,
                InMemoryGameRepository.DEFAULT_SNAPSHOT_INTERVAL, metrics, archiveRecovery.getIfAvailable());
    }

    @Bean
    GameViewQueryPort gameViewQueryPort(
            EventStorePort eventStore,
            InMemoryGameRepository repository,
            org.springframework.beans.factory.ObjectProvider<com.wolfmind.application.port.ReplayArchivePort>
                    replayArchive) {
        // 视图查询需要具体仓储（它要按快照+增量加载聚合），因此这里注入具体类型。
        // 端口只暴露只读方法，控制器拿到的仍是 GameViewQueryPort。
        //
        // 归档端口是可选的：内存模式没有 SQLite 归档，此时过期对局的回放如实报 404，
        // 而不是返回一份空历史。Redis 模式下由 RedisStorageConfiguration 提供它。
        return new InMemoryGameViewQuery(eventStore, repository, replayArchive.getIfAvailable());
    }

    @Bean
    SubmitActionApplicationService submitActionApplicationService(
            IdempotencyPort idempotency,
            GameLockPort lock,
            GameRepositoryPort repository,
            EventPublisherPort publisher,
            TimePort time,
            com.wolfmind.application.scheduling.TaskSubmissionGuard guard) {
        var service = new SubmitActionApplicationService(
                new ActionCodec(), idempotency, lock, repository, publisher, time);
        service.bindTaskGuard(guard);
        return service;
    }

    /** 开局用例（发牌）。此前只在测试里手工构造，Phase 4 需要它在容器里。 */
    @Bean
    com.wolfmind.application.game.StartGameApplicationService startGameApplicationService(
            GameLockPort lock,
            EventStorePort eventStore,
            GameRepositoryPort repository,
            EventPublisherPort publisher,
            TimePort time) {
        return new com.wolfmind.application.game.StartGameApplicationService(
                lock, eventStore, repository, publisher, time);
    }
}
