package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.DecisionTraceArchivePort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.application.room.RoomIndexPort;
import com.wolfmind.application.user.SessionPort;
import com.wolfmind.application.user.UserRepositoryPort;
import com.wolfmind.infrastructure.archive.ArchivingAgentTrace;
import com.wolfmind.infrastructure.archive.SqliteReplayArchive;
import com.wolfmind.infrastructure.archive.TerminalGameArchiver;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository;
import com.wolfmind.infrastructure.sqlite.SqliteDecisionTraceRepository;
import com.wolfmind.infrastructure.sqlite.SqliteUserRepository;
import com.wolfmind.interfaces.rest.HealthChecks;
import com.wolfmind.interfaces.rest.HealthIndicator;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis 存储装配（{@code wolfmind.storage=redis}）。
 *
 * <p>与 {@code infrastructure.config.MemoryStorageConfiguration} 中对应的条件 Bean 互斥。
 * 对局状态与事件流由 Redis 保存，完整终局归档和决策轨迹写入 SQLite；
 * 本机轨迹缓存和提交后唤醒不替代持久化事实，广播由消费组处理。
 *
 * <p>启动时先 {@code PING}：Redis 不可达时立即失败，而不是让第一个请求才暴露配置错误。
 */
@Configuration
@ConditionalOnProperty(name = "wolfmind.storage", havingValue = "redis")
public class RedisStorageConfiguration {

    @Bean
    RedisEventStore redisEventStore(StringRedisTemplate redis) {
        String pong = redis.execute(connection -> connection.ping(), true);
        LoggerFactory.getLogger(RedisStorageConfiguration.class)
                .info("存储模式 redis：已连接 Redis（PING={}）", pong);
        return new RedisEventStore(redis);
    }

    @Bean
    com.wolfmind.application.scheduling.TurnTaskStorePort redisTurnTaskStore(StringRedisTemplate redis) {
        return new RedisTurnTaskStore(redis);
    }

    @Bean
    com.wolfmind.application.scheduling.EventConsumptionPort redisEventConsumption(StringRedisTemplate redis,
                                                                                  RedisEventStore store) {
        return new RedisEventConsumption(redis, store);
    }

    @Bean
    SnapshotStorePort redisSnapshotStore(StringRedisTemplate redis, RedisEventStore eventStore) {
        return new RedisSnapshotStore(redis, eventStore);
    }

    @Bean
    GameLockPort redisLock(StringRedisTemplate redis) {
        return new RedisLock(redis);
    }

    @Bean
    IdempotencyPort redisIdempotencyPort(StringRedisTemplate redis) {
        return new RedisIdempotencyPort(redis);
    }

    @Bean
    RoomIndexPort redisRoomIndex(StringRedisTemplate redis) {
        return new RedisRoomIndex(redis);
    }

    /** 实验室配置与批次记录（redis-schema.md §15）：账号资产，不设 TTL。 */
    @Bean
    com.wolfmind.application.lab.LabStorePort redisLabStore(StringRedisTemplate redis) {
        return new RedisLabStore(redis);
    }

    @Bean
    SessionPort redisSessionStore(StringRedisTemplate redis) {
        return new RedisSessionStore(redis);
    }

    /** 账号凭据落 SQLite（{@code wolfmind.sqlite.path}，默认 {@code data/wolfmind.db}）。 */
    @Bean(destroyMethod = "close")
    UserRepositoryPort sqliteUserRepository(RedisEventStore eventStore, Environment environment) {
        Path path = sqlitePath(environment);
        LoggerFactory.getLogger(RedisStorageConfiguration.class).info("账号库：{}", path);
        return SqliteUserRepository.open("jdbc:sqlite:" + path, eventStore);
    }

    /** 终局事件归档与账号库同一个 SQLite 文件；WAL 下两条连接各自串行写入。 */
    @Bean(destroyMethod = "close")
    SqliteArchiveRepository sqliteArchiveRepository(Environment environment) {
        return new SqliteArchiveRepository("jdbc:sqlite:" + sqlitePath(environment));
    }

    /**
     * 决策痕迹归档（{@code agent_memory} 的 {@code DECISION_TRACE} 行）。
     *
     * <p>它用与事件归档**同一个 SQLite 文件**、但独立一条连接：两条写入者的频率与生命周期不同
     * （事件归档是批次扫描，轨迹是回合内逐条），共用连接会让一次扫描把一次回合的写入挡住。
     * WAL 下多连接是支持的，{@code busy_timeout=5000} 覆盖写锁竞争。
     */
    @Bean(destroyMethod = "close")
    DecisionTraceArchivePort decisionTraceArchivePort(Environment environment) {
        return new SqliteDecisionTraceRepository("jdbc:sqlite:" + sqlitePath(environment));
    }

