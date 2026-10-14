package com.wolfmind.infrastructure.config;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameStateCachePort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryEventPublisher;
import com.wolfmind.infrastructure.memory.InMemoryEventStore;
import com.wolfmind.infrastructure.memory.InMemoryGameStateCache;
import com.wolfmind.infrastructure.memory.InMemoryIdempotencyPort;
import com.wolfmind.infrastructure.memory.InMemoryLock;
import com.wolfmind.infrastructure.memory.InMemorySnapshotStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 进程内适配器装配；各 Bean 保留原有存储模式条件。 */
@Configuration
public class MemoryStorageConfiguration {

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    EventStorePort eventStorePort() {
        return new InMemoryEventStore();
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    com.wolfmind.application.scheduling.TurnTaskStorePort turnTaskStorePort() {
        return new com.wolfmind.infrastructure.memory.InMemoryTurnTaskStore();
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    com.wolfmind.application.scheduling.EventConsumptionPort eventConsumptionPort(EventStorePort store) {
        return new com.wolfmind.infrastructure.memory.InMemoryEventConsumption(store,
                (com.wolfmind.infrastructure.memory.GameIdCatalog) store);
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    SnapshotStorePort snapshotStorePort() {
        return new InMemorySnapshotStore();
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    GameStateCachePort gameStateCachePort() {
        return new InMemoryGameStateCache();
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    GameLockPort gameLockPort() {
        return new InMemoryLock();
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    IdempotencyPort idempotencyPort() {
        return new InMemoryIdempotencyPort();
    }

    /**
     * 事件发布器。
     *
     * <p>返回**具体类型**而不是 {@code EventPublisherPort}：WebSocket 扇出要注册为它的
     * 实时监听者（见 {@code WebSocketConfiguration}），按接口类型注入不到那个方法。
     * 返回具体类型不影响 Spring 按接口注入到各用例服务——它同时满足两种匹配。
     */
    @Bean
    InMemoryEventPublisher eventPublisherPort(org.springframework.core.env.Environment environment) {
        return new InMemoryEventPublisher(!"redis".equals(environment.getProperty("wolfmind.storage", "memory")));
    }

    /**
     * 决策痕迹端口（进程内）。
     *
     * <p><b>只暴露端口，不暴露具体实现。</b>先前的写法把 {@code InMemoryAgentTrace} 也注册成 Bean，
     * 于是容器里同时存在两个 {@code AgentTracePort} 候选（具体类与端口），任何按类型注入的地方都会
     * {@code NoUniqueBeanDefinitionException}。Redis 模式下的双写装饰器在自己的配置类里创建内部存储，
     * 因此这里不需要一个可被注入的"存储 Bean"。
     */
    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    AgentTracePort agentTracePort(org.springframework.core.env.Environment environment, TimePort time,
            com.wolfmind.application.port.MetricsPort metrics) {
        return new InMemoryAgentTrace(
                environment.getProperty("wolfmind.agent.trace.finished-max-games", Integer.class, 20),
                environment.getProperty("wolfmind.agent.trace.finished-retention-ms", Long.class, 3_600_000L),
                time::nowMillis, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    com.wolfmind.application.room.RoomIndexPort roomIndexPort() {
        return new com.wolfmind.infrastructure.memory.InMemoryRoomIndex();
    }

    // ------------------------------------------------------------------
    // 认证（Phase 4）
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    com.wolfmind.application.user.UserRepositoryPort userRepositoryPort(EventStorePort eventStore) {
        return new com.wolfmind.infrastructure.memory.InMemoryUserRepository(eventStore);
    }

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    com.wolfmind.application.user.SessionPort sessionPort() {
        return new com.wolfmind.infrastructure.memory.InMemorySessionStore();
    }

    // ------------------------------------------------------------------
    // AI 实验室（Phase 5）
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    com.wolfmind.application.lab.LabStorePort labStorePort() {
        return new com.wolfmind.infrastructure.memory.InMemoryLabStore();
    }
}
