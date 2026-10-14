package com.wolfmind.infrastructure.config;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.TimePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 事件驱动调度、超时和轮询回退装配。 */
@Configuration
public class SchedulingConfiguration {

    // ------------------------------------------------------------------
    // 对局驱动（Phase 4）：把回合派发给 Agent
    // ------------------------------------------------------------------

    @Bean
    com.wolfmind.application.scheduling.TurnPlanner turnPlanner(GameRepositoryPort repository,
            EventStorePort events, org.springframework.core.env.Environment environment) {
        var pacing = speechPacing(environment);
        return new com.wolfmind.application.scheduling.TurnPlanner(repository, events, pacing);
    }

    @Bean
    com.wolfmind.application.scheduling.TaskSubmissionGuard taskSubmissionGuard(
            com.wolfmind.application.scheduling.TurnTaskStorePort tasks,
            com.wolfmind.application.scheduling.TurnPlanner planner) {
        return new com.wolfmind.application.scheduling.TaskSubmissionGuard(tasks, planner);
    }

    @Bean
    com.wolfmind.application.game.TimeoutApplicationService timeoutApplicationService(GameRepositoryPort repository,
            GameLockPort lock, EventPublisherPort publisher, TimePort time,
            com.wolfmind.application.scheduling.TurnPlanner planner) {
        return new com.wolfmind.application.game.TimeoutApplicationService(repository, lock, publisher, time, planner);
    }

    @Bean(destroyMethod = "close")
    com.wolfmind.application.scheduling.EventDrivenTurnScheduler eventDrivenTurnScheduler(
            com.wolfmind.application.scheduling.EventConsumptionPort events,
            com.wolfmind.application.scheduling.TurnTaskStorePort tasks,
            com.wolfmind.application.scheduling.TurnPlanner planner,
            com.wolfmind.application.agent.runtime.AIControllerAdapter adapter,
            com.wolfmind.application.game.TimeoutApplicationService timeout,
            TimePort time, com.wolfmind.application.port.MetricsPort metrics,
            org.springframework.core.env.Environment environment) {
        var scheduler = new com.wolfmind.application.scheduling.EventDrivenTurnScheduler(events, tasks, planner,
                adapter, timeout, time, metrics,
                Math.max(1, environment.getProperty("wolfmind.agent.max-concurrent-turns", Integer.class, 8)),
                Math.max(1, environment.getProperty("wolfmind.game.driver.queue-size", Integer.class, 64)));
        return scheduler;
    }

    private static String driverMode(org.springframework.core.env.Environment environment) {
        String mode = environment.getProperty("wolfmind.game.driver.mode", "EVENT_DRIVEN").toUpperCase(java.util.Locale.ROOT);
        if (!mode.equals("EVENT_DRIVEN") && !mode.equals("POLLING")) throw new IllegalArgumentException("Invalid game driver mode: " + mode);
        return mode;
    }

    /**
     * 发言阅读停顿（默认开启）：约每秒 8 字，上限 25 s，留给 AI 自己的 60 s 回合窗口至少 35 s。
     * 实验室批量对局没有读者，不停顿。
     */
    static com.wolfmind.application.scheduling.SpeechPacing speechPacing(
            org.springframework.core.env.Environment environment) {
        if (!environment.getProperty("wolfmind.game.speech-pacing.enabled", Boolean.class, true)) {
            return com.wolfmind.application.scheduling.SpeechPacing.NONE;
        }
        return new com.wolfmind.application.scheduling.SpeechPacing(
                environment.getProperty("wolfmind.game.speech-pacing.base-ms", Long.class, 1_500L),
                environment.getProperty("wolfmind.game.speech-pacing.per-char-ms", Long.class, 120L),
                environment.getProperty("wolfmind.game.speech-pacing.max-ms", Long.class, 25_000L),
                gameId -> !gameId.startsWith(com.wolfmind.application.lab.LabBatchRunner.GAME_ID_PREFIX));
    }

    @Bean(destroyMethod = "shutdownNow")
    java.util.concurrent.ScheduledExecutorService pollingFallbackScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wolfmind-polling-fallback");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Start dispatch only after persisted seat configurations have been restored. */
    @Bean
    @org.springframework.core.annotation.Order(1)
    org.springframework.boot.ApplicationRunner startGameScheduling(
            com.wolfmind.application.scheduling.EventDrivenTurnScheduler scheduler,
            GameViewQueryPort gameViews, org.springframework.core.env.Environment environment,
            @org.springframework.beans.factory.annotation.Qualifier("pollingFallbackScheduler")
            java.util.concurrent.ScheduledExecutorService executor) {
        return arguments -> {
            if (!environment.getProperty("wolfmind.game.driver.enabled", Boolean.class, true)) return;
            boolean eventDriven = driverMode(environment).equals("EVENT_DRIVEN");
            scheduler.start(eventDriven);
            if (eventDriven) return;
            executor.scheduleWithFixedDelay(() -> {
                try {
                    for (int page = 1; ; page++) {
                        var batch = gameViews.listGames("RUNNING", null, page, 100);
                        for (var item : batch) if (item.get("gameId") instanceof String id) scheduler.reconcileGame(id);
                        if (batch.size() < 100) break;
                    }
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(SchedulingConfiguration.class)
                            .warn("Polling fallback failed: {}", failure.getClass().getSimpleName());
                }
            }, 200, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
        };
    }
}
