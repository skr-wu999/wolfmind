package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.memory.MemoryPort;
import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.model.TurnResult;
import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import com.wolfmind.application.agent.speech.SpeechPreviewStream;
import com.wolfmind.application.agent.strategy.StrategyPort;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.TimePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 回合的工作流实现（strategy-layer.md §6 的节点图）。
 *
 * <p>节点分工严格对应文档：代码节点做确定性的事（事实抽取、打分、候选、校验、守卫、提交），
 * {@code decide} / {@code express} / {@code repair} / {@code rewrite} 调 LLM，且调用次数受预算约束。
 *
 * <p>三条不可破坏的性质：
 * <ol>
 *   <li><b>降级不绕过领域</b>：兜底产出的是合法 {@code ActionProposal}，仍经工具网关 → 应用层 →
 *       {@code Game.handle}，因此仍产生领域事件（agent-runtime.md §10 末句）；</li>
 *   <li><b>信息边界</b>：上下文由 {@code ContextAssembler} 从角色可见视图装配，
 *       {@code JUDGE} 事实与他人私有事实在结构上进不来（§6 规则 1）；</li>
 *   <li><b>提交幂等</b>：幂等键含回合版本，同一回合重复执行只生效一次（§9）。</li>
 * </ol>
 */
public final class AgentWorkflow {

    /** 网关的目标非法拒绝码（tool-gateway.md §5）；本层不依赖基础设施的码表类。 */
    private static final String INVALID_TARGET = "ACTION_INVALID_TARGET";
    /** 兜底提交的总尝试次数：每次目标非法时换下一个候选，仍受网关的工具调用预算约束。 */
    private static final int FALLBACK_ATTEMPTS = 3;

    private final StrategyPort strategy;
    private final ToolGatewayPort gateway;
    private final MemoryPort memory;
    private final AgentTracePort trace;
    private final PromptBuilder promptBuilder;
    private final LlmInvoker llmInvoker;
    private final BudgetGuard budgetGuard;
    private final TimePort clock;
    private final WorkflowEngineFactory engineFactory;
    private final SpeechPreviewPort speechPreview;
    private final com.wolfmind.application.port.MetricsPort metrics;
    private final LlmCostGuard costGuard;

    /**
     * 工作流引擎工厂：由装配层提供（生产用 LangGraph4j 实现），接收本回合的节点表并返回引擎。
     *
     * <p>做成工厂而不是直接注入引擎实例，是因为节点处理器是每回合新建的闭包：
     * 引擎必须在拿到节点表之后才能建图，否则图里没有节点。
     */
    @FunctionalInterface
    public interface WorkflowEngineFactory {
        WorkflowEngine create(Map<WorkflowEngine.WorkflowNode, WorkflowEngine.NodeHandler> handlers);
        default String engineName() { return "CUSTOM"; }
    }

    public AgentWorkflow(
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            PromptBuilder promptBuilder,
            LlmInvoker llmInvoker,
            BudgetGuard budgetGuard,
            TimePort clock) {
        this(strategy, gateway, memory, trace, promptBuilder, llmInvoker, budgetGuard, clock,
                (WorkflowEngineFactory) null);
    }

    /**
     * @param engineFactory 工作流引擎工厂；{@code null} 时使用顺序引擎。
     *                      生产装配传入 LangGraph4j 实现；测试传 {@code null} 保持离线与确定性。
     */
    public AgentWorkflow(
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            PromptBuilder promptBuilder,
            LlmInvoker llmInvoker,
            BudgetGuard budgetGuard,
            TimePort clock,
            WorkflowEngineFactory engineFactory) {
        this(strategy, gateway, memory, trace, promptBuilder, llmInvoker, budgetGuard, clock, engineFactory,
                SpeechPreviewPort.NONE);
    }

    /**
     * @param speechPreview 发言阶段 {@code decide} 调用的流式预览出口；{@link SpeechPreviewPort#NONE} 时不走流式
     */
    public AgentWorkflow(
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            PromptBuilder promptBuilder,
            LlmInvoker llmInvoker,
            BudgetGuard budgetGuard,
            TimePort clock,
            WorkflowEngineFactory engineFactory,
            SpeechPreviewPort speechPreview) {
        this(strategy, gateway, memory, trace, promptBuilder, llmInvoker, budgetGuard, clock, engineFactory,
                speechPreview, com.wolfmind.application.port.MetricsPort.NOOP, null);
    }

    /**
     * @param metrics   指标出口；{@link com.wolfmind.application.port.MetricsPort#NOOP} 时不记指标
     * @param costGuard 单局 token 预算；{@code null} 表示不限。它在 {@code decide} 里于调用**之前**
     *                  判断"这一局还该不该再花钱"，超预算时走规则兜底而不是抛异常——
     *                  抛异常会让对局卡住，比多花一次钱更糟（agent-runtime.md §10）
     */
    public AgentWorkflow(
            StrategyPort strategy,
            ToolGatewayPort gateway,
            MemoryPort memory,
            AgentTracePort trace,
            PromptBuilder promptBuilder,
            LlmInvoker llmInvoker,
            BudgetGuard budgetGuard,
            TimePort clock,
            WorkflowEngineFactory engineFactory,
            SpeechPreviewPort speechPreview,
            com.wolfmind.application.port.MetricsPort metrics,
            LlmCostGuard costGuard) {
        this.metrics = metrics == null ? com.wolfmind.application.port.MetricsPort.NOOP : metrics;
        this.costGuard = costGuard;
        this.speechPreview = speechPreview == null ? SpeechPreviewPort.NONE : speechPreview;
        this.strategy = strategy;
        this.gateway = gateway;
        this.memory = memory;
        this.trace = trace;
        this.promptBuilder = llmInvoker.interactionMode() == LlmClient.InteractionMode.FUNCTION_CALLING
                ? new PromptBuilder(gateway, llmInvoker.interactionMode()) : promptBuilder;
        this.llmInvoker = llmInvoker;
        this.budgetGuard = budgetGuard;
        this.clock = clock;
        this.engineFactory = engineFactory;
    }

    /**
     * 一个回合的可变状态。
     *
     * <p>它是单线程对象：{@code AgentInstance} 禁止并发 {@code executeTurn}
     * （agent-runtime.md §11），因此这里不加同步。
     */
    public static final class FlowContext {

        private final TurnRequest request;
        private final AgentRuntime.AgentHandle handle;

        private AgentContext context;
        private List<Candidate> candidates = List.of();
        private String memoryText = "";
        private String rawLlmOutput;
        private ActionProposal proposal;
        private String rejectionReason;
        private boolean echoRewrite;
        private double echoSimilarity;
        private ToolGatewayPort.ToolResult lastToolResult;

        private int llmCalls;
        private int repairRounds;
        private int rewriteRounds;
        private int promptTokens;
        private int completionTokens;
        private String errorCode;
        private boolean fallbackUsed;
        private boolean submitted;
        private int submittedVersion;
        private final List<LlmClient.LlmRequest.Message> conversation = new ArrayList<>();
        private final java.util.Set<String> callIds = new java.util.HashSet<>();
        private LlmClient.ToolCall pendingCall;
        private int toolAttempts;
        private int queryRounds;
        private AgentView authorizedView;
        private SpeechPlan speechPlan;
        private Map<String, String> speechEvidence = Map.of();
        private String expressionContext = "";

