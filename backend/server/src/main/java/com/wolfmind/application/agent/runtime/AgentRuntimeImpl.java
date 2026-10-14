package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.memory.MemoryPort;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.model.TurnResult;
import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import com.wolfmind.application.agent.strategy.StrategyPort;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.port.TimePort;

import java.util.List;
import java.util.Map;

/**
 * Agent Runtime 的默认实现（agent-runtime.md §2、§3）。
 *
 * <p>它把"实例生命周期"与"一次回合的执行链"拼起来，本身不含策略与规则：
 * <ul>
 *   <li>实例管理交给 {@link AgentRegistry}（创建幂等、释放安全）；</li>
 *   <li>回合执行交给 {@link AgentWorkflow}（节点图）；</li>
 *   <li>视图读取交给 {@link AgentViewQueryPort}（角色可见，脱敏在领域侧完成）；</li>
 *   <li>写路径交给 {@link ToolGatewayPort}（唯一入口）。</li>
 * </ul>
 *
 * <p>依赖边界：本类不引用 {@code domain.game.model}（DEP-7），角色与阶段一律用字符串。
 */
public final class AgentRuntimeImpl implements AgentRuntime {

    private final AgentRegistry registry = new AgentRegistry();
    private final AgentViewQueryPort views;
    private final StrategyPort strategy;
    private final ToolGatewayPort gateway;
    private final MemoryPort memory;
    private final AgentTracePort trace;
    private final LlmClient llmClient;
    private final TimePort time;
    private final BudgetGuard budgetGuard;
    private final AgentWorkflow.WorkflowEngineFactory workflowEngineFactory;
    private final SpeechPreviewPort speechPreview;
    private final com.wolfmind.application.port.MetricsPort metrics;
    private final LlmCostGuard costGuard;
    private final Object lifecycleLock = new Object();
    private final Map<String, GameLifecycle> lifecycles = new java.util.HashMap<>();
    private final Map<String, Boolean> recentlyFinished = new java.util.LinkedHashMap<>();

    private static final class GameLifecycle {
        int inFlight;
        boolean closing;
        boolean cleaning;
    }
    private com.wolfmind.application.scheduling.TurnTaskStorePort taskStore;
    private AgentConfig.WorkflowMode speechModeOverride;
    public void bindSpeechMode(AgentConfig.WorkflowMode mode) { this.speechModeOverride = mode; }
    public void bindTaskStore(com.wolfmind.application.scheduling.TurnTaskStorePort store) { this.taskStore = store; }

    public AgentRuntimeImpl(
            AgentViewQueryPort views,
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            LlmClient llmClient,
            TimePort time) {
        this(views, strategy, gateway, memory, trace, llmClient, time, new BudgetGuard(time), null);
    }

    /** 便于测试注入固定时钟的预算守卫。 */
    public AgentRuntimeImpl(
            AgentViewQueryPort views,
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            LlmClient llmClient,
            TimePort time,
            BudgetGuard budgetGuard) {
        this(views, strategy, gateway, memory, trace, llmClient, time, budgetGuard, null);
    }

    /**
     * @param workflowEngineFactory 工作流引擎工厂；{@code null} 时用顺序引擎。
     *                       生产装配注入 LangGraph4j 实现（agent-runtime.md §8）。
     */
    public AgentRuntimeImpl(
            AgentViewQueryPort views,
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            LlmClient llmClient,
            TimePort time,
            BudgetGuard budgetGuard,
            AgentWorkflow.WorkflowEngineFactory workflowEngineFactory) {
        this(views, strategy, gateway, memory, trace, llmClient, time, budgetGuard, workflowEngineFactory,
                SpeechPreviewPort.NONE);
    }

