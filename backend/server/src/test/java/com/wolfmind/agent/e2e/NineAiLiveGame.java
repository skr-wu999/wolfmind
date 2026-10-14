package com.wolfmind.agent.e2e;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.TurnResult;
import com.wolfmind.application.agent.model.TurnSignal;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.runtime.BudgetGuard;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.ai.LangChain4jLlmClient;
import com.wolfmind.infrastructure.ai.LlmConnectivityCheck;
import com.wolfmind.infrastructure.ai.LlmSettingsLoader;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实模型下的九人 AI 对局驱动：**用你配置的 LLM 跑完一整局**。
 *
 * <p>它与 {@code NineAiGameE2ETest} 的分工：
 * <ul>
 *   <li>{@code NineAiGameE2ETest} 是自动化测试，用脚本化 LLM 离线验证机制（AGENTS.md §10
 *       要求单测禁止真实 LLM 调用）；</li>
 *   <li>本类是**手动验证入口**，用 {@link LlmSettingsLoader} 读到的真实参数驱动 9 个座位，
 *       因此会真实联网、真实消耗 token。</li>
 * </ul>
 *
 * <p>参数来源（优先级：系统属性 &gt; 环境变量 &gt; {@code wolfmind-llm.properties}）：
 * <pre>
 *   WOLFMIND_LLM_PROVIDER / WOLFMIND_LLM_BASE_URL / WOLFMIND_LLM_API_KEY / WOLFMIND_LLM_MODEL
 * </pre>
 * 若这些都没配，本类会**明确拒绝运行**而不是静默走规则兜底——否则你会以为"真实模型跑通了"，
 * 实际跑的是兜底。要跑纯规则基线请用 {@code NineAiGameE2ETest}。
 *
 * <p>运行方式（IDEA）：主类填本类，工作目录必须是 {@code D:\MyProject\wolfmind\backend}，
 * 环境变量或 VM 选项里带上面四个参数。命令行方式见类末 {@link #main} 的说明。
 */
public final class NineAiLiveGame {

    /** 步数上限：防规则缺陷导致死循环；一局正常在 30-80 步之间。 */
    private static final int MAX_STEPS = 400;

    /**
     * 输出分流：同时写控制台与 UTF-8 报告文件。
     *
     * <p>为什么要分流：Windows 上 Java 的 {@code System.out} 用系统默认编码（GBK），中文写进
     * 控制台再被重定向就会乱码；而真实对局的报告需要**可读地留档**（延迟与 token 用量是成本依据）。
     * 因此报告直接以 UTF-8 写文件，不经过控制台编码转换。
     */
    static final class Tee {
        private final java.io.PrintStream console;
        private final StringBuilder captured = new StringBuilder();

        Tee(java.io.PrintStream console) {
            this.console = console;
        }

        void println() {
            console.println();
            captured.append('\n');
        }

        void println(String line) {
            console.println(line);
            captured.append(line).append('\n');
        }

        void printf(String format, Object... args) {
            String line = String.format(format, args);
            console.print(line);
            captured.append(line);
        }

        /** 把捕获的完整报告以 UTF-8（无 BOM）写入文件。 */
        void writeTo(java.nio.file.Path path) {
            try {
                java.nio.file.Files.writeString(
                        path, captured.toString(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException error) {
                console.println("报告写入失败：" + path + " —— " + error.getMessage());
            }
        }
    }

    private static final Tee OUT = new Tee(System.out);
    private static final Tee ERR = new Tee(System.err);

    /** 每回合的超时（毫秒）。真实模型可能较慢，给足余量。 */
    private static final long TURN_DEADLINE_MS = 60_000L;

    private static final long CLOCK_START = 1_730_000_000_000L;
    private static final long SEED = 20240501L;

    private final InfrastructureGameHarness harness;
    private final String gameId;
    private final AIControllerAdapter adapter;
    private final AgentRuntimeImpl runtime;
    private final InMemoryAgentTrace trace;
    private final InMemoryMemoryPort memory;
    private final LlmClient llmClient;
    private final LlmSettings settings;

    /** 真实墙钟延迟（毫秒）：每个 AI 回合一次，用于成本与性能观测。 */
    private final List<Long> realLatencies = new ArrayList<>();
    private long realElapsedMs;
    private long totalPromptTokens;
    private long totalCompletionTokens;

    /** 诊断开关：兜底时打印该座位最近轨迹，用于排查模型输出被拒的原因。 */
    private static boolean diagnoseRejections;

    private NineAiLiveGame(
            InfrastructureGameHarness harness,
            String gameId,
            AIControllerAdapter adapter,
            AgentRuntimeImpl runtime,
            InMemoryAgentTrace trace,
            InMemoryMemoryPort memory,
            LlmClient llmClient,
            LlmSettings settings) {
        this.harness = harness;
        this.gameId = gameId;
        this.adapter = adapter;
        this.runtime = runtime;
        this.trace = trace;
        this.memory = memory;
        this.llmClient = llmClient;
        this.settings = settings;
    }

    /**
     * 用真实模型开局并装配。
     *
     * @param gameId   对局标识
     * @param settings LLM 参数；必须 {@code configured()}
     * @param persona  座位人格（影响表达，不影响约束）
     */
    public static NineAiLiveGame start(String gameId, LlmSettings settings, String persona) {
        // 时间锚点从"现在"开始（不是固定常量）；每步再对齐到当时的真实墙钟。
        InfrastructureGameHarness harness =
                new InfrastructureGameHarness(System.currentTimeMillis());
        Game game = harness.startNineAiGame(
                gameId, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, SEED);

        InMemoryAgentTrace trace = new InMemoryAgentTrace();
        InMemoryMemoryPort memory = new InMemoryMemoryPort();
        LlmClient client = new LangChain4jLlmClient(settings);

        AgentViewQueryPort views = new AgentViewAdapter(harness.viewQuery());
        ToolGatewayPort gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(),
                harness.idempotency(), trace, harness.time());
        WolfMindStrategy strategy = new WolfMindStrategy(gateway);

        AgentConfig config = new AgentConfig(
                "live-9ai",
                "真实模型九人局",
                settings.defaultModel(),
                0.6,
                0.2,
                AgentConfig.StrategyProfile.BALANCED,
                persona,
                AgentConfig.WorkflowMode.A,
                // 回合超时（秒）：与 TURN_DEADLINE_MS 一致，避免"驱动给了 60 秒、配置只认 20 秒"
                (int) (TURN_DEADLINE_MS / 1000),
                0.5,
                8,
                3,
                "live",
                // 真实模型可能慢：把回合预算放宽到 2 次 LLM 调用（决策 + 一次修复）
                com.wolfmind.application.agent.model.TurnBudget.DEFAULT);

        AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, strategy, gateway, memory, trace, client, harness.time(), new BudgetGuard(harness.time()));
        AIControllerAdapter adapter = new AIControllerAdapter(runtime, views, gateway, harness.time(), config);

        NineAiLiveGame live = new NineAiLiveGame(
                harness, gameId, adapter, runtime, trace, memory, client, settings);
        for (int seat = 1; seat <= 9; seat++) {
            runtime.createAgent(new AgentRuntime.CreateAgentCommand(
                    gameId, InfrastructureGameHarness.playerIdForSeat(seat), seat, config));
        }
        live.printRoster();
        return live;
    }

    /** 开局座位与角色一览（只打印公开信息，不打印"谁是狼"以外的私有事实细节）。 */
    private void printRoster() {
        GameSnapshot snapshot = state();
        OUT.println("开局：" + snapshot.players().size() + " 个座位，角色配置 "
                + snapshot.roleComposition().describe());
        for (var player : snapshot.players()) {
            OUT.printf("  座位 %d  角色 %-9s 控制者 %s%n",
                    player.seatNo().value(), player.role(), player.controller().type());
        }
        OUT.println();
    }

    /**
     * 跑到终局，并逐回合打印结果。
     *
     * @param verbose {@code true} 时打印每一步（含被跳过的座位），用于排查"为什么某座位没行动"
     */
    public int run(boolean verbose) {
        int steps = 0;
        long startedAt = System.currentTimeMillis();
        while (state().status() == GameStatus.RUNNING && steps < MAX_STEPS) {
            steps++;
            GameSnapshot before = state();
            String beforePhase = before.phase() + "@" + before.round();
            int beforeVersion = before.version();
            SeatNo expected = before.phase().autoSettled() ? null : actorToAct();
            String detail = step();
            GameSnapshot after = state();
            String afterPhase = after.phase() + "@" + after.round();
            boolean changed = !beforePhase.equals(afterPhase) || after.version() != beforeVersion;
            if (verbose || changed || steps % 10 == 0
                    || after.status() != GameStatus.RUNNING) {
                OUT.printf("  [%3d] %-18s → %-18s 期望座位=%-4s %s%n",
                        steps, beforePhase, afterPhase,
                        expected == null ? "-" : String.valueOf(expected.value()), detail);
                if (diagnoseRejections && expected != null) {
                    TurnSignal probe = signalFor(expected);
                    OUT.printf("        now=%d deadline=%d 已过期=%s turnIndex=%d version=%d%n",
                            harness.now(), probe.deadline(), harness.now() >= probe.deadline(),
                            probe.turnIndex(), probe.version());
                }
            }
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        printResult(steps, elapsed);
        return steps;
    }

    /** 跑到终局（简洁输出）。 */
    public int run() {
        return run(false);
    }

    /** 推进一步，返回便于观察的一行描述。 */
    private String step() {
        // 时钟对齐到**真实墙钟**，而不是"自己累加模型耗时"。
        //
        // 手工累加会漏掉每步之间的开销（重载聚合、持久化、诊断打印），十几步就能累积出 40 秒以上
        // 的漂移，于是 deadline 判定基于一个比真实时间更早的时刻，长发言会被误判为"回合已截止"。
        // 直接用系统时钟才是真实语义：deadline = 授予回合的时刻 + 回合窗口。
        harness.setClockTo(System.currentTimeMillis());
        GameSnapshot snapshot = state();
        GamePhase phase = snapshot.phase();

        if (phase.autoSettled()) {
            advancePhase();
            return "阶段结算";
        }
        SeatNo actor = actorToAct();
        if (actor == null) {
            advancePhase();
            return "无待行动者，推进";
        }
        TurnSignal signal = signalFor(actor);
        if (!signal.drivesAgent()) {
            submitTimeoutSkip();
            return "非 AI 座位，跳过";
        }
        // 真实耗时用墙钟单独测：TimePort 是受控时钟（确定性所需），不反映真实模型延迟。
        long startedAt = System.nanoTime();
        TurnResult result = adapter.dispatch(signal, seat -> null);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        realLatencies.add(elapsedMs);
        realElapsedMs += elapsedMs;

        if (result == null) {
            submitTimeoutSkip();
            return "派发失败，跳过";
        }
        totalPromptTokens += result.promptTokens();
        totalCompletionTokens += result.completionTokens();
        String description = describe(result, actor, elapsedMs);
        if (!result.submitted()) {
            // 真实模型可能超时/输出非法：由超时看护兜底，保证对局继续（agent-runtime.md §10）
            submitTimeoutSkip();
            description += " → 兜底跳过";
            if (diagnoseRejections) {
                dumpRecentTrace(actor);
            }
        }
        return description;
    }

    /**
     * 打印该座位最近几条决策轨迹。
     *
     * <p>用于回答"模型明明返回了内容，为什么还是兜底"——轨迹里有 LLM 调用、输出校验与拒绝原因。
     * 只做诊断，不参与对局逻辑。
     */
    private void dumpRecentTrace(SeatNo actor) {
        var events = trace.traces(gameId, InfrastructureGameHarness.playerIdForSeat(actor.value()));
        if (events.isEmpty()) {
            OUT.println("        轨迹：无");
            return;
        }
        int from = Math.max(0, events.size() - 4);
        for (int i = from; i < events.size(); i++) {
            var event = events.get(i);
            OUT.println("        轨迹 " + event.type() + " " + event.payload());
            if (event.payload() != null && String.valueOf(event.payload()).contains("BUDGET_EXCEEDED")) {
                OUT.println("        >> 拒绝详情: " + event.payload());
            }
        }
    }

    private String describe(TurnResult result, SeatNo actor, long elapsedMs) {
        String action = result.chosen() == null ? "-" : result.chosen().actionType();
        String target = result.chosen() == null || result.chosen().targetSeatNo() == null
                ? "" : "→" + result.chosen().targetSeatNo() + "号";
        return String.format("座位%d %-12s %-8s tokens=%d/%d %5dms",
                actor.value(), action, target,
                result.promptTokens(), result.completionTokens(), elapsedMs);
    }

    private void printResult(int steps, long elapsedMs) {
        GameSnapshot snapshot = state();
        long failed = trace.countOf(AgentTracePort.TraceEventType.AGENT_TURN_FAILED);
        long timedOut = trace.countOf(AgentTracePort.TraceEventType.AGENT_TURN_TIMEOUT);
        long rejected = trace.countOf(AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED);
        long llmInvoked = trace.countOf(AgentTracePort.TraceEventType.AGENT_LLM_INVOKED);
        long toolCalled = trace.countOf(AgentTracePort.TraceEventType.AGENT_TOOL_CALLED);

        OUT.println();
        OUT.println("══════════════════ 对局结果 ══════════════════");
        OUT.println("  状态      : " + snapshot.status()
                + (snapshot.winner() == null ? "" : "   获胜阵营: " + snapshot.winner()));
        OUT.println("  结束轮次  : 第 " + snapshot.round() + " 轮");
        OUT.println("  聚合版本  : " + snapshot.version() + " （事件流长度）");
        OUT.println("  推进步数  : " + steps);
        OUT.println("  存活座位  : " + snapshot.alivePlayers().stream()
                .map(p -> String.valueOf(p.seatNo().value())).toList());
        OUT.println();
        OUT.println("  LLM 调用    : " + llmInvoked + " 次（provider=" + settings.provider()
                + " model=" + settings.defaultModel() + "）");
        OUT.println("  token 用量  : prompt " + totalPromptTokens + " / completion "
                + totalCompletionTokens + " （合计 "
                + (totalPromptTokens + totalCompletionTokens) + "）");
        OUT.println("  真实延迟    : 总计 " + (realElapsedMs / 1000) + " 秒，平均 "
                + averageLatencyMs() + " ms/回合，最慢 " + maxLatencyMs() + " ms");
        OUT.println("  墙钟耗时    : " + (elapsedMs / 1000) + " 秒");
        OUT.println("  工具调用    : " + toolCalled + " 次");
        OUT.println("  输出被拒    : " + rejected + " 次（校验/防泄漏拦截）");
        OUT.println("  回合超时    : " + timedOut + " 次");
        OUT.println("  回合失败    : " + failed + " 次");
        OUT.println();
        OUT.println("  事件流      : " + harness.eventStream(gameId).size() + " 条，版本连续");
        OUT.println("══════════════════════════════════════════════");
    }

    private long averageLatencyMs() {
        return realLatencies.isEmpty() ? 0
                : realLatencies.stream().mapToLong(Long::longValue).sum() / realLatencies.size();
    }

    private long maxLatencyMs() {
        return realLatencies.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    // ---------------------------------------------------------------- 驱动细节

    private void advancePhase() {
        Game game = loadGame();
        if (game.status() != GameStatus.RUNNING) {
            return;
        }
        if (!game.advance(context(game.version())).isEmpty()) {
            persist(game);
        }
    }

    private void submitTimeoutSkip() {
        Game loaded = loadGame();
        var handling = loaded.timeoutSkips(harness.now(), true);
        if (handling.skips().isEmpty()) {
            advancePhase();
            return;
        }
        for (SkipAction skip : handling.skips()) {
            Game target = loadGame();
            target.handle(skip, context(target.version()));
            persist(target);
        }
    }

    private SeatNo actorToAct() {
        GameSnapshot snapshot = state();
        return switch (snapshot.phase()) {
            case DAY_DISCUSS, LAST_WORDS -> snapshot.currentTurn() == null
                    ? null : snapshot.currentTurn().seatNo();
            case DAY_VOTE -> snapshot.players().stream()
                    .filter(p -> p.alive() && !snapshot.votes().containsKey(p.seatNo().value()))
                    .map(p -> p.seatNo()).sorted().findFirst().orElse(null);
            case NIGHT_WOLF -> snapshot.players().stream()
                    .filter(p -> p.alive() && p.role() == Role.WEREWOLF)
                    .map(p -> p.seatNo())
                    .filter(seat -> snapshot.wolfNominations().stream()
                            .noneMatch(n -> n.wolfSeatNo() == seat.value()))
                    .sorted().findFirst().orElse(null);
            case NIGHT_SEER -> aliveSeatByRole(Role.SEER);
            case NIGHT_WITCH -> aliveSeatByRole(Role.WITCH);
            case HUNTER_SHOOT -> snapshot.pendingHunter() == null
                    ? null : SeatNo.of(snapshot.pendingHunter());
            default -> null;
        };
    }

    private SeatNo aliveSeatByRole(Role role) {
        return state().players().stream()
                .filter(p -> p.alive() && p.role() == role)
                .map(p -> p.seatNo()).findFirst().orElse(null);
    }

    private TurnSignal signalFor(SeatNo seatNo) {
        GameSnapshot snapshot = state();
        long deadline = snapshot.currentTurn() == null
                ? harness.now() + TURN_DEADLINE_MS
                : snapshot.currentTurn().deadline();
        return new TurnSignal(
                gameId,
                InfrastructureGameHarness.playerIdForSeat(seatNo.value()),
                seatNo.value(),
                snapshot.phase().name(),
                snapshot.turnIndex(),
                deadline,
                snapshot.version(),
                snapshot.playerAt(seatNo).controller().type().name());
    }

    private ActionContext context(int expectedVersion) {
        return new ActionContext(
                "cmd_" + expectedVersion, harness.now(), "SYSTEM",
                "cmd_" + expectedVersion, "cor_live", expectedVersion, 0L);
    }

    private Game loadGame() {
        return harness.repository().load(gameId).game();
    }

    private void persist(Game game) {
        List<com.wolfmind.domain.game.event.DomainEvent> events = game.pendingEvents();
        if (events.isEmpty()) {
            return;
        }
        List<EventEnvelope> envelopes = new ArrayList<>();
        int version = harness.eventStore().currentVersion(gameId) + 1;
        for (var event : events) {
            envelopes.add(EventEnvelope.of(event, gameId, version++));
        }
        harness.repository().commit(
                gameId, envelopes.get(0).version() - 1, envelopes,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);
    }

    private GameSnapshot state() {
        return loadGame().snapshot();
    }

    private List<String> phaseTimeline() {
        return harness.eventStream(gameId).stream()
                .filter(e -> "PHASE_CHANGED".equals(e.type()))
                .map(e -> e.payload().get("from") + "→" + e.payload().get("to") + "@"
                        + e.payload().get("round"))
                .toList();
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 命令行入口。
     *
     * <pre>
     *   # 先自检一次（不消耗多少 token）
     *   java -cp ... com.wolfmind.infrastructure.ai.LlmConnectivityCheck
     *
     *   # 再跑整局；参数从环境变量读
     *   set WOLFMIND_LLM_PROVIDER=deepseek
     *   set WOLFMIND_LLM_BASE_URL=https://api.deepseek.com/v1
     *   set WOLFMIND_LLM_API_KEY=sk-xxxx
     *   set WOLFMIND_LLM_MODEL=deepseek-flash
     *   java -cp ... com.wolfmind.agent.e2e.NineAiLiveGame
     * </pre>
     *
     * <p>程序实参（可选）：报告输出路径，默认 {@code nine-ai-report.txt}（相对工作目录）。
     * 报告以 UTF-8 写文件，避免 Windows 控制台 GBK 乱码。
     *
     * <p>退出码：{@code 0} 对局正常终局；{@code 1} 未配置 LLM（避免把规则兜底误判为真实模型跑通）；
     * {@code 2} 对局未能在步数上限内终局。
     */
    public static void main(String[] args) {
        LlmSettings settings = LlmSettingsLoader.load();

        OUT.println("══════════════════ 真实模型九人 AI 对局 ══════════════════");
        OUT.println("  参数来源 : 系统属性 > 环境变量 > " + LlmSettingsLoader.CONFIG_FILE);
        OUT.println("  参数摘要 : " + settings.describeForLog());

        if (!settings.configured()) {
            ERR.println();
            ERR.println("未检测到可用的 LLM 配置，拒绝继续。");
            ERR.println("本入口用于验证**真实模型**；未配置时它会静默走规则兜底，");
            ERR.println("那样你会把规则兜底误判为真实模型跑通。要跑纯规则基线请用 NineAiGameE2ETest。");
            ERR.println();
            ERR.println("请设置（两种方式任选）：");
            ERR.println("  1) 环境变量：WOLFMIND_LLM_PROVIDER / WOLFMIND_LLM_BASE_URL / "
                    + "WOLFMIND_LLM_API_KEY / WOLFMIND_LLM_MODEL");
            ERR.println("  2) 配置文件：把 backend/wolfmind-llm.properties.template 复制为 "
                    + "backend/wolfmind-llm.properties");
            System.exit(1);
        }

        // 先做一次最小调用：把"配置错误"与"策略问题"分开，避免一局跑到底才发现 401
        LlmConnectivityCheck.Report report = LlmConnectivityCheck.check(settings);
        OUT.println("  连通性   : " + report.describe());
        if (!report.reachable()) {
            ERR.println();
            ERR.println("LLM 不可达，已中止（不进入对局）。请先修正参数再重试。");
            System.exit(1);
        }

        OUT.println();
        NineAiLiveGame live = start(
                "g_live_" + System.currentTimeMillis(),
                settings,
                "冷静、简洁、按证据说话；发言不说废话");
        boolean verbose = args.length > 1
                && ("verbose".equals(args[1]) || "diag".equals(args[1]));
        diagnoseRejections = args.length > 1 && "diag".equals(args[1]);
        live.run(verbose);

        // 报告留档：控制台在 Windows 上会因 GBK 乱码，因此直接以 UTF-8 写文件。
        // 可用第一个程序实参指定路径；默认写到工作目录下的 nine-ai-report.txt。
        String reportPath = args.length > 0 ? args[0] : "nine-ai-report.txt";
        OUT.writeTo(java.nio.file.Path.of(reportPath));
        System.out.println("报告已写入（UTF-8）：" + java.nio.file.Path.of(reportPath).toAbsolutePath());

        System.exit(live.state().status() == GameStatus.FINISHED ? 0 : 2);
    }

    /** 便于测试与复用：只跑一个回合（不做连通性检查）。 */
    public void runOneTurn() {
        step();
    }

    /** 当前是否终局。 */
    public boolean finished() {
        return state().status() == GameStatus.FINISHED;
    }

    /** 当前视图（调试用）。 */
    public AgentView viewOf(int seatNo) {
        return new AgentViewAdapter(harness.viewQuery())
                .viewFor(gameId, InfrastructureGameHarness.playerIdForSeat(seatNo));
    }

    /** 运行期实例数（应为 9）。 */
    public int activeAgents() {
        return runtime.activeInstanceCount(gameId);
    }
}
