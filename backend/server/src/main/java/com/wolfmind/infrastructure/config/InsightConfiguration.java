package com.wolfmind.infrastructure.config;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.infrastructure.ai.LlmSettingsLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 对局分析与实验室批次用例装配。 */
@Configuration
public class InsightConfiguration {

    /**
     * AI 分析查询用例（api-spec.md §7）。
     *
     * <p>它只依赖只读端口，因此只读部署也能装配；写路径与它无关。
     */
    @Bean
    com.wolfmind.application.analysis.AnalysisQueryService analysisQueryService(
            GameViewQueryPort gameViewQuery, AgentTracePort trace) {
        return new com.wolfmind.application.analysis.AnalysisQueryService(gameViewQuery, trace);
    }

    /**
     * 实验室写用例。
     *
     * <p>{@code lab.maxConcurrentGames} 在这里读一次并作为**值**注入：
     * 应用层不得依赖 Spring 的 {@code Environment}（DEP-1 的方向要求）。
     */
    @Bean
    com.wolfmind.application.lab.LabApplicationService labApplicationService(
            com.wolfmind.application.lab.LabStorePort store,
            TimePort time,
            org.springframework.core.env.Environment environment) {
        return new com.wolfmind.application.lab.LabApplicationService(
                store, time, labMaxConcurrentGames(environment));
    }

    @Bean
    com.wolfmind.application.lab.LabQueryService labQueryService(
            com.wolfmind.application.lab.LabStorePort store, AgentTracePort trace) {
        return new com.wolfmind.application.lab.LabQueryService(
                store, trace, com.wolfmind.infrastructure.ai.LlmSettingsLoader.load());
    }

    /**
     * 批量实验运行器。
     *
     * <p>它只做"按并发上限开出对局"与"汇总进度"；对局的推进仍是生产环境的
     * 应用层回合调度器——实验室与真人对局共用同一条路径。
     */
    @Bean
    com.wolfmind.application.lab.LabBatchRunner labBatchRunner(
            com.wolfmind.application.lab.LabStorePort store,
            com.wolfmind.application.game.StartGameApplicationService startGame,
            GameRepositoryPort repository,
            AgentRuntime agentRuntime,
            TimePort time,
            org.springframework.core.env.Environment environment) {
        int maxConcurrent = labMaxConcurrentGames(environment);
        return new com.wolfmind.application.lab.LabBatchRunner(
                store, startGame, repository, agentRuntime,
                com.wolfmind.infrastructure.ai.LlmSettingsLoader.load(), time, maxConcurrent);
    }

    /**
     * 实验室并发上限。
     *
     * <p>接受两个键名：{@code lab.maxConcurrentGames} 是 api-spec.md §8 的写法，
     * {@code wolfmind.lab.max-concurrent-games} 是本项目其余配置的 kebab-case 风格。
     *
     * <p><b>为什么必须显式接受两种</b>：Spring 的 {@code Environment.getProperty} 对环境变量做的是
     * "把键名原样转换"（{@code lab.maxConcurrentGames} → {@code LAB_MAXCONCURRENTGAMES}），
     * 而不是模糊匹配。于是运维按直觉写成 {@code LAB_MAX_CONCURRENT_GAMES} 时会**静默失效**——
     * 上限仍是 8，而配置看起来已经生效。同时接受两种写法把这个坑移出运维的手。
     */
    private static int labMaxConcurrentGames(org.springframework.core.env.Environment environment) {
        Integer documented = environment.getProperty("lab.maxConcurrentGames", Integer.class);
        if (documented != null) {
            return documented;
        }
        return environment.getProperty("wolfmind.lab.max-concurrent-games", Integer.class, 8);
    }

    /**
     * 实验室扫描调度。
     *
     * <p>受 {@code wolfmind.lab.enabled} 控制（默认开启）：契约测试自己调
     * {@code runner.tick()} 并断言单轮行为，若调度线程同时跑，结果就取决于线程调度——
     * 与回合调度的启停配置采用相同处理方式。
     */
    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ScheduledExecutorService labScheduler(
            com.wolfmind.application.lab.LabBatchRunner runner,
            org.springframework.core.env.Environment environment) {
        java.util.concurrent.ScheduledExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "wolfmind-lab");
                    thread.setDaemon(true);
                    return thread;
                });
        if (!environment.getProperty("wolfmind.lab.enabled", Boolean.class, true)) {
            return executor;
        }
        long interval = environment.getProperty("wolfmind.lab.tick-interval-ms", Long.class, 2_000L);
        executor.scheduleWithFixedDelay(() -> {
            try {
                runner.tick();
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(InsightConfiguration.class)
                        .warn("实验室扫描失败，下一轮重试", error);
            }
        }, interval, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
        return executor;
    }
}
