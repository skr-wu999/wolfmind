package com.wolfmind.infrastructure.agent.wiring;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.application.agent.memory.MemoryPort;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.runtime.BudgetGuard;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.ai.LangChain4jLlmClient;
import com.wolfmind.infrastructure.ai.WorkflowEngineSelection;
import com.wolfmind.infrastructure.ai.LlmSettingsLoader;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;

import java.util.List;

/**
 * Phase 3 装配点：把 Agent 子系统的全部部件接成一个可用的整体。
 *
 * <p>它是"从零到能跑一局 AI 对局"的唯一入口，也是你在 IDE 里验证 9 个 AI 时唯一需要 new 的东西：
 * <pre>
 *   Phase3AgentAssembly ai = Phase3AgentAssembly.create(
 *           gameViewQuery, submitActionService, idempotencyPort, timePort, tracePort,
 *           memoryPort, llmSettings, strategy, agentConfig);
 *   ai.controllerAdapter().dispatch(signal, null);   // 每收到一个 TURN_GRANTED 调一次
 * </pre>
 *
 * <p><b>idempotencyPort 必须与应用层是同一份</b>：网关的 G12（幂等门）与应用层的命令幂等
 * 读同一个存储（tool-gateway.md §9"同一份存储，两层各自校验"）。各持一份会让"重复提交返回
 * 首次结果"退化成领域侧的 {@code ACTION_ALREADY_SUBMITTED} 拒绝——事件不会重复，但策略层
 * 拿不到首次的事件 id 与版本。
 *
 * <p>参数来源：{@code LlmSettings} 由 {@link LlmSettingsLoader} 从
 * 系统属性 / 环境变量 / {@code wolfmind-llm.properties} 解析。**未配置时不会失败**：
 * {@code LlmClient.available()} 为 false，Runtime 跳过 LLM 直接走规则兜底，
 * 9 个 AI 依然能完成对局（agent-runtime.md §10 最后一行）。
 *
 * <p>依赖方向：本类在 infrastructure，因此允许引用 LangChain4j 与 LangGraph4j（DEP-5）；
 * 它把这两个 SDK 收敛在 {@code infrastructure.ai} 的实现里，应用层只看到
 * {@code LlmClient} 与 {@code WorkflowEngine} 端口。
 */
public final class Phase3AgentAssembly {

    private final AgentRuntimeImpl runtime;
    private final AIControllerAdapter controllerAdapter;
    private final AgentTracePort trace;
    private final MemoryPort memory;
    private final LlmSettings llmSettings;

    private Phase3AgentAssembly(
            AgentRuntimeImpl runtime,
            AIControllerAdapter controllerAdapter,
            AgentTracePort trace,
            MemoryPort memory,
            LlmSettings llmSettings) {
        this.runtime = runtime;
        this.controllerAdapter = controllerAdapter;
        this.trace = trace;
        this.memory = memory;
        this.llmSettings = llmSettings;
    }

    /**
     * 装配整套 AI 子系统。
     *
     * @param gameViewQuery    只读视图端口（REST/回放用的那个，含可见性过滤）
     * @param submitAction     唯一写路径的应用服务
     * @param time             时间端口
     * @param trace            轨迹端口；测试可传内存实现
     * @param memory           记忆端口；测试可传内存实现
     * @param llmSettings      LLM 参数；未配置时全程规则兜底
     * @param strategy         策略层实现（{@code WolfMindStrategy} 或测试替身）
     * @param defaultConfig    默认座位配置
     */
    public static Phase3AgentAssembly create(
            GameViewQueryPort gameViewQuery,
            SubmitActionApplicationService submitAction,
            com.wolfmind.application.port.IdempotencyPort idempotency,
            TimePort time,
            AgentTracePort trace,
            MemoryPort memory,
            LlmSettings llmSettings,
            com.wolfmind.application.agent.strategy.StrategyPort strategy,
            AgentConfig defaultConfig) {

        AgentViewQueryPort views = new AgentViewAdapter(gameViewQuery);
        ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, gameViewQuery, submitAction, idempotency, trace, time);
        LlmClient llmClient = llmSettings.configured()
                ? new LangChain4jLlmClient(llmSettings)
                : LangChain4jLlmClient.disabled();