        public boolean twoStageSpeech() {
            return request.expectsSpeech()
                    && request.agentConfig().mode() == com.wolfmind.application.agent.model.AgentConfig.WorkflowMode.B;
        }
        public SpeechPlan speechPlan() { return speechPlan; }

        public AgentView view() { return authorizedView == null ? request.view() : authorizedView; }

        private final List<TurnResult.ToolCallRecord> toolCalls = new ArrayList<>();

        public FlowContext(TurnRequest request, AgentRuntime.AgentHandle handle) {
            this.request = request;
            this.handle = handle;
        }

        public TurnRequest request() {
            return request;
        }

        public AgentRuntime.AgentHandle handle() {
            return handle;
        }

        public AgentContext context() {
            return context;
        }

        public void context(AgentContext value) {
            this.context = value;
        }

        public List<Candidate> candidates() {
            return candidates;
        }

        public void candidates(List<Candidate> value) {
            this.candidates = List.copyOf(value);
        }

        public String memoryText() {
            return memoryText;
        }

        public void memoryText(String value) {
            this.memoryText = value == null ? "" : value;
        }

        public String rawLlmOutput() {
            return rawLlmOutput;
        }

        public void rawLlmOutput(String value) {
            this.rawLlmOutput = value;
        }

        public ActionProposal proposal() {
            return proposal;
        }

        public void proposal(ActionProposal value) {
            this.proposal = value;
        }

        public String rejectionReason() {
            return rejectionReason;
        }

        public void rejectionReason(String value) {
            this.rejectionReason = value;
        }

        /** 待重写的原因是复读而非泄密。 */
        public boolean echoRewrite() {
            return echoRewrite;
        }

        public void echoRewrite(boolean value) {
            this.echoRewrite = value;
        }

        /** 首稿被复读检查拦下时的相似度；未命中为 0。 */
        public double echoSimilarity() {
            return echoSimilarity;
        }

        public void echoSimilarity(double value) {
            this.echoSimilarity = value;
        }

        public ToolGatewayPort.ToolResult lastToolResult() {
            return lastToolResult;
        }

        public void lastToolResult(ToolGatewayPort.ToolResult value) {
            this.lastToolResult = value;
        }

        public int llmCalls() {
            return llmCalls;
        }

        public void incrementLlmCalls() {
            this.llmCalls++;
        }

        public int repairRounds() {
            return repairRounds;
        }

        public void incrementRepairRounds() {
            this.repairRounds++;
        }

        public int rewriteRounds() {
            return rewriteRounds;
        }

        public void incrementRewriteRounds() {
            this.rewriteRounds++;
        }

        public void addTokens(int prompt, int completion) {
            this.promptTokens += prompt;
            this.completionTokens += completion;
        }

        public int promptTokens() {
            return promptTokens;
        }

        public int completionTokens() {
            return completionTokens;
        }

        public String errorCode() {
            return errorCode;
        }

        public void errorCode(String value) {
            this.errorCode = value;
        }

        public boolean fallbackUsed() {
            return fallbackUsed;
        }

        public void fallbackUsed(boolean value) {
            this.fallbackUsed = value;
        }

        public boolean submitted() {
            return submitted;
        }

        public void submitted(boolean value) {
            this.submitted = value;
        }

        public int submittedVersion() {
            return submittedVersion;
        }

        public void submittedVersion(int value) {
            this.submittedVersion = value;
        }

        public List<TurnResult.ToolCallRecord> toolCalls() {
            return List.copyOf(toolCalls);
        }

        public void addToolCall(TurnResult.ToolCallRecord record) {
            toolCalls.add(record);
        }
    }

    /** 构造并执行一个回合。 */
    public FlowContext run(TurnRequest request, AgentRuntime.AgentHandle handle) {
        FlowContext state = new FlowContext(request, handle);
        trace.record(event(AgentTracePort.TraceEventType.AGENT_TURN_STARTED, state, Map.of(
                "deadline", request.deadline(),
                "budget", request.budget().toString(),
                "engine", engineFactory == null ? "SEQUENTIAL" : engineFactory.engineName(),
                "config", request.agentConfig().describeForTrace())));
        try {
            return engine().run(state);
        } catch (RuntimeException failure) {
            if (!state.submitted()) throw failure;
            System.getLogger(AgentWorkflow.class.getName()).log(System.Logger.Level.WARNING,
                    "行动已提交，后续回合处理失败：" + failure.getClass().getSimpleName());
            return state;
        }
    }

    /**
     * 取得本次执行用的引擎。
     *
     * <p>节点处理器是**按回合上下文**创建的闭包（它们读写 {@link FlowContext}），因此引擎也必须
     * 每回合构造一次并拿到完整的节点表——否则图里没有任何节点，条件边会指向不存在的节点而构建失败。
     * 注入的 {@link #engineFactory} 负责这件事；未注入时退回顺序引擎。
     */
    public void validateEngine() { engine().validate(); }

    private WorkflowEngine engine() {
        return engineFactory != null
                ? engineFactory.create(nodeHandlers())
                : new SequentialWorkflowEngine(nodeHandlers());
    }

    /**
     * 节点表：一次回合的全部节点处理器。
     *
     * <p>对外暴露是为了让图引擎复用**同一批**处理器定义——两条调度路径共用节点语义，
     * 因此可以用同样的断言验证它们的行为一致。
     */
    public Map<WorkflowEngine.WorkflowNode, WorkflowEngine.NodeHandler> nodeHandlers() {
        var handlers = WorkflowEngine.handlers(
                this::perceiveAndAnalyze,
                this::recall,
                this::summarize,
                this::analyze,
                this::strategize,
                this::decide,
                this::validate,
                this::repair,
                this::leakCheck,
                this::rewrite,
                this::submit,
                this::fallback,
                this::finalize);
        handlers.put(WorkflowEngine.WorkflowNode.EXPRESS, this::express);
        return handlers;
    }

    // ------------------------------------------------------------------
    // 代码节点
    // ------------------------------------------------------------------

    private boolean perceiveAndAnalyze(FlowContext state) {
        // perceive 与 analyze 都是纯函数，合并成一次上下文装配可以避免重复扫描视图
        state.candidates(List.of());
        return true;
    }

    private boolean recall(FlowContext state) {
        int limit = state.request().agentConfig().memoryRecallLimit();
        List<MemoryPort.MemoryEntry> entries = memory.loadLongTerm(
                state.request().agentConfig().agentConfigId(),
                state.request().role(),
                state.request().phase(),
                limit);
        state.memoryText(renderMemory(entries));
        return true;
    }

    private boolean summarize(FlowContext state) {
        // 是否需要压缩由 ContextAssembler 在装配时按 strategy.summarizeThreshold 决定，
        // 这里只负责把决策写进 Trace，便于分析"压缩是否影响了表现"
        return true;
    }

    private boolean analyze(FlowContext state) {
        state.context(strategy.assembleContext(
                state.view(), List.of(), state.request().agentConfig(), state.memoryText()));
        return true;
    }

