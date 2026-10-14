package com.wolfmind.infrastructure.config;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.infrastructure.ai.LlmSettingsLoader;
import com.wolfmind.infrastructure.memory.SystemTimePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 时间、指标、健康检查和轨迹维护装配。 */
@Configuration
public class ObservabilityConfiguration {

    @Bean
    TimePort timePort() {
        return new SystemTimePort();
    }

    /**
     * 模型配置健康（api-spec.md §9：LLM 不可用只是降级，不把服务判成不健康）。
     *
     * <p><b>它刻意不发起网络调用。</b>健康探针每 15 秒跑一次，把一次模型请求挂上去
     * 既费钱又会让探针超时误报——而它真正要回答的问题是"这个实例有没有配模型"，
     * 那是个本地事实。真正的可达性由启动自检（{@code LlmConnectivityCheck}）与
     * {@code /metrics} 的 {@code llm.failures} 回答。
     */
    @Bean
    com.wolfmind.interfaces.rest.HealthIndicator llmHealthIndicator() {
        return com.wolfmind.interfaces.rest.HealthChecks.toIndicator(() -> {
            LlmSettings settings = LlmSettingsLoader.load();
            boolean configured = settings.configured();
            return new com.wolfmind.interfaces.rest.HealthIndicator.Result(
                    "llm",
                    true,
                    false,
                    configured ? settings.describeForLog() : "未配置模型：AI 走规则兜底，对局仍可完成");
        });
    }

    /**
     * 事件存储健康（内存模式）。
     *
     * <p>内存模式没有外部依赖，但这个检查项仍然要报告出来：否则 {@code /health} 的
     * {@code dependencies: []} 会让人读成"依赖都好"，而它实际的含义是"没有任何检查"。
     */
    @Bean
    @ConditionalOnProperty(name = "wolfmind.storage", havingValue = "memory", matchIfMissing = true)
    com.wolfmind.interfaces.rest.HealthIndicator inMemoryEventStoreHealthIndicator(
            EventStorePort eventStore) {
        return com.wolfmind.interfaces.rest.HealthChecks.toIndicator(() -> {
            try {
                eventStore.currentVersion("__health_probe__");
                return new com.wolfmind.interfaces.rest.HealthIndicator.Result(
                        "event-store", true, true, "进程内事件流（memory 模式，重启即丢）");
            } catch (RuntimeException error) {
                return new com.wolfmind.interfaces.rest.HealthIndicator.Result(
                        "event-store", false, true, "不可用：" + error.getClass().getSimpleName());
            }
        });
    }

    /**
     * 指标出口（Phase 5 可观测性）。
     *
     * <p>返回具体类型而不是端口：{@code /metrics} 只读快照，而测试需要 {@code reset()}。
     * 按接口注入的用例仍然只看到 {@code MetricsPort}。
     */
    @Bean
    com.wolfmind.infrastructure.metrics.InProcessMetrics metricsPort() {
        return new com.wolfmind.infrastructure.metrics.InProcessMetrics();
    }

    /**
     * 单局 token 预算（Phase 5 的"模型成本可控"）。
     *
     * <p>{@code wolfmind.llm.max-tokens-per-game} 默认 {@code 0}（不限）。默认不限额是刻意的：
     * 一个没被验证过的默认上限会在真实对局里悄悄改变行为（AI 中途全部降级成规则基线），
     * 而那种变化看起来像"模型变笨了"。要省钱必须显式配置，并且能在 {@code /metrics} 上看到。
     */
    @Bean
    com.wolfmind.application.agent.runtime.LlmCostGuard llmCostGuard(
            TimePort time, org.springframework.core.env.Environment environment) {
        long limit = environment.getProperty("wolfmind.llm.max-tokens-per-game", Long.class, 0L);
        return new com.wolfmind.application.agent.runtime.LlmCostGuard(limit, time);
    }

    @Bean(destroyMethod = "shutdownNow")
    java.util.concurrent.ScheduledExecutorService agentTraceMaintenance(AgentTracePort trace) {
        var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "wolfmind-agent-trace-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try { trace.maintain(); }
            catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(ObservabilityConfiguration.class)
                        .warn("Agent trace maintenance failed", error);
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
        return timer;
    }
}