        // Standalone assembly uses the architecture baseline; Spring selects its engine through configuration.
        AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, strategy, gateway, memory, trace, llmClient, time, new BudgetGuard(time),
                new WorkflowEngineSelection("LANGGRAPH4J"));
        runtime.validateWorkflowEngine();
        AIControllerAdapter adapter = new AIControllerAdapter(runtime, views, gateway, time, defaultConfig);
        return new Phase3AgentAssembly(runtime, adapter, trace, memory, llmSettings);
    }

    /** 便捷装配：端口用内存实现，策略层用真实 {@code WolfMindStrategy}。 */
    public static Phase3AgentAssembly createDefault(
            GameViewQueryPort gameViewQuery,
            SubmitActionApplicationService submitAction,
            com.wolfmind.application.port.IdempotencyPort idempotency,
            TimePort time,
            AgentTracePort trace) {
        LlmSettings settings = LlmSettingsLoader.load();
        MemoryPort memory = new InMemoryMemoryPort();
        AgentViewQueryPort views = new AgentViewAdapter(gameViewQuery);
        ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, gameViewQuery, submitAction, idempotency, trace, time);
        WolfMindStrategy strategy = new WolfMindStrategy(gateway);
        AgentConfig config = defaultConfigFor(settings);
        return create(gameViewQuery, submitAction, idempotency, time, trace, memory, settings,
                strategy, config);
    }

    /**
     * 默认座位配置：把 {@code LlmSettings.defaultModel} 作为模型名。
     *
     * <p>模型名与"用哪个供应商"分开：前者是实验室维度（{@code AgentConfig}），
     * 后者是部署维度（{@code LlmSettings}）。因此换供应商不用改配置对象。
     */
    public static AgentConfig defaultConfigFor(LlmSettings settings) {
        if (!settings.configured()) {
            return AgentConfig.ruleBaseline("rule-baseline");
        }
        return new AgentConfig(
                "default",
                "默认 AI 座位",
                settings.defaultModel(),
                0.6,
                0.2,
                AgentConfig.StrategyProfile.BALANCED,
                "冷静、简洁、按证据说话",
                AgentConfig.WorkflowMode.A,
                60,
                0.5,
                8,
                3,
                "default",
                com.wolfmind.application.agent.model.TurnBudget.defaultFor(settings.interactionMode()));
    }

    public AgentRuntime runtime() {
        return runtime;
    }

    public AIControllerAdapter controllerAdapter() {
        return controllerAdapter;
    }

    public AgentTracePort trace() {
        return trace;
    }

    public MemoryPort memory() {
        return memory;
    }

    public LlmSettings llmSettings() {
        return llmSettings;
    }

    /** 启动摘要：不含密钥，便于在日志里确认"到底连的是哪个模型"。 */
    public String describeForLog() {
        return "Phase3AgentAssembly{" + llmSettings.describeForLog()
                + " llmAvailable=" + llmSettings.configured() + "}";
    }

    /** 便于测试：本次装配是否启用真实 LLM。 */
    public boolean usesRealLlm() {
        return llmSettings.configured();
    }

    /** 便于接线层列出所有需要的端口，避免漏注入。 */
    public static List<String> requiredPorts() {
        return List.of(
                "GameViewQueryPort（只读视图，含可见性过滤）",
                "SubmitActionApplicationService（唯一写路径）",
                "TimePort（回合 deadline 与轨迹时间戳）",
                "AgentTracePort（AGENT_ 决策事件）",
                "MemoryPort（短期/长期记忆）");
    }
}