    /**
     * 轨迹端口在 Redis 模式下是双写实现：进程内供本回合读取，SQLite 供重启后与隔日的分析页读取。
     *
     * <p>没有这一层时，"重启后 AI 分析全空"会看起来像一个缺陷——而它其实是"轨迹只活在进程里"
     * 这个设计的必然后果。归档失败只记 WARN，不影响对局（见 {@code ArchivingAgentTrace}）。
     *
     * <p>进程内存储在这里就地创建而不是作为一个 Bean：容器里只能有一个 {@code AgentTracePort}
     * 候选，否则按类型注入的地方会因为"具体类也是候选"而 {@code NoUniqueBeanDefinitionException}。
     */
    @Bean
    AgentTracePort agentTracePort(DecisionTraceArchivePort archive, RedisEventStore events,
            Environment environment, TimePort time, com.wolfmind.application.port.MetricsPort metrics) {
        return new ArchivingAgentTrace(
                new com.wolfmind.infrastructure.memory.InMemoryAgentTrace(
                        environment.getProperty("wolfmind.agent.trace.finished-max-games", Integer.class, 20),
                        environment.getProperty("wolfmind.agent.trace.finished-retention-ms", Long.class, 3_600_000L),
                        time::nowMillis, metrics), archive, events::requestInsightRebuild, metrics);
    }

    @Bean
    TerminalGameArchiver terminalGameArchiver(GameViewQueryPort gameViews, RedisEventStore eventStore,
                                              SqliteArchiveRepository archive, TimePort time,
                                              com.wolfmind.application.port.MetricsPort metrics,
                                              com.wolfmind.application.game.GameArchivalService archival) {
        return new TerminalGameArchiver(gameViews, eventStore, archive, eventStore::shortenAfterArchive, time, metrics, archival);
    }

    @Bean
    com.wolfmind.application.port.GameStateCachePort redisGameStateCache(StringRedisTemplate redis) {
        return new RedisGameStateCache(redis);
    }

    @Bean
    com.wolfmind.application.port.MatchArchivePort matchArchivePort(SqliteArchiveRepository archive) {
        return new com.wolfmind.infrastructure.archive.SqliteMatchArchive(archive);
    }

    @Bean
    com.wolfmind.application.game.GameArchivalService gameArchivalService(RedisEventStore source,
            com.wolfmind.application.port.MatchArchivePort archive, com.wolfmind.application.port.GameStateCachePort cache, TimePort time) {
        return new com.wolfmind.application.game.GameArchivalService(source,archive,cache,source::shortenAfterArchive,time);
    }

    @Bean(destroyMethod="close")
    com.wolfmind.infrastructure.sqlite.SqliteInsightRepository sqliteInsightRepository(Environment environment) {
        return new com.wolfmind.infrastructure.sqlite.SqliteInsightRepository("jdbc:sqlite:"+sqlitePath(environment));
    }

    @Bean
    com.wolfmind.application.events.GameInsightService gameInsightService(RedisEventStore events,
            com.wolfmind.application.port.MatchArchivePort archive,
            com.wolfmind.infrastructure.sqlite.SqliteInsightRepository insights,TimePort time) {
        return new com.wolfmind.application.events.GameInsightService(events,archive,insights,time);
    }

    @Bean(destroyMethod="close")
    ReliableEventConsumers reliableEventConsumers(StringRedisTemplate redis,RedisEventStore events,
            com.wolfmind.application.port.ReplayArchivePort archive,
            com.wolfmind.application.game.GameArchivalService archival,com.wolfmind.application.events.GameInsightService insights,
            com.wolfmind.interfaces.ws.WebSocketGateway gateway,GameViewQueryPort views,
            com.wolfmind.application.room.GetRoomQueryService rooms,com.wolfmind.application.port.MetricsPort metrics,
            Environment environment,com.wolfmind.application.room.RoomLifecycleService lifecycle) {
        var consumers=new ReliableEventConsumers(redis,events,archive,archival,insights,gateway,views,rooms,metrics,
                environment.getProperty("wolfmind.consumer.claim-idle-ms",Long.class,60000L),lifecycle);
        if(environment.getProperty("wolfmind.consumer.enabled",Boolean.class,true)) consumers.start();
        return consumers;
    }

