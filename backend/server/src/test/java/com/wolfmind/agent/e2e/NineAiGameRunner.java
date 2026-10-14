package com.wolfmind.agent.e2e;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.TurnResult;
import com.wolfmind.application.agent.model.TurnSignal;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.runtime.BudgetGuard;
import com.wolfmind.application.agent.runtime.AgentWorkflow;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.infrastructure.memory.SystemTimePort;

import java.util.ArrayList;
import java.util.List;

/**
 * 九人 AI 对局驱动器：让 **9 个 AI 座位在无真人参与下完成一整局**。
 *
 * <p>它是 AGENTS.md §11 Phase 3 验收项的实现与证据载体：
 * 「九个 AI 座位能完成一局，秘密角色保持隔离，非法/迟到的模型输出被拒绝或安全处理」。
 *
 * <p>它跑在**生产同构**的链路上（见 {@link InfrastructureGameHarness}）：
 * <pre>
 *   AIControllerAdapter → AgentRuntime → AgentWorkflow
 *      → ToolGatewayAdapter（G1..G13）→ SubmitActionApplicationService → Game.handle → 事件流
 * </pre>
 * 因此 Agent 的每一次行动都经过真实网关校验、真实应用层幂等与锁、真实领域规则。
 *
 * <p>驱动方式与生产一致（agent-runtime.md §4）：开局为每个 AI 座位 {@code createAgent}；
 * 每轮按当前状态派发 {@code TURN_GRANTED} 等价的信号；Agent 未能提交（预算/超时/被拒/模型不可用）
 * 或该阶段本就没有 Agent 行动者时，由超时看护提交 {@code SkipAction} 推进——因此"AI 卡住"
 * 不会让对局停下。
 */
public final class NineAiGameRunner {

    private final InfrastructureGameHarness harness;
    private final String gameId;
    private final AIControllerAdapter adapter;
    private final AgentRuntimeImpl runtime;
    private final InMemoryAgentTrace trace;
    private final List<EventEnvelope> observed = new ArrayList<>();

    private int agentTurns;
    private int submittedTurns;
    private int fallbackTurns;
    private int failedTurns;
    private int timeoutSkips;
    private int toolRejections;
    private int stageAdvanceSteps;
    /** 最近一次 Agent 回合的结果摘要，供诊断与失败信息使用。 */
    private String lastTurnStatus = "-";

    private NineAiGameRunner(
            InfrastructureGameHarness harness,
            String gameId,
            AIControllerAdapter adapter,
            AgentRuntimeImpl runtime,
            InMemoryAgentTrace trace) {
        this.harness = harness;
        this.gameId = gameId;
        this.adapter = adapter;
        this.runtime = runtime;
        this.trace = trace;
    }

    /**
     * 开局并装配。
     *
     * @param harness   基础设施装配（真实应用层 + 内存存储）
     * @param gameId    对局标识
     * @param llmClient LLM 客户端；传 {@link com.wolfmind.application.agent.llm.ScriptedLlmClient} 的
     *                  "不可用"实例或 {@code null} 表示"模型不可用"，全程走规则兜底
     * @param config    座位配置
     * @param seed      角色分配种子（固定值 → 对局可复现）
     */
    public static NineAiGameRunner start(
            InfrastructureGameHarness harness,
            String gameId,
            LlmClient llmClient,
            AgentConfig config,
            long seed) {
        return start(harness, gameId, llmClient, config, seed, null);
    }

    public static NineAiGameRunner start(InfrastructureGameHarness harness, String gameId, LlmClient llmClient,
                                        AgentConfig config, long seed, AgentWorkflow.WorkflowEngineFactory engine) {

        Game game = harness.startNineAiGame(
                gameId,
                com.wolfmind.domain.game.model.RoleComposition.STANDARD_NINE,
                com.wolfmind.domain.game.model.GameRules.DEFAULT,
                seed);

        InMemoryAgentTrace trace = new InMemoryAgentTrace();
        InMemoryMemoryPort memory = new InMemoryMemoryPort();
        TimePort clock = harness.time();

        AgentViewQueryPort views = new AgentViewAdapter(harness.viewQuery());
        ToolGatewayPort gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(),
                harness.idempotency(), trace, clock);
        WolfMindStrategy strategy = new WolfMindStrategy(gateway);

        AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, strategy, gateway, memory, trace, llmClient, clock, new BudgetGuard(clock), engine);
        runtime.validateWorkflowEngine();
        AIControllerAdapter adapter = new AIControllerAdapter(runtime, views, gateway, clock, config);

        NineAiGameRunner runner = new NineAiGameRunner(harness, gameId, adapter, runtime, trace);
        for (int seat = 1; seat <= 9; seat++) {
            runtime.createAgent(new AgentRuntime.CreateAgentCommand(
                    gameId, InfrastructureGameHarness.playerIdForSeat(seat), seat, config));
        }
        runner.reload();
        return runner;
    }

    /** 便捷入口：纯规则基线（不调用 LLM）。 */
    public static NineAiGameRunner startRuleBaseline(
            InfrastructureGameHarness harness, String gameId, long seed) {
        return start(harness, gameId, null, AgentConfig.ruleBaseline("rule-baseline"), seed);
    }

    /**
     * 跑到终局。
     *
     * @param maxSteps 步数上限，防止规则缺陷变成死循环（超限由调用方断言失败）
     * @return 实际执行的动作步数
     */
    public int runUntilFinished(int maxSteps) {
        int steps = 0;
        while (state().status() == GameStatus.RUNNING && steps < maxSteps) {
            steps++;
            step();
            reload();
        }
        return steps;
    }

    /** 只推进指定步数；供诊断与"逐步观察"的测试使用。 */
    public void runSteps(int steps) {
        for (int index = 0; index < steps; index++) {
            if (state().status() != GameStatus.RUNNING) {
                return;
            }
            step();
            reload();
        }
    }

    /** 便于诊断：最后一次 Agent 回合的结果状态。 */
    public String lastTurnStatus() {
        return lastTurnStatus;
    }

    private void step() {
        // 每步只推进 1 秒：回合窗口是 30 秒（GameRules.agentTurnDeadlineMs），
        // 若每步盲推 60 秒，第二个座位就会继承到已被自己推过期的 deadline。
        harness.advanceClock(1_000L);
        reload();

        GamePhase phase = state().phase();
        if (phase.autoSettled()) {
            advancePhase();
            return;
        }
        SeatNo actor = actorToAct();
        if (actor == null) {
            advancePhase();
            return;
        }
        TurnSignal signal = signalFor(actor);
        if (!signal.drivesAgent()) {
            submitTimeoutSkip();
            return;
        }
        TurnResult result = adapter.dispatch(signal, seat -> null);
        countResult(result);
        if (result == null || !result.submitted()) {
            // Agent 未能提交：由超时看护兜底，保证对局继续（agent-runtime.md §10）
            submitTimeoutSkip();
        }
    }

    private void countResult(TurnResult result) {
        if (result == null) {
            failedTurns++;
            return;
        }
        agentTurns++;
        lastTurnStatus = result.status() + "/" + (result.errorCode() == null ? "-" : result.errorCode());
        switch (result.status()) {
            case SUBMITTED -> submittedTurns++;
            case FALLBACK -> fallbackTurns++;
            default -> failedTurns++;
        }
        toolRejections += (int) result.toolCalls().stream()
                .filter(call -> "DENIED".equals(call.decision()))
                .count();
    }

    /** 当前应当表态的座位；没有待行动者时返回 {@code null}。 */
    private SeatNo actorToAct() {
        GameSnapshot snapshot = state();
        return switch (snapshot.phase()) {
            case DAY_DISCUSS, LAST_WORDS -> snapshot.currentTurn() == null
                    ? null
                    : snapshot.currentTurn().seatNo();
            case DAY_VOTE -> snapshot.players().stream()
                    .filter(p -> p.alive() && !snapshot.votes().containsKey(p.seatNo().value()))
                    .map(p -> p.seatNo())
                    .sorted()
                    .findFirst()
                    .orElse(null);
            case NIGHT_WOLF -> snapshot.players().stream()
                    .filter(p -> p.alive() && p.role() == Role.WEREWOLF)
                    .map(p -> p.seatNo())
                    .filter(seat -> snapshot.wolfNominations().stream()
                            .noneMatch(n -> n.wolfSeatNo() == seat.value()))
                    .sorted()
                    .findFirst()
                    .orElse(null);
            case NIGHT_SEER -> aliveSeatByRole(Role.SEER);
            case NIGHT_WITCH -> aliveSeatByRole(Role.WITCH);
            case HUNTER_SHOOT -> snapshot.pendingHunter() == null
                    ? null
                    : SeatNo.of(snapshot.pendingHunter());
            default -> null;
        };
    }

    private SeatNo aliveSeatByRole(Role role) {
        return state().players().stream()
                .filter(p -> p.alive() && p.role() == role)
                .map(p -> p.seatNo())
                .findFirst()
                .orElse(null);
    }

    private TurnSignal signalFor(SeatNo seatNo) {
        GameSnapshot snapshot = state();
        long deadline = snapshot.currentTurn() == null
                ? harness.now() + 30_000L
                : snapshot.currentTurn().deadline();
        return new TurnSignal(
                gameId,
                InfrastructureGameHarness.playerIdForSeat(seatNo.value()),
                seatNo.value(),
                snapshot.phase().name(),
                snapshot.turnIndex(),
                deadline,
                snapshot.version(),
                InfrastructureGameHarness.playerIdForSeat(seatNo.value())
                        .equals(snapshot.playerAt(seatNo).controller().refId())
                        ? snapshot.playerAt(seatNo).controller().type().name()
                        : snapshot.playerAt(seatNo).controller().type().name());
    }

    /**
     * 超时看护：把"未提交"表达成合法领域输入。
     *
     * <p>领域不读时钟（INV-16），因此"谁该跳过"由 {@code Game.timeoutSkips} 回答，
     * 这里只负责逐条提交——与生产路径一致（ddd-design.md §3.2 的职责表）。
     */
    private void submitTimeoutSkip() {
        Game loaded = loadGame();
        var handling = loaded.timeoutSkips(harness.now(), true);
        List<SkipAction> skips = handling.skips();
        if (skips.isEmpty()) {
            advancePhase();
            return;
        }
        for (SkipAction skip : skips) {
            timeoutSkips++;
            Game target = loadGame();
            target.handle(skip, context(target.version()));
            persist(target);
        }
        reload();
    }

    /** 阶段推进：自动结算阶段与"无待行动者"阶段都由应用层显式调用。 */
    private void advancePhase() {
        Game game = loadGame();
        if (game.status() != GameStatus.RUNNING) {
            return;
        }
        List<com.wolfmind.domain.game.event.DomainEvent> events = game.advance(context(game.version()));
        stageAdvanceSteps++;
        if (!events.isEmpty()) {
            persist(game);
        }
        reload();
    }

    /** 从事件存储重新加载聚合，并把新增事件登记到观测流。 */
    private void reload() {
        int before = observed.size();
        List<EventEnvelope> stream = harness.eventStream(gameId);
        if (stream.size() > before) {
            observed.addAll(stream.subList(before, stream.size()));
        }
    }

    private Game loadGame() {
        return harness.repository().load(gameId).game();
    }

    /** 把内存聚合的最新事件追加进事件存储（阶段推进与超时跳过的路径）。 */
    private void persist(Game game) {
        List<com.wolfmind.domain.game.event.DomainEvent> events = game.pendingEvents();
        if (events.isEmpty()) {
            return;
        }
        List<EventEnvelope> envelopes = new ArrayList<>();
        int version = harness.eventStore().currentVersion(gameId) + 1;
        for (com.wolfmind.domain.game.event.DomainEvent event : events) {
            envelopes.add(EventEnvelope.of(event, gameId, version++));
        }
        harness.repository().commit(
                gameId, envelopes.get(0).version() - 1, envelopes,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);
    }

    private ActionContext context(int expectedVersion) {
        return new ActionContext(
                "cmd_" + expectedVersion, harness.now(), "SYSTEM",
                "cmd_" + expectedVersion, "cor_e2e", expectedVersion, 0L);
    }

    /** 当前权威状态：从事件存储加载（不是内存里的旧引用）。 */
    private GameSnapshot state() {
        return loadGame().snapshot();
    }

    // ---------------------------------------------------------------- 断言辅助

    public GameSnapshot snapshot() {
        return state();
    }

    public Camp winner() {
        return state().winner();
    }

    public int round() {
        return state().round();
    }

    public GamePhase phase() {
        return state().phase();
    }

    public int version() {
        return state().version();
    }

    public int agentTurns() {
        return agentTurns;
    }

    public int submittedTurns() {
        return submittedTurns;
    }

    public int fallbackTurns() {
        return fallbackTurns;
    }

    public int failedTurns() {
        return failedTurns;
    }

    public int timeoutSkips() {
        return timeoutSkips;
    }

    public int toolRejections() {
        return toolRejections;
    }

    public int stageAdvanceSteps() {
        return stageAdvanceSteps;
    }

    /** 从事件存储读回的完整事件流。 */
    public List<EventEnvelope> eventStream() {
        return harness.eventStream(gameId);
    }

    public List<String> eventTypes() {
        return harness.eventTypes(gameId);
    }

    /** 事件流里出现过的阶段跳转序列，用于断言"每条边都在转换表内"。 */
    public List<String> phaseTimeline() {
        return eventStream().stream()
                .filter(envelope -> "PHASE_CHANGED".equals(envelope.type()))
                .map(envelope -> envelope.payload().get("from") + "->" + envelope.payload().get("to")
                        + "@" + envelope.payload().get("round"))
                .toList();
    }

    public InMemoryAgentTrace trace() {
        return trace;
    }

    public AgentRuntimeImpl runtime() {
        return runtime;
    }

    /** 每个 AI 座位都产生过轨迹（证明 9 个座位都被真正驱动过）。 */
    public boolean everySeatHasTrace() {
        for (int seat = 1; seat <= 9; seat++) {
            if (trace.traces(gameId, InfrastructureGameHarness.playerIdForSeat(seat)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 在岗座位数量（未被释放）。 */
    public int activeInstanceCount() {
        return runtime.activeInstanceCount(gameId);
    }

    /** 便于断言：某座位在视图里能看到的信息（秘密角色隔离用）。 */
    public AgentView viewOf(int seatNo) {
        return new AgentViewAdapter(harness.viewQuery())
                .viewFor(gameId, InfrastructureGameHarness.playerIdForSeat(seatNo));
    }

    /** 便于断言：事件流里是否存在某个类型。 */
    public boolean hasEventType(String type) {
        return eventTypes().contains(type);
    }

    /** 便于断言：对局是否真的终局。 */
    public boolean finished() {
        return state().status() == GameStatus.FINISHED;
    }

    /** 需要时间端口时的出口（生产接线用）。 */
    public TimePort clock() {
        return new SystemTimePort();
    }
}