    /** @param speechPreview 发言流式预览出口；{@link SpeechPreviewPort#NONE} 时发言不走流式 */
    public AgentRuntimeImpl(
            AgentViewQueryPort views,
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            LlmClient llmClient,
            TimePort time,
            BudgetGuard budgetGuard,
            AgentWorkflow.WorkflowEngineFactory workflowEngineFactory,
            SpeechPreviewPort speechPreview) {
        this(views, strategy, gateway, memory, trace, llmClient, time, budgetGuard, workflowEngineFactory,
                speechPreview, com.wolfmind.application.port.MetricsPort.NOOP, null);
    }

    /**
     * @param metrics   指标出口（Phase 5 可观测性）；{@code MetricsPort.NOOP} 时不记指标
     * @param costGuard 单局 token 预算；{@code null} 表示不限。超预算时该座位转入规则兜底，
     *                  对局仍会正常结束——这是"模型成本可控"的落点
     */
    public AgentRuntimeImpl(
            AgentViewQueryPort views,
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            LlmClient llmClient,
            TimePort time,
            BudgetGuard budgetGuard,
            AgentWorkflow.WorkflowEngineFactory workflowEngineFactory,
            SpeechPreviewPort speechPreview,
            com.wolfmind.application.port.MetricsPort metrics,
            LlmCostGuard costGuard) {
        this.metrics = metrics == null ? com.wolfmind.application.port.MetricsPort.NOOP : metrics;
        this.costGuard = costGuard;
        this.speechPreview = speechPreview == null ? SpeechPreviewPort.NONE : speechPreview;
        this.views = views;
        this.strategy = strategy;
        this.gateway = gateway;
        this.memory = memory;
        this.trace = trace;
        this.llmClient = llmClient;
        this.time = time;
        this.budgetGuard = budgetGuard;
        this.workflowEngineFactory = workflowEngineFactory;
    }

    @Override
    public AgentHandle createAgent(CreateAgentCommand command) {
        synchronized (lifecycleLock) {
            if (recentlyFinished.containsKey(command.gameId()))
                throw new IllegalStateException("Agent game is finished: " + command.gameId());
            GameLifecycle lifecycle = lifecycles.computeIfAbsent(command.gameId(), ignored -> new GameLifecycle());
            if (lifecycle.closing) throw new IllegalStateException("Agent game is closing: " + command.gameId());
            AgentRegistry.Registration registration = registry.register(
                    command.gameId(), command.playerId(), command.seatNo(), speechModeOverride == null
                            ? command.config() : command.config().withWorkflowMode(speechModeOverride));
            return registration.instance().handle();
        }
    }

    @Override
    public void destroyAgent(AgentHandle handle) {
        if (handle == null) {
            return;
        }
        synchronized (lifecycleLock) {
            registry.find(handle.gameId(), handle.playerId()).ifPresent(AgentInstance::destroy);
            registry.unregister(handle.gameId(), handle.playerId());
        }
    }

    @Override
    public void destroyGame(String gameId) {
        GameLifecycle lifecycle;
        synchronized (lifecycleLock) {
            lifecycle = lifecycles.get(gameId);
            if (lifecycle == null) return;
            lifecycle.closing = true;
            registry.all().stream()
                    .filter(instance -> instance.handle().gameId().equals(gameId))
                    .forEach(AgentInstance::destroy);
            registry.unregisterGame(gameId);
            if (lifecycle.inFlight != 0 || lifecycle.cleaning) return;
            lifecycle.cleaning = true;
        }
        finishGame(gameId, lifecycle);
    }

    private void finishGame(String gameId, GameLifecycle lifecycle) {
        try {
            trace.finishGame(gameId);
        } finally {
            if (costGuard != null) costGuard.forget(gameId);
            synchronized (lifecycleLock) {
                lifecycles.remove(gameId, lifecycle);
                recentlyFinished.put(gameId, true);
                while (recentlyFinished.size() > 1_000)
                    recentlyFinished.remove(recentlyFinished.keySet().iterator().next());
            }
        }
    }

    public int trackedGameCount() {
        synchronized (lifecycleLock) { return lifecycles.size(); }
    }