    @Bean(destroyMethod="shutdown")
    ScheduledExecutorService insightRepairScheduler(RedisEventStore events,
            com.wolfmind.infrastructure.sqlite.SqliteInsightRepository insights,
            ReliableEventConsumers consumers, com.wolfmind.application.port.MetricsPort metrics, Environment environment) {
        var timer=Executors.newSingleThreadScheduledExecutor(runnable->{var thread=new Thread(runnable,"wolfmind-insight-backfill");thread.setDaemon(true);return thread;});
        if(environment.getProperty("wolfmind.consumer.enabled",Boolean.class,true)) timer.scheduleWithFixedDelay(()->{
            try{for(String id:insights.pendingRebuildIds())events.requestInsightRebuild(id);}
            catch(RuntimeException failure){metrics.counter("wolfmind.consumer.cg-insight.repair.failures",1);}
        },0,30000L,TimeUnit.MILLISECONDS);
        return timer;
    }

    /**
     * 归档回放（api-spec.md §6）：事件流过期后，回放页仍要能读到那一局。
     *
     * <p>复用事件归档那条连接：回放是只读查询，与归档写入共用 {@code busy_timeout} 即可，
     * 再开一条只读连接只会多一个需要关闭的资源。
     */
    @Bean
    com.wolfmind.application.port.ReplayArchivePort replayArchivePort(SqliteArchiveRepository archive) {
        return new SqliteReplayArchive(archive);
    }

    @Bean
    com.wolfmind.application.port.ArchivedGameRecoveryPort archivedGameRecovery(
            RedisEventStore eventStore, com.wolfmind.application.port.ReplayArchivePort archive) {
        eventStore.reconcileRetention(archive);
        return new com.wolfmind.application.game.RestoreArchivedGameApplicationService(eventStore, archive, eventStore);
    }

    /**
     * 依赖健康（api-spec.md §9）。
     *
     * <p><b>没有它，{@code /health} 会永远返回 {@code dependencies: []} 与 {@code UP}——
     * 一个声称在检查依赖、实际什么都没检查的端点。</b>那比没有健康检查更糟：
     * 编排层会依据它做决策，而那个依据是假的。
     *
     * <p>Redis 与 SQLite 都是 required：Redis 是事件流（不可用则无法对局），
     * SQLite 在 Redis 模式下同时承载账号库（不可用则登录失败）。
     * 两者任一不可用，实例都该被摘掉而不是继续接流量。
     */
    @Bean
    HealthIndicator redisHealthIndicator(StringRedisTemplate redis) {
        return HealthChecks.toIndicator(() -> {
            try {
                String pong = redis.execute(connection -> connection.ping(), true);
                return new HealthIndicator.Result("redis", "PONG".equalsIgnoreCase(pong), true,
                        "事件流/锁/会话/实验室存储；PING=" + pong);
            } catch (RuntimeException error) {
                return new HealthIndicator.Result("redis", false, true,
                        "不可达：" + error.getClass().getSimpleName());
            }
        });
    }

    @Bean
    HealthIndicator sqliteHealthIndicator(SqliteArchiveRepository archive) {
        return HealthChecks.toIndicator(() -> {
            boolean alive = archive.ping();
            return new HealthIndicator.Result("sqlite", alive, true,
                    alive ? "账号库与事件归档" : "不可读：SELECT 1 失败");
        });
    }

    /**
     * 归档调度：启动后立即补扫一轮，此后每 {@code wolfmind.archive.interval-ms}（默认 30 秒）一轮。
     * {@code wolfmind.archive.enabled=false} 时不调度，未归档终局流不设 TTL。
     */
    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService archiveScheduler(TerminalGameArchiver archiver, Environment environment) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wolfmind-archiver");
            thread.setDaemon(true);
            return thread;
        });
        if (!environment.getProperty("wolfmind.archive.enabled", Boolean.class, true)) {
            return executor;
        }
        long interval = environment.getProperty("wolfmind.archive.interval-ms", Long.class, 30_000L);
        executor.scheduleWithFixedDelay(() -> {
            try {
                archiver.sweep();
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(RedisStorageConfiguration.class).warn("归档扫描失败，下一轮重试", error);
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
        return executor;
    }

    private static Path sqlitePath(Environment environment) {
        Path path = Path.of(environment.getProperty("wolfmind.sqlite.path", "data/wolfmind.db"))
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException("无法创建 SQLite 目录 " + path.getParent(), error);
        }
        return path;
    }
}
