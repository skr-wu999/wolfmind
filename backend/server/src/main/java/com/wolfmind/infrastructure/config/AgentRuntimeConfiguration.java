package com.wolfmind.infrastructure.config;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.runtime.BudgetGuard;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.ai.LangChain4jLlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.infrastructure.ai.LlmSettingsLoader;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Agent Runtime、模型、工作流和工具网关装配。 */
@Configuration
public class AgentRuntimeConfiguration {

    @Bean
    com.wolfmind.application.port.AgentProvisioningPort agentProvisioningPort(AgentRuntime runtime) {
        return new com.wolfmind.application.agent.runtime.AgentProvisioningService(runtime, LlmSettingsLoader.load());
    }

    @Bean
    AgentViewQueryPort agentViewQueryPort(GameViewQueryPort gameViewQuery) {
        return new AgentViewAdapter(gameViewQuery);
    }

    /** Process-level engine choice; invalid values fail startup. */
    @Bean
    com.wolfmind.infrastructure.ai.WorkflowEngineSelection workflowEngineSelection(
            org.springframework.core.env.Environment environment) {
        return new com.wolfmind.infrastructure.ai.WorkflowEngineSelection(
                environment.getProperty("wolfmind.agent.workflow-engine", "LANGGRAPH4J"));
    }

    @Bean
    AgentRuntime agentRuntime(
            AgentViewQueryPort views,
            ToolGatewayAdapter gateway,
            AgentTracePort trace,
            TimePort time,
            com.wolfmind.application.port.MetricsPort metrics,
            com.wolfmind.application.agent.runtime.LlmCostGuard costGuard,
            com.wolfmind.application.scheduling.TurnTaskStorePort tasks,
            com.wolfmind.infrastructure.ai.WorkflowEngineSelection workflowEngineSelection,
            org.springframework.beans.factory.ObjectProvider<com.wolfmind.application.agent.speech.SpeechPreviewPort>
                    speechPreview) {
        WolfMindStrategy strategy = new WolfMindStrategy(gateway);
        LlmSettings settings = LlmSettingsLoader.load();
        var runtime = new AgentRuntimeImpl(
                views,
                strategy,
                gateway,
                new InMemoryMemoryPort(),
                trace,
                settings.configured() ? new LangChain4jLlmClient(settings) : LangChain4jLlmClient.disabled(),
                time,
                new BudgetGuard(time),
                workflowEngineSelection,
                speechPreview.getIfAvailable(() -> com.wolfmind.application.agent.speech.SpeechPreviewPort.NONE),
                metrics,
                costGuard);
        runtime.bindTaskStore(tasks);
        runtime.validateWorkflowEngine();
        org.slf4j.LoggerFactory.getLogger(AgentRuntimeConfiguration.class)
                .info("AI workflow engine: {} (startup validation passed)", workflowEngineSelection.engineName());
        return runtime;
    }

    @Bean
    org.springframework.beans.factory.InitializingBean agentSpeechMode(AgentRuntime runtime,
            org.springframework.core.env.Environment environment) {
        String value = environment.getProperty("wolfmind.agent.speech-mode", "CONFIG").trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("CONFIG", "A", "B").contains(value))
            throw new IllegalArgumentException("wolfmind.agent.speech-mode must be CONFIG, A or B");
        return () -> {
            ((AgentRuntimeImpl) runtime).bindSpeechMode(value.equals("CONFIG") ? null
                    : com.wolfmind.application.agent.model.AgentConfig.WorkflowMode.valueOf(value));
            org.slf4j.LoggerFactory.getLogger(AgentRuntimeConfiguration.class).info("AI speech mode: {}", value);
        };
    }

    /**
     * 工具网关。
     *
     * <p>提成 Bean（原先只是 `agentRuntime` 内的局部变量）是为了让
     * {@code aiControllerAdapter} 也能用它——它内部的写工具就是"提交行动"那条路径。
     * 局部变量无法注入，而复制一份会让幂等与鉴权出现两条独立实现。
     */
    @Bean
    ToolGatewayAdapter toolGatewayAdapter(
            AgentViewQueryPort views,
            GameViewQueryPort gameViewQuery,
            SubmitActionApplicationService submitAction,
            IdempotencyPort idempotency,
            AgentTracePort trace,
            TimePort time,
            com.wolfmind.application.scheduling.TurnTaskStorePort tasks) {
        var gateway = new ToolGatewayAdapter(views, gameViewQuery, submitAction, idempotency, trace, time);
        gateway.bindTaskStore(tasks);
        return gateway;
    }

    /**
     * AI 控制器适配器：把回合信号派发给 Agent Runtime。
     *
     * <p>此前它只被测试脚手架（`NineAiGameRunner`）构造，**生产装配里没有它**——
     * 而它内部的工具网关写工具正是"提交行动"的那条路径（
     * `ToolGatewayAdapter → WriteActionHandler → SubmitActionApplicationService`）。
     * 因此注册它同时也是让驱动能复用既有提交路径、而不另开一条的前提。
     */
    @Bean
    com.wolfmind.application.agent.runtime.AIControllerAdapter aiControllerAdapter(
            AgentRuntime agentRuntime,
            AgentViewQueryPort views,
            ToolGatewayAdapter gateway,
            TimePort time) {
        // 默认配置用规则基线：未显式指定时，Agent 不应因为"没配模型"而无法行动。
        return new com.wolfmind.application.agent.runtime.AIControllerAdapter(
                agentRuntime,
                views,
                gateway,
                time,
                com.wolfmind.application.agent.model.AgentConfig.ruleBaseline("driver-default"));
    }
}