    @Override
    public int activeInstanceCount(String gameId) {
        return registry.countInGame(gameId);
    }

    @Override
    public TurnResult executeTurn(TurnRequest request) {
        AgentInstance instance;
        GameLifecycle lifecycle;
        synchronized (lifecycleLock) {
            lifecycle = lifecycles.get(request.gameId());
            instance = registry.find(request.gameId(), request.playerId()).orElse(null);
            if (instance != null && lifecycle != null && !lifecycle.closing) lifecycle.inFlight++;
            else instance = null;
        }
        if (instance == null) {
            // Rejected late requests must not recreate an already released per-game cache.
            metrics.counter("wolfmind.agent.turn.unregistered", 1);
            return new TurnResult(
                    TurnResult.Status.FAILED, request.gameId(), request.playerId(), null,
                    List.of(), 0, List.of(), null, List.of(),
                    0, 0, 0, 0L, false, "AGENT_NOT_REGISTERED");
        }
        try {
            if (speechModeOverride != null) {
                request = new TurnRequest(request.gameId(), request.playerId(), request.seatNo(), request.role(), request.camp(),
                        request.phase(), request.round(), request.turnIndex(), request.version(), request.deadline(), request.view(),
                        request.availableTools(), request.budget(), instance.config(), request.fence());
            }
            return instance.execute(request, this::runWorkflow);
        } finally {
            boolean finish;
            synchronized (lifecycleLock) {
                lifecycle.inFlight--;
                finish = lifecycle.closing && lifecycle.inFlight == 0 && !lifecycle.cleaning;
                if (finish) lifecycle.cleaning = true;
            }
            if (finish) finishGame(request.gameId(), lifecycle);
        }
    }

    /** Startup validation and real turns use the same handler construction. */
    private AgentWorkflow workflow(TurnRequest request) {
        return new AgentWorkflow(
                strategy,
                gateway,
                memory,
                trace,
                new PromptBuilder(),
                new LlmInvoker(llmClient, "unset", time).bindTaskStore(taskStore),
                budgetGuard,
                time,
                workflowEngineFactory,
                request == null || request.fence() == null || speechPreview == SpeechPreviewPort.NONE ? speechPreview : preview -> {
                    if (taskStore != null && taskStore.valid(request.fence(), time.nowMillis())) speechPreview.publish(preview);
                },
                metrics,
                costGuard);
    }

    public void validateWorkflowEngine() { workflow(null).validateEngine(); }

    private TurnResult runWorkflow(AgentInstance instance, TurnRequest request) {
        long startedAt = time.nowMillis();
        AgentWorkflow workflow = workflow(request);
        AgentWorkflow.FlowContext state = workflow.run(request, instance.handle());
        TurnResult raw = workflow.result(state, null);
        // latencyMs 在收敛之后补，避免把"构造结果"的时间算进模型延迟
        return new TurnResult(
                raw.status(),
                raw.gameId(),
                raw.playerId(),
                raw.submittedAction(),
                raw.submittedEventId(),
                raw.version(),
                raw.candidateSet(),
                raw.chosen(),
                raw.toolCalls(),
                raw.llmCalls(),
                raw.promptTokens(),
                raw.completionTokens(),
                time.nowMillis() - startedAt,
                raw.fallbackUsed(),
                raw.errorCode());
    }

    /** 供测试与分析读取某局某座位的轨迹。 */
    public List<AgentTracePort.AgentTraceEvent> traces(String gameId, String playerId) {
        return trace.traces(gameId, playerId);
    }

    /** 供运维与测试断言：某座位是否仍有实例。 */
    public boolean hasInstance(String gameId, String playerId) {
        return registry.find(gameId, playerId).isPresent();
    }

    @Override
    public java.util.Optional<AgentConfig> configOf(String gameId, String playerId) {
        return registry.find(gameId, playerId).map(AgentInstance::config);
    }
}