    private boolean strategize(FlowContext state) {
        List<Candidate> candidates = strategy.candidates(
                state.view(), state.request().role(), state.request().phase(),
                state.request().agentConfig());
        state.candidates(candidates);
        state.context(strategy.assembleContext(
                state.view(), candidates, state.request().agentConfig(), state.memoryText()));
        trace.record(event(AgentTracePort.TraceEventType.AGENT_STRATEGY_DECIDED, state, Map.of(
                "candidates", describeCandidates(candidates),
                "count", candidates.size())));
        trace.record(event(AgentTracePort.TraceEventType.AGENT_CONTEXT_BUILT, state, Map.of(
                "truncationLevel", state.context().truncationLevel(),
                "candidateCount", candidates.size(),
                "memoryHits", state.memoryText().isBlank() ? 0 : 1)));
        return true;
    }

    private boolean decide(FlowContext state) {
        if (!state.request().agentConfig().usesLlm() || !llmInvoker.available()) {
            // 纯规则基线或模型不可用：直接进入兜底，不消耗预算
            return false;
        }
        if (state.twoStageSpeech()) return decideSpeechPlan(state);
        if (nativeTools()) {
            LlmInvoker.Outcome outcome = invokeTools(state, promptBuilder.build(state.request(), state.context()),
                    state.request().agentConfig().temperature(), true);
            if (!outcome.ok()) { state.errorCode(outcome.errorCode()); return false; }
            if (outcome.errorCode() != null) {
                state.errorCode(outcome.errorCode());
                state.rejectionReason(outcome.errorMessage());
            }
            state.rawLlmOutput(outcome.text());
            return true;
        }
        // 软截止（deadline − softDeadlineMs）到期就不要再起一次模型调用：
        // 一次真实调用可能耗时数十秒，起步时已经接近 deadline 的话，必然赶不上提交窗口，
        // 结果是网关以 BUDGET_EXCEEDED 拒掉已经算出来的动作——比"直接兜底"更差，
        // 因为白烧了 token 且丢掉了一个合法动作（agent-runtime.md §13）。
        if (budgetGuard.pastSoftDeadline(state)) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_TURN_TIMEOUT, state,
                    Map.of("stage", "SOFT_DEADLINE", "remainingMs", remainingMs(state))));
            state.errorCode("SOFT_DEADLINE");
            return false;
        }
        if (!budgetGuard.tryConsumeLlmCall(state)) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_TURN_TIMEOUT, state, Map.of("stage", "BUDGET_LLM")));
            state.errorCode("BUDGET_LLM");
            return false;
        }
        // 单局 token 预算：在调用之前判断。已经花掉的追不回来，能省的只有下一次。
        if (costGuard != null && !costGuard.allow(state.request().gameId())) {
            metrics.counter(com.wolfmind.application.port.MetricsPort.Names.LLM_BUDGET_REJECTIONS, 1);
            trace.record(event(AgentTracePort.TraceEventType.AGENT_TURN_TIMEOUT, state,
                    Map.of("stage", "BUDGET_TOKENS",
                            "spentTokens", costGuard.tokensSpent(state.request().gameId()))));
            state.errorCode("BUDGET_TOKENS");
            return false;
        }
        SpeechPreviewStream preview = previewFor(state);
        LlmInvoker.Outcome outcome = llmInvoker.invoke(
                promptBuilder.build(state.request(), state.context()),
                state.request().agentConfig().temperature(),
                state.request(),
                preview);
        if (preview != null) {
            preview.complete(outcome.ok());
        }
        state.incrementLlmCalls();
        state.addTokens(outcome.promptTokens(), outcome.completionTokens());
        if (costGuard != null) {
            costGuard.record(state.request().gameId(), outcome.promptTokens(), outcome.completionTokens());
        }
        recordLlmMetrics(outcome);

        trace.record(event(AgentTracePort.TraceEventType.AGENT_LLM_INVOKED, state, Map.of(
                "model", state.request().agentConfig().model(),
                "provider", llmInvoker.providerId(),
                "promptTokens", outcome.promptTokens(),
                "completionTokens", outcome.completionTokens(),
                "latencyMs", outcome.latencyMs(),
                "ok", outcome.ok(),
                "previewSegments", preview == null ? 0 : preview.emittedCount())));

        if (!outcome.ok()) {
            state.errorCode(outcome.errorCode());
            return false;
        }
        state.rawLlmOutput(outcome.text());
        return true;
    }

    private LlmInvoker.Outcome invokeStage(FlowContext state, LlmClient.LlmRequest prompt,
                                          String stage, boolean reserveExpression) {
        int reserve = reserveExpression ? 1 : 0;
        if (state.llmCalls() + reserve >= state.request().budget().maxLlmCalls()
                || budgetGuard.pastSoftDeadline(state))
            return LlmInvoker.Outcome.failure("BUDGET_LLM_OR_DEADLINE", "阶段预算或时间耗尽", false);
        if (costGuard != null && !costGuard.allow(state.request().gameId()))
            return LlmInvoker.Outcome.failure("BUDGET_TOKENS", "对局预算耗尽", false);
        long remaining = state.request().deadline() - clock.nowMillis() - state.request().budget().softDeadlineMs();
        // Planning gets at most half the remaining time, leaving time for expression and submission.
        long timeout = reserveExpression ? Math.max(1, remaining / 2) : Math.max(1, remaining);
        var effective = new LlmClient.LlmRequest(prompt.model(), prompt.temperature(), prompt.messages(),
                prompt.maxTokens(), prompt.tools(), timeout, true, prompt.interactionMode());
        var outcome = llmInvoker.invoke(effective, effective.temperature(), state.request());
        state.incrementLlmCalls();
        state.addTokens(outcome.promptTokens(), outcome.completionTokens());
        if (costGuard != null) costGuard.record(state.request().gameId(), outcome.promptTokens(), outcome.completionTokens());
        recordLlmMetrics(outcome);
        trace.record(event(AgentTracePort.TraceEventType.AGENT_LLM_INVOKED, state, Map.of(
                "stage", stage, "model", effective.model(), "provider", llmInvoker.providerId(), "ok", outcome.ok(),
                "promptTokens", outcome.promptTokens(), "completionTokens", outcome.completionTokens(),
                "latencyMs", outcome.latencyMs())));
        return outcome;
    }

    private boolean decideSpeechPlan(FlowContext state) {
        state.speechEvidence = SpeechPlan.publicEvidence(state.view());
        var prompt = promptBuilder.buildSpeechPlan(state.request(), state.context(), state.speechEvidence);
        List<LlmClient.LlmRequest.Message> conversation = new ArrayList<>(prompt.messages());
        int invalidPlans = 0;
        while (state.llmCalls() + 1 < state.request().budget().maxLlmCalls()) {
            boolean readsAllowed = nativeTools() && state.queryRounds < 2
                    && state.llmCalls() + 3 <= state.request().budget().maxLlmCalls()
                    && state.toolAttempts < state.request().budget().maxToolCalls() - 3;
            var specs = prompt.tools().stream().filter(s -> readsAllowed || !isRead(s.name())).toList();
            var outcome = invokeStage(state, new LlmClient.LlmRequest(prompt.model(), prompt.temperature(), conversation,
                    prompt.maxTokens(), specs, prompt.timeoutMs(), true, prompt.interactionMode()), "SPEECH_PLAN", true);
            if (!outcome.ok()) { state.errorCode(outcome.errorCode()); return false; }
            String planJson = outcome.text();
            String rejection = null;
            if (nativeTools()) {
                var calls = outcome.toolCalls();
                boolean bounded = !calls.isEmpty() && calls.size() <= 6 && calls.stream().allMatch(c ->
                        c.callId() != null && !c.callId().isBlank() && c.callId().length() <= 128
                                && c.name() != null && c.name().length() <= 64
                                && c.argumentsJson() != null && c.argumentsJson().length() <= 32768);
                var ids = new java.util.HashSet<String>();
                if (!bounded || calls.stream().anyMatch(c -> !ids.add(c.callId()) || state.callIds.contains(c.callId()))) {
                    state.toolAttempts++;
                    rejection = "PLAN_TOOL_PROTOCOL";
                } else {
                    state.callIds.addAll(ids);
                    conversation.add(LlmClient.LlmRequest.Message.assistant(outcome.text(), calls, outcome.continuation()));
                    if (readsAllowed && calls.stream().allMatch(c -> isRead(c.name()))
                            && calls.size() <= state.request().budget().maxToolCalls() - state.toolAttempts - 3) {
                        state.queryRounds++;
                        for (var call : calls) {
                            if (budgetGuard.pastSoftDeadline(state)) { state.errorCode("SOFT_DEADLINE"); return false; }
                            var read = gateway.executeRead(state.request().toolSession(
                                    state.request().budget().maxToolCalls() - state.toolAttempts), call);
                            state.toolAttempts++;
                            int limit = Math.max(256, state.request().budget().maxTokens() / 2);
                            conversation.add(LlmClient.LlmRequest.Message.tool(call, read.content().length() <= limit
                                    ? read.content() : "{\"ok\":false,\"code\":\"QUERY_TOO_LARGE\"}"));
                            if (read.result() instanceof ToolGatewayPort.ToolResult.Ok ok) {
                                state.addToolCall(TurnResult.ToolCallRecord.allowed(call.name(), List.of("callId=" + call.callId()),
                                        "", ok.version(), 0));
                                if (read.authorizedView() != null) state.authorizedView = mergeAuthorized(state.view(), read.authorizedView());
                            } else {
                                var denied = (ToolGatewayPort.ToolResult.Denied) read.result();
                                state.addToolCall(TurnResult.ToolCallRecord.denied(call.name(), List.of("callId=" + call.callId()), denied.code()));
                            }
                        }
                        // The evidence catalog is fixed for this attempt: newly read private facts never become public evidence.
                        continue;
                    }
                    if (calls.size() == 1 && SpeechPlan.TOOL_NAME.equals(calls.get(0).name())) {
                        planJson = calls.get(0).argumentsJson();
                        state.toolAttempts++;
                        try { state.speechPlan = SpeechPlan.parse(planJson, state.view(), state.speechEvidence); }
                        catch (IllegalArgumentException badPlan) { rejection = "SPEECH_PLAN_INVALID"; }
                    } else {
                        rejection = "PLAN_TOOL_BATCH_REJECTED";
                        state.toolAttempts++;
                    }
                    for (var call : calls) conversation.add(LlmClient.LlmRequest.Message.tool(call,
                            rejection == null ? "{\"ok\":true}" : "{\"ok\":false,\"code\":\"" + rejection + "\"}"));
                }
            } else {
                try { state.speechPlan = SpeechPlan.parse(planJson, state.view(), state.speechEvidence); }
                catch (IllegalArgumentException badPlan) { rejection = "SPEECH_PLAN_INVALID"; }
            }
            if (rejection == null && state.speechPlan != null) {
                var publicView = SpeechPlan.publicView(state.view());
                state.expressionContext = strategy.assembleContext(publicView, state.candidates(),
                        state.request().agentConfig(), "").context();
                state.errorCode(null);
                return true;
            }
            trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state,
                    Map.of("stage", "SPEECH_PLAN", "code", rejection == null ? "SPEECH_PLAN_INVALID" : rejection)));
            state.errorCode("SPEECH_PLAN_INVALID");
            if (++invalidPlans > 1) return false;
            conversation.add(LlmClient.LlmRequest.Message.user("计划未通过检查。仅使用公开证据目录 ID，调用 PrepareSpeechPlan；不要提交行动。"));
        }
        state.errorCode("PLAN_BUDGET_EXHAUSTED");
        return false;
    }

    private boolean express(FlowContext state) {
        if (!state.twoStageSpeech()) return true;
        if (state.speechPlan == null) return false;
        var outcome = invokeExpression(state, null);
        state.rawLlmOutput(outcome.ok() ? outcome.text() : "");
        if (!outcome.ok()) state.errorCode(outcome.errorCode());
        return outcome.ok();
    }

    private LlmInvoker.Outcome invokeExpression(FlowContext state, String rejection) {
        // Never continue the planning conversation: it contains role-private context and query results.
        var prompt = promptBuilder.buildExpression(state.request(), state.speechPlan, state.speechEvidence,
                state.expressionContext, rejection);
        var outcome = invokeStage(state, prompt, "SPEECH_EXPRESSION", false);
        if (!outcome.ok()) return outcome;
        if (!nativeTools()) return outcome;
        var calls = outcome.toolCalls();
        if (calls.size() != 1 || !"SpeakTool".equals(calls.get(0).name()))
            return rejectExpression(state, outcome, "EXPRESSION_TOOL_REJECTED");
        var call = calls.get(0);
        if (call.callId() == null || call.callId().isBlank() || call.callId().length() > 128
                || !state.callIds.add(call.callId()) || state.toolAttempts >= state.request().budget().maxToolCalls() - 1)
            return rejectExpression(state, outcome, "EXPRESSION_TOOL_PROTOCOL");
        var prepared = gateway.prepareCall(state.request().toolSession(
                state.request().budget().maxToolCalls() - state.toolAttempts), call);
        if (!prepared.valid() || prepared.proposal() == null || !state.speechPlan.matches(prepared.proposal()))
            return rejectExpression(state, outcome, "EXPRESSION_PLAN_MISMATCH");
        state.pendingCall = call;
        return normalized(outcome, prepared.proposalJson(), null);
    }

    private LlmInvoker.Outcome rejectExpression(FlowContext state, LlmInvoker.Outcome outcome, String code) {
        state.toolAttempts++;
        trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state,
                Map.of("stage", "SPEECH_EXPRESSION", "code", code)));
        return normalized(outcome, "", code);
    }

    private boolean nativeTools() { return llmInvoker.interactionMode() == LlmClient.InteractionMode.FUNCTION_CALLING; }

    private LlmInvoker.Outcome invokeTools(FlowContext state, LlmClient.LlmRequest prompt, double temperature, boolean allowReads) {
        if (state.conversation.isEmpty()) state.conversation.addAll(prompt.messages());
        else {
            if (state.pendingCall != null) {
                state.conversation.add(LlmClient.LlmRequest.Message.tool(state.pendingCall,
                        "{\"ok\":false,\"code\":\"ACTION_PROPOSAL_REJECTED\"}"));
                state.pendingCall = null;
                state.toolAttempts++;
            }
            state.conversation.add(prompt.messages().get(prompt.messages().size() - 1));
        }
        while (budgetGuard.tryConsumeLlmCall(state) && !budgetGuard.pastSoftDeadline(state)) {
            if (costGuard != null && !costGuard.allow(state.request().gameId())) {
                return LlmInvoker.Outcome.failure("BUDGET_TOKENS", "对局 token 预算耗尽", false);
            }
            boolean readsAllowed = allowReads && state.queryRounds < 2
                    && state.toolAttempts < Math.max(0, state.request().budget().maxToolCalls() - 2)
                    && state.llmCalls() < state.request().budget().maxLlmCalls() - 1;
            List<LlmClient.LlmRequest.ToolSpec> specs = prompt.tools().stream()
                    .filter(spec -> readsAllowed || !isRead(spec.name())).toList();
            if (!readsAllowed) state.conversation.add(LlmClient.LlmRequest.Message.user("查询额度已结束，请直接选择一个行动工具。"));
            var request = new LlmClient.LlmRequest(prompt.model(), temperature, state.conversation,
                    prompt.maxTokens(), specs, Math.max(1, state.request().deadline() - clock.nowMillis()
                    - state.request().budget().softDeadlineMs()), prompt.speechTurn(), prompt.interactionMode());
            LlmInvoker.Outcome outcome = llmInvoker.invoke(request, temperature, state.request(),
                    state.request().expectsSpeech() && speechPreview != SpeechPreviewPort.NONE ? ignored -> {} : null);
            state.incrementLlmCalls();
            state.addTokens(outcome.promptTokens(), outcome.completionTokens());
            if (costGuard != null) costGuard.record(state.request().gameId(), outcome.promptTokens(), outcome.completionTokens());
            recordLlmMetrics(outcome);
            trace.record(event(AgentTracePort.TraceEventType.AGENT_LLM_INVOKED, state, Map.of(
                    "model", prompt.model(), "provider", llmInvoker.providerId(), "ok", outcome.ok(),
                    "promptTokens", outcome.promptTokens(), "completionTokens", outcome.completionTokens(),
                    "latencyMs", outcome.latencyMs(), "toolCallCount", outcome.toolCalls().size())));
            if (!outcome.ok()) return outcome;
            List<LlmClient.ToolCall> calls = outcome.toolCalls();
            if (calls.isEmpty()) return normalized(outcome, "", "模型必须返回工具调用");
            boolean idsValid = calls.size() <= 6 && calls.stream().allMatch(call -> call.callId() != null && !call.callId().isBlank()
                    && call.callId().length() <= 128 && call.name() != null && call.name().length() <= 64
                    && call.argumentsJson() != null && call.argumentsJson().length() <= 32768);
            java.util.Set<String> ids = new java.util.HashSet<>();
            if (!idsValid || calls.stream().anyMatch(call -> !ids.add(call.callId()) || state.callIds.contains(call.callId()))) {
                return normalized(outcome, "", "工具调用 ID 缺失或重复");
            }
            state.callIds.addAll(ids);
            state.conversation.add(LlmClient.LlmRequest.Message.assistant(outcome.text(), calls, outcome.continuation()));
            long writes = calls.stream().filter(call -> !isRead(call.name())).count();
            if ((writes > 0 && calls.size() != 1) || (writes == 0 && (!readsAllowed
                    || calls.size() > state.request().budget().maxToolCalls() - state.toolAttempts - 2))) {
                for (var call : calls) {
                    state.conversation.add(LlmClient.LlmRequest.Message.tool(call,
                            "{\"ok\":false,\"code\":\"TOOL_BATCH_REJECTED\"}"));
                }
                state.toolAttempts++;
                trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state,
                        Map.of("stage", "TOOL_BATCH", "code", "TOOL_BATCH_REJECTED", "callCount", calls.size())));
                return normalized(outcome, "", "工具批次不合法或查询预算耗尽");
            }
            if (writes == 1) {
                LlmClient.ToolCall call = calls.get(0);
                var prepared = gateway.prepareCall(state.request().toolSession(
                        Math.max(0, state.request().budget().maxToolCalls() - state.toolAttempts)), call);
                if (!prepared.valid() || prepared.proposal() == null) {
                    String denialCode = prepared.rejection() == null ? "TOOL_ARGS_INVALID" : prepared.rejection().code();
                    trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state,
                            Map.of("stage", "TOOL_ARGUMENTS", "code", prepared.rejection() == null
                                    ? "TOOL_ARGS_INVALID" : prepared.rejection().code(), "tool", call.name())));
                    state.conversation.add(LlmClient.LlmRequest.Message.tool(call,
                            "{\"ok\":false,\"code\":\"" + denialCode + "\"}"));
                    state.toolAttempts++;
                    return normalized(outcome, "", prepared.rejection() == null ? "不是行动工具" : prepared.rejection().code());
                }
                state.pendingCall = call;
                SpeechPreviewStream preview = previewFor(state);
                if (allowReads && preview != null) {
                    preview.accept(prepared.proposalJson());
                    preview.complete(true);
                }
                return normalized(outcome, prepared.proposalJson(), null);
            }
            state.queryRounds++;
            for (LlmClient.ToolCall call : calls) {
                if (budgetGuard.pastSoftDeadline(state)) return LlmInvoker.Outcome.failure("SOFT_DEADLINE", "查询已过期", false);
                var read = gateway.executeRead(state.request().toolSession(
                        Math.max(0, state.request().budget().maxToolCalls() - state.toolAttempts)), call);
                state.toolAttempts++;
                int resultLimit = Math.max(256, state.request().budget().maxTokens() / 2);
                String content = read.content().length() <= resultLimit ? read.content()
                        : "{\"ok\":false,\"code\":\"TOOL_ARGS_INVALID\",\"message\":\"结果超过上下文预算，请缩小查询范围\"}";
                state.conversation.add(LlmClient.LlmRequest.Message.tool(call, content));
                if (read.result() instanceof ToolGatewayPort.ToolResult.Ok ok) {
                    state.addToolCall(TurnResult.ToolCallRecord.allowed(call.name(), List.of("callId=" + call.callId()), "", ok.version(), 0));
                    if (read.authorizedView() != null) state.authorizedView = mergeAuthorized(state.view(), read.authorizedView());
                } else {
                    var denied = (ToolGatewayPort.ToolResult.Denied) read.result();
                    state.addToolCall(TurnResult.ToolCallRecord.denied(call.name(), List.of("callId=" + call.callId()), denied.code()));
                }
            }
        }
        return LlmInvoker.Outcome.failure("BUDGET_LLM", "模型预算或时间耗尽", false);
    }

    private LlmInvoker.Outcome invokeForPath(FlowContext state, LlmClient.LlmRequest prompt,
                                              double temperature, TurnRequest turn) {
        return nativeTools() ? invokeTools(state, prompt, temperature, false)
                : llmInvoker.invoke(prompt, temperature, turn);
    }

    private static boolean isRead(String name) { return "QueryGameStateTool".equals(name) || "QueryHistoryTool".equals(name); }

    private static LlmInvoker.Outcome normalized(LlmInvoker.Outcome response, String text, String rejection) {
        return new LlmInvoker.Outcome(true, text, 0, 0, 0, rejection == null ? null : "TOOL_ARGS_INVALID", rejection);
    }

    private static AgentView mergeAuthorized(AgentView base, AgentView extra) {
        var speeches = new java.util.LinkedHashSet<>(base.speeches()); speeches.addAll(extra.speeches());
        var facts = new java.util.LinkedHashSet<>(base.privateEvents()); facts.addAll(extra.privateEvents());
        return new AgentView(base.gameId(), base.version(), base.status(), base.phase(), base.round(), base.turnIndex(),
                base.turnOrder(), base.currentTurn(), base.rules(), base.me(), base.other(), base.votes(), base.deaths(),
                List.copyOf(speeches), List.copyOf(facts), base.winner(), base.wolfChat());
    }

    /** 只有发言阶段的首次决策走流式预览；repair / rewrite 的输出直接以最终 {@code PLAYER_SPOKE} 为准。 */
    private SpeechPreviewStream previewFor(FlowContext state) {
        TurnRequest request = state.request();
        if (speechPreview == SpeechPreviewPort.NONE || !request.expectsSpeech()) {
            return null;
        }
        return new SpeechPreviewStream(speechPreview, request.gameId(), request.playerId(), request.seatNo(),
                request.version(), text -> !strategy.leakCheck(text, request.view()).leaked());
    }

    private boolean validate(FlowContext state) {
        if (state.rawLlmOutput() == null) {
            return false;
        }
        if (!state.twoStageSpeech() && nativeTools() && state.pendingCall == null && state.rejectionReason() != null) return true;
        StrategyPort.ValidationResult result = strategy.validate(
                state.rawLlmOutput(), state.candidates(), state.view(),
                state.request().agentConfig());
        if (result.valid() && (!state.twoStageSpeech() || state.speechPlan.matches(result.proposal()))) {
            state.proposal(result.proposal());
            if (state.twoStageSpeech()) { state.rejectionReason(null); state.errorCode(null); }
            return true;
        }
        if (state.twoStageSpeech() && result.valid()) {
            state.rejectionReason("EXPRESSION_PLAN_MISMATCH");
            state.errorCode("EXPRESSION_PLAN_MISMATCH");
            return true;
        }
        state.rejectionReason(result.failureReason());
        state.errorCode(result.failureCode());
        trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state, Map.of(
                "stage", "VALIDATE",
                "code", result.failureCode(),
                "reason", result.failureReason())));
        // 交给 repair 节点处理
        return true;
    }

    private boolean repair(FlowContext state) {
        if (state.proposal() != null) {
            return true;
        }
        if (state.twoStageSpeech()) {
            if (state.speechPlan == null || state.repairRounds() > 0) return false;
            state.incrementRepairRounds();
            var outcome = invokeExpression(state, "EXPRESSION_INVALID");
            if (!outcome.ok()) { state.errorCode(outcome.errorCode()); return false; }
            state.rawLlmOutput(outcome.text());
            validate(state);
            return state.proposal() != null;
        }
        if (!budgetGuard.tryConsumeLlmCall(state)) {
            return false;
        }
        LlmInvoker.Outcome outcome = invokeForPath(state,
                promptBuilder.buildRepair(state.request(), state.context(), state.rawLlmOutput(),
                        state.rejectionReason()),
                state.request().agentConfig().repairTemperature(),
                state.request());
        if (!nativeTools()) {
            state.incrementLlmCalls();
            state.addTokens(outcome.promptTokens(), outcome.completionTokens());
        }
        state.incrementRepairRounds();
        if (!outcome.ok()) {
            state.errorCode(outcome.errorCode());
            return false;
        }
        state.rawLlmOutput(outcome.text());
        StrategyPort.ValidationResult retried = strategy.validate(
                outcome.text(), state.candidates(), state.view(), state.request().agentConfig());
        if (!retried.valid()) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state, Map.of(
                    "stage", "REPAIR",
                    "code", retried.failureCode(),
                    "reason", retried.failureReason())));
            state.rejectionReason(retried.failureReason());
            return false;
        }
        state.proposal(retried.proposal());
        // 校验理由只针对被修复掉的原始输出；留着它会让 REWRITE 把合规的修复结果再改写一次。
        state.rejectionReason(null);
        return true;
    }

    private boolean leakCheck(FlowContext state) {
        if (state.proposal() == null || !state.proposal().hasSpeech()) {
            return true;
        }
        if (state.twoStageSpeech() && !state.speechPlan.matches(state.proposal())) {
            state.rejectionReason("EXPRESSION_PLAN_MISMATCH");
            return true;
        }
        if (state.twoStageSpeech() && !SpeechPlan.expressionSafe(state.proposal().speech())) {
            state.rejectionReason("EXPRESSION_PRIVATE_ASSERTION");
            trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state,
                    Map.of("stage", "EXPRESSION_SAFETY", "code", "EXPRESSION_PRIVATE_ASSERTION")));
            return true;
        }
        StrategyPort.LeakCheckResult result =
                strategy.leakCheck(state.proposal().speech(), state.view());
        if (result.leaked()) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state, Map.of(
                    "stage", "LEAK_CHECK",
                    "kind", String.valueOf(result.violationKind()))));
            state.rejectionReason(result.detail());
            return true;
        }
        StrategyPort.EchoCheckResult echo = echoCheck(state, state.proposal());
        if (echo.echo()) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state, Map.of(
                    "stage", "ECHO_CHECK",
                    "seatNo", echo.seatNo(),
                    "round", echo.round(),
                    "similarity", echo.similarity(),
                    "draft", state.proposal().speech())));
            state.rejectionReason(echo.detail());
            state.echoRewrite(true);
            state.echoSimilarity(echo.similarity());
        }
        return true;
    }

    /** 只查发言回合：投票等阶段附带的短评不算复读。 */
    private StrategyPort.EchoCheckResult echoCheck(FlowContext state, ActionProposal proposal) {
        if (!state.request().expectsSpeech() || proposal == null || !proposal.hasSpeech()) {
            return StrategyPort.EchoCheckResult.clean();
        }
        return strategy.echoCheck(proposal.speech(), state.view());
    }

    /**
     * 防泄漏 / 复读重写，一次机会。
     *
     * <p>退路按拦截原因区分（strategy-layer.md §8.2）：泄密稿不能发，重写失败只能用安全模板；
     * 复读稿已经过了防泄漏，重写失败时仍提交两版中相似度较低的那版，不退成"暂时不表态"。
     */
    private boolean rewrite(FlowContext state) {
        if (state.proposal() == null || state.rejectionReason() == null) {
            return true;
        }
        if (state.twoStageSpeech()) {
            state.incrementRewriteRounds();
            var outcome = invokeExpression(state, "EXPRESSION_SAFETY_OR_ECHO");
            state.proposal(null);
            if (outcome.ok()) {
                state.rawLlmOutput(outcome.text());
                validate(state);
                if (state.proposal() != null) {
                    leakCheck(state);
                    if (state.rejectionReason() != null) state.proposal(null);
                }
            }
            return state.proposal() != null;
        }
        ActionProposal original = state.proposal();
        LlmClient.ToolCall originalCall = state.pendingCall;
        if (!budgetGuard.tryConsumeLlmCall(state)) {
            // 预算耗尽：保证对局不因表达问题卡住（strategy-layer.md §8.2 末句）
            state.proposal(rewriteFailed(state, original));
            return true;
        }
        LlmInvoker.Outcome outcome = invokeForPath(state,
                state.echoRewrite()
                        ? promptBuilder.buildEchoRewrite(state.request(), state.context(),
                                state.proposal().speech(), state.rejectionReason())
                        : promptBuilder.buildRewrite(state.request(), state.context(),
                                state.proposal().speech(), state.rejectionReason()),
                state.request().agentConfig().repairTemperature(),
                state.request());
        if (nativeTools()) state.pendingCall = originalCall;
        if (!nativeTools()) {
            state.incrementLlmCalls();
            state.addTokens(outcome.promptTokens(), outcome.completionTokens());
        }
        state.incrementRewriteRounds();
        if (!outcome.ok()) {
            state.proposal(rewriteFailed(state, original));
            return true;
        }
        StrategyPort.ValidationResult retried = strategy.validate(
                outcome.text(), state.candidates(), state.view(), state.request().agentConfig());
        if (!retried.valid()) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state, Map.of(
                    "stage", "REWRITE_INVALID",
                    "code", String.valueOf(retried.failureCode()),
                    "reason", String.valueOf(retried.failureReason()))));
            state.proposal(rewriteFailed(state, original));
            return true;
        }
        ActionProposal draft = retried.proposal();
        ActionProposal rewritten = new ActionProposal(original.actionType(), original.targetId(),
                original.targetSeatNo(), draft.speech(), original.confidence(), original.reasonCode(), original.notes());
        StrategyPort.LeakCheckResult again = strategy.leakCheck(rewritten.speech(), state.view());
        if (again.leaked()) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state, Map.of(
                    "stage", "LEAK_CHECK_REWRITE",
                    "kind", String.valueOf(again.violationKind()))));
            state.proposal(rewriteFailed(state, original));
            return true;
        }
        StrategyPort.EchoCheckResult echoAgain = echoCheck(state, rewritten);
        if (!echoAgain.echo()) {
            state.proposal(rewritten);
            return true;
        }
        boolean keepOriginal = state.echoRewrite() && state.echoSimilarity() <= echoAgain.similarity();
        trace.record(event(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED, state, Map.of(
                "stage", "ECHO_CHECK_REWRITE",
                "seatNo", echoAgain.seatNo(),
                "round", echoAgain.round(),
                "similarity", echoAgain.similarity(),
                "draft", rewritten.speech(),
                "kept", keepOriginal ? "ORIGINAL" : "REWRITE")));
        state.proposal(keepOriginal ? original : rewritten);
        return true;
    }

    /** 重写没拿到可用稿：复读稿原样提交，泄密稿换安全模板。 */
    private ActionProposal rewriteFailed(FlowContext state, ActionProposal original) {
        return state.echoRewrite() ? original : safeRewrite(state);
    }

    private boolean submit(FlowContext state) {
        if (state.proposal() == null) {
            return false;
        }
        if (state.twoStageSpeech() && (state.speechPlan == null || !state.speechPlan.matches(state.proposal())
                || state.rejectionReason() != null)) return false;
        // 提交预留（deadline − submitReserveMs）之后不再尝试提交：网关会以
        // BUDGET_EXCEEDED 拒掉，而我们更希望把"这一步没提交"记为超时而不是工具拒绝——
        // 两者对策略层与审计的含义不同（agent-runtime.md §13）。
        if (!budgetGuard.withinSubmitWindow(state)) {
            trace.record(event(AgentTracePort.TraceEventType.AGENT_TURN_TIMEOUT, state,
                    Map.of("stage", "SUBMIT_WINDOW", "remainingMs", remainingMs(state))));
            state.errorCode("SUBMIT_WINDOW");
            return false;
        }
        if (nativeTools() && state.toolAttempts >= state.request().budget().maxToolCalls()) return false;
        if (state.twoStageSpeech()) {
            var preview = previewFor(state);
            if (preview != null) {
                String content = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                        Map.of("actionType", "SPEAK", "speech", state.proposal().speech())).toString();
                preview.accept(content);
                preview.complete(true);
            }
        }
        ToolGatewayPort.ToolResult result = gateway.submit(
                state.request().toolSession(Math.max(0, state.request().budget().maxToolCalls() - state.toolAttempts)),
                nativeTools() && state.pendingCall != null ? state.pendingCall.name() : toolNameFor(state.proposal().actionType()),
                state.proposal());
        if (nativeTools()) state.toolAttempts++;
        state.lastToolResult(result);
        if (result instanceof ToolGatewayPort.ToolResult.Ok ok) {
            state.submitted(true);
            state.submittedVersion(ok.version());
            state.addToolCall(TurnResult.ToolCallRecord.allowed(
                    ok.tool(), state.proposal().describeDigest(), String.join(",", ok.eventIds()),
                    ok.version(), 0L));
        } else if (result instanceof ToolGatewayPort.ToolResult.Denied denied) {
            state.addToolCall(TurnResult.ToolCallRecord.denied(
                    denied.tool(), state.proposal().describeDigest(), denied.code()));
            state.errorCode(denied.code());
            dropRejectedTarget(state, state.proposal(), result);
            state.proposal(null);
            if (!state.twoStageSpeech() && nativeTools() && state.repairRounds() == 0 && state.pendingCall != null
                    && state.toolAttempts < state.request().budget().maxToolCalls() - 1
                    && budgetGuard.tryConsumeLlmCall(state) && !budgetGuard.pastSoftDeadline(state)) {
                state.conversation.add(LlmClient.LlmRequest.Message.tool(state.pendingCall,
                        "{\"ok\":false,\"code\":\"ACTION_REJECTED\"}"));
                state.pendingCall = null;
                state.incrementRepairRounds();
                state.context(strategy.assembleContext(state.view(), state.candidates(), state.request().agentConfig(), state.memoryText()));
                var repaired = invokeTools(state, promptBuilder.buildRepair(state.request(), state.context(),
                        state.rawLlmOutput(), denied.code()), state.request().agentConfig().repairTemperature(), false);
                if (repaired.ok()) {
                    var validation = strategy.validate(repaired.text(), state.candidates(), state.view(), state.request().agentConfig());
                    if (validation.valid() && !strategy.leakCheck(validation.proposal().speech(), state.view()).leaked()
                            && !strategy.echoCheck(validation.proposal().speech(), state.view()).echo()) {
                        state.proposal(validation.proposal());
                        return submit(state);
                    }
                }
            }
        }
        return state.submitted();
    }

    private boolean fallback(FlowContext state) {
        if (state.submitted()) {
            // 幂等：图引擎的边是无条件的（SUBMIT → FALLBACK → FINALIZE），已经提交过的回合
            // 再次进入本节点时必须原样返回，不能把已提交的动作改写成兜底动作。
            return true;
        }
        state.fallbackUsed(true);
        ToolGatewayPort.ToolResult result = null;
        for (int attempt = 0; attempt < FALLBACK_ATTEMPTS; attempt++) {
            ActionProposal proposal = strategy.fallback(
                    state.candidates(), state.view(), state.request().role(), state.request().phase());
            state.proposal(proposal);
            if (nativeTools() && state.toolAttempts >= state.request().budget().maxToolCalls()) break;
            result = gateway.submit(
                    state.request().toolSession(Math.max(0, state.request().budget().maxToolCalls() - state.toolAttempts)),
                    toolNameFor(proposal.actionType()),
                    proposal);
            if (nativeTools()) state.toolAttempts++;
            state.lastToolResult(result);
            if (result instanceof ToolGatewayPort.ToolResult.Ok ok) {
                state.submitted(true);
                state.submittedVersion(ok.version());
                state.addToolCall(TurnResult.ToolCallRecord.allowed(
                        ok.tool(), proposal.describeDigest(), String.join(",", ok.eventIds()), ok.version(), 0L));
                return true;
            }
            if (!dropRejectedTarget(state, proposal, result)) {
                break;
            }
        }
        state.errorCode(result instanceof ToolGatewayPort.ToolResult.Denied denied
                ? denied.code()
                : "FALLBACK_FAILED");
        return false;
    }

    /**
     * 目标被领域判为非法时把它移出本回合候选，返回是否移除了。
     *
     * <p>夜里刚死、尚未公布的座位在视图里仍显示存活（例如猎人夜里死亡后在 {@code DAY_ANNOUNCE}
     * 之前开枪，女巫同夜毒死的人还"活着"），候选集可能包含它；不移除的话兜底会反复选中同一目标。
     */
    private static boolean dropRejectedTarget(
            FlowContext state, ActionProposal proposal, ToolGatewayPort.ToolResult result) {
        if (!(result instanceof ToolGatewayPort.ToolResult.Denied denied)
                || !INVALID_TARGET.equals(denied.code())
                || proposal == null
                || proposal.targetId() == null) {
            return false;
        }
        List<Candidate> remaining = state.candidates().stream()
                .filter(candidate -> !proposal.targetId().equals(candidate.targetId()))
                .toList();
        if (remaining.size() == state.candidates().size()) {
            return false;
        }
        state.candidates(remaining);
        return true;
    }

    private boolean finalize(FlowContext state) {
        trace.record(event(AgentTracePort.TraceEventType.AGENT_TURN_FINISHED, state, Map.of(
                "chosen", state.proposal() == null ? "-" : state.proposal().actionType(),
                "submitted", state.submitted(),
                "version", state.submittedVersion(),
                "fallbackUsed", state.fallbackUsed(),
                "llmCalls", state.llmCalls(),
                "repairRounds", state.repairRounds(),
                "toolCalls", state.toolCalls().size())));
        metrics.counter(com.wolfmind.application.port.MetricsPort.Names.TURNS_FINISHED, 1);
        if (state.fallbackUsed()) {
            metrics.counter(com.wolfmind.application.port.MetricsPort.Names.TURNS_FALLBACK, 1);
        }
        return true;
    }

    /**
     * 模型调用的成本指标。
     *
     * <p>它与 {@code AGENT_LLM_INVOKED} 轨迹记录的是同一件事，但用途不同：
     * 轨迹回答"这一局这个座位当时怎么想的"，指标回答"整个实例这段时间花了多少钱"。
     * 只有轨迹时，后者需要把每一局的轨迹全读一遍才能算出来。
     */
    private void recordLlmMetrics(LlmInvoker.Outcome outcome) {
        metrics.counter(com.wolfmind.application.port.MetricsPort.Names.LLM_CALLS, 1);
        metrics.counter(com.wolfmind.application.port.MetricsPort.Names.LLM_PROMPT_TOKENS,
                Math.max(0, outcome.promptTokens()));
        metrics.counter(com.wolfmind.application.port.MetricsPort.Names.LLM_COMPLETION_TOKENS,
                Math.max(0, outcome.completionTokens()));
        metrics.timing(com.wolfmind.application.port.MetricsPort.Names.LLM_LATENCY, outcome.latencyMs());
        if (!outcome.ok()) {
            metrics.counter(com.wolfmind.application.port.MetricsPort.Names.LLM_FAILURES, 1);
        }
    }

    /** 收敛 TurnResult；由实例在 run 之后调用。 */
    public TurnResult result(FlowContext state, WorkflowEngine engine) {
        if (state.submitted()) {
            TurnResult.Status status = state.fallbackUsed() ? TurnResult.Status.FALLBACK : TurnResult.Status.SUBMITTED;
            return new TurnResult(
                    status,
                    state.request().gameId(),
                    state.request().playerId(),
                    state.proposal(),
                    toolEventIds(state),
                    state.submittedVersion(),
                    state.candidates(),
                    state.proposal(),
                    state.toolCalls(),
                    state.llmCalls(),
                    state.promptTokens(),
                    state.completionTokens(),
                    0L,
                    state.fallbackUsed(),
                    state.fallbackUsed() ? state.errorCode() : null);
        }
        return new TurnResult(
                TurnResult.Status.TIMEOUT,
                state.request().gameId(),
                state.request().playerId(),
                null,
                List.of(),
                0,
                state.candidates(),
                state.proposal(),
                state.toolCalls(),
                state.llmCalls(),
                state.promptTokens(),
                state.completionTokens(),
                0L,
                state.fallbackUsed(),
                state.errorCode() == null ? "NOT_SUBMITTED" : state.errorCode());
    }

    /** 工具名 ↔ 动作类型的映射（tool-gateway.md §2 的工具目录）。 */
    public static String toolNameFor(String actionType) {
        return switch (actionType) {
            case "SPEAK" -> "SpeakTool";
            case "VOTE" -> "SubmitVoteTool";
            case "WOLF_KILL" -> "KillPlayerTool";
            case "SEER_INSPECT", "WITCH_SAVE", "WITCH_POISON", "WITCH_PASS",
                 "HUNTER_SHOOT", "HUNTER_SKIP", "SEER_SKIP" -> "UseSkillTool";
            case "SKIP" -> "SpeakTool";
            default -> actionType;
        };
    }

    private List<String> toolEventIds(FlowContext state) {
        if (state.lastToolResult() instanceof ToolGatewayPort.ToolResult.Ok ok) {
            return ok.eventIds();
        }
        return List.of();
    }

    /** 安全模板发言：只含阶段相关的通用表态，不含任何事实断言。 */
    private ActionProposal safeRewrite(FlowContext state) {
        ActionProposal original = state.proposal();
        String safe = com.wolfmind.application.agent.strategy.FallbackPlanner.templateSpeech(
                state.request().round(), state.request().phase(), null);
        return new ActionProposal(
                original == null ? "SPEAK" : original.actionType(),
                original == null ? null : original.targetId(),
                original == null ? null : original.targetSeatNo(),
                safe,
                original == null ? 0.3 : original.confidence(),
                "SAFE_TEMPLATE",
                null);
    }

    private String renderMemory(List<MemoryPort.MemoryEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (MemoryPort.MemoryEntry entry : entries) {
            builder.append("- [").append(entry.type()).append("] ").append(entry.content()).append('\n');
        }
        return builder.toString();
    }

    private List<String> describeCandidates(List<Candidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.actionType()
                        + (candidate.targetSeatNo() == null ? "" : ("@" + candidate.targetSeatNo()))
                        + String.format(java.util.Locale.ROOT, ":%.2f", candidate.score()))
                .toList();
    }

    private AgentTracePort.AgentTraceEvent event(
            AgentTracePort.TraceEventType type, FlowContext state, Map<String, Object> payload) {
        return new AgentTracePort.AgentTraceEvent(
                type,
                state.request().gameId(),
                state.request().playerId(),
                state.request().version(),
                state.request().phase(),
                state.request().round(),
                clock.nowMillis(),
                payload);
    }

    /**
     * 距回合截止还剩多少毫秒（可为负）。
     *
     * <p>只用于轨迹与诊断：把"为什么这一回合转入兜底"落成可读数字，
     * 否则同一个 {@code AGENT_TURN_TIMEOUT} 无法区分"模型太慢"与"起步就晚了"。
     */
    private long remainingMs(FlowContext state) {
        return state.request().deadline() - clock.nowMillis();
    }
}
